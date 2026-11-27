"""Usenet engine build recipe (usenet/build.py): pins, linkage and where it lands. Fixtures only."""
import hashlib
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
REPO = PACKAGING.parents[2]
sys.path.insert(0, str(PACKAGING))
from usenet import build as usenet  # noqa: E402
import stage  # noqa: E402

ELF_HEADER = '  Class:                             ELF64\n  Machine:                           Advanced Micro Devices X86-64\n'


class UsenetRecipeTest(unittest.TestCase):
    def test_inputs_are_pinned_and_name_one_commit(self):
        manifest = usenet.MANIFEST
        go = manifest['go']
        self.assertTrue(go['url'].startswith('https://'), go['url'])
        self.assertRegex(go['sha256'], '^[0-9a-f]{64}$')
        self.assertRegex(manifest['version'], '^[0-9a-f]{40}$')
        self.assertNotIn('url', manifest['source'])
        self.assertIn('-buildid=', manifest['build']['ldflags'])
        self.assertIn('-static-libstdc++', manifest['build']['ldflags'])
        self.assertTrue(manifest['go']['url'].endswith('/' + manifest['go']['version'] + '.linux-amd64.tar.gz'))

    def test_it_is_the_commit_windows_builds(self):
        gradle = (REPO / 'composeApp/build.gradle.kts').read_text()
        self.assertIn(f'val usenetExpectedCommit = "{usenet.MANIFEST["version"]}"', gradle)

    def test_it_builds_the_tree_the_app_carries(self):
        # The same directory Windows builds (usenetSourceDir's default), fork changes included.
        self.assertEqual(REPO / 'composeApp/src/desktopMain/native/nuvio-usenet-engine', usenet.SOURCE)
        gradle = (REPO / 'composeApp/build.gradle.kts').read_text()
        self.assertIn('projectDir.resolve("src/desktopMain/native/nuvio-usenet-engine")', gradle)
        usenet.check_tree(usenet.SOURCE)
        self.assertIn('NuvioDesktop fork changes', (usenet.SOURCE / 'NOTICE.md').read_text(encoding='utf-8'))
        self.assertTrue((usenet.SOURCE / usenet.PREBUILT).is_file())

    def test_the_tree_digest_covers_names_and_bytes(self):
        import tempfile
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            (tree / 'a').write_bytes(b'1')
            first = usenet.tree_digest(tree)
            (tree / 'a').write_bytes(b'2')
            second = usenet.tree_digest(tree)
            (tree / 'a').rename(tree / 'b')
            third = usenet.tree_digest(tree)
        self.assertEqual(3, len({first, second, third}))

    def test_the_build_is_vendored_and_cgo(self):
        command = usenet.build_command(Path('out'))
        self.assertIn('-mod=vendor', command)
        self.assertIn('-trimpath', command)
        environment = usenet.cgo_environment(Path('/w'), Path('/go'), Path('/src'))
        self.assertEqual('1', environment['CGO_ENABLED'])
        self.assertEqual('local', environment['GOTOOLCHAIN'])
        self.assertIn('-ffile-prefix-map=/src=.', environment['CGO_CFLAGS'])
        rapidyenc = usenet.rapidyenc_command(Path('/src'), Path('/b'))
        self.assertIn('-DDISABLE_SHARED=ON', rapidyenc)
        self.assertIn('/src/third_party/rapidyenc-native', rapidyenc)

    def test_only_the_c_library_is_needed(self):
        good = ' 0x1 (NEEDED)  Shared library: [libc.so.6]\n'
        with patch.object(usenet.subprocess, 'check_output', side_effect=[ELF_HEADER, good]):
            self.assertEqual(['libc.so.6'], usenet.check_dynamic(Path('nuvio-usenet')))
        stdcpp = good + ' 0x1 (NEEDED)  Shared library: [libstdc++.so.6]\n'
        with patch.object(usenet.subprocess, 'check_output', side_effect=[ELF_HEADER, stdcpp]):
            with self.assertRaisesRegex(ValueError, 'more than the C library'):
                usenet.check_dynamic(Path('nuvio-usenet'))
        runpath = good + ' 0x1d (RUNPATH)  Library runpath: [$ORIGIN]\n'
        with patch.object(usenet.subprocess, 'check_output', side_effect=[ELF_HEADER, runpath]):
            with self.assertRaisesRegex(ValueError, 'search path'):
                usenet.check_dynamic(Path('nuvio-usenet'))

    def test_the_engine_lands_where_the_app_looks(self):
        self.assertEqual('lib/native/nuvio-usenet', stage.USENET_ENGINE)
        self.assertIn('destination / USENET_ENGINE', (PACKAGING / 'stage.py').read_text())
        lookup = (REPO / 'composeApp/src/desktopMain/kotlin/com/nuvio/app/features/usenet/LinuxUsenetEngine.kt').read_text()
        self.assertIn('"nuvio-usenet"', lookup)
        self.assertIn('appDir.resolve("native")', lookup)

    def test_it_builds_with_network_before_gradle(self):
        script = (PACKAGING / 'native-build.sh').read_text()
        phase = script[script.index('[[ $phase == build ]]'):]
        self.assertLess(phase.index('usenet/build.py /output'), phase.index('./gradlew'))

    def test_recipe_files_are_not_builder_seed_inputs(self):
        from webkit import builder_seed
        with patch.object(builder_seed, 'digest', side_effect=lambda path: hashlib.sha256(b'').hexdigest()):
            names = builder_seed.inputs()
        self.assertFalse([name for name in names if 'usenet' in name])


if __name__ == '__main__':
    unittest.main()
