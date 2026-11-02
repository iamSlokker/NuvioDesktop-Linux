"""format_smoke.py and the smoke script's --allow-failed-probe. No container and no app is started."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGING))
import format_smoke  # noqa: E402

SCRIPT = PACKAGING.parents[2] / 'composeApp/scripts/linux-native-smoke.py'
spec = importlib.util.spec_from_file_location('linux_native_smoke', SCRIPT)
smoke_script = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke_script)


class LaunchesTest(unittest.TestCase):
    def test_only_the_run_without_a_device_may_fail_and_only_playback(self):
        runs = {name: (env, allowed) for name, env, allowed in format_smoke.launches('llvmpipe (x)')}
        self.assertEqual(['gnome', 'lavapipe', 'no-device'], sorted(runs))
        self.assertEqual([], runs['lavapipe'][1])
        self.assertEqual([], runs['gnome'][1])
        self.assertEqual(['linux playback'], runs['no-device'][1])
        self.assertEqual('vulkan-device=llvmpipe (x)', runs['lavapipe'][0]['NUVIO_NATIVE_SELFTEST_MPV_OPTIONS'])
        self.assertEqual('GNOME', runs['gnome'][0]['XDG_CURRENT_DESKTOP'])
        self.assertEqual('', runs['no-device'][0]['NUVIO_NATIVE_SELFTEST_MPV_OPTIONS'])

    def test_a_failed_launch_does_not_hide_the_next_and_no_display_leaks(self):
        seen = []

        def fake_run(args, env, timeout):
            seen.append((args, env))
            return type('Result', (), {'returncode': 1 if len(seen) == 1 else 0})()

        environ = dict(DISPLAY=':0', WAYLAND_DISPLAY='wayland-0', NUVIO_RUN_LIVE_DISPLAY_TESTS='1', LD_PRELOAD='x', KEEP='1')
        with patch.object(format_smoke.subprocess, 'run', fake_run):
            results = format_smoke.run_launches('/out', ['/bin/app'], 'lavapipe', {'A': 'b'}, environ)
        self.assertEqual({'lavapipe': False, 'gnome': True, 'no-device': True}, results)
        self.assertEqual(3, len(seen))
        for args, env in seen:
            self.assertEqual(['--', '/bin/app'], args[-2:])
            self.assertIn('--env=A=b', args)
            self.assertIn('--env=WEBKIT_DISABLE_COMPOSITING_MODE=1', args)
            self.assertEqual('1', env['KEEP'])
            for name in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS', 'LD_PRELOAD', 'LD_LIBRARY_PATH'):
                self.assertNotIn(name, env)

    def test_a_hung_launch_fails_instead_of_holding_the_job(self):
        def hung(args, env, timeout):
            raise format_smoke.subprocess.TimeoutExpired(args, timeout)

        with patch.object(format_smoke.subprocess, 'run', hung):
            results = format_smoke.run_launches('/out', ['/bin/app'], 'lavapipe', environ={})
        self.assertEqual({'lavapipe': False, 'gnome': False, 'no-device': False}, results)

    def test_the_report_needs_every_launch_to_pass(self):
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(0, format_smoke.report('deb', {'a': True, 'b': True}, out))
            self.assertEqual(1, format_smoke.report('deb', {'a': True, 'b': False}, out))
            self.assertEqual(1, format_smoke.report('deb', {}, out))
            self.assertEqual({'format': 'deb', 'launches': {}}, json.loads(Path(out, 'format-smoke.json').read_text()))


class ContainerTest(unittest.TestCase):
    def test_the_package_and_the_scripts_are_read_only(self):
        command = format_smoke.container_command('deb', '/tmp/a.deb', '24.04', '/tmp/out')
        self.assertIn('/tmp/a.deb:/package/a.deb:ro', command)
        self.assertIn('%s:/packaging:ro' % PACKAGING, command)
        self.assertIn('docker.io/library/ubuntu:24.04', command)
        self.assertIn('/tmp/out:/out', command)
        self.assertNotIn('--cap-add=ALL', command)
        self.assertNotIn('--privileged', command)

    def test_only_the_appimage_gets_nesting_and_no_host_device(self):
        command = format_smoke.container_command('appimage', '/tmp/a.AppImage', '22.04', '/tmp/out')
        self.assertIn('--cap-add=ALL', command)
        self.assertNotIn('--privileged', command)
        self.assertFalse([arg for arg in command if arg.startswith('--device')])

    def test_the_appimage_harness_leaves_its_payload_out(self):
        for package in format_smoke.APPIMAGE_MUST_BRING:
            self.assertNotIn(package, format_smoke.HARNESS_PACKAGES + format_smoke.APPIMAGE_HOST_PACKAGES)
        # The host-owned libraries a bridge load needs; packaging keeps them out on purpose.
        self.assertIn('libva2', format_smoke.APPIMAGE_HOST_PACKAGES)

    def test_a_display_refuses(self):
        with tempfile.TemporaryDirectory() as out, patch.dict(os.environ, {'WAYLAND_DISPLAY': 'wayland-0'}):
            with self.assertRaises(SystemExit):
                format_smoke.main(['host', '--format', 'deb', '--package', '/tmp/a.deb', '--out', out])


class AllowFailedProbeTest(unittest.TestCase):
    def launch(self, lines, done, allowed=()):
        launch = smoke_script.Launch(1, '/nonexistent', ['/bin/app'], True, None, 71, (), allowed)
        launch.first_frame_ms = 1
        launch.exited_early = False
        launch.exit_code = 0
        launch.lines = lines
        launch.done = done
        return launch

    FAILED = 'Error: (NativeSelfTest) linux playback FAILED: timed out'

    def test_the_named_probe_may_fail(self):
        self.assertEqual([], self.launch([self.FAILED], (14, 1), ['linux playback']).failures())

    def test_any_other_probe_still_fails(self):
        other = 'Error: (NativeSelfTest) linux mpris FAILED: boom'
        problems = self.launch([self.FAILED, other], (13, 2), ['linux playback']).failures()
        self.assertEqual(['2 self-test probe(s) failed: linux mpris'], problems)

    def test_nothing_is_allowed_by_default(self):
        self.assertEqual(['1 self-test probe(s) failed: linux playback'], self.launch([self.FAILED], (14, 1)).failures())

    def test_a_count_with_no_failure_line_still_fails(self):
        self.assertTrue(self.launch([], (14, 1), ['linux playback']).failures())

    def test_extra_environment_replaces_the_self_test_data_directory(self):
        with tempfile.TemporaryDirectory() as out:
            launch = smoke_script.Launch(1, out, ['/bin/app'], True, None, 71, ['NUVIO_NATIVE_SELFTEST_DATA_DIR='])
            self.assertEqual('', launch.environment()['NUVIO_NATIVE_SELFTEST_DATA_DIR'])


if __name__ == '__main__':
    unittest.main()
