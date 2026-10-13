"""Focused asset/provenance payload checks; no network, native player or legal certification."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract as contract


class ArtworkContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'lib/app').mkdir(parents=True)
        self.provenance = json.loads(contract.PROVENANCE.read_text())
        self.payload = {}
        for name in contract.expected_resources():
            relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(contract.PREFIX)
                        if name.startswith(contract.PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
            self.payload[name] = (contract.REPO / relative).read_bytes()

    def jar(self, payload, filename='application.jar'):
        with zipfile.ZipFile(self.root / 'lib/app' / filename, 'w') as jar:
            for name, data in payload.items():
                jar.writestr(name, data)

    def test_seven_original_vectors_preserve_sizes_use_safe_svg_and_have_real_mit_notice(self):
        self.assertEqual(7, len(self.provenance['assets']))
        for row in self.provenance['assets']:
            data = (contract.REPO / row['resource']).read_bytes()
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
        text = contract.PROVENANCE.with_name('LICENSE.txt').read_text()
        self.assertIn('Copyright (c) 2026 NuvioDesktop-Linux contributors', text)
        self.assertIn('Permission is hereby granted', text)

    def test_actual_pinned_icons_data_and_notices_pass_without_claiming_rights_clearance(self):
        self.jar(self.payload)
        report = contract.verify_packaged_artwork(self.root)
        self.assertEqual(7, report['replacement_icons'])
        self.assertEqual(15, len(report['resources']))
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
        report = json.loads((contract.REPO / 'docs/compliance/evidence/artwork-data-review.json').read_text())
        self.assertEqual('BLOCKED', report['redistribution_status'])
        self.assertEqual(7, report['replaced'])
        self.assertEqual(66, len(report['remaining_artwork']))
        drawables = contract.REPO / 'composeApp/src/commonMain/composeResources/drawable'
        scoped = {p.relative_to(contract.REPO).as_posix() for p in drawables.iterdir()
                  if p.name.startswith(('rating_', 'hero_badge_', 'quality_badge_')) or
                  p.stem in ('discord_logo', 'kitsu_logo', 'mdblist_logo', 'simkl_logo',
                             'trakt_logo_wordmark', 'trakt_tv_favicon', 'tvdb_logo', 'introdb_favicon')}
        scoped.update(p.relative_to(contract.REPO).as_posix() for p in
                      (contract.REPO / 'composeApp/src/desktopMain/resources/setup').glob('*.webp'))
        self.assertEqual(scoped, set(report['remaining_artwork']))
        for name, row in report['remaining_artwork'].items():
            self.assertEqual('UNRESOLVED', row['status'])
            self.assertEqual(row['sha256'], hashlib.sha256((contract.REPO / name).read_bytes()).hexdigest())
        self.assertEqual(4, sum(row['category'] == 'setup-preview' for row in report['remaining_artwork'].values()))


if __name__ == '__main__':
    unittest.main()
