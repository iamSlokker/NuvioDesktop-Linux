"""Native-image inputs, notices, smoke record and evidence archive; fixtures only, no GraalVM."""
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_evidence
import native_fixture
import native_smoke


class NativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def make(self, **kwargs):
        self.image, self.evidence, self.exe = native_fixture.make(self.root, **kwargs)
        return self.image, self.evidence

    def test_stage_ships_a_path_free_manifest_and_every_notice(self):
        image, evidence = self.make()
        shipped = (image / native_evidence.PACKAGED_MANIFEST).read_text()
        self.assertEqual(shipped, (evidence / 'inputs.json').read_text())
        self.assertNotIn(str(self.root), shipped)
        manifest = json.loads(shipped)
        self.assertEqual(['application.jar', 'library.jar'], [row['name'] for row in manifest['classpath']])
        self.assertIn('-cp', manifest['arguments'])
        self.assertEqual('application.jar:library.jar', manifest['arguments'][manifest['arguments'].index('-cp') + 1])
        self.assertEqual('nuvio', manifest['arguments'][manifest['arguments'].index('-o') + 1])
        self.assertIn('-H:ConfigurationFileDirectories=composeApp/native-image-linux/agent,'
                      'composeApp/build/native-image/generated', manifest['arguments'])
        self.assertEqual('GraalVM Community', manifest['graalvm']['IMPLEMENTOR'])
        self.assertEqual({'lib/' + n for n in native_fixture.JDK_LIBRARIES} |
                         {'lib/runtime/lib/libjawt.so', 'lib/runtime/lib/fontconfig.properties.src'},
                         set(manifest['jdk_files']))
        # Notices of the JARs that no longer ship, the anime attribution and GraalVM's own.
        notices = image / native_evidence.JAR_NOTICES
        self.assertEqual(b'fixture license', (notices / 'application/META-INF/LICENSE').read_bytes())
        self.assertEqual(b'fixture notice', (notices / 'library/META-INF/NOTICE.txt').read_bytes())
        self.assertFalse(any(p.name.endswith('.jar') for p in notices.rglob('*')))
        for name in native_evidence.ANIME_FILES:
            self.assertTrue((image / native_evidence.ANIME_NOTICES / name).is_file())
        for name in ('LICENSE.txt', 'LICENSE_NATIVEIMAGE.txt', 'THIRD_PARTY_LICENSE.txt', 'release',
                     'legal/java.base/LICENSE'):
            self.assertTrue((image / native_evidence.GRAALVM_NOTICES / name).is_file(), name)
        native = native_evidence.verify(image, evidence)
        self.assertEqual(self.exe, native.executable)
        self.assertTrue(native.contained(b'fixture font bytes'))
        self.assertFalse(native.contained(b''))

    def test_qa_builds_unknown_graalvm_and_unreported_libraries_are_refused(self):
        with self.assertRaisesRegex(ValueError, 'QA build'):
            self.make(args_extra=['-R:MissingRegistrationReportingMode=Warn'])
        shutil.rmtree(self.root)
        self.root.mkdir()
        with self.assertRaisesRegex(ValueError, 'Build-machine path'):
            self.make(args_extra=['-H:Path=/home/someone/x'])
        home = native_fixture.graalvm_home(self.root / 'other')
        self.assertEqual('25.0.2', native_evidence.release_fields(home)['GRAALVM_VERSION'])
        (home / 'release').write_text('IMPLEMENTOR="Oracle Corporation"\nGRAALVM_VERSION="25.0.2"\n')
        with self.assertRaisesRegex(ValueError, 'Community Edition'):
            native_evidence.release_fields(home)

    def test_reported_jdk_libraries_must_equal_the_emitted_ones(self):
        image, evidence = self.make()
        (image / 'lib/libextra.so').write_bytes(b'unreported')
        with self.assertRaisesRegex(ValueError, 'differ from native-image report'):
            native_evidence.stage(image, self.root / 'work', self.root / 'source', self.root / 'graalvm',
                                  native_fixture.CONFIG)

    def test_any_difference_from_the_retained_evidence_fails(self):
        def mutate(action):
            shutil.rmtree(self.root)
            self.root.mkdir()
            image, evidence = self.make()
            action(image, evidence)
            return image, evidence
        cases = [
            (lambda i, e: (e / 'inputs/library.jar').write_bytes(b'changed'), 'Retained native-image JAR differs'),
            (lambda i, e: (e / 'inputs/extra.jar').write_bytes(b'x'), 'differ from the manifest classpath'),
            (lambda i, e: (i / 'lib/nuvio').write_bytes(self.exe + b'x'), 'differs from the native-image output'),
            (lambda i, e: (i / 'lib/libawt.so').write_bytes(b'x'), 'JDK file'),
            (lambda i, e: (i / 'lib/app').mkdir() or (i / 'lib/app/app.jar').write_bytes(b'x'), 'JAR in a native package'),
            (lambda i, e: (i / 'lib/runtime/lib/server').mkdir(), 'JVM runtime'),
            (lambda i, e: (i / 'lib/native-qa').write_text('qa'), 'QA marker'),
            (lambda i, e: (i / native_evidence.PACKAGED_MANIFEST).write_text('{}'), 'differs from the retained'),
            (lambda i, e: (i / native_evidence.GRAALVM_NOTICES / 'LICENSE.txt').write_text('x'), 'GraalVM notice'),
            (lambda i, e: (i / native_evidence.GRAALVM_NOTICES / 'legal/java.base/LICENSE').unlink(), 'GraalVM notice'),
            (lambda i, e: (i / native_evidence.JAR_NOTICES / 'library/META-INF/NOTICE.txt').write_text('x'), 'JAR notice'),
            (lambda i, e: (i / native_evidence.JAR_NOTICES / 'library/extra.txt').write_text('x'), 'JAR notice'),
        ]
        for action, message in cases:
            with self.subTest(message=message):
                image, evidence = mutate(action)
                with self.assertRaisesRegex(ValueError, message):
                    native_evidence.verify(image, evidence)

    def test_embedded_resources_must_come_from_recorded_input_jar_entries(self):
        image, evidence = self.make()
        native = native_evidence.verify(image, evidence)
        names, modules = native_evidence.embedded_resources(evidence, native)
        self.assertEqual({'composeResources/fixture.ttf', 'META-INF/LICENSE', 'META-INF/NOTICE.txt'}, names)
        self.assertEqual(dict(jdk_module=1, injected_service_registrations=0), modules)
        report = json.loads((evidence / 'embedded-resources.json').read_text())
        for change, message in [(dict(origin='file:///elsewhere/other.jar!/x'), 'unrecorded origin'),
                                (dict(size=1), 'differs from its input JAR'),
                                (dict(origin='INJECTED'), 'Unexpected injected resource')]:
            with self.subTest(message=message):
                altered = json.loads(json.dumps(report))
                altered[0]['entries'][0].update(change)
                (evidence / 'embedded-resources.json').write_text(json.dumps(altered))
                with self.assertRaisesRegex(ValueError, message):
                    native_evidence.embedded_resources(evidence, native)

    def test_smoke_record_must_pass_for_this_executable_and_payload(self):
        image, evidence = self.make()
        with self.assertRaisesRegex(ValueError, 'No passing native smoke record'):
            native_evidence.check_smoke(evidence, image)
        record = native_fixture.smoke(evidence, image)
        identity = native_evidence.image_identity(image)
        self.assertEqual('PASS', native_evidence.check_smoke(evidence, image, identity)['status'])
        for changes, message in [(dict(status='FAIL'), 'not a pass'),
                                 (dict(runs=[dict(record['runs'][0], failed=1)]), 'not a pass'),
                                 (dict(runs=[dict(record['runs'][0], first_frame_ms=None)]), 'not a pass'),
                                 (dict(runs=[]), 'not a pass'),
                                 (dict(executable_sha256='0' * 64), 'different executable'),
                                 (dict(image_identity='0' * 64), 'different package payload')]:
            with self.subTest(changes=changes):
                native_fixture.smoke(evidence, image, **changes)
                with self.assertRaisesRegex(ValueError, message):
                    native_evidence.check_smoke(evidence, image, identity)
        native_fixture.smoke(evidence, image)
        (image / 'share/extra').write_text('changed after the smoke run')
        with self.assertRaisesRegex(ValueError, 'different package payload'):
            native_evidence.check_smoke(evidence, image, native_evidence.image_identity(image))

    def test_evidence_archive_is_deterministic_and_exact(self):
        image, evidence = self.make()
        native_fixture.smoke(evidence, image)
        work = self.root / 'work'
        (work / 'native-smoke/run-1').mkdir(parents=True)
        (work / 'native-smoke/run-1/console.log').write_text('(NativeSelfTest) done passed=14 failed=0\n')
        first, second = self.root / 'a.tar', self.root / 'b.tar'
        native_evidence.archive(work, first, 1234)
        native_evidence.archive(work, second, 1234)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        result = native_evidence.check_archive(work, first)
        self.assertEqual(4 + 2 + 1, result['files'])
        (evidence / 'inputs/library.jar').write_bytes(b'changed after archiving')
        with self.assertRaisesRegex(ValueError, 'differs from the retained evidence'):
            native_evidence.check_archive(work, first)
        (evidence / 'smoke.json').unlink()
        with self.assertRaises(FileNotFoundError):
            native_evidence.archive(work, self.root / 'c.tar', 1234)

    def test_argfile_quoting_matches_the_gradle_task(self):
        self.assertEqual(['-cp', 'a b.jar', 'x"y', 'c\\d'],
                         native_evidence.parse_argfile('-cp\n"a b.jar"\n"x\\"y"\n"c\\\\d"\n'))
        with self.assertRaisesRegex(ValueError, 'Malformed'):
            native_evidence.parse_argfile('"unterminated\n')


