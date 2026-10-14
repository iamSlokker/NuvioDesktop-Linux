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
import xml.etree.ElementTree as ET
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract as contract


class ArtworkContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'lib/app').mkdir(parents=True)
        self.provenance = json.loads(contract.icon_provenance().read_text())
        self.release = json.loads(contract.release_provenance().read_text())
        self.overlay = self.release['linux_overlay']['resources']
        # Linux packages carry overlay bytes for the 47 overlay targets, repository bytes otherwise.
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

    def test_seven_original_vectors_preserve_sizes_use_safe_svg_and_have_real_mit_notice(self):
        self.assertEqual(7, len(self.provenance['assets']))
        for row in self.provenance['assets']:
            data = (contract.repository() / row['resource']).read_bytes()
            with self.subTest(resource=row['resource']):
                self.assertEqual(hashlib.sha256(data).hexdigest(), row['replacement']['sha256'])
                self.assertNotEqual(row['original']['sha256'], row['replacement']['sha256'])
                self.assertEqual('MIT', row['replacement']['license'])
                self.assertEqual('REPLACED', row['status'])
                self.assertNotIn(b'SVG Repo', data)
                self.assertNotIn(b'<!DOCTYPE', data)
                self.assertNotIn(b'<!ENTITY', data)
                svg = ET.fromstring(data)
                self.assertEqual(row['dimensions'], {key: svg.get(key) for key in row['dimensions']})
                self.assertTrue(all(x.tag.split('}')[-1] in ('svg', 'path', 'rect', 'circle') for x in svg.iter()))
                self.assertFalse(any('href' in key or key.lower().startswith('on') for x in svg.iter() for key in x.attrib))
        text = contract.icon_provenance().with_name('LICENSE.txt').read_text()
        self.assertIn('Copyright (c) 2026 NuvioDesktop-Linux contributors', text)
        self.assertIn('Permission is hereby granted', text)

    def test_actual_pinned_icons_data_and_notices_pass_without_claiming_rights_clearance(self):
        self.jar(self.payload)
        report = contract.verify_packaged_artwork(self.root)
        self.assertEqual(7, report['replacement_icons'])
        self.assertEqual(74, len(report['resources']))
        self.assertEqual(6, report['replacement_quality_badges'])
        self.assertEqual(4, report['replacement_setup_previews'])
        self.assertEqual(10, report['removed_resources'])
        self.assertEqual(47, report['linux_overlay_replacements'])
        self.assertEqual(0, report['unresolved_exact_path_exceptions'])
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

    def test_remaining_inventory_covers_actual_marks_badges_and_setup_without_inventing_grants(self):
        report = json.loads((contract.repository() / 'docs/compliance/evidence/artwork-data-review.json').read_text())
        self.assertEqual('BLOCKED', report['redistribution_status'])
        self.assertEqual(17, report['replaced'])
        self.assertEqual(47, report['linux_overlay_replaced'])
        self.assertEqual(10, report['excluded_from_packaging'])
        self.assertEqual(0, report['unresolved'])
        self.assertEqual(0, len(report['remaining_artwork']))
        self.assertEqual(47, report['unresolved_upstream_other_platforms'])
        drawables = contract.repository() / 'composeApp/src/commonMain/composeResources/drawable'
        scoped = {p.relative_to(contract.repository()).as_posix() for p in drawables.iterdir()
                  if p.name.startswith(('rating_', 'hero_badge_', 'quality_badge_')) or
                  p.stem in ('discord_logo', 'kitsu_logo', 'mdblist_logo', 'simkl_logo',
                             'trakt_logo_wordmark', 'trakt_tv_favicon', 'tvdb_logo', 'introdb_favicon')}
        scoped.update(p.relative_to(contract.repository()).as_posix() for p in
                      (contract.repository() / 'composeApp/src/desktopMain/resources/setup').glob('*.webp'))
        self.assertEqual(scoped, set(report['remaining_artwork']) | set(report['resolved_artwork']))
        overlaid = {name: row for name, row in report['resolved_artwork'].items() if row['status'] == 'LINUX_OVERLAY_REPLACED'}
        self.assertEqual({row['resource'] for row in self.overlay}, set(overlaid))
        for row in self.overlay:
            # Upstream originals remain unchanged (still UNRESOLVED for other platforms).
            name = row['resource']
            self.assertEqual(row, overlaid[name])
            self.assertEqual('UNRESOLVED', row['original']['status'])
            self.assertEqual(row['original']['sha256'], hashlib.sha256((contract.repository() / name).read_bytes()).hexdigest())
        self.assertEqual({'provider-or-rating-mark': 20, 'award-or-hero-badge': 27},
                         {c: sum(r['category'] == c for r in overlaid.values()) for c in ('provider-or-rating-mark', 'award-or-hero-badge')})

    def test_ten_original_replacements_preserve_dimensions_and_pin_authoring_and_notices(self):
        self.assertEqual(10, len(self.release['replacements']))
        for name, digest in self.release['authoring']['recipe_sha256'].items():
            self.assertEqual(digest, hashlib.sha256((contract.repository() / name).read_bytes()).hexdigest())
        for row in self.release['replacements']:
            with self.subTest(resource=row['resource']):
                data = (contract.repository() / row['resource']).read_bytes()
                self.assertEqual(row['replacement']['sha256'], hashlib.sha256(data).hexdigest())
                self.assertEqual('MIT', row['replacement']['license'])
                self.assertNotEqual(row['original']['sha256'], row['replacement']['sha256'])
                if row['resource'].endswith('.svg'):
                    svg = ET.fromstring(data)
                    self.assertEqual(row['dimensions'], {k: svg.get(k) for k in row['dimensions']})
                    self.assertEqual({'svg', 'title', 'rect', 'path'}, {x.tag.split('}')[-1] for x in svg.iter()})
                    self.assertFalse(any('href' in k or k.lower().startswith('on') for x in svg.iter() for k in x.attrib))
                    self.assertNotIn(b'<!', data.replace(b'<!--', b''))
                    self.assertNotIn(b'<text', data)

    def test_all_nineteen_previous_asset_bytes_are_rejected_even_renamed_as_nonimages(self):
        # Synthetic historical bytes avoid committing the unlicensed imports as fixtures
        # or making unit tests depend on full Git history. Exercise the production loader/gate.
        fixture = self.root / 'provenance'; fixture.mkdir()
        for name in ('LICENSE.txt', 'NOTICE.txt'):
            (fixture / name).write_bytes(contract.release_provenance().with_name(name).read_bytes())
        originals = []
        for index, row in enumerate(self.release['removed'] + self.release['replacements']):
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

    def test_removed_names_are_rejected_in_any_directory_case_or_bytes(self):
        self.assertEqual(10, len(self.release['removed']))
        for row in self.release['removed']:
            self.assertFalse((contract.repository() / row['resource']).exists())
            for name in (row['filename'], 'another/' + row['filename'].upper(), 'another\\' + row['filename']):
                payload = dict(self.payload); payload[name] = b'different new bytes'; self.jar(payload)
                with self.assertRaisesRegex(ValueError, 'Removed artwork filename'):
                    contract.verify_packaged_artwork(self.root)

    def test_all_47_upstream_originals_are_rejected_at_their_own_or_renamed_paths(self):
        # Actual upstream bytes from composeApp, i.e. what a JAR built without the overlay carries.
        self.assertEqual(47, len(self.overlay))
        self.assertNotIn('retained_hash_exceptions', self.release)
        for row in self.overlay:
            original = (contract.repository() / row['resource']).read_bytes()
            self.assertEqual(row['original']['sha256'], hashlib.sha256(original).hexdigest())
            name = contract.packaged_path(row['resource'])
            for target in (name, 'renamed.bin', name.upper(), name.replace('/drawable/', '/other/')):
                with self.subTest(resource=row['resource'], target=target):
                    payload = dict(self.payload); payload[target] = original; self.jar(payload)
                    with self.assertRaisesRegex(ValueError, 'Forbidden historical artwork bytes'):
                        contract.verify_packaged_artwork(self.root)

    def test_retained_exceptions_cannot_return_and_overlay_must_match_provenance(self):
        fixture = self.root / 'provenance'; fixture.mkdir()
        for name in ('LICENSE.txt', 'NOTICE.txt'):
            (fixture / name).write_bytes(contract.release_provenance().with_name(name).read_bytes())
        row = self.overlay[0]
        for mutate, message in (
                (lambda r: r.update(retained_hash_exceptions=[{'resource': row['resource'], 'sha256': row['original']['sha256']}]),
                 'Retained original artwork exceptions'),
                (lambda r: r['linux_overlay']['resources'][0]['replacement'].update(sha256='0' * 64),
                 'overlay differs from pinned provenance'),
                (lambda r: r['linux_overlay']['resources'][1]['replacement'].update(
                    sha256=r['linux_overlay']['resources'][0]['replacement']['sha256']), 'distinct, non-original'),
                (lambda r: r['linux_overlay']['resources'][0]['replacement'].update(sha256=row['original']['sha256']),
                 'distinct, non-original')):
            release = json.loads(contract.release_provenance().read_text()); mutate(release)
            (fixture / 'PROVENANCE.json').write_text(json.dumps(release))
            with self.subTest(message=message), patch.object(contract, 'release_provenance', lambda: fixture / 'PROVENANCE.json'), \
                    self.assertRaisesRegex(ValueError, message):
                check = contract.release_rules if 'distinct' in message else lambda: contract.verify_packaged_artwork(self.root)
                check()

    def test_linux_overlay_is_original_safe_sized_distinct_and_pinned(self):
        lines = self.release['linux_overlay']
        self.assertIn('Linux packages', lines['scope'])
        digests = set()
        receipt = self.release['authoring']['toolchain']['overlay_files']
        self.assertEqual({row['resource'] for row in self.overlay}, set(receipt))
        on_disk = {p.relative_to(contract.OVERLAY).as_posix() for p in contract.OVERLAY.rglob('*') if p.is_file()}
        self.assertEqual(set(receipt), on_disk)
        for row in self.overlay:
            with self.subTest(resource=row['resource']):
                data = (contract.OVERLAY / row['resource']).read_bytes()
                original = (contract.repository() / row['resource']).read_bytes()
                digest = hashlib.sha256(data).hexdigest()
                self.assertEqual(row['replacement']['sha256'], digest)
                self.assertEqual(receipt[row['resource']], digest)
                self.assertEqual(row['replacement']['bytes'], len(data))
                self.assertEqual('MIT', row['replacement']['license'])
                self.assertEqual('LINUX_OVERLAY_REPLACED', row['status'])
                self.assertNotEqual(row['original']['sha256'], digest)
                self.assertNotIn(digest, digests); digests.add(digest)
                self.assertEqual(Path(row['resource']).suffix, Path(row['overlay']).suffix)
                if row['resource'].endswith('.svg'):
                    svg, old = ET.fromstring(data), ET.fromstring(original)
                    self.assertEqual({k: old.get(k) for k in ('width', 'height', 'viewBox')},
                                     {k: svg.get(k) for k in ('width', 'height', 'viewBox')})
                    self.assertEqual(row['dimensions'], {k: v for k, v in svg.attrib.items() if k in ('width', 'height', 'viewBox')})
                    self.assertLessEqual({x.tag.split('}')[-1] for x in svg.iter()}, {'svg', 'title', 'rect', 'path'})
                    self.assertFalse(any('href' in k or k.lower().startswith('on') for x in svg.iter() for k in x.attrib))
                    self.assertNotIn(b'<!', data.replace(b'<!--', b''))
                    self.assertNotIn(b'<text', data)
                    self.assertLess(len(data), 16384)
                else:
                    self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
                    # IHDR: same pixel size as the original, 8-bit RGBA (transparent ARGB source).
                    self.assertEqual(original[16:24], data[16:24])
                    self.assertEqual(b'\x08\x06', data[24:26])
                    self.assertEqual({'width': int.from_bytes(data[16:20], 'big'), 'height': int.from_bytes(data[20:24], 'big'),
                                      'mode': 'RGBA'}, row['dimensions'])

    def test_recipe_reproduces_linux_overlay_byte_identically(self):
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
        generated = output / 'linux-overlay'
        produced = {p.relative_to(generated).as_posix(): p.read_bytes() for p in generated.rglob('*') if p.is_file()}
        self.assertEqual({row['resource'] for row in self.overlay}, set(produced))
        for name, data in produced.items():
            with self.subTest(resource=name):
                self.assertEqual((contract.OVERLAY / name).read_bytes(), data)
        # The pre-existing ten release outputs are unchanged by the overlay recipe.
        files = self.release['authoring']['toolchain']['files']
        for name in [n for n in files if n.endswith('.svg')]:
            self.assertEqual(files[name], hashlib.sha256((output / name).read_bytes()).hexdigest())

    def test_replacement_copies_are_rejected_at_renamed_paths_and_across_jars(self):
        for row in self.release['replacements'] + self.overlay:
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
