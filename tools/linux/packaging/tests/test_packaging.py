#!/usr/bin/env python3
"""Pure file/policy tests; no display, real user data, or network."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fetch import CONFIG, fetch
from verify import entries, scan_bytes, check_webkit_relocation
from formats import PLATFORM
from build import development_source
from flatpak import launcher_text


class PackagingContractTest(unittest.TestCase):
    def test_flatpak_launcher_keeps_runtime_driver_extensions_after_private_libraries(self):
        with tempfile.TemporaryDirectory(prefix='nuvio flatpak ') as tmp:
            root = pathlib.Path(tmp)
            (root / 'bin').mkdir()
            fake = root / 'bin/nuvio'
            fake.write_text('#!/bin/sh\nprintf "%s\\n" "$LD_LIBRARY_PATH" "$GDK_BACKEND" "$@"\n')
            fake.chmod(0o755)
            launcher = root / 'launcher'
            launcher.write_text(launcher_text(root))
            env = dict(os.environ, LD_LIBRARY_PATH='/usr/lib/x86_64-linux-gnu/GL/default/lib')
            actual = subprocess.check_output(['/bin/sh', str(launcher), 'a b'], text=True, env=env).splitlines()
            self.assertEqual([f'{root}/lib/native:{root}/lib/runtime/lib:{root}/lib/runtime/lib/server:'
                              '/usr/lib/x86_64-linux-gnu/GL/default/lib', 'x11', 'a b'], actual)

    def test_release_webkit_cannot_pass_as_relocatable_appimage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            check_webkit_relocation(root)  # DEB/Flatpak use platform WebKit.
            (root / 'lib/webkit2gtk-4.1').mkdir(parents=True)
            (root / 'lib/native').mkdir()
            (root / 'lib/native/libwebkit2gtk-4.1.so.0').write_bytes(b'/usr/lib/webkit\x00')
            with self.assertRaisesRegex(ValueError, 'AppImage blocked'):
                check_webkit_relocation(root)

    def test_launcher_uses_only_private_libraries_and_preserves_arguments(self):
        with tempfile.TemporaryDirectory(prefix='nuvio image ') as tmp:
            image = pathlib.Path(tmp)
            (image / 'bin').mkdir()
            launcher = pathlib.Path(__file__).resolve().parents[1] / 'nuvio-desktop'
            shutil.copy2(launcher, image / 'bin/nuvio-desktop')
            fake = image / 'bin/nuvio'
            fake.write_text('#!/bin/sh\nprintf "%s\\n" "$LD_LIBRARY_PATH" "$GDK_BACKEND" "$@"\n')
            fake.chmod(0o755)
            env = dict(os.environ, LD_LIBRARY_PATH='/untrusted/lib', GDK_BACKEND='wayland')
            result = subprocess.check_output([str(image / 'bin/nuvio-desktop'), 'a b', '--fixture'],
                                             text=True, env=env).splitlines()
            self.assertEqual([f'{image}/lib/native:{image}/lib/runtime/lib:{image}/lib/runtime/lib/server',
                              'x11', 'a b', '--fixture'], result)

    def test_development_snapshot_excludes_arbitrary_untracked_files(self):
        self.assertTrue(development_source('tools/linux/packaging/build.py'))
        self.assertTrue(development_source('.github/workflows/linux-packages.yml'))
        for name in ['personal-token.txt', '.env', 'local.properties', 'settings/account.json']:
            self.assertFalse(development_source(name), name)

    def test_all_tool_inputs_are_https_and_sha256_pinned(self):
        for spec in CONFIG['tools'].values():
            self.assertTrue(spec['url'].startswith('https://'))
            self.assertNotIn('/latest/', spec['url'])
            self.assertNotIn('/continuous/', spec['url'])
            self.assertRegex(spec['sha256'], '^[0-9a-f]{64}$')

    def test_cached_tool_bytes_are_verified(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / 'tool'
            p.write_bytes(b'trusted fixture')
            spec = dict(url='https://unreachable.invalid/fixture', sha256=hashlib.sha256(p.read_bytes()).hexdigest())
            self.assertEqual(p, fetch(spec, p))

    def test_inventory_includes_modes_hashes_and_relative_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'file').write_bytes(b'fixture')
            (root / 'file').chmod(0o755)
            (root / 'link').symlink_to('file')
            actual = entries(root)
            self.assertEqual(0o755, actual['file']['mode'])
            self.assertEqual(hashlib.sha256(b'fixture').hexdigest(), actual['file']['sha256'])
            self.assertEqual(dict(target='file'), actual['link'])

    def test_broken_link_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'link').symlink_to('missing')
            with self.assertRaises(ValueError):
                entries(root)

    def test_absolute_link_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'file').touch()
            (root / 'link').symlink_to(root / 'file')
            with self.assertRaises(ValueError):
                entries(root)

    def test_machine_paths_are_rejected_including_archive_entry_bytes(self):
        for path in [b'/home/hmacedo/a', b'/var/home/hmacedo/a', b'/home/linuxbrew/a', b'/work/prefix/a']:
            with self.assertRaises(ValueError):
                scan_bytes(path, 'fixture.jar:entry')
        scan_bytes(b'$ORIGIN/../runtime/lib', 'fixture')
        scan_bytes(b'com/nuvio/app/features/home/components/Hero.class', 'fixture.jar')

    def test_graphics_drivers_and_platform_abi_are_never_portable_payload(self):
        for name in ['libc.so.6', 'ld-linux-x86-64.so.2', 'libstdc++.so.6', 'libgcc_s.so.1',
                     'libGL.so.1', 'libGLX.so.0', 'libEGL.so.1', 'libgbm.so.1',
                     'libdrm.so.2', 'libva.so.2', 'libnvidia-glcore.so.1', 'libcuda.so.1']:
            self.assertIsNotNone(PLATFORM.match(name), name)
        for name in ['libmpv.so.2', 'libwebkit2gtk-4.1.so.0', 'libgtk-3.so.0']:
            self.assertIsNone(PLATFORM.match(name), name)

    def test_flatpak_permissions_are_bounded_and_x11_explicit(self):
        permissions = json.loads((pathlib.Path(__file__).resolve().parents[1] / 'flatpak-permissions.json').read_text())
        self.assertIn('--socket=x11', permissions)
        self.assertIn('--device=dri', permissions)
        self.assertIn('--own-name=org.mpris.MediaPlayer2.Nuvio', permissions)
        for forbidden in ['--socket=session-bus', '--socket=system-bus', '--device=all',
                          '--filesystem=host', '--filesystem=home', '--talk-name=org.freedesktop.Flatpak']:
            self.assertNotIn(forbidden, permissions)


if __name__ == '__main__':
    unittest.main()
