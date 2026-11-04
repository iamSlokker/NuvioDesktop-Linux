"""whisper.cpp build recipe (whisper/build.py): pin, exports and where it lands. Fixtures only."""
import hashlib
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
REPO = PACKAGING.parents[2]
sys.path.insert(0, str(PACKAGING))
from whisper import build as whisper  # noqa: E402


class WhisperRecipeTest(unittest.TestCase):
    def test_the_input_is_pinned_and_names_one_version(self):
        manifest = whisper.MANIFEST
        self.assertTrue(manifest['source']['url'].startswith('https://'))
        self.assertRegex(manifest['source']['sha256'], '^[0-9a-f]{64}$')
        self.assertTrue(manifest['source']['url'].endswith('/refs/tags/' + manifest['version']))

    def test_it_is_the_version_windows_builds(self):
        gradle = (REPO / 'composeApp/build.gradle.kts').read_text()
        self.assertIn(f'val whisperExpectedTag = "{whisper.MANIFEST["version"]}"', gradle)

    def test_exports_are_the_jni_bridge_only(self):
        good = '0000 T Java_com_nuvio_app_features_voice_WhisperNative_nativeInit\n'
        with patch.object(whisper.subprocess, 'check_output', return_value=good):
            whisper.check_exports(Path('lib.so'))
        with patch.object(whisper.subprocess, 'check_output', return_value='0000 T whisper_full\n'):
            with self.assertRaisesRegex(ValueError, 'no JNI bridge'):
                whisper.check_exports(Path('lib.so'))
        with patch.object(whisper.subprocess, 'check_output', return_value=good + '0000 T ggml_init\n'):
            with self.assertRaisesRegex(ValueError, 'more than its API'):
                whisper.check_exports(Path('lib.so'))

    def test_the_library_lands_where_the_loader_looks(self):
        self.assertIn("native / 'libnuvio_whisper.so'", (PACKAGING / 'stage.py').read_text())
        loader = (REPO / 'composeApp/src/desktopMain/kotlin/com/nuvio/app/features/voice/WhisperNative.kt').read_text()
        self.assertIn('native/$LIBRARY_FILE_NAME', loader)
        self.assertIn('"libnuvio_whisper.so"', loader)

    def test_it_builds_with_network_before_gradle(self):
        script = (PACKAGING / 'native-build.sh').read_text()
        phase = script[script.index('[[ $phase == build ]]'):]
        self.assertLess(phase.index('whisper/build.py /output'), phase.index('./gradlew'))

    def test_recipe_files_are_not_builder_seed_inputs(self):
        from webkit import builder_seed
        with patch.object(builder_seed, 'digest', side_effect=lambda path: hashlib.sha256(b'').hexdigest()):
            names = builder_seed.inputs()
        self.assertFalse([name for name in names if 'whisper' in name])


if __name__ == '__main__':
    unittest.main()
