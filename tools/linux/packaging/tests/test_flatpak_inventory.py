"""The exported Flatpak bundle, not staging, must carry the smoke-tested native image and no JAR/JVM."""
import json
import os
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import artwork_contract
import client_config
import flatpak_bundle
import native_evidence
import native_fixture
import notice_contract
import verify

IMAGE = '/files/lib/nuvio-desktop'


class FlatpakNativeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        self.work = root / 'work'
        entries = {name: artwork_contract.linux_source(name).read_bytes() for name in artwork_contract.expected_resources()}
        entries[client_config.CONFIG_CLASS] = native_fixture.supabase_class()
        anime = {name: artwork_contract.data_provenance().with_name(name).read_bytes()
                 for name in native_evidence.ANIME_FILES}
        self.jar = {'application.jar': entries}
        embedded = [n for n in entries if not n.startswith('anime-mapping/') and not n.endswith('.class')]
        canonical, evidence, _ = native_fixture.make(root, jars=self.jar, embedded=embedded, anime=anime)
        notice_contract.stage_notices(canonical)
        (canonical / 'lib/native').mkdir()
        (canonical / 'lib/native/nuvio-hdr-probe').write_bytes(b'fixture helper')
        (canonical / 'lib/native/nuvio-hdr-probe').chmod(0o755)
        (canonical / verify.TORRSERVER).parent.mkdir(parents=True)
        (canonical / verify.TORRSERVER).write_bytes(b'fixture torrserver')
        (canonical / verify.TORRSERVER).chmod(0o755)
        (canonical / 'lib/native/libmpv.so.2').symlink_to('libmpv.so.2.5.0')
        (canonical / 'lib/native/libmpv.so.2.5.0').write_bytes(b'fixture mpv')
        self.info = dict(application_id='com.example.NuvioFixture', channel='nightly')
        (canonical / 'release-info.json').write_text(json.dumps(self.info))
        native_fixture.smoke(evidence, canonical)
        (self.work / 'artifacts').mkdir()
        native_evidence.archive(self.work, self.work / 'artifacts/Nuvio-native-image-inputs-aaaaaaaaaaaa.tar', 1)
        (self.work / 'artifacts/Nuvio-TorrServer-source-aaaaaaaaaaaa.tar.gz').write_bytes(b't' * 4096)
        (self.work / 'artifacts/Nuvio-InnerTubeX-source-aaaaaaaaaaaa.tar.gz').write_bytes(b'i' * 4096)
        runtime_repo = json.loads((pathlib.Path(verify.__file__).parent / 'flatpak-repo/repo.json').read_text())['runtime_repo']
        # A real bundle header (unsigned CI contract) plus padding past the size floor.
        (self.work / 'artifacts/Nuvio.flatpak').write_bytes(flatpak_bundle.fixture_bundle(
            'app/com.example.NuvioFixture/x86_64/nightly', runtime_repo=('s', runtime_repo),
            **{'deltas/fixture/0': ('ay', bytes(8192))}))
        staging = self.work / 'flatpak-build/files/lib/nuvio-desktop'
        shutil.copytree(canonical, staging, symlinks=True)
        (self.work / 'flatpak-build/metadata').write_bytes(b'fixture metadata')
        # What the bundle exported; tests change it to model a different export.
        self.exported = root / 'exported'
        shutil.copytree(staging, self.exported, symlinks=True)
        self.cat_paths = []
        # What build-export makes of the packaged desktop entry (Exec rewritten, MimeType kept).
        self.exported_desktop = (pathlib.Path(verify.__file__).parent / 'com.nuvio.media.desktop.desktop').read_text().replace(
            'Exec=nuvio-desktop %u', 'Exec=/usr/bin/flatpak run --branch=nightly --arch=x86_64 '
            '--command=nuvio-desktop --file-forwarding com.example.NuvioFixture @@u %u @@').encode()

    def listing(self):
        lines = ['d00755 0 0 0 ' + IMAGE]
        for path in sorted(self.exported.rglob('*')):
            name = IMAGE + '/' + path.relative_to(self.exported).as_posix()
            if path.is_symlink():
                lines.append('l00777 0 0 0 %s -> %s' % (name, os.readlink(path)))
            elif path.is_dir():
                lines.append('d00755 0 0 0 ' + name)
            else:
                lines.append('-%05o 0 0 %d %s' % (path.stat().st_mode & 0o7777, path.stat().st_size, name))
        return '\n'.join(lines) + '\n'

    def command_output(self, args, **kwargs):
        self.assertEqual('ostree', args[0])
        if args[2] == 'ls':
            self.assertTrue(kwargs['text'])
            if args[3] == '-R':
                self.assertEqual(IMAGE, args[-1])
                return self.listing()
            if args[-1].endswith(('/lib/native/nuvio-hdr-probe', '/' + verify.TORRSERVER)):
                path = self.exported / args[-1][len(IMAGE) + 1:]
                return '-%05o 0 0 4096 %s\n' % (path.stat().st_mode & 0o7777, args[-1]) if path.is_file() else ''
            raise AssertionError(args)
        self.assertEqual('cat', args[2])
        if args[-1] == '/metadata':
            return b'fixture metadata'
        if args[-1].startswith('/export/'):
            self.assertEqual('/export/share/applications/com.example.NuvioFixture.desktop', args[-1])
            return self.exported_desktop
        self.assertTrue(args[-1].startswith(IMAGE + '/'), args[-1])
        relative = args[-1][len(IMAGE) + 1:]
        self.cat_paths.append(relative)
        return (self.exported / relative).read_bytes()

    def audit(self):
        with patch.object(verify.subprocess, 'run'), \
                patch.object(verify.subprocess, 'check_output', side_effect=self.command_output), \
                patch.object(verify, 'verify_packaged_onnx', return_value={'files': {}}):
            verify.artifacts(self.work, 'flatpak')
        return json.loads((self.work / 'artifacts/flatpak-audit.json').read_text())

    def test_exported_native_image_is_bound_to_the_retained_inputs_and_smoke_record(self):
        report = self.audit()
        self.assertIn('lib/nuvio', self.cat_paths)
        self.assertIn(str(native_evidence.PACKAGED_MANIFEST), self.cat_paths)
        self.assertEqual('app/com.example.NuvioFixture/x86_64/nightly', report['bundle']['ref'])
        self.assertIsNone(report['bundle']['origin'])
        self.assertEqual('PASS', report['smoke']['status'])
        self.assertEqual(['ANON_KEY', 'URL'], report['backend'])
        self.assertEqual(1, report['native_image']['input_jars'])

    def test_exported_desktop_entry_must_keep_the_nuvio_link_handler(self):
        report = self.audit()
        self.assertEqual('x-scheme-handler/nuvio;', report['desktop_entry']['mime_type'])
        self.assertIn('@@u %u @@', report['desktop_entry']['exec'])
        original = self.exported_desktop
        for broken, message in [(original.replace(b'MimeType=x-scheme-handler/nuvio;\n', b''), 'does not register'),
                                (original.replace(b' @@u %u @@', b''), 'does not pass the link')]:
            with self.subTest(message=message):
                self.exported_desktop = broken
                with self.assertRaisesRegex(ValueError, message):
                    self.audit()

    def test_jars_jvm_or_a_different_executable_in_the_export_are_rejected(self):
        def jar(e): (e / 'lib/app').mkdir(exist_ok=True); (e / 'lib/app/app.jar').write_bytes(b'x')
        def link(e): (e / 'lib/app').mkdir(exist_ok=True); (e / 'lib/app/hidden').symlink_to('payload.jar')
        def jvm(e): (e / 'lib/runtime/lib/server').mkdir(); (e / 'lib/runtime/lib/server/libjvm.so').write_bytes(b'x')
        def exe(e): (e / 'lib/nuvio').write_bytes(b'another executable')
        def notice(e): (e / native_evidence.GRAALVM_NOTICES / 'LICENSE.txt').unlink()
        def jar_notice(e): (e / native_evidence.JAR_NOTICES / 'index.json').write_text('{}')
        for change, message in [(jar, 'JAR in the Flatpak bundle'), (link, 'JAR link'), (jvm, 'JVM runtime'),
                                (exe, 'differs from the inspected sandbox'), (notice, 'Missing regular file'),
                                (jar_notice, 'JAR notice index')]:
            with self.subTest(message=message):
                shutil.rmtree(self.exported)
                shutil.copytree(self.work / 'flatpak-build/files/lib/nuvio-desktop', self.exported, symlinks=True)
                change(self.exported)
                with self.assertRaisesRegex(ValueError, message):
                    self.audit()

    def test_exported_torrserver_must_be_executable(self):
        (self.exported / verify.TORRSERVER).chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'non-executable TorrServer'):
            self.audit()
        (self.exported / verify.TORRSERVER).unlink()
        with self.assertRaisesRegex(ValueError, 'non-executable TorrServer'):
            self.audit()

    def test_missing_torrserver_source_archive_blocks_every_format(self):
        (self.work / 'artifacts/Nuvio-TorrServer-source-aaaaaaaaaaaa.tar.gz').unlink()
        with self.assertRaisesRegex(ValueError, 'TorrServer source archive'):
            self.audit()

    def test_missing_smoke_record_or_evidence_archive_blocks_the_flatpak(self):
        (self.work / 'native-image/smoke.json').write_text(json.dumps(dict(status='FAIL', runs=[])))
        with self.assertRaisesRegex(ValueError, 'differs from the retained evidence'):
            self.audit()
        (self.work / 'artifacts/Nuvio-native-image-inputs-aaaaaaaaaaaa.tar').unlink()
        native_evidence.archive(self.work, self.work / 'artifacts/Nuvio-native-image-inputs-aaaaaaaaaaaa.tar', 1)
        with self.assertRaisesRegex(ValueError, 'not a pass'):
            self.audit()
        (self.work / 'artifacts/Nuvio-native-image-inputs-aaaaaaaaaaaa.tar').unlink()
        with self.assertRaisesRegex(ValueError, 'evidence archive'):
            self.audit()

    def test_ci_bundle_with_repository_link_or_key_is_rejected_before_import(self):
        path = self.work / 'artifacts/Nuvio.flatpak'
        ref = 'app/com.example.NuvioFixture/x86_64/nightly'
        runtime_repo = json.loads((pathlib.Path(verify.__file__).parent / 'flatpak-repo/repo.json').read_text())['runtime_repo']
        pad = {'deltas/fixture/0': ('ay', bytes(8192))}
        for extra in [dict(origin=('s', 'https://example.invalid/repo/')), dict(gpg_keys=('ay', b'\x99\x00\x00')),
                      dict(signatures=[b'sig']), dict()]:
            with self.subTest(extra=sorted(extra)):
                path.write_bytes(flatpak_bundle.fixture_bundle(ref, **dict(pad, runtime_repo=('s', runtime_repo)
                                                                         if extra else ('s', 'https://other/x.flatpakrepo'), **extra)))
                with patch.object(verify.subprocess, 'run') as run, \
                        patch.object(verify.subprocess, 'check_output', side_effect=self.command_output):
                    with self.assertRaisesRegex(ValueError, 'must not embed|runtime repository'):
                        verify.artifacts(self.work, 'flatpak')
                self.assertFalse(any('build-import-bundle' in call.args[0] for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
