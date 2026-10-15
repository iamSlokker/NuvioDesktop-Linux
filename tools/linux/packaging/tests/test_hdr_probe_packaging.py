"""Experimental HDR capability helper packaging: build wiring, staging and image gates.

Pure file/ELF fixtures compiled locally; never connects to a display or runs the helper.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import verify
from stage import remove_build_data

MEDIA = json.loads((HERE.parents[0] / 'media-runtime/manifest.json').read_text())


class NativeBuildWiringTest(unittest.TestCase):
    def test_package_build_requires_probe_from_media_sdk_and_runs_session_test(self):
        script = (HERE / 'native-build.sh').read_text()
        self.assertIn('-DNUVIO_PACKAGE_BUILD=ON', script)
        self.assertRegex(script, r'--target player_bridge nuvio-hdr-probe ')
        self.assertIn('linux_hdr_session_test', script)
        self.assertIn('/output/native/linux_hdr_session_test --expect-wayland=yes\n', script)
        # Every build-only input the bridge build names is exported by the media contract.
        contract = MEDIA['artifact_contract']
        self.assertIn('-DNUVIO_WAYLAND_SCANNER=/media/bin/wayland-scanner', script)
        self.assertIn('bin/wayland-scanner', contract['executables'])
        self.assertIn('-DNUVIO_WAYLAND_PROTOCOLS_DIR=/media/share/wayland-protocols', script)
        self.assertIn('share/wayland-protocols/staging/color-management/color-management-v1.xml',
                      contract['build_data'])
        self.assertIn('-DNUVIO_VULKAN_INCLUDE_DIR=/media/include', script)
        self.assertIn('include/vulkan/vulkan.h', contract['headers'])
        self.assertIn('include/vulkan/vulkan_wayland.h', contract['headers'])
        self.assertIn('-DNUVIO_VULKAN_LIBRARY=/media/lib/libvulkan.so', script)
        self.assertEqual('libvulkan.so.1', MEDIA['artifact_contract']['symlinks']['lib/libvulkan.so'])
        # The helper's runtime dependencies are shipped private media libraries.
        sonames = {record['soname'] for record in contract['libraries'].values()}
        self.assertLessEqual(set(verify.HDR_PROBE_PRIVATE), sonames)
        # Only the scanner may use a builder library outside host_runtime.
        self.assertEqual({'bin/wayland-scanner': ['libexpat.so.1']}, contract['build_tool_needed'])
        self.assertNotIn('libexpat.so.1', MEDIA['host_runtime']['allowed_needed'])

    def test_cmake_fails_closed_for_package_builds_without_probe(self):
        text = (HERE.parents[2] / 'composeApp/src/desktopMain/native/linux/CMakeLists.txt').read_text()
        self.assertIn('if(NUVIO_PACKAGE_BUILD AND NOT NUVIO_HDR_PROBE)', text)
        self.assertIn('elseif(NUVIO_PACKAGE_BUILD)\n        message(FATAL_ERROR "nuvio-hdr-probe is required', text)

    def test_flatpak_sandbox_checks_probe_closure(self):
        text = (HERE / 'flatpak.py').read_text()
        self.assertIn('test -x /app/lib/nuvio-desktop/lib/native/nuvio-hdr-probe', text)
        self.assertIn("-o -name nuvio-hdr-probe", text)


class StageBuildDataTest(unittest.TestCase):
    def test_build_only_protocol_xml_is_removed_with_empty_parents(self):
        contract = MEDIA['artifact_contract']
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name in contract['build_data']:
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text('<protocol/>\n')
            notice = root / 'share/licenses/wayland-protocols/COPYING'
            notice.parent.mkdir(parents=True)
            notice.write_text('MIT\n')
            remove_build_data(root, contract)
            self.assertFalse((root / 'share/wayland-protocols').exists())
            self.assertTrue(notice.is_file())

    def test_build_data_outside_share_or_escaping_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name in ['bin/wayland-scanner', 'share/../bin/x']:
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'build-data location'):
                    remove_build_data(root, {'build_data': [name]})


@unittest.skipUnless(shutil.which('cc') and shutil.which('readelf') and shutil.which('ldd'),
                     'C compiler and binutils required for ELF fixtures')
class ImageProbeGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name) / 'image with spaces'
        self.native = self.root / 'lib/native'
        self.native.mkdir(parents=True)
        self.sources = pathlib.Path(self.tmp.name) / 'src'
        self.sources.mkdir()
        for soname in verify.HDR_PROBE_PRIVATE + ('libextra.so.1',):
            self.library(soname)

    def compile(self, *args):
        subprocess.run(['cc', *map(str, args)], check=True, capture_output=True)

    def library(self, soname):
        source = self.sources / (soname + '.c')
        source.write_text('int fixture_%s(void) { return 1; }\n' % soname.split('.')[0].replace('-', '_'))
        self.compile('-shared', '-fPIC', '-Wl,-soname,' + soname, '-o', self.native / soname, source)

    def probe(self, runpath='$ORIGIN:$ORIGIN/../runtime/lib', libraries=verify.HDR_PROBE_PRIVATE):
        source = self.sources / 'probe.c'
        source.write_text('int main(void) { return 0; }\n')
        links = [str(self.native / name) for name in libraries]
        self.compile('-o', self.native / 'nuvio-hdr-probe', source, '-Wl,--no-as-needed',
                     '-Wl,--enable-new-dtags,-rpath,' + runpath, *links)

    def test_bundled_runpath_layout_passes(self):
        self.probe()
        with patch.dict(os.environ, {'LD_LIBRARY_PATH': '/nonexistent'}):
            report = verify.check_hdr_probe(self.root)
        self.assertEqual('$ORIGIN:$ORIGIN/../runtime/lib', report['runpath'])
        self.assertLessEqual(set(verify.HDR_PROBE_PRIVATE), set(report['needed']))

    def test_missing_or_non_executable_helper_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing/non-executable HDR'):
            verify.check_hdr_probe(self.root)
        self.probe()
        (self.native / 'nuvio-hdr-probe').chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'Missing/non-executable HDR'):
            verify.check_hdr_probe(self.root)

    def test_non_bundled_runpath_is_rejected(self):
        for runpath in ['/usr/lib/x86_64-linux-gnu', '$ORIGIN']:
            with self.subTest(runpath=runpath):
                self.probe(runpath=runpath)
                with self.assertRaisesRegex(ValueError, 'RUNPATH differs'):
                    verify.check_hdr_probe(self.root)

    def test_unexpected_or_missing_dependency_is_rejected(self):
        for libraries in [verify.HDR_PROBE_PRIVATE + ('libextra.so.1',), verify.HDR_PROBE_PRIVATE[:1]]:
            with self.subTest(libraries=libraries):
                self.probe(libraries=libraries)
                with self.assertRaisesRegex(ValueError, 'Unexpected HDR capability helper dependencies'):
                    verify.check_hdr_probe(self.root)

    def test_private_library_missing_from_image_is_rejected(self):
        self.probe()
        (self.native / 'libvulkan.so.1').unlink()
        with self.assertRaisesRegex(ValueError, 'Unresolved HDR|outside the bundled runtime'):
            verify.check_hdr_probe(self.root)

    def test_build_only_media_inputs_in_image_are_rejected(self):
        self.probe()
        for relative in verify.HDR_BUILD_ONLY:
            with self.subTest(relative=relative):
                (self.root / relative).mkdir(parents=True)
                with self.assertRaisesRegex(ValueError, 'Build-only media SDK input'):
                    verify.check_hdr_probe(self.root)
                shutil.rmtree(self.root / relative)


class FlatpakBundleProbeTest(unittest.TestCase):
    TARGET = '/files/lib/nuvio-desktop/lib/native/nuvio-hdr-probe'

    def check(self, listing):
        with patch.object(verify.subprocess, 'check_output', return_value=listing) as output:
            result = verify.flatpak_hdr_probe('/repo', 'app/x/x86_64/nightly')
        self.assertEqual(['ostree', '--repo=/repo', 'ls', 'app/x/x86_64/nightly', self.TARGET],
                         output.call_args.args[0])
        return result

    def test_executable_regular_helper_is_accepted(self):
        self.assertEqual('-00755', self.check('-00755 0 0  12345 ' + self.TARGET + '\n')['mode'])

    def test_missing_non_regular_or_unsafe_mode_is_rejected(self):
        for listing in ['', '-00644 0 0 1 ' + self.TARGET, 'l00777 0 0 1 ' + self.TARGET + ' -> x',
                        '-04755 0 0 1 ' + self.TARGET, 'd00755 0 0 0 ' + self.TARGET,
                        '-00755 0 0 1 /files/other']:
            with self.subTest(listing=listing), self.assertRaisesRegex(ValueError, 'HDR capability helper'):
                self.check(listing)


if __name__ == '__main__':
    unittest.main()
