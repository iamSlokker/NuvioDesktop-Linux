"""Headless engine cache and production-policy contracts. No network or GUI."""
import importlib.util
import json
import pathlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

RECIPE = pathlib.Path(__file__).resolve().parents[1] / 'webkit'
spec = importlib.util.spec_from_file_location('webkit_build', RECIPE / 'build.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
from webkit.contract import ENGINE_FILES, PROCESSES, HELPERS, RESOURCES, check_runtime
from webkit.stage import linked_paths, clean_icon_sources, packaged_build_policy
from verify import check_webkit_relocation, scan_bytes

driver_spec = importlib.util.spec_from_file_location('packaging_driver', RECIPE.parent / 'build.py')
driver = importlib.util.module_from_spec(driver_spec)
driver_spec.loader.exec_module(driver)


class WebKitBuildTest(unittest.TestCase):
    def test_runtime_policy_retains_provenance_without_compiler_build_paths(self):
        policy = dict(DEVELOPER_MODE='OFF', ENABLE_BUBBLEWRAP_SANDBOX='ON', CMAKE_BUILD_TYPE='Release',
                      USE_GTK4='OFF', USE_SOUP2='OFF', CMAKE_CXX_FLAGS_RELEASE='-ffile-prefix-map=/work/source/=')
        original = dict(policy)
        packaged = packaged_build_policy(policy)
        scan_bytes(json.dumps(packaged).encode(), 'runtime policy')
        self.assertEqual(packaged['configuration']['ENABLE_BUBBLEWRAP_SANDBOX'], 'ON')
        self.assertEqual(policy, original)  # Full source/build record is preserved.
        policy['CMAKE_CXX_FLAGS_RELEASE'] += ' -DNEW_FLAG'
        self.assertNotEqual(packaged['full_policy_sha256'], packaged_build_policy(policy)['full_policy_sha256'])

    def test_source_path_scan_keeps_absolute_guards_and_preserves_notices(self):
        for value in [b' https://www.openssl.org/source/license.html\n',
                      b'Source: https://example.org/source/archive.tar.xz\n',
                      b'Files: third_party/libyuv/source/*\nCopyright: fixture\n',
                      b'\x00./source/scale.cc\x00', b'../source/file.cpp']:
            with self.subTest(allowed=value):
                scan_bytes(value, 'fixture')
        for value in [b'/source/private.cpp', b'"/source/private.cpp"',
                      b'CODE_FILE=/source/private.cpp', b'/work/source/private.cpp',
                      b'/build/path with spaces/source/private.cpp', b'/build/./source/file',
                      b'/build/../source/file', b'Files: /source/private.cpp\n',
                      b'Files: safe /source/private.cpp\n', b'file:///source/private.cpp',
                      b'/build/https://example.org/source/private.cpp',
                      b'./source/safe.cpp\x00/source/private.cpp\x00',
                      b' https://example.org/source/license.html\n/source/private.cpp',
                      b' https://example.org/source/home/hmacedo/private\n',
                      b'./source/safe.cpp\x00/home/another-user/private\x00',
                      b'./source/work/prefix/private\x00']:
            with self.subTest(rejected=value), self.assertRaises(ValueError):
                scan_bytes(value, 'fixture')

    def test_installed_icon_cleanup_handles_both_quotes_and_retains_originals(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            originals, cleaned, retained = [base / name for name in ['original', 'clean', 'source']]
            originals.mkdir()
            retained.mkdir()
            for number, quote in enumerate(['"', "'"]):
                name = f'icon{number}.svg'
                drawing = '<svg viewBox="0 0 16 16"><path d="M0 0h16v16H0z"/></svg>\n'
                metadata = f' inkscape:export-filename={quote}/home/artist/export.png{quote}'
                original = drawing.replace('<svg ', '<svg' + metadata + ' ')
                (originals / name).write_text(original)
            (originals / 'unchanged.svg').write_text(drawing)
            self.assertEqual(clean_icon_sources(originals, cleaned, retained), {'icon0.svg': 1, 'icon1.svg': 1})
            for name in ['icon0.svg', 'icon1.svg']:
                self.assertEqual((cleaned / name).read_text(), drawing)
                self.assertEqual((retained / 'Adwaita/original' / name).read_bytes(), (originals / name).read_bytes())
                scan_bytes((cleaned / name).read_bytes(), name)
            self.assertFalse((cleaned / 'unchanged.svg').exists())
            self.assertIn('/home/artist/export.png', (retained / 'adwaita-resource-paths.patch').read_text())

    def test_source_svg_cleanup_retains_drawing_and_patch_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            icons = base / 'icons'
            icons.mkdir()
            icon = icons / 'fixture.svg'
            original = ('<svg xmlns="http://www.w3.org/2000/svg" '
                        'xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" '
                        'inkscape:export-filename="/home/artist/export.png" viewBox="0 0 16 16">'
                        '<path d="M0 0h16v16H0z"/></svg>\n')
            icon.write_text(original)
            with self.assertRaises(ValueError):
                scan_bytes(icon.read_bytes(), 'source SVG')
            subprocess.run([sys.executable, str(RECIPE / 'clean-gtk-resources.py'), str(icons)],
                           check=True, stdout=subprocess.DEVNULL)
            self.assertEqual(icon.read_text(), original.replace(' inkscape:export-filename="/home/artist/export.png"', ''))
            scan_bytes(icon.read_bytes(), 'cleaned SVG')
            self.assertEqual(json.loads((base / 'gtk-resource-paths.json').read_text()), {'fixture.svg': 1})
            self.assertIn('/home/artist/export.png', (base / 'gtk-resource-paths.patch').read_text())

    def test_appimage_cannot_pass_when_the_entire_engine_is_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            check_webkit_relocation(root)  # A canonical DEB/Flatpak payload has no private WebKit.
            with self.assertRaisesRegex(ValueError, 'provenance'):
                check_webkit_relocation(root, required=True)
            marker = root / 'share/nuvio-webkit/runtime.json'
            marker.parent.mkdir(parents=True)
            marker.write_text('{}')
            with self.assertRaises((ValueError, KeyError)):
                check_webkit_relocation(root)

    def test_format_dispatch_keeps_engine_separate_from_working_formats(self):
        for selection in ['deb', 'flatpak', 'webkit', 'appimage']:
            with self.subTest(format=selection), tempfile.TemporaryDirectory() as tmp, \
                    patch.object(sys, 'argv', ['build.py', '--format', selection, '--work-dir', tmp]), \
                    patch.dict(os.environ, DISPLAY='', WAYLAND_DISPLAY='', NUVIO_RUN_LIVE_DISPLAY_TESTS=''), \
                    patch.object(driver, 'native') as native, patch.object(driver, 'appimage') as appimage, \
                    patch.object(driver, 'run') as run:
                driver.main()
                self.assertEqual(native.call_count, int(selection == 'deb'))
                self.assertEqual(appimage.call_count, int(selection == 'appimage'))
                commands = [str(call.args[1]) for call in run.call_args_list]
                self.assertEqual(commands, {
                    'deb': [], 'appimage': [],
                    'flatpak': [str(RECIPE.parent / 'flatpak.py'), str(RECIPE.parent / 'verify.py')],
                    'webkit': [str(RECIPE / 'build.py')],
                }[selection])

    def test_all_formats_preserves_independent_results_but_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(sys, 'argv', ['build.py', '--format', 'all', '--work-dir', tmp]), \
                patch.dict(os.environ, DISPLAY='', WAYLAND_DISPLAY='', NUVIO_RUN_LIVE_DISPLAY_TESTS=''), \
                patch.object(driver, 'native', side_effect=ValueError('canonical failure')), \
                patch.object(driver, 'appimage', side_effect=ValueError('engine failure')) as appimage, \
                patch.object(driver, 'run') as run:
            with self.assertRaises(SystemExit) as failure:
                driver.main()
            self.assertIn('canonical failure', str(failure.exception))
            self.assertIn('engine failure', str(failure.exception))
            self.assertEqual(run.call_count, 2)  # Flatpak export and verification still attempted.
            appimage.assert_called_once()

    def test_source_is_the_audited_release(self):
        self.assertEqual(engine.MANIFEST['version'], '2.50.4')
        self.assertEqual(engine.MANIFEST['source']['sha256'],
                         'd3bfa473845acfab72635bada5e0d134fda6792c5b95c5c5cd141b46125bd8e4')
        self.assertTrue(engine.MANIFEST['source']['url'].startswith('https://webkitgtk.org/releases/'))

    def test_recipe_changes_and_builder_changes_invalidate_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            here = base / 'webkit'
            here.mkdir()
            (base / 'apt-prefetch.py').write_text('transport fixture')
            for name in engine.BUILD_INPUTS:
                (here / name).write_text('fixture ' + name)
            script = here / 'build-engine.sh'
            script.write_text('first')
            with patch.object(engine, 'HERE', here):
                first = engine.recipe_identity('base-image-1')
                (here / 'README.md').write_text('Documentation is not a compiler input')
                self.assertEqual(first, engine.recipe_identity('base-image-1'))
                self.assertNotEqual(first, engine.recipe_identity('base-image-2'))
                script.write_text('second')
                self.assertNotEqual(first, engine.recipe_identity('base-image-1'))

    def test_cache_requires_matching_provenance_bytes_and_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            generation = pathlib.Path(tmp)
            root = generation / 'install/opt/nuvio-webkit'
            root.mkdir(parents=True)
            library = root / 'library.so'
            library.write_bytes(b'fixture')
            library.chmod(0o755)
            for relative in ENGINE_FILES + PROCESSES:
                p = root / relative
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(b'\x7fELFfixture engine')
                p.chmod(0o755)
            (generation / 'objects').mkdir()
            (generation / 'objects/CMakeCache.txt').write_text(
                'DEVELOPER_MODE:BOOL=OFF\nENABLE_BUBBLEWRAP_SANDBOX:BOOL=ON\n'
                'CMAKE_BUILD_TYPE:STRING=Release\nUSE_GTK4:BOOL=OFF\nUSE_SOUP2:BOOL=OFF\n'
                'CMAKE_C_FLAGS_RELEASE:STRING=-fstack-protector-strong -D_FORTIFY_SOURCE=2\n'
                'CMAKE_CXX_FLAGS_RELEASE:STRING=-fstack-protector-strong -D_FORTIFY_SOURCE=2\n'
                'CMAKE_SHARED_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now\n'
                'CMAKE_MODULE_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now\n'
                'CMAKE_EXE_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now\n')
            (generation / 'corresponding-source').mkdir()
            (generation / 'corresponding-source/source.tar').write_bytes(b'fixture source')
            self.assertFalse(engine.valid_cache(generation, 'recipe'))
            record = dict(recipe_identity='recipe', source_sha256=engine.MANIFEST['source']['sha256'],
                          build_policy=engine.build_policy(generation),
                          corresponding_source=engine.inventory(generation / 'corresponding-source'),
                          files=engine.inventory(root))
            (generation / 'build-complete.json').write_text(json.dumps(record))
            self.assertTrue(engine.valid_cache(generation, 'recipe'))
            cache = generation / 'objects/CMakeCache.txt'
            configured = cache.read_text()
            cache.write_text(configured.replace('CMAKE_MODULE_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now',
                                                'CMAKE_MODULE_LINKER_FLAGS:STRING=-Wl,-z,relro'))
            with self.assertRaisesRegex(ValueError, 'Missing linker hardening: CMAKE_MODULE_LINKER_FLAGS'):
                engine.valid_cache(generation, 'recipe')
            cache.write_text(configured)
            with self.assertRaisesRegex(ValueError, 'provenance'):
                engine.valid_cache(generation, 'other')
            library.chmod(0o644)
            with self.assertRaisesRegex(ValueError, 'inventory'):
                engine.valid_cache(generation, 'recipe')
            library.chmod(0o755)
            library.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'inventory'):
                engine.valid_cache(generation, 'recipe')

    def test_private_runtime_requires_inventory_and_executable_helpers(self):
        with tempfile.TemporaryDirectory(prefix='nuvio contract ') as tmp:
            root = pathlib.Path(tmp)
            with self.assertRaisesRegex(ValueError, 'provenance'):
                check_runtime(root)
            files = {}
            for relative in ENGINE_FILES + PROCESSES + HELPERS + RESOURCES:
                p = root / relative
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(b'\x7fELFfixture engine')
                p.chmod(0o755)
                files[relative] = dict(sha256=engine.digest(p), mode=0o755)
            record = root / 'share/nuvio-webkit/runtime.json'
            record.parent.mkdir(parents=True)
            data = dict(version='2.50.4', source_sha256=engine.MANIFEST['source']['sha256'],
                        developer_mode=False, bubblewrap_sandbox=True, files=files)
            record.write_text(json.dumps(data))
            check_runtime(root)
            helper = root / HELPERS[0]
            helper.chmod(0o4755)
            with self.assertRaisesRegex(ValueError, 'permissions'):
                check_runtime(root)
            helper.chmod(0o755)
            helper.write_bytes(b'\x7fELFtampered')
            with self.assertRaisesRegex(ValueError, 'inventory mismatch'):
                check_runtime(root)
            helper.write_bytes(b'not a binary')
            with self.assertRaisesRegex(ValueError, 'not ELF'):
                check_runtime(root)

    def test_ldd_contract_preserves_spaces(self):
        self.assertEqual(linked_paths('  libfoo.so.0 => /tmp/image with spaces/lib/foo.so (0x00007ff)\n'
                                      '  libc.so.6 => /lib/libc.so.6 (0x0000123)\n'),
                         [('libfoo.so.0', '/tmp/image with spaces/lib/foo.so'), ('libc.so.6', '/lib/libc.so.6')])

    def test_appimage_launcher_regenerates_private_caches_and_preserves_arguments(self):
        with tempfile.TemporaryDirectory(prefix='nuvio mount with spaces ') as tmp:
            root = pathlib.Path(tmp) / 'image'
            (root / 'bin').mkdir(parents=True)
            for rel in ['lib/native/gdk-pixbuf-2.0/2.10.0/loaders', 'lib/native/gtk-3.0/3.0.0/immodules']:
                (root / rel).mkdir(parents=True)
                (root / rel / 'fixture.so').touch()
            for name in ['nuvio-pixbuf-query', 'nuvio-gtk-query']:
                p = root / 'bin' / name
                p.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
                p.chmod(0o755)
            (root / 'bin/nuvio').write_text('#!/bin/sh\nset -eu\n'
                '[ "$WEBKIT_FORCE_SANDBOX" = 1 ]\n'
                '[ "$GST_PLUGIN_SCANNER_1_0" = "$GST_PLUGIN_SCANNER" ]\n'
                'cat "$GDK_PIXBUF_MODULE_FILE" "$GTK_IM_MODULE_FILE"\n'
                'printf "%s\\n" "$GIO_MODULE_DIR" "$GST_PLUGIN_SCANNER" "$LD_LIBRARY_PATH" "$GDK_BACKEND" "$@"\n'
                'IFS= read -r input\nprintf "%s\\n" "$input"\n')
            (root / 'bin/nuvio').chmod(0o755)
            launcher = root / 'bin/nuvio-desktop'
            shutil.copy2(RECIPE / 'nuvio-appimage', launcher)
            alias = pathlib.Path(tmp) / 'symlinked image'
            alias.symlink_to(root, target_is_directory=True)
            for entry in [launcher, alias / 'bin/nuvio-desktop']:
                with self.subTest(entry=entry):
                    result = subprocess.check_output(['/bin/sh', str(entry), 'one two', '--fixture'], text=True,
                                                     input='fixture stdin\n',
                                                     env=dict(os.environ, TMPDIR=tmp, WEBKIT_FORCE_SANDBOX='0')).splitlines()
                    self.assertEqual(result, [str(root / 'lib/native/gdk-pixbuf-2.0/2.10.0/loaders/fixture.so'),
                                              str(root / 'lib/native/gtk-3.0/3.0.0/immodules/fixture.so'),
                                              str(root / 'lib/native/gio/modules'), str(root / 'bin/nuvio-gst-plugin-scanner'),
                                              f'{root}/lib/native:{root}/lib/runtime/lib:{root}/lib/runtime/lib/server',
                                              'x11', 'one two', '--fixture', 'fixture stdin'])
            self.assertEqual(list(pathlib.Path(tmp).glob('nuvio-modules.*')), [])

    def test_cache_rejects_absolute_and_escaping_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = pathlib.Path(tmp)
            root = base / 'image'
            root.mkdir()
            (base / 'outside').touch()
            link = root / 'library.so'
            for target in [base / 'outside', pathlib.Path('../outside')]:
                link.symlink_to(target)
                with self.assertRaises(ValueError):
                    engine.inventory(root)
                with self.assertRaises(ValueError):
                    engine.inventory(root, source=True)
                link.unlink()

    def test_dangling_source_link_never_becomes_an_accepted_runtime_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'optional-source').symlink_to('not-in-release-archive')
            self.assertEqual(engine.inventory(root, source=True),
                             {'optional-source': {'link': 'not-in-release-archive'}})
            with self.assertRaisesRegex(ValueError, 'broken'):
                engine.inventory(root)

    def test_build_is_production_sandboxed_and_bounded(self):
        script = (RECIPE / 'build-engine.sh').read_text()
        for flag in ['-DDEVELOPER_MODE=OFF', '-DENABLE_BUBBLEWRAP_SANDBOX=ON',
                     '-DCMAKE_MODULE_LINKER_FLAGS=-Wl,--no-keep-memory,-z,relro,-z,now',
                     '--parallel 4', 'cmake --install']:
            self.assertIn(flag, script)
        self.assertNotIn('-DENABLE_BUBBLEWRAP_SANDBOX=OFF', script)
        self.assertNotIn('WEBKIT_DISABLE_SANDBOX', script)
        self.assertLessEqual(engine.MANIFEST['jobs'], 4)
        self.assertLessEqual(engine.MANIFEST['memory_gib'], 18)


if __name__ == '__main__':
    unittest.main()
