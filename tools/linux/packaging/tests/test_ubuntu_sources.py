import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compliance import ubuntu_sources as source


def digest(data):
    return hashlib.sha256(data).hexdigest()


class UbuntuSourceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.dsc = ('Format: 3.0 (quilt)\nSource: fixture\nVersion: 1:2-3\nChecksums-Sha256:\n ' +
                    digest(b'source') + ' 6 fixture.orig.tar.xz\n').encode()
        self.record = {'Package': 'fixture', 'Version': '1:2-3', 'Directory': 'pool/main/f/fixture',
                       'Checksums-Sha256': '\n' + digest(self.dsc) + ' ' + str(len(self.dsc)) +
                       ' fixture.dsc\n' + digest(b'source') + ' 6 fixture.orig.tar.xz'}
        self.provenance = {'builder_image_id': 'b' * 64, 'packages': {
            'libfixture:amd64': {'binary_version': '1:2-3', 'source_package': 'fixture', 'source_version': '1:2-3'}}}

    def tearDown(self):
        self.tmp.cleanup()

    def lock(self, records=None):
        return source.source_lock(self.provenance, records or [self.record], [])

    def test_exact_version_epoch_mapping_and_deduplication(self):
        result = self.lock([dict(self.record, Version='1:2-2'), self.record, self.record])
        self.assertEqual(len(result['source_packages']), 1)
        row = result['source_packages'][0]
        self.assertEqual(row['version'], '1:2-3')
        self.assertEqual(row['binaries'][0]['package'], 'libfixture:amd64')
        self.assertEqual(row['inputs'][0]['url'], source.SNAPSHOT + 'pool/main/f/fixture/fixture.dsc')
        with self.assertRaisesRegex(ValueError, 'never substitute'):
            self.lock([dict(self.record, Version='1:2-2')])

    def test_conflicting_signed_records_and_missing_dsc_rejected(self):
        same_bytes = self.lock([self.record, dict(self.record, Directory='pool/universe/f/fixture')])
        self.assertEqual(len(same_bytes['source_packages']), 1)
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            self.lock([self.record, dict(self.record, **{'Checksums-Sha256': digest(b'other') + ' 5 fixture.dsc'})])
        with self.assertRaisesRegex(ValueError, 'missing dsc'):
            self.lock([dict(self.record, **{'Checksums-Sha256': digest(b'source') + ' 6 fixture.tar'})])

    def test_index_parser_rejects_duplicates_traversal_and_bad_hashes(self):
        with self.assertRaises(ValueError): source.paragraphs('Package: a\nPackage: b')
        with self.assertRaises(ValueError): source.paragraphs(' continuation')
        for name in ['../secret', '/secret', 'pool/../../secret', 'a\\b', 'a//b', 'a/./b']:
            with self.subTest(name=name), self.assertRaises(ValueError): source.safe_path(name)
        with self.assertRaises(ValueError): source.hash_rows('not-a-hash 1 file')
        with self.assertRaises(ValueError): source.hash_rows((digest(b'a') + ' 1 a\n') * 2)

    def test_retained_inputs_verified_offline_no_network(self):
        for data in [self.dsc, b'source']:
            (self.root / digest(data)).write_bytes(data)
        with patch.object(source.urllib.request, 'urlopen', side_effect=AssertionError('network')):
            result = source.retain(self.lock(), self.root, offline=True)
        self.assertEqual(result['verified_inputs'], 2)
        self.assertEqual(result['bytes'], len(self.dsc) + 6)
        self.assertEqual(result['verified_source_packages'], 1)
        self.assertFalse(result['uploaded'])
        (self.root / digest(b'source')).write_bytes(b'bad')
        with self.assertRaises(ValueError): source.retain(self.lock(), self.root, offline=True)

    def test_missing_and_symlinked_archives_fail_without_fetch(self):
        with patch.object(source.urllib.request, 'urlopen', side_effect=AssertionError('network')):
            with self.assertRaises(ValueError): source.retain(self.lock(), self.root, offline=True)
            (self.root / 'outside').write_bytes(self.dsc)
            (self.root / digest(self.dsc)).symlink_to('outside')
            with self.assertRaises(ValueError): source.retain(self.lock(), self.root)

    def test_signed_release_chain_and_source_hash_must_both_match(self):
        data = 'Package: fixture\nVersion: 1:2-3\n\n'
        for suite in source.SUITES:
            (self.root / (suite + '.InRelease')).write_text('signed fixture')
            for section in source.SECTIONS:
                (self.root / (suite + '-' + section + '.Sources')).write_text(data)
        def verified(command, **kwargs):
            suite = Path(command[-1]).name.removesuffix('.InRelease')
            release = f'Suite: {suite}\nCodename: jammy\nSHA256:\n'
            for section in source.SECTIONS:
                release += f' {digest(data.encode())} {len(data)} {section}/source/Sources\n'
            return subprocess.CompletedProcess(command, 0, release.encode(),
                                               ('[GNUPG:] VALIDSIG ' + source.SIGNER + ' rest\n').encode())
        with patch.object(source.subprocess, 'run', side_effect=verified):
            rows, evidence = source.verify_indexes(self.root)
            self.assertEqual(len(rows), 6)
            self.assertEqual(len(evidence), 3)
            (self.root / 'jammy-main.Sources').write_text('tampered')
            with self.assertRaisesRegex(ValueError, 'signed release'):
                source.verify_indexes(self.root)
        with patch.object(source.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')):
            with self.assertRaisesRegex(ValueError, 'signing key'): source.verify_indexes(self.root)
        with patch.object(source.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['gpgv'])):
            with self.assertRaises(subprocess.CalledProcessError): source.verify_indexes(self.root)

    def test_unpinned_source_url_is_rejected(self):
        lock = self.lock()
        lock['source_packages'][0]['inputs'][0]['url'] = 'https://example.test/fixture.dsc'
        with self.assertRaisesRegex(ValueError, 'pinned snapshot'): source.retain(lock, self.root)

    def test_download_checks_signed_size_hash_and_preserves_existing_partial(self):
        lock = self.lock()
        (self.root / digest(b'source')).write_bytes(b'source')
        spec = lock['source_packages'][0]['inputs'][0]
        def response(data):
            stream = io.BytesIO(data); stream.url = spec['url']; return stream
        with patch.object(source.urllib.request, 'urlopen', return_value=response(self.dsc)):
            self.assertEqual(source.retain(lock, self.root)['verified_inputs'], 2)
        path = self.root / spec['sha256']; self.assertEqual(path.read_bytes(), self.dsc); path.unlink()
        for data in [b'bad', self.dsc + b'too long']:
            with self.subTest(data=data), patch.object(source.urllib.request, 'urlopen', return_value=response(data)):
                with self.assertRaises(ValueError): source.retain(lock, self.root)
                self.assertFalse(path.exists())
                self.assertFalse((self.root / (spec['sha256'] + '.partial')).exists())
        partial = self.root / (spec['sha256'] + '.partial'); partial.write_bytes(b'other attempt')
        with patch.object(source.urllib.request, 'urlopen', return_value=response(self.dsc)):
            with self.assertRaises(FileExistsError): source.retain(lock, self.root)
        self.assertEqual(partial.read_bytes(), b'other attempt')

    def test_publish_does_not_overwrite_concurrently_created_final_input(self):
        lock = self.lock()
        (self.root / digest(b'source')).write_bytes(b'source')
        target = self.root / digest(self.dsc)
        stream = io.BytesIO(self.dsc); stream.url = lock['source_packages'][0]['inputs'][0]['url']
        original_link = source.os.link
        def raced_link(src, dst, **kwargs):
            Path(dst).write_bytes(b'created concurrently')
            original_link(src, dst, **kwargs)
        with patch.object(source.urllib.request, 'urlopen', return_value=stream), \
                patch.object(source.os, 'link', side_effect=raced_link):
            with self.assertRaises(FileExistsError): source.retain(lock, self.root)
        self.assertEqual(target.read_bytes(), b'created concurrently')
        self.assertFalse(target.with_suffix('.partial').exists())

    def test_symlinked_output_and_ancestor_are_rejected_before_network_or_write(self):
        real = self.root / 'real'; real.mkdir()
        link = self.root / 'link'; link.symlink_to(real, target_is_directory=True)
        with patch.object(source.urllib.request, 'urlopen', side_effect=AssertionError('network')):
            for output in [link, link / 'nested']:
                with self.subTest(output=output), self.assertRaisesRegex(ValueError, 'Symlinked'):
                    source.retain(self.lock(), output)
        self.assertEqual(list(real.iterdir()), [])

    def test_dsc_identity_and_complete_archive_closure_are_required(self):
        lock = self.lock(); row = lock['source_packages'][0]
        for data in [self.dsc, b'source']: (self.root / digest(data)).write_bytes(data)
        source.verify_dsc_closure(row, self.root)
        for name, value in [('package', 'wrong'), ('version', 'wrong')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'identity'):
                source.verify_dsc_closure(dict(row, **{name: value}), self.root)
        missing = dict(row, inputs=[row['inputs'][0]])
        with self.assertRaisesRegex(ValueError, 'closure'): source.verify_dsc_closure(missing, self.root)
        extra = dict(row, inputs=row['inputs'] + [dict(row['inputs'][1], filename='unexpected.tar')])
        with self.assertRaisesRegex(ValueError, 'closure'): source.verify_dsc_closure(extra, self.root)
        multiple = dict(row, inputs=row['inputs'] + [dict(row['inputs'][0], filename='other.dsc')])
        with self.assertRaisesRegex(ValueError, 'exactly one'): source.verify_dsc_closure(multiple, self.root)

    def test_clearsigned_dsc_uses_hash_authenticated_cleartext(self):
        signed = (b'-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n' + self.dsc +
                  b'\n-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n')
        self.assertEqual(source.source_control_text(signed), source.source_control_text(self.dsc))
        for data in [signed.replace(b'\n\n', b'\n', 1), signed.split(b'-----BEGIN PGP SIGNATURE-----')[0],
                     signed.replace(b'-----END PGP SIGNATURE-----', b'')]:
            with self.subTest(data=data), self.assertRaises(ValueError): source.source_control_text(data)


if __name__ == '__main__':
    unittest.main()
