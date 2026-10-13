"""Font delivery contract against real pinned resource bytes; no application/display/network."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import font_contract as contract

REPO = Path(__file__).resolve().parents[4]


class FontContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'lib/app').mkdir(parents=True)
        self.manifest = json.loads(contract.MANIFEST.read_text())
        self.records = self.manifest['files'] + [self.manifest['license_file'], self.manifest['notice_file']]
        self.payload = {contract.PREFIX + item['path'].split('/composeResources/', 1)[1]:
                        (REPO / item['path']).read_bytes() for item in self.records}

    def jar(self, payload=None, name='application.jar'):
        with zipfile.ZipFile(self.root / 'lib/app' / name, 'w') as jar:
            for filename, content in (self.payload if payload is None else payload).items():
                jar.writestr(filename, content)

    def test_actual_pinned_font_and_license_bytes_are_required_in_packaged_jar(self):
        self.jar()
        report = contract.verify_packaged_fonts(self.root)
        self.assertEqual('4.1', report['version'])
        self.assertEqual('OFL-1.1', report['license'])
        self.assertEqual(set(self.payload), set(report['resources']))
        self.assertEqual([400, 600, 700], [item['weight'] for item in self.manifest['files']])
        for item in self.records:
            self.assertEqual(item['sha256'], hashlib.sha256((REPO / item['path']).read_bytes()).hexdigest())
        license_text = (REPO / self.manifest['license_file']['path']).read_text()
        self.assertIn('SIL OPEN FONT LICENSE Version 1.1', license_text)
        self.assertIn('The Inter Project Authors', license_text)
        for item in self.manifest['removed_fonts']:
            self.assertFalse((REPO / 'composeApp/src/commonMain/composeResources/font' / item['filename']).exists())

    def test_missing_or_tampered_font_and_license_fail(self):
        for name in self.payload:
            for kind in ['missing', 'tampered']:
                with self.subTest(name=name, kind=kind):
                    payload = dict(self.payload)
                    if kind == 'missing':
                        del payload[name]
                    else:
                        payload[name] += b'corrupt'
                    self.jar(payload)
                    with self.assertRaisesRegex(ValueError, 'Missing|hash mismatch'):
                        contract.verify_packaged_fonts(self.root)

    def test_duplicate_resources_and_old_named_or_renamed_fonts_fail(self):
        self.jar()
        self.jar(self.payload, 'duplicate.jar')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            contract.verify_packaged_fonts(self.root)
        (self.root / 'lib/app/duplicate.jar').unlink()
        self.jar({'font/jetbrains_sans_regular.ttf': b'old font'}, 'old.jar')
        with self.assertRaisesRegex(ValueError, 'Removed unlicensed font'):
            contract.verify_packaged_fonts(self.root)
        # Historical binaries are deliberately absent from the repository. Substitute
        # a small fixture digest to exercise detection even if a font is renamed.
        manifest = dict(self.manifest)
        manifest['removed_fonts'] = [{'filename': 'old.ttf', 'sha256': hashlib.sha256(b'old font').hexdigest()}]
        fixture = self.root / 'manifest.json'
        fixture.write_text(json.dumps(manifest))
        self.jar({'font/renamed.ttf': b'old font'}, 'old.jar')
        with patch.object(contract, 'MANIFEST', fixture), self.assertRaisesRegex(ValueError, 'Removed unlicensed font bytes'):
            contract.verify_packaged_fonts(self.root)


if __name__ == '__main__':
    unittest.main()