class NativeSmokeTest(unittest.TestCase):
    def test_run_lines_are_parsed_exactly(self):
        output = ('  run 1: (NativeSelfTest) done passed=14 failed=0\n'
                  'RUN 1 FIRST_FRAME_MS 1312 SELFTEST passed=14 failed=0 EXIT -15\n'
                  'RUN 2 FIRST_FRAME_MS none SELFTEST none EXIT 1 FAIL\n')
        self.assertEqual([dict(run=1, first_frame_ms=1312, passed=14, failed=0, exit='-15', failed_checks=False),
                          dict(run=2, first_frame_ms=None, passed=None, failed=None, exit='1', failed_checks=True)],
                         native_smoke.parse_runs(output))

    def test_a_hung_smoke_fails_the_gate_instead_of_holding_the_job(self):
        # 150 s first frame/self-test, 5 s linger, 60 s SIGTERM, 10+10 s shutdown per run.
        self.assertGreater(native_smoke.smoke_deadline_seconds(3), 3 * (150 + 5 + 60 + 10 + 10))
        self.assertLess(native_smoke.smoke_deadline_seconds(3), 3600)
        source = Path(native_smoke.__file__).read_text()
        self.assertIn('timeout=smoke_deadline_seconds(runs)', source)
        self.assertIn('except subprocess.TimeoutExpired', source)
        script = (Path(native_smoke.__file__).parents[3] / 'composeApp/scripts/linux-native-smoke.py').read_text()
        self.assertIn('select.select([bus.stdout], [], [], 30)', script)

    def test_a_hung_smoke_reports_its_partial_output_and_deadline(self):
        import subprocess
        hung = subprocess.TimeoutExpired(['smoke'], 960, output=b'RUN 1 FIRST_FRAME_MS 1312 SELFTEST passed=14 failed=0 EXIT -15\n  run 2: waiting\xff\n')
        partial, message = native_smoke.hung_report(hung, Path('/output/native-smoke'))
        self.assertIn('RUN 1 FIRST_FRAME_MS 1312', partial)
        self.assertIn('run 2: waiting', partial)
        self.assertEqual('Native smoke gate FAILED: hung past 960s; no package may be built '
                         '(logs in /output/native-smoke)', message)
        self.assertEqual('', native_smoke.hung_report(subprocess.TimeoutExpired(['smoke'], 960), 'x')[0])

    def test_build_script_gates_the_deb_on_the_smoke_without_network(self):
        script = (Path(native_smoke.__file__).parent / 'native-build.sh').read_text()
        package = script[script.index('if [[ $phase == package ]]'):script.index('exit 0')]
        self.assertLess(package.index('native_smoke.py'), package.index('formats.py'))
        self.assertIn('-Pnuvio.native.qa=false', script)
        self.assertIn('createLinuxNativeDistributable', script)
        self.assertIn('--native-evidence /output/native-image', script)


if __name__ == '__main__':
    unittest.main()
