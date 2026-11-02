"""Nuvio Engine build recipe (nuvio_engine/build.py): pins, exports and where it lands. Fixtures only."""
from pathlib import Path
import hashlib
import sys
import tempfile
import unittest
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGING))
from nuvio_engine import build as engine  # noqa: E402

REPO = PACKAGING.parents[2]


class NuvioEngineRecipeTest(unittest.TestCase):
    def test_inputs_are_pinned_and_name_one_version(self):
        manifest = engine.MANIFEST
        for spec in (manifest['source'], manifest['boost']):
            self.assertTrue(spec['url'].startswith('https://'), spec['url'])
            self.assertRegex(spec['sha256'], '^[0-9a-f]{64}$')
        self.assertTrue(manifest['source']['url'].endswith('/refs/tags/' + manifest['version']))
        self.assertRegex(manifest['libtorrent_commit'], '^[0-9a-f]{40}$')

    def test_engine_must_pin_the_same_libtorrent_and_boost(self):
        manifest = engine.MANIFEST
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'cmake').mkdir()
            dependencies = root / 'cmake/Dependencies.cmake'
            dependencies.write_text(f"URL_HASH SHA256={manifest['boost']['sha256']}\nGIT_TAG {manifest['libtorrent_commit']}\n")
            engine.check_libtorrent_pin(root)
            dependencies.write_text(f"URL_HASH SHA256={manifest['boost']['sha256']}\nGIT_TAG {'0' * 40}\n")
            with self.assertRaises(ValueError):
                engine.check_libtorrent_pin(root)

    def test_exports_are_the_jni_bridge_and_the_c_api_only(self):
        good = ('0000 T Java_com_nuvio_engine_internal_NativeBridge_nativeCreate\n0000 T nuvio_engine_create\n')
        with patch.object(engine.subprocess, 'check_output', return_value=good):
            engine.check_exports(Path('lib.so'))
        with patch.object(engine.subprocess, 'check_output', return_value='0000 T nuvio_engine_create\n'):
            with self.assertRaisesRegex(ValueError, 'no JNI bridge'):
                engine.check_exports(Path('lib.so'))
        leaky = good + '0000 T _ZN10libtorrent7sessionC1Ev\n'
        with patch.object(engine.subprocess, 'check_output', return_value=leaky):
            with self.assertRaisesRegex(ValueError, 'more than its API'):
                engine.check_exports(Path('lib.so'))

    def test_the_build_is_position_independent_and_uses_the_given_jni_headers(self):
        command = engine.configure_command(Path('/e'), Path('/b'), Path('/d'), Path('/boost'), Path('/jdk/include'))
        self.assertIn('-DCMAKE_POSITION_INDEPENDENT_CODE=ON', command)
        self.assertIn('-DNUVIO_ENGINE_ROOT=/e', command)
        self.assertIn('-DNUVIO_JNI_INCLUDE_DIR=/jdk/include', command)
        self.assertIn('-DFETCHCONTENT_SOURCE_DIR_NUVIO_BOOST=/boost', command)

    def test_the_library_lands_where_the_loader_looks(self):
        stage_text = (PACKAGING / 'stage.py').read_text()
        self.assertIn("native / 'libnuvio_engine.so'", stage_text)
        loader = (REPO / 'composeApp/src/desktopMain/kotlin/com/nuvio/engine/internal/NuvioEngineLibrary.kt').read_text()
        self.assertIn('native/$LIBRARY_FILE_NAME', loader)
        self.assertIn('"libnuvio_engine.so"', loader)

    def test_it_builds_with_network_before_gradle(self):
        script = (PACKAGING / 'native-build.sh').read_text()
        phase = script[script.index('[[ $phase == build ]]'):]
        self.assertLess(phase.index('nuvio_engine/build.py /output'), phase.index('./gradlew'))

    def test_recipe_files_are_not_builder_seed_inputs(self):
        from webkit import builder_seed
        with patch.object(builder_seed, 'digest', side_effect=lambda path: hashlib.sha256(b'').hexdigest()):
            names = builder_seed.inputs()
        self.assertFalse([name for name in names if 'nuvio_engine' in name])


if __name__ == '__main__':
    unittest.main()
