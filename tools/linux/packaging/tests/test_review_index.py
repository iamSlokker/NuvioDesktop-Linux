import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compliance import review_index as review


class ReviewIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inventory = self.root / review.INVENTORY
        self.inventory.parent.mkdir(parents=True)
        self.artifacts = {'deb': ('fixture.deb', 'a' * 64),
                          'flatpak': ('fixture.flatpak', 'b' * 64),
                          'appimage': ('fixture.AppImage', 'c' * 64)}
        self.data = {'package_commit': 'd' * 40,
                     'artifacts': {name: digest for name, digest in self.artifacts.values()}}
        self.data['artifacts']['fixture-source.tar.gz'] = 'e' * 64
        self.inventory.write_text(json.dumps(self.data))
        (self.root / 'receipt.json').write_text('{"local_retention_only": true}')
        (self.root / 'verify.py').write_text('# fixture verifier\n')
        self.options = {'inventory_sha256': self.digest(self.inventory),
                        'package_commit': 'd' * 40, 'artifacts': self.artifacts,
                        'references': {'receipt.json': 'local receipt only', 'verify.py': 'verifier bytes only'}}

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def build(self):
        return review.build_index(self.root, **self.options)

    def test_binds_original_artifacts_sources_receipts_and_verifier_bytes(self):
        index = self.build()
        self.assertEqual(index['original_artifacts'], self.data['artifacts'])
        self.assertEqual(index['original_inventory']['sha256'], self.digest(self.inventory))
        self.assertEqual(index['supplements'][0]['sha256'], self.digest(self.root / 'receipt.json'))
        self.assertEqual(index['supplements'][1]['sha256'], self.digest(self.root / 'verify.py'))
        self.assertEqual(review.check_index(self.root, index, **self.options), index)
        self.assertEqual(index['status'], 'LOCAL_REVIEW_ONLY')
        self.assertFalse(index['publication_authorized'])
        self.assertEqual(set(index['redistribution_assessment'].values()), {'BLOCKED'})

    def test_receipt_and_script_tampering_invalidates_existing_index(self):
        index = self.build()
        for name in ['receipt.json', 'verify.py']:
            path = self.root / name; original = path.read_bytes(); path.write_bytes(b'changed')
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'differs'):
                review.check_index(self.root, index, **self.options)
            path.write_bytes(original)
        (self.root / 'verify.py').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing'): self.build()

    def test_original_inventory_identity_and_artifact_hashes_cannot_drift(self):
        self.data['package_commit'] = 'f' * 40
        self.inventory.write_text(json.dumps(self.data))
        with self.assertRaisesRegex(ValueError, 'immutable'): self.build()
        self.options['inventory_sha256'] = self.digest(self.inventory)
        with self.assertRaisesRegex(ValueError, 'package commit'): self.build()
        self.data['package_commit'] = 'd' * 40
        self.data['artifacts']['fixture.deb'] = '0' * 64
        self.inventory.write_text(json.dumps(self.data)); self.options['inventory_sha256'] = self.digest(self.inventory)
        with self.assertRaisesRegex(ValueError, 'artifact set/hash'): self.build()

    def test_unknown_missing_extra_formats_cannot_be_silently_ignored(self):
        for filename in ['unexpected.rpm', 'second.deb']:
            original = copy.deepcopy(self.data)
            self.data['artifacts'][filename] = '0' * 64
            self.inventory.write_text(json.dumps(self.data)); self.options['inventory_sha256'] = self.digest(self.inventory)
            with self.subTest(filename=filename), self.assertRaises(ValueError): self.build()
            self.data = original
        self.options['artifacts'] = {'rpm': ('fixture.rpm', '1' * 64)}
        with self.assertRaisesRegex(ValueError, 'package format'): self.build()

    def test_unsafe_paths_symlinks_and_missing_inputs_fail(self):
        for path in ['../receipt.json', '/receipt.json', './receipt.json', 'a//b', 'a\\b', '.']:
            with self.subTest(path=path), self.assertRaises(ValueError): review.file_record(self.root, path)
        (self.root / 'link').symlink_to('receipt.json')
        with self.assertRaisesRegex(ValueError, 'Symlinked'): review.file_record(self.root, 'link')
        (self.root / 'dirlink').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Symlinked'): review.file_record(self.root, 'dirlink/receipt.json')
        with self.assertRaisesRegex(ValueError, 'Missing'): review.file_record(self.root, 'missing')

    def test_index_cannot_claim_ready_or_add_unbound_scope(self):
        index = self.build(); index['redistribution_assessment']['deb'] = 'READY'
        with self.assertRaises(ValueError): review.check_index(self.root, index, **self.options)
        index = self.build(); index['packages']['rpm'] = {'name': 'fake.rpm', 'sha256': 'f' * 64}
        with self.assertRaises(ValueError): review.check_index(self.root, index, **self.options)


if __name__ == '__main__':
    unittest.main()
