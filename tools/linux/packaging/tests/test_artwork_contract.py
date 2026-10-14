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
        self.payload = {}
        for name in contract.expected_resources():
            relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(contract.PREFIX)
                        if name.startswith(contract.PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
            self.payload[name] = (contract.repository() / relative).read_bytes()

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
        self.assertEqual(32, len(report['resources']))
        self.assertEqual(6, report['replacement_quality_badges'])
        self.assertEqual(4, report['replacement_setup_previews'])
        self.assertEqual(9, report['removed_resources'])
        self.assertEqual(4, report['unresolved_exact_path_exceptions'])
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
        self.assertEqual(9, report['excluded_from_packaging'])
        self.assertEqual(47, len(report['remaining_artwork']))
        drawables = contract.repository() / 'composeApp/src/commonMain/composeResources/drawable'
        scoped = {p.relative_to(contract.repository()).as_posix() for p in drawables.iterdir()
                  if p.name.startswith(('rating_', 'hero_badge_', 'quality_badge_')) or
                  p.stem in ('discord_logo', 'kitsu_logo', 'mdblist_logo', 'simkl_logo',
                             'trakt_logo_wordmark', 'trakt_tv_favicon', 'tvdb_logo', 'introdb_favicon')}
        scoped.update(p.relative_to(contract.repository()).as_posix() for p in
                      (contract.repository() / 'composeApp/src/desktopMain/resources/setup').glob('*.webp'))
        self.assertEqual(scoped, set(report['remaining_artwork']) | set(report['resolved_artwork']))
        for name, row in report['remaining_artwork'].items():
            self.assertEqual('UNRESOLVED', row['status'])
            self.assertEqual(row['sha256'], hashlib.sha256((contract.repository() / name).read_bytes()).hexdigest())
        self.assertEqual(0, sum(row['category'] in ('setup-preview', 'quality-format-mark') for row in report['remaining_artwork'].values()))

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
        self.assertEqual(9, len(self.release['removed']))
        for row in self.release['removed']:
            self.assertFalse((contract.repository() / row['resource']).exists())
            for name in (row['filename'], 'another/' + row['filename'].upper(), 'another\\' + row['filename']):
                payload = dict(self.payload); payload[name] = b'different new bytes'; self.jar(payload)
                with self.assertRaisesRegex(ValueError, 'Removed artwork filename'):
                    contract.verify_packaged_artwork(self.root)

    def test_four_umbra_originals_are_allowed_only_at_their_exact_pinned_paths(self):
        self.assertEqual(4, len(self.release['retained_hash_exceptions']))
        self.jar(self.payload); contract.verify_packaged_artwork(self.root)
        for row in self.release['retained_hash_exceptions']:
            self.assertEqual('UNRESOLVED', row['status'])
            name = contract.packaged_path(row['resource'])
            for target in ('renamed.bin', name.upper(), name.replace('/drawable/', '/other/')):
                payload = dict(self.payload); payload[target] = payload[name]; self.jar(payload)
                with self.assertRaisesRegex(ValueError, 'Forbidden historical artwork bytes'):
                    contract.verify_packaged_artwork(self.root)

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
