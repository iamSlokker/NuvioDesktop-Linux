"""tools/linux/umbra_sync_checklist.py over a throwaway repository with a fake upstream change."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import umbra_sync_checklist as checklist  # noqa: E402

BRIDGE = 'composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt'
SHARED = 'composeApp/src/desktopMain/kotlin/com/nuvio/app/Shared.kt'
LINUX_BRIDGE = 'composeApp/src/desktopMain/native/linux/player_bridge.cpp'
WINDOWS_BRIDGE = 'composeApp/src/desktopMain/native/windows/player_bridge.cpp'
CONTROLS_JS = 'composeApp/src/desktopMain/resources/player-ui/controls.js'
CONTROLS_HTML = 'composeApp/src/desktopMain/resources/player-ui/controls.html'


class ChecklistTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.environment = patch.dict(os.environ, dict(
            GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@invalid',
            GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@invalid'))
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.git('init', '-q', '-b', 'main')
        self.write({
            BRIDGE: 'object NativePlayerBridge {\n    external fun play()\n}\n',
            SHARED: 'fun old() = 1\n',
            LINUX_BRIDGE: 'void BRIDGE(play)() {}\nvoid BRIDGE(alreadyPorted)() {}\nconst char *c = "handledCommand";\n',
            WINDOWS_BRIDGE: 'void play() {}\n',
            CONTROLS_JS: 'const contextMenuItems = [];\n',
            CONTROLS_HTML: '<div></div>\n',
        })
        self.since = self.commit('base')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.repo), *args], check=True, stdout=subprocess.PIPE, text=True).stdout.strip()

    def write(self, files):
        for name, text in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def upstream_change(self, files):
        """The change on an upstream branch, with this checkout left at the Linux tree."""
        self.git('checkout', '-q', '-b', 'upstream')
        self.write(files)
        self.commit('upstream')
        self.git('checkout', '-q', 'main')
        return checklist.checklist(self.repo, self.since, 'upstream')

    def names(self, items, linux):
        return [item.name for item in items if item.linux is linux]

    def test_a_new_external_fun_needs_a_linux_symbol(self):
        result = self.upstream_change({BRIDGE: (
            'object NativePlayerBridge {\n    external fun play()\n'
            '    external fun alreadyPorted(handle: Long)\n'
            '    private external fun neverPorted(handle: Long): Int\n'
            '    // external fun commentedOut()\n}\n')})
        self.assertEqual(['alreadyPorted'], self.names(result['external fun'], True))
        self.assertEqual(['neverPorted'], self.names(result['external fun'], False))
        missing = next(item for item in result['external fun'] if not item.linux)
        self.assertEqual((BRIDGE, 4), (missing.path, missing.line))

    def test_a_windows_implementation_does_not_count_as_a_linux_side(self):
        self.git('checkout', '-q', '-b', 'windows-symbol')
        self.write({WINDOWS_BRIDGE: 'void neverPorted() {}\n'})
        self.commit('windows')
        self.git('checkout', '-q', 'main')
        self.git('merge', '-q', 'windows-symbol')
        result = self.upstream_change({BRIDGE: 'object NativePlayerBridge {\n    external fun neverPorted()\n}\n'})
        self.assertEqual(['neverPorted'], self.names(result['external fun'], False))

    def test_an_os_branch_needs_a_linux_case_nearby(self):
        padding = '// padding\n' * 20
        result = self.upstream_change({SHARED: (
            'fun old() = when (DesktopHostOs.current) {\n    DesktopHostOs.WINDOWS -> 1\n    DesktopHostOs.LINUX -> 2\n    DesktopHostOs.MACOS -> 3\n}\n'
            + padding + 'fun onlyWindows() = if (DesktopHostOs.current == DesktopHostOs.WINDOWS) 1 else 2\n'
            + padding + '// DesktopHostOs.WINDOWS in a comment\n')})
        self.assertEqual(['fun onlyWindows() = if (DesktopHostOs.current == DesktopHostOs.WINDOWS) 1 else 2'],
                         self.names(result['OS branch'], False))
        self.assertEqual(2, len(self.names(result['OS branch'], True)))

    def test_a_linux_case_already_in_this_checkout_counts(self):
        self.write({SHARED: (
            'fun old() = 1\nval here = if (DesktopHostOs.current == DesktopHostOs.WINDOWS) 1 else 2\nval l = DesktopHostOs.LINUX\n')})
        self.commit('linux side')
        result = self.upstream_change({SHARED: 'fun old() = 1\n' + '// x\n' * 20 + 'val here = if (DesktopHostOs.current == DesktopHostOs.WINDOWS) 1 else 2\n'})
        self.assertEqual([], self.names(result['OS branch'], False))

    def test_other_os_files_and_ifdefs(self):
        result = self.upstream_change({
            WINDOWS_BRIDGE: 'void play() {}\n#ifdef _WIN32\n#endif\n',
            'composeApp/src/desktopMain/kotlin/com/nuvio/app/WindowsThing.kt': 'val a = DesktopHostOs.WINDOWS\n',
            'composeApp/src/desktopMain/native/shared.cpp': '#if defined(__APPLE__)\n#endif\n',
        })
        self.assertEqual(['#if defined(__APPLE__)'], self.names(result['OS branch'], False))

    def test_a_hud_entry_needs_a_handler_outside_the_page(self):
        result = self.upstream_change({
            CONTROLS_JS: ('const contextMenuItems = [\n  { label: "A", action: "send:handledCommand" },\n'
                          '  { label: "B", action: "send:newCommand" },\n  { label: "C", action: "local:x" },\n];\n'),
            CONTROLS_HTML: '<div></div>\n<button data-command="newCommand"></button>\n<button data-command="otherCommand"></button>\n',
        })
        self.assertEqual(['handledCommand'], self.names(result['HUD entry'], True))
        # One line per command, though newCommand was added to the menu and to the page.
        self.assertCountEqual(['newCommand', 'otherCommand'], self.names(result['HUD entry'], False))

    def test_output_and_strict_exit(self):
        self.git('checkout', '-q', '-b', 'upstream')
        self.write({BRIDGE: 'object NativePlayerBridge {\n    external fun play()\n    external fun neverPorted()\n}\n'})
        self.commit('upstream')
        self.git('checkout', '-q', 'main')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, checklist.main([self.since, '--to', 'upstream', '--repo', str(self.repo)]))
            self.assertEqual(1, checklist.main([self.since, '--to', 'upstream', '--repo', str(self.repo), '--strict']))
        self.assertIn('external fun: 1 new, 1 with no Linux side', output.getvalue())
        self.assertIn('NO LINUX', output.getvalue())
        self.assertIn(BRIDGE + ':3  neverPorted', output.getvalue())

    def test_nothing_new_is_a_clean_list(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, checklist.main([self.since, '--to', self.since, '--repo', str(self.repo), '--strict']))
        self.assertIn('external fun: 0 new, 0 with no Linux side', output.getvalue())


if __name__ == '__main__':
    unittest.main()
