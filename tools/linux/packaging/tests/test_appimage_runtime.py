"""Source-built AppImage runtime: pins, input refusal, assembly and verifier. No network/Podman."""
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import appimage_runtime as rt
import formats
import verify

HEX = re.compile('^[0-9a-f]{64}$')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def runtime_elf():
    """Minimal ELF64 with a 16-byte .digest_md5 section at offset 320."""
    data = bytearray(512); data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 40, 64)
    struct.pack_into('<HHH', data, 58, 64, 3, 1)
    names = b'\0.shstrtab\0.digest_md5\0'
    struct.pack_into('<IIQQQQIIQQ', data, 128, 1, 3, 0, 0, 256, len(names), 0, 0, 1, 0)
    struct.pack_into('<IIQQQQIIQQ', data, 192, 11, 1, 0, 0, 320, 16, 0, 0, 1, 0)
    data[256:256 + len(names)] = names
    return bytes(data)


def runtime_dir(directory, data):
    directory.mkdir(parents=True)
    sha = digest(data)
    (directory / rt.RUNTIME).write_bytes(data)
    licenses = {}
    for name in rt.required_notices():
        path = directory / 'licenses' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('notice ' + name)
        licenses[name] = digest(path.read_bytes())
    archive = directory / rt.archive_name(sha)
    archive.write_bytes(b'source fixture')
    (directory / rt.RECEIPT).write_text(json.dumps({'runtime': {'sha256': sha}, 'licenses': licenses,
        'source_archive': {'file': archive.name, 'sha256': digest(archive.read_bytes())}}))
    return sha


class ManifestPinTest(unittest.TestCase):
    def test_every_input_is_https_and_sha256_pinned(self):
        for key, spec in rt.MANIFEST['sources'].items():
            with self.subTest(key=key):
                self.assertTrue(spec['url'].startswith('https://'))
                self.assertNotRegex(spec['url'], '/(latest|continuous)/|refs/heads/')
                self.assertRegex(spec['sha256'], HEX)
                self.assertNotIn('..', Path(spec['file']).parts)
        self.assertRegex(rt.MANIFEST['runtime_sha256'], HEX)
        for key in rt.BUILD_INPUTS:
            self.assertEqual(rt.MANIFEST['sources'][key]['role'], 'build')
        self.assertEqual(rt.MANIFEST['sources']['libfuse']['sha256'],
                         '70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0')
        self.assertEqual(rt.MANIFEST['sources']['squashfuse']['sha256'],
                         'db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3')
        self.assertIn('dd6cebedcbddde9c82f89b011e8e1d40b6e43868', rt.MANIFEST['sources']['type2-runtime']['url'])
        self.assertIn('musl-' + rt.MANIFEST['musl']['version'] + '.tar.gz', rt.MANIFEST['sources']['musl']['url'])
        self.assertIn(rt.MANIFEST['musl']['aports_commit'], rt.MANIFEST['sources']['aports-musl/APKBUILD']['url'])

    def test_toolchain_image_and_apk_versions_are_pinned(self):
        text = (rt.RECIPE / 'Containerfile').read_text()
        base = rt.MANIFEST['image']['base']
        self.assertRegex(base, r'^docker\.io/library/alpine:3\.21\.\d+@sha256:[0-9a-f]{64}$')
        self.assertEqual([line for line in text.splitlines() if line.startswith('FROM ')], ['FROM ' + base])
        packages = re.search(r'apk add --no-cache \\\n(.*?)\n    &&', text, re.S).group(1).split()
        packages = [p for p in packages if p != '\\']
        self.assertTrue(packages)
        for package in packages:
            self.assertRegex(package, r'^[a-z0-9-]+=[0-9][^=]*-r\d+$')
        for name in ['musl', 'musl-dev']:
            self.assertIn(name + '=' + rt.MANIFEST['musl']['apk'], packages)

    def test_compile_is_offline_and_runtime_flags_follow_upstream(self):
        script = (rt.RECIPE / 'build-runtime.sh').read_text()
        relink = (rt.RECIPE / 'relink.sh').read_text()
        for flag in ['-std=gnu99', '-Os', '-D_FILE_OFFSET_BITS=64', '-ffunction-sections', '-fdata-sections', '-Werror']:
            self.assertIn(flag, script)
        for flag in ['-static-pie', 'data_sections.ld', '-Wl,--gc-sections', '--only-keep-debug',
                     '--add-gnu-debuglink', "printf 'AI\\002'", 'seek=8',
                     '-lsquashfuse -lsquashfuse_ll -lzstd -lz -lfuse3 -lmimalloc']:
            self.assertIn(flag, relink)
        self.assertIn('mount.c.diff', script)
        with patch.object(rt.subprocess, 'run') as run, patch.object(rt.shutil, 'copyfile'), \
                patch.object(rt, 'sha256', side_effect=lambda p: rt.MANIFEST['sources'][Path(p).name]['sha256']), \
                tempfile.TemporaryDirectory() as tmp:
            rt.compile_runtime(Path(tmp), Path(tmp) / 'stage', 'image', 2)
            command = run.call_args.args[0]
        self.assertEqual(command[:3], ['podman', 'run', '--rm'])
        self.assertIn('--network=none', command)
        self.assertIn('SOURCE_DATE_EPOCH=' + str(rt.MANIFEST['source_date_epoch']), command)


