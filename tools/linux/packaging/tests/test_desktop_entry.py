"""nuvio:// links on Linux: the packaged desktop entry claims the scheme and every launcher
forwards the link to lib/nuvio. Runs stub executables only; never the app or a display."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import flatpak
import verify

ENTRY = HERE / 'com.nuvio.media.desktop.desktop'
LINK = 'nuvio://party/ABC234'


class DesktopEntryTest(unittest.TestCase):
    def test_packaged_entry_registers_the_scheme_and_passes_the_link(self):
        report = verify.check_desktop_entry(ENTRY.read_text(), ENTRY.name)
        self.assertEqual('x-scheme-handler/nuvio;', report['mime_type'])
        self.assertEqual('nuvio-desktop %u', report['exec'])
        if shutil.which('desktop-file-validate'):
            subprocess.run(['desktop-file-validate', str(ENTRY)], check=True)

    def test_entries_without_the_handler_or_the_link_argument_are_rejected(self):
        text = ENTRY.read_text()
        for broken, message in [(text.replace('MimeType=x-scheme-handler/nuvio;\n', ''), 'does not register'),
                                (text.replace('MimeType=x-scheme-handler/nuvio;', 'MimeType=video/mp4;'), 'does not register'),
                                (text.replace('Exec=nuvio-desktop %u', 'Exec=nuvio-desktop'), 'does not pass the link'),
                                (text.replace('Exec=nuvio-desktop %u', 'Exec=nuvio-desktop %f'), 'does not pass the link'),
                                # Only the [Desktop Entry] group counts.
                                (text.replace('MimeType=x-scheme-handler/nuvio;\n', '')
                                 + '[Desktop Action other]\nMimeType=x-scheme-handler/nuvio;\n', 'does not register')]:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    verify.check_desktop_entry(broken, 'fixture')
        flatpak_exec = text.replace('Exec=nuvio-desktop %u', 'Exec=/usr/bin/flatpak run --branch=stable --arch=x86_64 '
                                    '--command=nuvio-desktop --file-forwarding com.nuvio.media.desktop @@u %u @@')
        self.assertIn('@@u %u @@', verify.check_desktop_entry(flatpak_exec, 'fixture')['exec'])

    def run_launcher(self, launcher, image):
        """Executes `launcher` with a link; a stub lib/nuvio records the arguments it receives."""
        out = image / 'argv.txt'
        (image / 'lib').mkdir(parents=True, exist_ok=True)
        (image / 'lib/nuvio').write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$NUVIO_TEST_ARGV"\n')
        (image / 'lib/nuvio').chmod(0o755)
        env = {k: v for k, v in os.environ.items() if k not in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS')}
        subprocess.run([str(launcher), LINK], env=dict(env, NUVIO_TEST_ARGV=str(out)), check=True, timeout=30)
        return out.read_text().splitlines()

    def test_packaged_and_flatpak_launchers_forward_the_link_to_the_native_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = Path(temporary) / 'image'
            (image / 'bin').mkdir(parents=True)
            shutil.copy2(HERE / 'nuvio-desktop', image / 'bin/nuvio-desktop')
            self.assertEqual([LINK], self.run_launcher(image / 'bin/nuvio-desktop', image))
            launcher = Path(temporary) / 'flatpak-launcher'
            launcher.write_text(flatpak.launcher_text(image))
            launcher.chmod(0o755)
            self.assertEqual([LINK], self.run_launcher(launcher, image))

    def test_deb_and_appimage_wrappers_forward_their_arguments(self):
        source = (HERE / 'formats.py').read_text()
        self.assertIn('exec /opt/nuvio-desktop/bin/nuvio-desktop "$@"', source)
        self.assertIn('exec "$root/usr/lib/nuvio-desktop/bin/nuvio-desktop" "$@"', source)


if __name__ == '__main__':
    unittest.main()
