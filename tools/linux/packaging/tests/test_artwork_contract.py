"""Focused asset/provenance payload checks; no network, native player or legal certification."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract as contract


class ArtworkContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'lib/app').mkdir(parents=True)
        self.release = json.loads(contract.release_provenance().read_text())
        # Linux packages carry the checkout bytes (upstream artwork, owner decision 2026-10-17).
        self.payload = {name: contract.linux_source(name).read_bytes() for name in contract.expected_resources()}

    def jar(self, payload, filename='application.jar'):
        with zipfile.ZipFile(self.root / 'lib/app' / filename, 'w') as jar:
            for name, data in payload.items():
                jar.writestr(name, data)

    def test_tools_mounted_alone_use_explicit_repository_or_fail_closed(self):
        # Match the CI verifier: only tools/linux/packaging at /packaging, with
        # composeApp/src supplied separately through NUVIO_REPOSITORY.
        tools = self.root / 'packaging'
        shutil.copytree(contract.HERE, tools, ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        repository = self.root / 'repository'; (repository / 'composeApp/src').mkdir(parents=True)
        script = 'import sys; sys.path.insert(0, sys.argv[1]); import verify, artwork_contract; print(artwork_contract.repository())'
        env = {k: v for k, v in os.environ.items() if k != 'NUVIO_REPOSITORY'}
        result = subprocess.run([sys.executable, '-B', '-c', script, str(tools)], cwd=self.root,
                                env=dict(env, NUVIO_REPOSITORY=str(repository)), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(repository.resolve()), result.stdout.strip())
        result = subprocess.run([sys.executable, '-B', '-c', script, str(tools)], cwd=self.root,
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('requires the repository', result.stderr)

    def test_runtime_downloaded_anime_dataset_is_rejected_by_name_or_bytes(self):
        dataset = contract.repository() / 'composeApp/src/desktopMain/resources/anime-list-mini.json'
        data = dataset.read_bytes()
        for name in ('anime-list-mini.json', 'nested/ANIME-LIST-MINI.JSON', 'renamed/data.bin'):
            with self.subTest(name=name):
                self.jar(dict(self.payload, **{name: data}))
                with self.assertRaisesRegex(ValueError, 'Removed artwork filename|Forbidden historical artwork bytes'):
                    contract.verify_packaged_artwork(self.root)
        self.jar(self.payload)
        self.assertNotIn('anime-list-mini.json', contract.verify_packaged_artwork(self.root)['resources'])

    def test_linux_source_snapshot_omits_runtime_downloaded_dataset(self):
        import build
        import source_policy
        self.assertEqual({'composeApp/src/desktopMain/resources/anime-list-mini.json'}, source_policy.RUNTIME_DOWNLOADED)
        repo = self.root / 'repo'
        tracked = ['composeApp/src/desktopMain/resources/anime-list-mini.json',
                   'composeApp/src/desktopMain/resources/anime-mapping/NOTICE.txt',
                   'composeApp/Configuration/DesktopVersion.properties']
        for name in tracked:
            (repo / name).parent.mkdir(parents=True, exist_ok=True)
            (repo / name).write_text('VERSION_NAME=1\nVERSION_CODE=1\n')
        answers = {'ls-files': '\0'.join(tracked), 'status': '', 'rev-parse': 'a' * 40, 'show': '0'}
        with patch.object(build, 'REPO', repo), \
                patch.object(build, 'git', lambda *args: '' if '--others' in args else answers[args[0]]):
            info = build.source_tree(self.root / 'work/source', 'nightly')
        info = source_policy.apply(self.root / 'work/source', info)
        rows = json.loads((self.root / 'work/source-files.json').read_text())
        names = [row[0] for row in rows]
        self.assertNotIn('composeApp/src/desktopMain/resources/anime-list-mini.json', names)
        self.assertIn('composeApp/src/desktopMain/resources/anime-mapping/NOTICE.txt', names)
        self.assertFalse((self.root / 'work/source/composeApp/src/desktopMain/resources/anime-list-mini.json').exists())
        self.assertEqual(hashlib.sha256(json.dumps([tuple(r) for r in rows]).encode()).hexdigest(), info['source_sha256'])

    def test_actual_pinned_previews_data_and_notices_pass_without_claiming_rights_clearance(self):
        self.jar(self.payload)
        report = contract.verify_packaged_artwork(self.root)
        self.assertEqual(set(self.payload), set(report['resources']))
        self.assertEqual(4, report['replacement_payloads'])
        self.assertEqual('BLOCKED', report['redistribution_assessment'])

    def test_missing_tampered_old_or_duplicate_packaged_resources_fail(self):
        for name in self.payload:
            with self.subTest(name=name):
                payload = dict(self.payload); del payload[name]; self.jar(payload)
                with self.assertRaisesRegex(ValueError, 'Missing'): contract.verify_packaged_artwork(self.root)
                payload[name] = b'unreviewed old artwork or corrupt data'; self.jar(payload)
                with self.assertRaisesRegex(ValueError, 'hash mismatch'): contract.verify_packaged_artwork(self.root)
        self.jar(self.payload)
        self.jar({next(iter(self.payload)): b'duplicate'}, 'extra.jar')
        with self.assertRaisesRegex(ValueError, 'Duplicate'): contract.verify_packaged_artwork(self.root)

    def test_four_setup_preview_replacements_pin_authoring_and_notices(self):
        self.assertEqual(4, len(self.release['replacements']))
        self.assertNotIn('linux_overlay', self.release)
        for name, digest in self.release['authoring']['recipe_sha256'].items():
            self.assertEqual(digest, hashlib.sha256((contract.repository() / name).read_bytes()).hexdigest())
        for row in self.release['replacements']:
            with self.subTest(resource=row['resource']):
                self.assertEqual('setup-preview', row['category'])
                data = (contract.repository() / row['resource']).read_bytes()
                self.assertEqual(row['replacement']['sha256'], hashlib.sha256(data).hexdigest())
                self.assertEqual('MIT', row['replacement']['license'])
                self.assertNotEqual(row['original']['sha256'], row['replacement']['sha256'])

    def test_previous_setup_preview_bytes_are_rejected_even_renamed_as_nonimages(self):
        # Synthetic historical bytes avoid committing the replaced originals as fixtures
        # or making unit tests depend on full Git history. Exercise the production loader/gate.
        fixture = self.root / 'provenance'; fixture.mkdir()
        for name in ('LICENSE.txt', 'NOTICE.txt'):
            (fixture / name).write_bytes(contract.release_provenance().with_name(name).read_bytes())
        originals = []
        for index, row in enumerate(self.release['replacements']):
            data = f'unverified previous image fixture {index}'.encode(); originals.append(data)
            row['original'].update(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
        (fixture / 'PROVENANCE.json').write_text(json.dumps(self.release))
        with patch.object(contract, 'release_provenance', lambda: fixture / 'PROVENANCE.json'):
            for index, data in enumerate(originals):
                with self.subTest(index=index):
                    payload = dict(self.payload)
                    payload[contract.PREFIX + 'files/licenses/ReleaseArtwork/PROVENANCE.json'] = (fixture / 'PROVENANCE.json').read_bytes()
                    payload[f'arbitrary/{index}.bin'] = data; self.jar(payload)
                    with self.assertRaisesRegex(ValueError, 'Forbidden historical artwork bytes'):
                        contract.verify_packaged_artwork(self.root)

    def test_replacements_must_be_distinct_and_non_original(self):
        fixture = self.root / 'provenance'; fixture.mkdir()
        for mutate in (lambda r: r['replacements'][1]['replacement'].update(sha256=r['replacements'][0]['replacement']['sha256']),
                       lambda r: r['replacements'][0]['replacement'].update(sha256=r['replacements'][0]['original']['sha256'])):
            release = json.loads(contract.release_provenance().read_text()); mutate(release)
            (fixture / 'PROVENANCE.json').write_text(json.dumps(release))
            with patch.object(contract, 'release_provenance', lambda: fixture / 'PROVENANCE.json'), \
                    self.assertRaisesRegex(ValueError, 'distinct, non-original'):
                contract.release_rules()

    def test_recipe_reproduces_setup_previews(self):
        java_home = Path(os.environ.get('JAVA_HOME', '/nonexistent'))
        if not (java_home / 'bin/javac').is_file():
            self.skipTest('JAVA_HOME with javac required to run the headless authoring recipe')
        recipe = contract.HERE / 'artwork/GenerateReleaseArtwork.java'
        classes, output = self.root / 'classes', self.root / 'generated'
        env = {k: v for k, v in os.environ.items() if k in ('PATH', 'LANG')}
        env.update(HOME=str(self.root), XDG_CONFIG_HOME=str(self.root), XDG_CACHE_HOME=str(self.root))
        subprocess.run([str(java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(classes), str(recipe)],
                       env=env, check=True, capture_output=True)
        subprocess.run([str(java_home / 'bin/java'), '-Djava.awt.headless=true', '-Duser.home=' + str(self.root),
                        '-cp', str(classes), 'GenerateReleaseArtwork', str(contract.repository()), str(output)],
                       env=env, check=True, capture_output=True)
        files = self.release['authoring']['toolchain']['files']
        pngs = [n for n in files if n.endswith('.png')]
        self.assertEqual(4, len(pngs))
        self.assertEqual(set(pngs), {p.name for p in output.iterdir()})
        for name in pngs:
            with self.subTest(name=name):
                self.assertEqual(files[name], hashlib.sha256((output / name).read_bytes()).hexdigest())

    def test_replacement_copies_are_rejected_at_renamed_paths_and_across_jars(self):
        for row in self.release['replacements']:
            name = contract.packaged_path(row['resource']); payload = dict(self.payload)
            payload['renamed.bin'] = payload[name]; self.jar(payload)
            with self.assertRaisesRegex(ValueError, 'Renamed/duplicate replacement'):
                contract.verify_packaged_artwork(self.root)
        self.jar(self.payload)
        self.jar({name: self.payload[name]}, 'second.jar')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            contract.verify_packaged_artwork(self.root)


if __name__ == '__main__':
    unittest.main()
