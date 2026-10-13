"""Real class/JAR fixtures, no application initialization or backend requests."""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from client_config import CONFIG_CLASS, HERE, public_defaults, verify_packaged_backend


class PackagedClientConfigTest(unittest.TestCase):
    def test_snapshot_uses_only_reviewed_public_properties_and_refuses_private_configuration(self):
        script = (HERE / 'native-build.sh').read_text()
        staging = script[script.index('[[ ! -e local.properties ]]'):script.index('offline=()')]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            destination = root / 'tools/linux/packaging'
            destination.mkdir(parents=True)
            shutil.copy2(HERE / 'public-client.properties', destination)
            subprocess.run(['bash', '-euc', staging], cwd=root, check=True)
            self.assertEqual((root / 'local.properties').read_bytes(),
                             (HERE / 'public-client.properties').read_bytes())
            (root / 'local.properties').write_text('private fixture, must not be overwritten')
            result = subprocess.run(['bash', '-euc', staging], cwd=root, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('private fixture, must not be overwritten', (root / 'local.properties').read_text())

    def test_reviewed_defaults_are_exact_official_public_client_values(self):
        self.assertEqual({'URL': 'https://api.nuvio.tv',
                          'ANON_KEY': 'sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN'}, public_defaults())

    def test_packaged_constants_reject_empty_localhost_wrong_key_missing_and_duplicate_classes(self):
        javac = pathlib.Path(os.environ.get('JAVA_HOME', '/nonexistent')) / 'bin/javac'
        if not javac.is_file():
            self.skipTest('JAVA_HOME with javac required for actual packaged class fixtures')
        defaults = public_defaults()
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            app = root / 'lib/app'
            app.mkdir(parents=True)
            source = root / 'SupabaseConfig.java'
            # Keeping the correct values elsewhere in the constant pool must not
            # allow an invalid URL/key field to pass the artifact audit.
            for url, key in [(defaults['URL'], defaults['ANON_KEY']), ('', ''),
                             ('http://localhost:8000', defaults['ANON_KEY']),
                             (defaults['URL'], 'sb_secret_forbidden-fixture')]:
                with self.subTest(url=url, key=key):
                    source.write_text('package com.nuvio.app.core.network; public class SupabaseConfig {'
                                      f'public static final String URL="{url}", ANON_KEY="{key}";'
                                      f'public String unrelated() {{ return "{defaults["URL"]}{defaults["ANON_KEY"]}"; }}'
                                      '}')
                    subprocess.run([str(javac), '-d', str(root), str(source)], check=True)
                    with zipfile.ZipFile(app / 'app.jar', 'w') as jar:
                        jar.write(root / CONFIG_CLASS, CONFIG_CLASS)
                    if url == defaults['URL'] and key == defaults['ANON_KEY']:
                        verify_packaged_backend(root)
                        shutil.copy2(app / 'app.jar', app / 'duplicate.jar')
                        with self.assertRaisesRegex(ValueError, 'Packaged official backend'):
                            verify_packaged_backend(root)
                        (app / 'duplicate.jar').unlink()
                    else:
                        with self.assertRaisesRegex(ValueError, 'Packaged official backend'):
                            verify_packaged_backend(root)
            (app / 'app.jar').unlink()
            with self.assertRaisesRegex(ValueError, 'Packaged official backend'):
                verify_packaged_backend(root)
