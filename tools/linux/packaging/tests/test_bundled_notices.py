"""Offline byte/notice checks, not a legal or complete-source certification."""
import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[4]
RESOURCES = ROOT / 'composeApp/src/desktopMain/resources'


class BundledNoticesTest(unittest.TestCase):
    def test_complete_publisher_license_bytes_remain_bundled(self):
        # Full texts linked by the authoritative ODC version-1.0 license pages.
        expected = {
            'ODBL-1.0.txt': ('607680718977f6f6c9607972afd98f208573f19251315ed1362a8589b51beaf5', 25278),
            'DBCL-1.0.txt': ('3cbfa990f26df856c6a7d3fff3d1f236e3b0165b1e8257ca0d00f15758754f81', 2374),
        }
        provenance = json.loads((RESOURCES / 'anime-mapping/PROVENANCE.json').read_text())
        self.assertEqual({row['path'] for row in provenance['license_texts']}, set(expected))
        for row in provenance['license_texts']:
            data = (RESOURCES / 'anime-mapping' / row['path']).read_bytes()
            with self.subTest(path=row['path']):
                self.assertEqual((hashlib.sha256(data).hexdigest(), len(data)), expected[row['path']])
                self.assertEqual((row['sha256'], row['bytes']), expected[row['path']])
                self.assertTrue(row['url'].startswith('https://opendatacommons.org/licenses/'))

    def test_notice_binds_actual_dataset_and_import_without_inventing_generation_identity(self):
        provenance = json.loads((RESOURCES / 'anime-mapping/PROVENANCE.json').read_text())
        row = provenance['dataset']
        data = (RESOURCES / row['resource']).read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])
        self.assertEqual(len(data), row['bytes'])
        blob = b'blob ' + str(len(data)).encode() + b'\0' + data
        self.assertEqual(hashlib.sha1(blob).hexdigest(), row['repository_git_blob'])
        self.assertEqual(row['upstream_revision'], '4ddbccd752fb4fe39da71f9729b33e4fd7257555')
        self.assertEqual(row['entry_count'], 39577)
        self.assertIn(row['upstream_revision'], row['upstream_url'])
        self.assertIsNone(row['generation_recipe'])
        self.assertIsNone(provenance['generation_investigation']['original_input_revisions'])
        self.assertIsNone(provenance['generation_investigation']['run_receipt'])
        self.assertEqual(provenance['regeneration_evaluation']['decision'].split(';')[0], 'NOT_REGENERATED')
        self.assertTrue(provenance['remaining_blockers'])
        notice = (RESOURCES / 'anime-mapping/NOTICE.txt').read_text()
        for name in ('Fribb/anime-lists', 'anime-offline-database', 'Anime-Lists/anime-lists',
                     'ODBL-1.0.txt', 'DBCL-1.0.txt', 'PROVENANCE.json'):
            self.assertIn(name, notice)

    def test_shader_notice_preserves_the_actual_fourteen_mit_two_unlicense_split(self):
        directory = RESOURCES / 'player-shaders'
        public_domain = {'Anime4K_AutoDownscalePre_x2.glsl', 'Anime4K_AutoDownscalePre_x4.glsl'}
        shaders = sorted(directory.glob('*.glsl'))
        self.assertEqual(len(shaders), 16)
        mit = []
        for shader in shaders:
            text = shader.read_text()
            if shader.name in public_domain:
                self.assertIn('This is free and unencumbered software released into the public domain.', text)
                self.assertIn('For more information, please refer to <https://unlicense.org>', text)
                self.assertNotIn('Permission is hereby granted', text)
            else:
                self.assertRegex(text, r'Copyright \(c\) 2019(?:-2021)? bloc97')
                self.assertIn('Permission is hereby granted', text)
                self.assertIn('THE SOFTWARE IS PROVIDED "AS IS"', text)
                mit.append(shader.name)
        self.assertEqual(len(mit), 14)
        notice = (directory / 'LICENSE.md').read_text()
        self.assertIn('fourteen shaders', notice)
        for name in public_domain:
            self.assertIn(name, notice)
        self.assertIn('MIT notice below\ndoes not replace those terms', notice)


if __name__ == '__main__':
    unittest.main()
