"""Pinned native third-party notices; no network, native code or legal certification."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import notice_contract as contract


class NoticeContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.image = Path(self.temp.name)

    def test_manifest_pins_every_tracked_notice_file(self):
        manifest = contract.manifest()
        tracked = {p.relative_to(contract.NOTICES).as_posix() for p in contract.NOTICES.rglob('*') if p.is_file() and p.name != '.gitattributes'}
        self.assertEqual(tracked, set(contract.expected()))
        for component, record in manifest['components'].items():
            with self.subTest(component=component):
                self.assertRegex(record['revision'], '^[0-9a-f]{40}$')
                self.assertTrue(record['source'].startswith('https://'))
                for name, digest in record['files'].items():
                    data = (contract.NOTICES / component / name).read_bytes()
                    self.assertEqual(digest, hashlib.sha256(data).hexdigest())
                    self.assertGreater(len(data), 100)

    def test_skia_static_libraries_have_notice_owners(self):
        manifest = contract.manifest()
        owners = {'bentleyottmann': 'skia', 'jsonreader': 'skia', 'skcms': 'skia', 'skia': 'skia', 'skottie': 'skia',
                  'skparagraph': 'skia', 'skresources': 'skia', 'sksg': 'skia', 'skshaper': 'skia',
                  'skunicode_core': 'skia', 'skunicode_icu': 'skia', 'svg': 'skia', 'dng_sdk': 'dng_sdk',
                  'expat': 'expat', 'freetype2': 'freetype', 'harfbuzz': 'harfbuzz', 'icu': 'icu',
                  'jpeg': 'libjpeg-turbo', 'jpeg12': 'libjpeg-turbo', 'jpeg16': 'libjpeg-turbo', 'piex': 'piex',
                  'png': 'libpng', 'webp': 'libwebp', 'webp_sse41': 'libwebp', 'wuffs': 'wuffs', 'zlib': 'zlib'}
        libraries = {name.removeprefix('lib').removesuffix('.a') for name in manifest['skia_pack']['static_libraries']}
        self.assertEqual(set(owners), libraries)
        for library, owner in owners.items():
            self.assertIn('skia/' + owner, manifest['components'], library)

    def test_summary_carries_attribution_required_by_ftl_and_dng(self):
        text = (contract.NOTICES / 'NOTICE.txt').read_text()
        self.assertIn('The FreeType Project', text)
        self.assertIn('DNG technology under license by Adobe', text)
        for component in contract.manifest()['components']:
            self.assertIn(component.split('/')[0] + '/', text)

    def test_staged_notices_verify_and_missing_or_tampered_files_fail(self):
        contract.stage_notices(self.image)
        report = contract.verify_packaged_notices(self.image)
        self.assertEqual(len(contract.expected()), report['files'])
        root = self.image / contract.TARGET
        (root / 'skia/freetype/FTL.TXT').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            contract.verify_packaged_notices(self.image)
        (root / 'skia/freetype/FTL.TXT').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing native third-party notice'):
            contract.verify_packaged_notices(self.image)

    def test_tampered_summary_is_rejected_before_staging(self):
        manifest = json.loads((contract.NOTICES / 'manifest.json').read_text())
        self.assertEqual(manifest['notice_sha256'],
                         hashlib.sha256((contract.NOTICES / 'NOTICE.txt').read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
