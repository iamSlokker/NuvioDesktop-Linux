"""Actual-bundle inventory must match staging before inspecting packaged classes."""
import json
import io
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import verify
import artwork_contract
import flatpak_bundle
import notice_contract


class FlatpakJarInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = pathlib.Path(self.tmp.name)
        (self.work / 'artifacts').mkdir()
        self.info = dict(application_id='com.example.NuvioFixture', channel='nightly')
        runtime_repo = json.loads((pathlib.Path(verify.__file__).parent / 'flatpak-repo/repo.json').read_text())['runtime_repo']
        # A real bundle header (unsigned CI contract) plus padding past the size floor.
        (self.work / 'artifacts/Nuvio.flatpak').write_bytes(flatpak_bundle.fixture_bundle(
            'app/com.example.NuvioFixture/x86_64/nightly', runtime_repo=('s', runtime_repo),
            **{'deltas/fixture/0': ('ay', bytes(8192))}))
        (self.work / 'canonical').mkdir()
        (self.work / 'canonical/release-info.json').write_text(json.dumps(self.info))
        self.jars = ['application.jar', 'onnx runtime.jar']
        directory = self.work / 'flatpak-build/files/lib/nuvio-desktop/lib/app'
        directory.mkdir(parents=True)
        for name in self.jars:
            (directory / name).write_bytes(('fixture ' + name).encode())
        (self.work / 'flatpak-build/metadata').write_bytes(b'fixture metadata')
        self.cat_jars = []
        self.listing = self.inventory(self.jars)

    @staticmethod
    def inventory(names):
        directory = '/files/lib/nuvio-desktop/lib/app'
        return '\n'.join(['d00755 1000 1000 0 ' + directory] +
                         ['-00644 1000 1000 10 ' + directory + '/' + name for name in names]) + '\n'

    def command_output(self, args, **kwargs):
        self.assertEqual('ostree', args[0])
        if args[2] == 'ls':
            self.assertTrue(kwargs['text'])
            if args[-1].endswith('/lib/native/nuvio-hdr-probe'):
                return '-00755 1000 1000 4096 ' + args[-1] + '\n'
            return self.listing
        self.assertEqual('cat', args[2])
        if args[-1].endswith('/release-info.json'):
            return json.dumps(self.info).encode()
        if args[-1] == '/metadata':
            return b'fixture metadata'
        if '/share/licenses/third-party/' in args[-1]:
            return (notice_contract.NOTICES / args[-1].split('/share/licenses/third-party/', 1)[1]).read_bytes()
        self.assertTrue(args[-1].endswith('.jar'))
        name = pathlib.PurePosixPath(args[-1]).name
        self.cat_jars.append(name)
        return ('fixture ' + name).encode()

    def audit(self):
        return verify.artifacts(self.work, 'flatpak')

    def test_matching_actual_inventory_reads_and_verifies_each_packaged_jar(self):
        with patch.object(verify.subprocess, 'run'), \
                patch.object(verify.subprocess, 'check_output', side_effect=self.command_output), \
                patch.object(verify, 'verify_packaged_backend') as backend, \
                patch.object(verify, 'verify_packaged_onnx', return_value={'provider': 'CPU'}) as onnx, \
                patch.object(verify, 'verify_packaged_fonts', return_value={'version': '4.1'}) as fonts, \
                patch.object(verify, 'verify_packaged_artwork', return_value={'redistribution_assessment': 'BLOCKED'}) as artwork:
            self.audit()
        self.assertEqual(self.jars, self.cat_jars)
        backend.assert_called_once()
        onnx.assert_called_once()
        fonts.assert_called_once()
        artwork.assert_called_once()
        report = json.loads((self.work / 'artifacts/flatpak-audit.json').read_text())
        self.assertEqual('app/com.example.NuvioFixture/x86_64/nightly', report['bundle']['ref'])
        self.assertIsNone(report['bundle']['origin'])

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
                        self.audit()
                self.assertFalse(any('build-import-bundle' in call.args[0] for call in run.call_args_list))

    def test_extra_or_missing_bundle_jar_is_rejected_before_class_verification(self):
        for names in [self.jars + ['unexpected.jar'], self.jars[:1]]:
            with self.subTest(names=names):
                self.listing = self.inventory(names)
                with patch.object(verify.subprocess, 'run'), \
                        patch.object(verify.subprocess, 'check_output', side_effect=self.command_output), \
                        patch.object(verify, 'verify_packaged_backend') as backend, \
                        patch.object(verify, 'verify_packaged_onnx') as onnx, \
                        patch.object(verify, 'verify_packaged_fonts') as fonts, \
                        patch.object(verify, 'verify_packaged_artwork') as artwork:
                    with self.assertRaisesRegex(ValueError, 'JAR inventory differs'):
                        self.audit()
                self.assertEqual([], self.cat_jars)
                backend.assert_not_called()
                onnx.assert_not_called()
                fonts.assert_not_called()
                artwork.assert_not_called()

    def test_non_regular_or_duplicate_bundle_jars_are_rejected(self):
        directory = '/files/lib/nuvio-desktop/lib/app/'
        for bad_line in ['l00777 1000 1000 0 ' + directory + 'linked.jar -> application.jar',
                         'l00777 1000 1000 0 ' + directory + 'hidden -> payload.jar -> nonjar',
                         'd00755 1000 1000 0 ' + directory + 'directory.jar',
                         '-00644 1000 1000 10 ' + directory + 'application.jar']:
            with self.subTest(line=bad_line):
                self.listing = self.inventory(self.jars) + bad_line + '\n'
                with patch.object(verify.subprocess, 'run'), \
                        patch.object(verify.subprocess, 'check_output', side_effect=self.command_output):
                    with self.assertRaisesRegex(ValueError, 'Non-regular/duplicate'):
                        self.audit()
                self.assertEqual([], self.cat_jars)

    def test_exported_bundle_reaches_real_artwork_gate_instead_of_trusting_staging(self):
        payload = {}
        for name in artwork_contract.expected_resources():
            payload[name] = artwork_contract.linux_source(name).read_bytes()
        dataset = (artwork_contract.repository() /
                   'composeApp/src/desktopMain/resources/anime-list-mini.json').read_bytes()
        for bad_export in (False, True, 'original'):
            content = dict(payload)
            if bad_export == 'original':
                # The runtime-downloaded dataset (here renamed) in the exported JAR must fail.
                content['nested/renamed.bin'] = dataset
            elif bad_export:
                # Correct staging plus a forbidden asset in the actual exported JAR must fail.
                content['nested/ANIME-LIST-MINI.JSON'] = b'new bytes'
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as jar:
                for name, data in content.items(): jar.writestr(name, data)
            def output(args, **kwargs):
                if args[-1].endswith('.jar'):
                    if args[-1].endswith('application.jar'): return stream.getvalue()
                    empty = io.BytesIO()
                    with zipfile.ZipFile(empty, 'w'): pass
                    return empty.getvalue()
                return self.command_output(args, **kwargs)
            with self.subTest(bad_export=bad_export), patch.object(verify.subprocess, 'run'), \
                    patch.object(verify.subprocess, 'check_output', side_effect=output), \
                    patch.object(verify, 'verify_packaged_backend'), \
                    patch.object(verify, 'verify_packaged_onnx', return_value={}), \
                    patch.object(verify, 'verify_packaged_fonts', return_value={}):
                if bad_export == 'original':
                    with self.assertRaisesRegex(ValueError, 'Forbidden historical artwork bytes'): self.audit()
                elif bad_export:
                    with self.assertRaisesRegex(ValueError, 'Removed artwork filename'): self.audit()
                else:
                    self.audit()
                    report = json.loads((self.work / 'artifacts/flatpak-audit.json').read_text())
                    self.assertEqual(4, report['artwork']['replacement_setup_previews'])
                    self.assertEqual('BLOCKED', report['artwork']['redistribution_assessment'])


if __name__ == '__main__':
    unittest.main()
