"""DEB/AppImage extraction handoff for native-image packages, with real resource JARs and no app."""
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract
import client_config
import native_evidence
import native_fixture
import notice_contract
import verify


def artwork_jar(bad):
    entries = {name: artwork_contract.linux_source(name).read_bytes() for name in artwork_contract.expected_resources()}
    entries[client_config.CONFIG_CLASS] = native_fixture.supabase_class()
    if bad:
        entries['nested/anime-list-mini.json'] = b'runtime-downloaded dataset'
    return entries


class NativeArtworkGateTest(unittest.TestCase):
    def build(self, root, bad=False, embed_all=True):
        jars = {'application.jar': artwork_jar(bad)}
        embedded = [name for name in jars['application.jar']
                    if not name.startswith('anime-mapping/') and not name.endswith('.class')
                    and (embed_all or 'setup/' not in name)]
        anime = {name: artwork_contract.data_provenance().with_name(name).read_bytes()
                 for name in native_evidence.ANIME_FILES}
        image, evidence, _ = native_fixture.make(root, jars=jars, embedded=embedded, anime=anime)
        self.info = {'git_commit': 'a' * 40, 'version': 'fixture',
                     'graalvm_release': native_evidence.release_fields(root / 'graalvm')}
        for name in ['bin/nuvio', 'bin/nuvio-desktop', 'lib/native/libplayer_bridge.so', 'lib/native/libmpv.so.2',
                     'share/applications/com.nuvio.media.desktop.desktop',
                     'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png',
                     'share/licenses/Nuvio/COPYING', 'share/licenses/mpv/LICENSE.GPL']:
            path = image / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture, not a native binary')
            if name.startswith('bin/'):
                path.chmod(0o755)
        (image / 'release-info.json').write_text(json.dumps(self.info))
        notice_contract.stage_notices(image)
        (image / 'lib/app').mkdir(parents=True, exist_ok=True)
        (image / 'lib/app/build-info.properties').write_text(
            'GIT_COMMIT=' + self.info['git_commit'] + '\nVERSION_NAME=fixture\n')
        native_fixture.smoke(evidence, image)
        work = root / 'work'
        (work / 'artifacts').mkdir()
        native_evidence.archive(work, work / 'artifacts/Nuvio-native-image-inputs-aaaaaaaaaaaa.tar', 1)
        return work, image

    def audit(self, kind, bad=False, embed_all=True, mutate=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work, image = self.build(root, bad, embed_all)
            (work / ('artifacts/fixture.' + ('deb' if kind == 'deb' else 'AppImage'))).write_bytes(b'x' * 4096)

            def run(args, **kwargs):
                if args[0] == 'dpkg-deb' and args[1] == '--extract':
                    shutil.copytree(image, Path(args[-1]) / 'opt/nuvio-desktop', symlinks=True)
                    if mutate:
                        mutate(Path(args[-1]) / 'opt/nuvio-desktop')
                elif '--appimage-extract' in args:
                    extracted = Path(kwargs['cwd']) / 'squashfs-root'
                    shutil.copytree(image, extracted / 'usr/lib/nuvio-desktop', symlinks=True)
                    if mutate:
                        mutate(extracted / 'usr/lib/nuvio-desktop')
                    (extracted / 'AppRun').write_text('fixture')
                    (extracted / 'AppRun').chmod(0o755)
            with patch.object(verify.subprocess, 'run', side_effect=run), \
                    patch.object(verify, 'check_webkit_relocation'), \
                    patch.object(verify, 'check_hdr_probe', return_value={}), \
                    patch.object(verify, 'verify_packaged_onnx', return_value={'files': {}}), \
                    patch.object(verify, 'verify_packaged_fonts', return_value={'resources': {}}), \
                    patch.object(verify, 'check_appimage_runtime', return_value={}):
                verify.artifacts(work, kind)
            return json.loads((work / ('artifacts/' + kind + '-audit.json')).read_text())

    def test_both_native_format_verifiers_check_input_jars_and_executable_bytes(self):
        for kind in ('deb', 'appimage'):
            with self.subTest(kind=kind):
                report = self.audit(kind)
                self.assertEqual(4, report['artwork']['replacement_setup_previews'])
                self.assertEqual('BLOCKED', report['artwork']['redistribution_assessment'])
                self.assertEqual(4, report['native_payloads']['anime_notice_files'])
                self.assertEqual('PASS', report['smoke']['status'])
                self.assertEqual(['ANON_KEY', 'URL'], report['backend'])

    def test_both_native_format_verifiers_reject_forbidden_input_artwork(self):
        for kind in ('deb', 'appimage'):
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Removed artwork filename'):
                self.audit(kind, bad=True)

    def test_reviewed_payload_missing_from_the_image_fails(self):
        with self.assertRaisesRegex(ValueError, 'not embedded in the native image'):
            self.audit('deb', embed_all=False)

    def test_deb_payload_must_be_the_image_the_smoke_gate_launched(self):
        with self.assertRaisesRegex(ValueError, 'different package payload'):
            self.audit('deb', mutate=lambda image: (image / 'share/added-after-smoke').write_text('x'))
        with self.assertRaisesRegex(ValueError, 'anime-mapping notice'):
            self.audit('appimage', mutate=lambda image: (
                image / native_evidence.ANIME_NOTICES / 'NOTICE.txt').write_text('changed'))


if __name__ == '__main__':
    unittest.main()
