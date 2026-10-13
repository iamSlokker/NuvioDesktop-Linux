import copy
import hashlib
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compliance import dependency_sources as sources


class DependencySourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / 'a-sources.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('A.java', 'class A {}')
            archive.writestr('META-INF/LICENSE', 'license fixture')
        self.pom = self.root / 'a.pom'
        self.pom.write_text('<project/>')
        self.component = 'maven:example:a:1'
        self.inventory = {'package_commit': 'a' * 40, 'components': {self.component: {
            'upstream_binary_sha256': 'b' * 64,
            'source': {'url': 'https://example.invalid/a-sources.jar', 'sha256': sources.sha256(self.jar)},
            'pom': {'url': 'https://example.invalid/a.pom', 'sha256': sources.sha256(self.pom)}}}}
        self.row = {'component': self.component, 'url': 'https://example.invalid/a-sources.jar',
                    'binary_sha256': 'b' * 64, 'path': self.jar.name, 'sha256': sources.sha256(self.jar),
                    'metadata': [{'url': 'https://example.invalid/a.pom', 'path': self.pom.name,
                                  'sha256': sources.sha256(self.pom)}], **sources.inspect_sources(self.jar)}
        self.receipt = {'audit_package_commit': 'a' * 40, 'maven': [self.row]}

    def check(self):
        return sources.verify(self.inventory, self.receipt, self.root)

    def test_actual_source_bytes_metadata_and_notice_receipt(self):
        result = self.check()
        self.assertEqual(result['verified_maven_source_artifacts'], 1)
        self.assertEqual(result['code_empty_artifacts'], 0)
        self.assertIn('NOT_REDISTRIBUTION_APPROVAL', result['assessment'])

    def test_missing_changed_source_and_metadata_fail(self):
        original = self.jar.read_bytes()
        self.jar.write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.check()
        self.jar.write_bytes(original)
        self.pom.write_text('tampered')
        with self.assertRaises(ValueError): self.check()
        self.pom.unlink()
        with self.assertRaises(ValueError): self.check()

    def test_identity_component_coverage_and_duplicate_receipts_fail(self):
        for edit in [lambda: self.receipt.update(audit_package_commit='wrong'),
                     lambda: self.receipt['maven'].append(copy.deepcopy(self.row)),
                     lambda: self.receipt.update(maven=[]),
                     lambda: self.row.update(binary_sha256='c' * 64),
                     lambda: self.row.update(url='https://example.invalid/other.jar')]:
            before = copy.deepcopy(self.receipt)
            edit()
            with self.assertRaises(ValueError): self.check()
            self.receipt = before
            self.row = self.receipt['maven'][0]

    def test_published_sha256_and_metadata_pin_cannot_be_replaced(self):
        self.inventory['components'][self.component]['source']['sha256'] = 'c' * 64
        with self.assertRaises(ValueError): self.check()
        self.inventory['components'][self.component]['source']['sha256'] = sources.sha256(self.jar)
        self.row['metadata'][0]['sha256'] = 'd' * 64
        with self.assertRaises(ValueError): self.check()

    def test_inherited_license_requires_matching_retained_parent_pom(self):
        parent = self.root / 'parent.pom'; parent.write_text('<project><licenses/></project>')
        evidence = {'coordinates': 'example:parent:1', 'sha256': sources.sha256(parent)}
        self.inventory['components'][self.component]['pom']['parent_license_evidence'] = evidence
        with self.assertRaisesRegex(ValueError, 'parent license'): self.check()
        self.row['parent_metadata'] = [dict(evidence, path=parent.name)]
        self.check()
        parent.write_text('changed parent license')
        with self.assertRaises(ValueError): self.check()

    def test_source_path_escape_and_symlink_are_rejected(self):
        for name in ['../a-sources.jar', '/a-sources.jar', './a-sources.jar', 'a\\b', '']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                sources.checked_file(self.root, name, self.row['sha256'])
        (self.root / 'link').symlink_to(self.jar)
        with self.assertRaises(ValueError): sources.checked_file(self.root, 'link', self.row['sha256'])
        (self.root / 'directory').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            sources.checked_file(self.root, 'directory/a-sources.jar', self.row['sha256'])

    def test_unsafe_duplicate_and_symlink_archive_entries_are_rejected(self):
        for name in ['../A.java', '/A.java', 'dir/../A.java', 'dir\\A.java']:
            with zipfile.ZipFile(self.jar, 'w') as archive: archive.writestr(name, 'source')
            with self.subTest(name=name), self.assertRaises(ValueError): sources.inspect_sources(self.jar)
        entry = zipfile.ZipInfo('link'); entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(self.jar, 'w') as archive: archive.writestr(entry, '../outside')
        with self.assertRaises(ValueError): sources.inspect_sources(self.jar)
        with warnings.catch_warnings(), zipfile.ZipFile(self.jar, 'w') as archive:
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('A.java', 'first')
            archive.writestr('A.java', 'second')
        with self.assertRaises(ValueError): sources.inspect_sources(self.jar)

    def test_empty_and_native_containing_sources_are_not_mistaken_for_native_closure(self):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0')
            archive.writestr('native/libfixture.so', b'\x7fELFfixture')
        detail = sources.inspect_sources(self.jar)
        self.assertEqual(detail['code_file_count'], 0)
        self.assertEqual(detail['embedded_native_binaries'], [{'path': 'native/libfixture.so',
                         'sha256': hashlib.sha256(b'\x7fELFfixture').hexdigest()}])

    def test_copyright_icon_source_is_not_mistaken_for_license_text(self):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('icons/Copyright.kt', 'fun copyrightIcon() = icon()')
            archive.writestr('META-INF/LICENSE.txt', 'license fixture')
        detail = sources.inspect_sources(self.jar)
        self.assertEqual(len(detail['notices']), 1)
        self.assertEqual(detail['notices'][0]['path'], 'META-INF/LICENSE.txt')

    def test_content_and_repository_checksum_receipts_are_verified(self):
        self.row['repository_checksum'] = {'algorithm': 'sha1', 'digest': hashlib.sha1(self.jar.read_bytes()).hexdigest()}
        self.check()
        self.row['repository_checksum']['digest'] = '0' * 40
        with self.assertRaises(ValueError): self.check()
        del self.row['repository_checksum']
        self.row['notices'][0]['sha256'] = '0' * 64
        with self.assertRaises(ValueError): self.check()

    def test_additional_source_archives_and_evidence_require_actual_hashes(self):
        extra = self.root / 'source.tar.gz'; extra.write_bytes(b'source fixture')
        self.receipt['additional_retained_inputs'] = [{'path': extra.name, 'sha256': sources.sha256(extra)}]
        self.assertEqual(self.check()['verified_additional_inputs'], 1)
        extra.write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.check()
        extra.write_bytes(b'source fixture')
        self.receipt['additional_retained_inputs'] *= 2
        with self.assertRaises(ValueError): self.check()


if __name__ == '__main__':
    unittest.main()