class InputRefusalTest(unittest.TestCase):
    def fixture(self, root):
        sources = {}
        for name in ['a.tar.gz', 'b.tar.xz']:
            (root / name).write_bytes(b'fixture ' + name.encode())
            sources[name] = dict(file=name, url='https://example.invalid/' + name, sha256=digest((root / name).read_bytes()))
        return dict(sources=sources)

    def test_tampered_missing_or_linked_inputs_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self.fixture(root)
            rt.verify_inputs(root, manifest)
            (root / 'a.tar.gz').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                rt.verify_inputs(root, manifest)
            (root / 'a.tar.gz').unlink()
            with self.assertRaisesRegex(ValueError, 'Missing'):
                rt.verify_inputs(root, manifest)
            (root / 'a.tar.gz').symlink_to(root / 'b.tar.xz')
            with self.assertRaisesRegex(ValueError, 'Missing'):
                rt.verify_inputs(root, manifest)

    def test_aports_files_must_match_apkbuild_sha512sums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'fix.patch').write_bytes(b'patch')
            listed = hashlib.sha512(b'other').hexdigest()
            (root / 'APKBUILD').write_text(f'sha512sums="\n{listed}  fix.patch\n"\n')
            manifest = dict(sources={key: dict(file=name, url='https://example.invalid', sha256=digest((root / name).read_bytes()))
                                     for key, name in [('aports-musl/APKBUILD', 'APKBUILD'), ('aports-musl/fix.patch', 'fix.patch')]})
            with self.assertRaisesRegex(ValueError, 'sha512sums'):
                rt.verify_inputs(root, manifest)

    def test_build_never_starts_podman_with_tampered_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(rt, 'fetch'), patch.object(rt.subprocess, 'run') as run, \
                    patch.object(rt.subprocess, 'check_output') as output:
                with self.assertRaises(ValueError):
                    rt.build(Path(tmp) / 'out', Path(tmp) / 'inputs')
                run.assert_not_called(); output.assert_not_called()


class RuntimeOutputTest(unittest.TestCase):
    def test_prebuilt_output_must_match_pin_notices_and_archive(self):
        data = runtime_elf()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / 'runtime'
            sha = runtime_dir(directory, data)
            with patch.dict(rt.MANIFEST, runtime_sha256=sha):
                self.assertEqual(rt.verify_output(directory)['runtime'], directory / rt.RUNTIME)
                with self.assertRaisesRegex(ValueError, 'differs from configured'):
                    rt.verify_output(directory, '0' * 64)
                notice = directory / 'licenses/libfuse/LGPL2.txt'
                notice.unlink()
                with self.assertRaisesRegex(ValueError, 'notice'):
                    rt.verify_output(directory)
                notice.write_text('notice libfuse/LGPL2.txt')
                (directory / (rt.ARCHIVE_PREFIX + 'other.tar.gz')).write_bytes(b'x')
                with self.assertRaisesRegex(ValueError, 'source archive'):
                    rt.verify_output(directory)

    def test_required_notices_cover_all_statically_linked_licenses(self):
        notices = rt.required_notices()
        for name in ['type2-runtime/LICENSE', 'libfuse/LGPL2.txt', 'libfuse/GPL2.txt', 'libfuse/LICENSE',
                     'squashfuse/LICENSE', 'zstd/LICENSE', 'zlib/LICENSE', 'mimalloc/LICENSE', 'musl/COPYRIGHT']:
            self.assertIn(name, notices)


