"""Exercise DEB/AppImage extraction handoff with real resource JARs, no native runtime."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract
import notice_contract
import verify


class NativeArtworkGateTest(unittest.TestCase):
    ORIGINAL = 'composeApp/src/commonMain/composeResources/drawable/hero_badge_win.png'

    def image(self, directory, bad):
        directory.mkdir(parents=True)
        required = ['bin/nuvio', 'bin/nuvio-desktop', 'lib/runtime/lib/libjawt.so',
                    'lib/runtime/lib/server/libjvm.so', 'lib/native/libplayer_bridge.so',
                    'lib/native/libmpv.so.2', 'share/applications/com.nuvio.media.desktop.desktop',
                    'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png',
                    'share/licenses/Nuvio/COPYING', 'share/licenses/mpv/LICENSE.GPL']
        for name in required:
            path = directory / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture, not a native binary')
            if name.startswith('bin/'): path.chmod(0o755)
        (directory / 'release-info.json').write_text(json.dumps(self.info))
        notice_contract.stage_notices(directory)
        (directory / 'lib/app').mkdir(parents=True)
        (directory / 'lib/app/build-info.properties').write_text('GIT_COMMIT=' + self.info['git_commit'] + '\nVERSION_NAME=fixture\n')
        with zipfile.ZipFile(directory / 'lib/app/application.jar', 'w') as jar:
            for name in artwork_contract.expected_resources():
                source = artwork_contract.linux_source(name)
                if bad == 'original' and name == artwork_contract.packaged_path(self.ORIGINAL):
                    # The upstream original at its own path, i.e. a JAR built without the overlay.
                    source = artwork_contract.repository() / self.ORIGINAL
                jar.writestr(name, source.read_bytes())
            if bad is True: jar.writestr('nested/hero_badge_must_see.png', b'unreviewed')

    def audit(self, kind, bad):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'artifacts').mkdir(); (root / 'canonical').mkdir()
            self.info = {'git_commit': 'a' * 40, 'version': 'fixture'}
            (root / 'canonical/release-info.json').write_text(json.dumps(self.info))
            (root / ('artifacts/fixture.' + ('deb' if kind == 'deb' else 'AppImage'))).write_bytes(b'x' * 4096)
            def run(args, **kwargs):
                if args[0] == 'dpkg-deb' and args[1] == '--extract':
                    self.image(Path(args[-1]) / 'opt/nuvio-desktop', bad)
                elif '--appimage-extract' in args:
                    extracted = Path(kwargs['cwd']) / 'squashfs-root'
                    self.image(extracted / 'usr/lib/nuvio-desktop', bad)
                    (extracted / 'AppRun').write_text('fixture'); (extracted / 'AppRun').chmod(0o755)
            with patch.object(verify.subprocess, 'run', side_effect=run), \
                    patch.object(verify, 'check_webkit_relocation'), \
                    patch.object(verify, 'verify_packaged_backend'), \
                    patch.object(verify, 'verify_packaged_onnx', return_value={}), \
                    patch.object(verify, 'verify_packaged_fonts', return_value={}):
                verify.artifacts(root, kind)
            report = json.loads((root / ('artifacts/' + kind + '-audit.json')).read_text())
            self.assertEqual(10, report['artwork']['removed_resources'])
            self.assertEqual(47, report['artwork']['linux_overlay_replacements'])
            self.assertEqual('BLOCKED', report['artwork']['redistribution_assessment'])

    def test_both_native_format_verifiers_inspect_actual_extracted_jar(self):
        for kind in ('deb', 'appimage'):
            with self.subTest(kind=kind): self.audit(kind, False)

    def test_both_native_format_verifiers_reject_forbidden_exported_artwork(self):
        for kind in ('deb', 'appimage'):
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Removed artwork filename'):
                self.audit(kind, True)

    def test_both_native_format_verifiers_reject_upstream_original_artwork(self):
        for kind in ('deb', 'appimage'):
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Forbidden historical artwork bytes'):
                self.audit(kind, 'original')


if __name__ == '__main__':
    unittest.main()
