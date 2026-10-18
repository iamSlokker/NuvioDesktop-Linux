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

    def test_anime_dataset_ships_bundled_with_the_checkout_bytes(self):
        # Bundled like upstream (owner decision 2026-10-18): required, any other bytes rejected.
        dataset = contract.repository() / 'composeApp/src/desktopMain/resources/anime-list-mini.json'
        self.jar(self.payload)
        report = contract.verify_packaged_artwork(self.root)
        self.assertEqual(hashlib.sha256(dataset.read_bytes()).hexdigest(), report['resources']['anime-list-mini.json'])
        payload = dict(self.payload); del payload['anime-list-mini.json']; self.jar(payload)
        with self.assertRaisesRegex(ValueError, 'Missing reviewed artwork/data: anime-list-mini.json'):
            contract.verify_packaged_artwork(self.root)

    def test_linux_source_snapshot_keeps_the_anime_dataset(self):
        import build
        import source_policy
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
        manifest = (self.root / 'work/source-files.json').read_text()
        self.assertEqual(info, source_policy.apply(self.root / 'work/source', dict(info)))
        self.assertEqual(manifest, (self.root / 'work/source-files.json').read_text())
        self.assertIn('composeApp/src/desktopMain/resources/anime-list-mini.json', [row[0] for row in json.loads(manifest)])
        self.assertTrue((self.root / 'work/source/composeApp/src/desktopMain/resources/anime-list-mini.json').is_file())

    def test_actual_pinned_previews_data_and_notices_pass_without_claiming_rights_clearance(self):
        self.jar(self.payload)
        report = contract.verify_packaged_artwork(self.root)
        self.assertEqual(set(self.payload), set(report['resources']))
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


if __name__ == '__main__':
    unittest.main()