class AssemblyAndVerifierTest(unittest.TestCase):
    def work(self, root, data):
        canonical = root / 'canonical'
        (canonical / 'share/applications').mkdir(parents=True)
        (canonical / 'share/icons/hicolor/512x512/apps').mkdir(parents=True)
        (canonical / 'share/applications/com.nuvio.media.desktop.desktop').write_text('[Desktop Entry]\n')
        (canonical / 'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png').write_bytes(b'png')
        (canonical / 'release-info.json').write_text(json.dumps(dict(channel='nightly', version='1.0', git_commit='a' * 40, dirty=False)))
        return runtime_dir(root / 'appimage-runtime', data)

    def test_assembly_uses_source_built_runtime_and_publishes_notices_and_source(self):
        data = runtime_elf()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sha = self.work(root, data)
            (root / 'artifacts').mkdir(); (root / 'inputs').mkdir()
            (root / 'inputs/appimagetool').write_bytes(b'tool fixture')
            (root / 'artifacts' / (rt.ARCHIVE_PREFIX + 'stale.tar.gz')).write_bytes(b'stale')
            with patch.dict(rt.MANIFEST, runtime_sha256=sha), patch.object(formats, 'fetch') as fetch, \
                    patch.object(formats, 'run') as run, patch.object(formats, 'portable_support'):
                formats.build_appimage(root, engine=root / 'engine')
            fetch.assert_called_once_with(formats.CONFIG['tools']['appimagetool'], root / 'inputs/appimagetool')
            assemble = run.call_args_list[-1].args
            self.assertEqual(assemble[assemble.index('--runtime-file') + 1], root / 'appimage-runtime' / rt.RUNTIME)
            notices = root / 'Nuvio.AppDir' / rt.NOTICE_DIR
            self.assertEqual(sorted(p.relative_to(notices).as_posix() for p in notices.rglob('*') if p.is_file()),
                             rt.required_notices())
            self.assertEqual(sorted(p.name for p in (root / 'artifacts').iterdir()), [rt.archive_name(sha)])

    def verifier(self, embedded, notices=True, archive=True):
        data = runtime_elf()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sha = runtime_dir(root / 'appimage-runtime', data)
            (root / 'artifacts').mkdir()
            if archive:
                (root / 'artifacts' / rt.archive_name(sha)).write_bytes(b'source fixture')
            extracted = root / 'squashfs-root'
            if notices:
                for name in rt.required_notices():
                    path = extracted / rt.NOTICE_DIR / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('notice ' + name)
            image = root / 'artifacts/fixture.AppImage'
            prefix = bytearray(embedded(data)); prefix[320:336] = b'0123456789abcdef'
            image.write_bytes(bytes(prefix) + b'hsqs squashfs fixture')
            with patch.dict(rt.MANIFEST, runtime_sha256=sha):
                return verify.check_appimage_runtime(root, image, extracted)

    def test_verifier_accepts_pinned_runtime_prefix_with_notices_and_source(self):
        result = self.verifier(lambda data: data)
        self.assertEqual(result['modification']['section'], '.digest_md5')
        self.assertEqual(result['notices'], rt.NOTICE_DIR)

    def test_verifier_rejects_other_runtime_prefix(self):
        def other(data):
            changed = bytearray(data); changed[400] ^= 1
            return bytes(changed)
        with self.assertRaisesRegex(ValueError, 'Embedded runtime differs'):
            self.verifier(other)

    def test_verifier_rejects_missing_notices_or_source_archive(self):
        with self.assertRaisesRegex(ValueError, 'runtime notice'):
            self.verifier(lambda data: data, notices=False)
        with self.assertRaisesRegex(ValueError, 'source archive'):
            self.verifier(lambda data: data, archive=False)


if __name__ == '__main__':
    unittest.main()
