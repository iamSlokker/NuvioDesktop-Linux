import hashlib
import io
import json
import pathlib
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import appimage_runtime  # noqa: E402
import appimage_runtime_published as published  # noqa: E402


def elf(sections):
    """Minimal ELF64: header, section bodies, .shstrtab, then the section header table."""
    names = b'\0' + b''.join(name.encode() + b'\0' for name, _ in sections) + b'.shstrtab\0'
    body = bytearray(64)
    headers = [bytes(64)]
    offset_of_name = 1
    for name, content in sections:
        offset = len(body)
        body += content
        headers.append(struct.pack('<IIQQQQIIQQ', offset_of_name, 1, 0, 0, offset, len(content), 0, 0, 1, 0))
        offset_of_name += len(name) + 1
    strtab = len(body)
    body += names
    headers.append(struct.pack('<IIQQQQIIQQ', offset_of_name, 3, 0, 0, strtab, len(names), 0, 0, 1, 0))
    shoff = len(body)
    body[0:4] = b'\x7fELF'
    body[4], body[5] = 2, 1
    struct.pack_into('<Q', body, 0x28, shoff)
    struct.pack_into('<HHH', body, 0x3A, 64, len(headers), len(headers) - 1)
    return bytes(body) + b''.join(headers)


class RuntimeFromAppImageTest(unittest.TestCase):
    def test_appimagetool_sections_are_zeroed_and_the_squashfs_is_cut_off(self):
        pristine = elf([('.text', b'code' * 8), ('.digest_md5', bytes(16)), ('.upd_info', bytes(32))])
        stamped = bytearray(pristine)
        offset = pristine.index(bytes(16), 64 + 32)
        stamped[offset:offset + 16] = b'\xaa' * 16
        appimage = bytes(stamped) + b'hsqs' + b'squashfs payload'
        self.assertEqual(pristine, published.runtime_from_appimage(appimage))

    def test_rejects_non_runtimes(self):
        with self.assertRaisesRegex(ValueError, 'ELF64'):
            published.runtime_from_appimage(b'#!/bin/sh\n' + bytes(100))
        with self.assertRaisesRegex(ValueError, 'digest_md5'):
            published.runtime_from_appimage(elf([('.text', b'code')]))
        truncated = elf([('.digest_md5', bytes(16))])
        with self.assertRaisesRegex(ValueError, 'outside the fetched'):
            published.runtime_from_appimage(truncated[:-10])


class RecreateTest(unittest.TestCase):
    def archive(self, path, top, receipt, notices):
        with tarfile.open(path, 'w:gz') as tar:
            files = {'BUILD-RECEIPT.json': json.dumps(receipt).encode(), 'RELINK.md': b'relink'}
            files.update({'licenses/' + name: content for name, content in notices.items()})
            for name, content in files.items():
                info = tarfile.TarInfo(top + '/' + name)
                info.size = len(content)
                tar.addfile(info, io.BytesIO(content))

    def test_recreated_directory_passes_verify_output(self):
        runtime = elf([('.digest_md5', bytes(16))])
        pin = hashlib.sha256(runtime).hexdigest()
        notices = {'musl/COPYRIGHT': b'musl', 'zlib/LICENSE': b'zlib'}
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(appimage_runtime, 'expected_sha256', return_value=pin), \
                mock.patch.object(appimage_runtime, 'required_notices', return_value=list(notices)):
            tmp = pathlib.Path(tmp)
            top = appimage_runtime.archive_name(pin)[:-len('.tar.gz')]
            source = tmp / 'NuvioDesktop-Linux-9.9.9-source-appimage-runtime.tar.gz'
            self.archive(source, top, {'runtime': {'sha256': pin}}, notices)
            result = published.recreate(tmp / 'out', runtime, source, {'release': 'v9.9.9'})
            self.assertEqual(pin, appimage_runtime.sha256(result['runtime']))
            self.assertEqual(source.read_bytes(), result['source_archive'].read_bytes())
            self.assertEqual({'release': 'v9.9.9'}, result['receipt']['recreated_from'])

            with self.assertRaisesRegex(ValueError, 'not the pinned runtime'):
                published.recreate(tmp / 'other', runtime + b'x', source, {})
            wrong = tmp / 'wrong.tar.gz'
            self.archive(wrong, top, {'runtime': {'sha256': '0' * 64}}, notices)
            with self.assertRaisesRegex(ValueError, 'receipt does not describe'):
                published.recreate(tmp / 'other', runtime, wrong, {})
            renamed = tmp / 'renamed.tar.gz'
            self.archive(renamed, 'Nuvio-AppImage-runtime-source-000000000000', {'runtime': {'sha256': pin}}, notices)
            with self.assertRaisesRegex(ValueError, 'Source archive is not'):
                published.recreate(tmp / 'other', runtime, renamed, {})


class PackageHookTest(unittest.TestCase):
    def test_package_tries_a_published_runtime_before_building(self):
        package = (pathlib.Path(__file__).resolve().parents[1] / 'webkit/package.py').read_text()
        self.assertIn('appimage_runtime_published.from_github(', package)
        self.assertLess(package.index('from_github('), package.index('prepare_runtime(work'))


if __name__ == '__main__':
    unittest.main()
