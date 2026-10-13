"""Compiler/CI orchestration fixtures only: no Podman, network, compiler or GUI."""
import hashlib
import io
import json
import os
import pathlib
import re
import runpy
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from webkit import builder_seed as seed, execution, build as engine, export_candidate
from webkit.contract import ENGINE_FILES, PROCESSES


def workflow_steps(name):
    text = (seed.REPO / '.github/workflows' / name).read_text()
    # Extract actual step blocks/run scripts without making PyYAML a test dependency.
    return re.split(r'(?=^      - (?:name|uses):)', text, flags=re.MULTILINE)[1:]


def named_step(name, title):
    return next(step for step in workflow_steps(name) if step.startswith('      - name: ' + title + '\n'))


def step_field(step, name):
    return re.search(r'^        ' + re.escape(name) + r': (.+)$', step, re.MULTILINE).group(1)


def run_script(step):
    return textwrap.dedent(step.split('        run: |\n', 1)[1])


class WorkflowIntegrationTest(unittest.TestCase):
    def test_engine_dispatch_allows_only_linux_and_the_exact_fix_branch(self):
        text = (seed.REPO / '.github/workflows/linux-webkit.yml').read_text()
        condition = re.search(r'^    if: (.+)$', text, re.MULTILINE).group(1)
        for ref, expected in [('refs/heads/linux', True),
                              ('refs/heads/fix/linux-cloud-player-2026-10-12', True),
                              ('refs/heads/fix/other', False), ('refs/pull/1/merge', False),
                              ('refs/heads/sync/nuvio-dev-2026-10-12', False)]:
            expression = condition.replace('github.ref', repr(ref)).replace('||', 'or')
            self.assertEqual(eval(expression, {'__builtins__': {}}), expected, ref)

    def test_engine_download_accepts_fix_branch_only_for_its_exact_packaging_commit(self):
        step = named_step('linux-packages.yml', 'Download trusted engine and its canonical builder seed')
        script = run_script(step)
        # Execute the production jq guard, without downloading or invoking Podman.
        guard = script[script.index('jq -e --arg ref'):script.index('attempt=$(jq')]
        fix = 'fix/linux-cloud-player-2026-10-12'
        good = dict(path='.github/workflows/linux-webkit.yml', head_branch=fix,
                    head_sha='a' * 40, conclusion='success', event='workflow_dispatch')
        cases = [(good, 'refs/heads/' + fix, True),
                 (dict(good, head_sha='b' * 40), 'refs/heads/' + fix, False),
                 (good, 'refs/heads/linux', False),
                 (dict(good, head_branch='linux'), 'refs/heads/linux', True),
                 (dict(good, head_branch='fix/other'), 'refs/heads/' + fix, False),
                 (dict(good, conclusion='failure'), 'refs/heads/' + fix, False),
                 (dict(good, event='pull_request'), 'refs/heads/' + fix, False),
                 (dict(good, path='.github/workflows/other.yml'), 'refs/heads/' + fix, False)]
        with tempfile.TemporaryDirectory() as tmp:
            for record, ref, expected in cases:
                (pathlib.Path(tmp) / 'engine-run.json').write_text(json.dumps(record))
                result = subprocess.run(['bash', '-ec', guard], capture_output=True,
                    env=dict(os.environ, RUNNER_TEMP=tmp, GITHUB_REF=ref, GITHUB_SHA='a' * 40))
                self.assertEqual(result.returncode == 0, expected, (record, ref, result.stderr))

    def test_cross_recipe_restore_is_seed_scoped_and_contains_no_completed_engine(self):
        step = next(block for block in workflow_steps('linux-webkit.yml') if 'id: cache\n' in block)
        paths = step.split('          path: |\n', 1)[1].split('          key:', 1)[0]
        self.assertEqual(paths.split(), ['build/linux-webkit/downloads', 'build/linux-webkit/ccache-snapshot'])
        prefixes = step.split('          restore-keys: |\n', 1)[1].splitlines()
        self.assertEqual(len(prefixes), 2)
        self.assertEqual(prefixes[-1].strip(), 'webkit-inputs-v1-${{ inputs.builder_seed_sha256 }}-')
        self.assertIn('hashFiles(', prefixes[0])
        script = (seed.HERE / 'build-engine.sh').read_text()
        self.assertLess(script.index('ccache --show-stats > /work/ccache-before-stats.txt'),
                        script.index('ccache --zero-stats'))
        self.assertLess(script.index('ccache --zero-stats'), script.index('cmake -S'))
        self.assertIn('ccache --show-stats > /work/ccache-stats.txt', script)

    def test_engine_handoff_requires_complete_manual_inputs(self):
        step = named_step('linux-packages.yml', 'Validate optional verified engine inputs')
        empty = dict(ENGINE_RUN='', ENGINE_SHA='', SEED_RUN='', SEED_SHA='', BOOTSTRAP_ONLY='false',
                     GITHUB_EVENT_NAME='workflow_dispatch')
        good = dict(empty, ENGINE_RUN='123', ENGINE_SHA='a' * 64, SEED_RUN='456', SEED_SHA='b' * 64)
        cases = [(empty, True), (dict(empty, GITHUB_EVENT_NAME='push'), True), (good, True),
                 (dict(good, BOOTSTRAP_ONLY='true'), False), (dict(good, GITHUB_EVENT_NAME='push'), False)]
        cases += [(dict(good, **{name: ''}), False) for name in ['ENGINE_RUN', 'ENGINE_SHA', 'SEED_RUN', 'SEED_SHA']]
        for values, success in cases:
            with self.subTest(values=values):
                result = subprocess.run(['bash', '-c', run_script(step)], env=dict(os.environ, **values), capture_output=True)
                self.assertEqual(result.returncode == 0, success)

    def test_appimage_uses_verified_generation_and_never_recompiles_failed_import(self):
        script = run_script(named_step('linux-packages.yml', 'Build and verify AppImage with private production WebKit'))
        stub = '''
python3() {
  if [[ "$*" == *import_candidate.py* ]]; then
    [[ "$FIXTURE_IMPORT" == success ]] || return 17
    printf '/fixture/verified generation\\n'
  else
    printf '%s\\n' "$@" > "$FIXTURE_ARGS"
  fi
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            args = pathlib.Path(tmp) / 'args'
            for engine_run, imported in [('', 'success'), ('123', 'success'), ('123', 'failure')]:
                with self.subTest(engine_run=engine_run, imported=imported):
                    args.unlink(missing_ok=True)
                    result = subprocess.run(['bash', '-c', stub + script], capture_output=True,
                        env=dict(os.environ, ENGINE_RUN=engine_run, ENGINE_SHA='a' * 64,
                                 FIXTURE_IMPORT=imported, FIXTURE_ARGS=str(args)))
                    if imported == 'failure':
                        self.assertNotEqual(result.returncode, 0)
                        self.assertFalse(args.exists())
                    else:
                        self.assertEqual(result.returncode, 0, result.stderr)
                        expected = ['tools/linux/packaging/build.py', '--format', 'appimage']
                        if engine_run:
                            expected += ['--webkit-generation', '/fixture/verified generation']
                        self.assertEqual(args.read_text().splitlines(), expected)

    def test_bootstrap_is_manual_opt_in_and_normal_release_steps_are_guarded(self):
        text = (seed.REPO / '.github/workflows/linux-packages.yml').read_text()
        self.assertRegex(text, r'bootstrap_only:\n        description: [^\n]+\n        type: boolean\n        default: false')
        self.assertIn("tags: ['v*']", text)
        expression = re.search(r'BOOTSTRAP_ONLY: \$\{\{ (.+) \}\}', text).group(1)
        self.assertEqual(expression, "github.event_name == 'workflow_dispatch' && inputs.bootstrap_only && 'true' || 'false'")
        # Evaluate the actual, explicitly checked expression for dispatch and tags.
        for event, flag, expected in [('workflow_dispatch', True, 'true'),
                                      ('workflow_dispatch', False, 'false'), ('push', True, 'false'), ('push', False, 'false')]:
            python_expression = expression.replace('github.event_name', repr(event)).replace('inputs.bootstrap_only', repr(flag))
            self.assertEqual(eval(python_expression.replace('&&', 'and').replace('||', 'or'), {'__builtins__': {}}), expected)
        for title in ['Independently verify DEB in its pinned builder', 'Smoke-install DEB on Ubuntu 22.04',
                      'Prepare isolated pinned Flatpak runtime', 'Export and verify Flatpak',
                      'Build and verify AppImage with private production WebKit',
                      'Checksum available packages and evidence', 'Require all three valid packages',
                      'Report independent format results']:
            with self.subTest(title=title):
                condition = step_field(named_step('linux-packages.yml', title), 'if')
                self.assertIn("env.BOOTSTRAP_ONLY != 'true'", condition)
        release_upload = next(step for step in workflow_steps('linux-packages.yml') if 'name: nuvio-linux-x86_64-' in step)
        self.assertIn("env.BOOTSTRAP_ONLY != 'true'", step_field(release_upload, 'if'))
        self.assertNotIn('BOOTSTRAP_ONLY', named_step('linux-packages.yml', 'Build private media from pinned source'))
        self.assertNotIn('BOOTSTRAP_ONLY', named_step('linux-packages.yml', 'Build canonical Compose image and DEB'))

    def test_bootstrap_gate_requires_build_export_and_real_upload_output(self):
        step = named_step('linux-packages.yml', 'Require bootstrap seed export and upload')
        self.assertEqual(step_field(step, 'if'), "${{ always() && env.BOOTSTRAP_ONLY == 'true' }}")
        good = dict(MEDIA_RESULT='success', NATIVE_RESULT='success', SEED_RESULT='success',
                    UPLOAD_RESULT='success', SEED_ARTIFACT_ID='1234')
        with tempfile.TemporaryDirectory() as tmp:
            summary = pathlib.Path(tmp) / 'summary'
            for missing in [None, *good]:
                values = dict(good)
                if missing:
                    values[missing] = '' if missing == 'SEED_ARTIFACT_ID' else 'failure'
                summary.write_text('')
                result = subprocess.run(['bash', '-c', run_script(step)],
                                        env=dict(os.environ, **values, GITHUB_STEP_SUMMARY=str(summary)), capture_output=True)
                self.assertEqual(result.returncode == 0, missing is None, missing)
                self.assertIn('BOOTSTRAP ONLY — not a validated release', summary.read_text())
        for step in workflow_steps('linux-packages.yml'):
            if 'id: builder_seed\n' in step or 'id: builder_seed_upload\n' in step:
                self.assertEqual(step_field(step, 'continue-on-error'), "${{ env.BOOTSTRAP_ONLY != 'true' }}")

    def test_runner_setup_and_resource_gate_precede_download_and_compilation(self):
        text = (seed.REPO / '.github/workflows/linux-webkit.yml').read_text()
        stages = ['Install or verify host orchestration tools', 'Headless orchestrator preflight',
                  'Download immutable canonical builder seed', 'Build or resume only the pinned engine']
        positions = [text.index('      - name: ' + stage) for stage in stages]
        self.assertEqual(positions, sorted(positions))
        self.assertIn('setup-runner.sh', named_step('linux-webkit.yml', stages[0]))
        self.assertIn('execution.py preflight', named_step('linux-webkit.yml', stages[1]))

    def test_cleanup_steps_are_bounded_and_failed_snapshot_cannot_upload(self):
        for workflow, title, step_id in [
                ('linux-packages.yml', 'Stop WebKit compiler before caching', 'webkit_quiescent'),
                ('linux-webkit.yml', 'Stop compiler and snapshot completed cache entries', 'quiescent')]:
            step = named_step(workflow, title)
            self.assertEqual(step_field(step, 'timeout-minutes'), '10')
            self.assertIn('always()', step_field(step, 'if'))
            self.assertNotIn('continue-on-error:', step)
            save = next(block for block in workflow_steps(workflow) if 'uses: actions/cache/save@' in block)
            self.assertEqual(step_field(save, 'if'), "${{ always() && steps." + step_id + ".outcome == 'success' }}")


class RunnerSetupTest(unittest.TestCase):
    def run_setup(self, root, environment='github-hosted', missing='', rootless=True, cgroup='v2', actions='true'):
        calls = root / 'calls'
        calls.write_text('')
        # Execute the production script with harmless shell stand-ins. No apt,
        # sudo, Podman, real containers, host configuration or network is used.
        stubs = '\n'.join(name + '() { :; }' for name in
                          ['apt-get', 'curl', 'jq', 'gh', 'tar', 'zstd', 'newuidmap', 'newgidmap', 'slirp4netns', 'fuse-overlayfs'])
        stubs += '\n' + 'python3() { ' + shlex.quote(sys.executable) + ' "$@"; }\n'
        stubs += '''
sudo() { printf '%s\\n' "$*" >> "$FIXTURE_CALLS"; }
timeout() { shift; "$@"; }
uname() { if [[ $1 == -s ]]; then printf 'Linux\\n'; else printf 'x86_64\\n'; fi; }
podman() {
  if [[ "$*" == 'info --format json' ]]; then printf '%s\\n' "$FIXTURE_INFO";
  elif [[ "$*" == --version ]]; then printf 'Podman fixture\\n';
  else return 99; fi
}
command() {
  if [[ $1 == -v && ${2:-} == "$FIXTURE_MISSING" ]]; then return 1; fi
  builtin command "$@"
}
source "$1"
'''
        result = subprocess.run(['bash', '-c', stubs, 'runner-fixture', str(engine.HERE / 'setup-runner.sh')],
                                env=dict(os.environ, GITHUB_ACTIONS=actions, RUNNER_ENVIRONMENT=environment,
                                         FIXTURE_MISSING=missing, FIXTURE_CALLS=str(calls),
                                         FIXTURE_INFO=json.dumps(dict(host=dict(security=dict(rootless=rootless), cgroupVersion=cgroup)))),
                                text=True, capture_output=True)
        return result, calls.read_text()

    def test_hosted_runner_explicitly_installs_required_orchestrators(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, calls = self.run_setup(pathlib.Path(tmp))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(' update', calls)
            self.assertIn('install -y --no-install-recommends', calls)
            for name in ['podman', 'uidmap', 'slirp4netns', 'fuse-overlayfs', 'python3', 'curl', 'jq', 'gh', 'ca-certificates', 'zstd']:
                self.assertIn(name, calls)

    def test_self_hosted_runner_is_checked_without_automatic_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            result, calls = self.run_setup(root, environment='self-hosted')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(calls, '')
            result, calls = self.run_setup(root, environment='self-hosted', missing='podman')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Missing runner tool: podman', result.stderr)
            self.assertEqual(calls, '')

    def test_local_unknown_rootful_and_cgroup_v1_runners_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            for overrides in [dict(actions='false'), dict(environment='unknown'), dict(rootless=False), dict(cgroup='v1')]:
                with self.subTest(overrides=overrides):
                    result, calls = self.run_setup(pathlib.Path(tmp), **overrides)
                    self.assertNotEqual(result.returncode, 0)
                    if 'actions' in overrides or 'environment' in overrides:
                        self.assertEqual(calls, '')


class BuilderSeedTest(unittest.TestCase):
    def fixture(self, root):
        archive = root / 'packaging-builder.oci'
        archive.write_bytes(b'fixture OCI archive')
        record = dict(schema_version=1, builder_image_id='a' * 64, archive_sha256=seed.digest(archive),
                      inputs={'recipe': 'fixture'}, canonical_git_commit='b' * 40, canonical_source_sha256='c' * 64)
        receipt = root / 'builder-seed.json'
        receipt.write_text(json.dumps(record))
        return record, seed.digest(receipt)

    def test_seed_roundtrip_requires_exact_receipt_archive_recipe_and_image(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(seed, 'inputs', return_value={'recipe': 'fixture'}):
            root = pathlib.Path(tmp)
            record, sha = self.fixture(root)
            with patch.object(seed.subprocess, 'run') as run, \
                    patch.object(seed.subprocess, 'check_output', return_value='sha256:' + 'a' * 64):
                self.assertEqual(seed.load_seed(root, sha), 'a' * 64)
                run.assert_called_once_with(['podman', 'load', '--input', str(root / 'packaging-builder.oci')], check=True)
            with patch.object(seed.subprocess, 'run'), \
                    patch.object(seed.subprocess, 'check_output', return_value='d' * 64):
                with self.assertRaisesRegex(ValueError, 'immutable image'):
                    seed.load_seed(root, sha)

    def test_rejects_missing_anchor_tampered_receipt_archive_and_recipe_before_loading(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(seed, 'inputs', return_value={'recipe': 'fixture'}), \
                patch.object(seed.subprocess, 'run') as run:
            root = pathlib.Path(tmp)
            for failure in ['anchor', 'receipt', 'archive', 'recipe', 'mutable_image']:
                record, sha = self.fixture(root)
                if failure == 'anchor':
                    sha = None
                elif failure == 'receipt':
                    (root / 'builder-seed.json').write_text('{}')
                elif failure == 'archive':
                    (root / 'packaging-builder.oci').write_bytes(b'corrupt')
                else:
                    record['inputs' if failure == 'recipe' else 'builder_image_id'] = 'unexpected'
                    (root / 'builder-seed.json').write_text(json.dumps(record))
                    sha = seed.digest(root / 'builder-seed.json')
                with self.subTest(failure=failure), self.assertRaises(ValueError):
                    seed.load_seed(root, sha)
                run.assert_not_called()

    def test_export_checks_canonical_source_inventory_before_podman(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(seed, 'inputs', return_value={'recipe': 'expected'}), \
                patch.object(seed, 'inspect_image') as inspect, patch.object(seed.subprocess, 'run') as run:
            root = pathlib.Path(tmp)
            (root / 'canonical').mkdir()
            sources = [['recipe', 'wrong']]
            (root / 'source-files.json').write_text(json.dumps(sources))
            release = dict(source_sha256=hashlib.sha256(json.dumps(sources).encode()).hexdigest())
            (root / 'canonical/release-info.json').write_text(json.dumps(release))
            with self.assertRaisesRegex(ValueError, 'recipe differs'):
                seed.export_seed(root, root / 'export')
            release['source_sha256'] = 'corrupt'
            (root / 'canonical/release-info.json').write_text(json.dumps(release))
            with self.assertRaisesRegex(ValueError, 'inventory'):
                seed.export_seed(root, root / 'export')
            inspect.assert_not_called()
            run.assert_not_called()

    def test_export_and_import_preserve_canonical_image_and_source_identity(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(seed, 'inputs', return_value={'recipe': 'expected'}), \
                patch.object(seed, 'inspect_image') as inspect:
            root = pathlib.Path(tmp)
            (root / 'canonical').mkdir()
            sources = [['recipe', 'expected']]
            (root / 'source-files.json').write_text(json.dumps(sources, indent=2))
            release = dict(source_sha256=hashlib.sha256(json.dumps(sources).encode()).hexdigest(),
                           builder_image_id='sha256:' + 'a' * 64, git_commit='b' * 40)
            (root / 'canonical/release-info.json').write_text(json.dumps(release))
            def save(command, **kwargs):
                self.assertEqual(command[-1], release['builder_image_id'])
                pathlib.Path(command[command.index('--output') + 1]).write_bytes(b'fixture OCI')
            with patch.object(seed.subprocess, 'run', side_effect=save):
                sha = seed.export_seed(root, root / 'export')
            data = seed.verify_seed(root / 'export', sha)
            self.assertEqual(data['builder_image_id'], release['builder_image_id'])
            self.assertEqual(data['canonical_source_sha256'], release['source_sha256'])
            self.assertEqual(data['canonical_git_commit'], release['git_commit'])
            inspect.assert_called_once_with(release['builder_image_id'])


class ExecutionTest(unittest.TestCase):
    def test_preflight_rejects_each_insufficient_resource(self):
        good = dict(ram_available_gib=20, ram_total_gib=24, cpus=4, disk_free_gib=60)
        with tempfile.TemporaryDirectory() as tmp:
            for key in ['ram_available_gib', 'cpus', 'disk_free_gib']:
                with self.subTest(key=key), patch.object(execution, 'resources', return_value=dict(good, **{key: 0})):
                    with self.assertRaisesRegex(ValueError, key):
                        execution.preflight(pathlib.Path(tmp), engine.MANIFEST)
            with patch.object(execution, 'resources', return_value=good):
                self.assertEqual(execution.preflight(pathlib.Path(tmp), engine.MANIFEST), good)

    def test_live_tee_precedes_process_exit_and_keeps_complete_append_log(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(execution, 'stop_container') as stop:
            root = pathlib.Path(tmp)
            log, ack = root / 'build.log', root / 'ack'
            log.write_bytes(b'prior attempt\n')
            worker = ('import os, pathlib, time; os.write(1, b"[1/2] compiled\\n"); '
                      'p=pathlib.Path(' + repr(str(ack)) + ')\n'
                      'while not p.exists(): time.sleep(.01)\n'
                      'os.write(1, b"[2/2] linked\\n")')
            def heartbeat(*args):
                if b'[1/2]' in log.read_bytes():
                    ack.touch()  # Child cannot exit until the parent has tee'd its output.
            console = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')
            with patch.object(execution, 'heartbeat', side_effect=heartbeat), patch.object(sys, 'stdout', console):
                execution.stream_build([sys.executable, '-c', worker], log, root, 5, interval=.01)
                console.flush()
                self.assertEqual(console.buffer.getvalue(), b'[1/2] compiled\n[2/2] linked\n')
            self.assertTrue(log.read_bytes().startswith(b'prior attempt\n'))
            self.assertIn(b'[2/2] linked', log.read_bytes())
            stop.assert_called_once_with(root)

    def test_failure_and_deadline_both_stop_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for script, seconds, exception in [('raise SystemExit(7)', 5, subprocess.CalledProcessError),
                                               ('import time; time.sleep(30)', .02, TimeoutError)]:
                with self.subTest(script=script), patch.object(execution, 'stop_container') as stop:
                    with self.assertRaises(exception):
                        execution.stream_build([sys.executable, '-c', script], root / 'build.log', root, seconds)
                    stop.assert_called_once_with(root)

    def test_sigterm_stops_container_and_restores_signal_handler(self):
        import signal
        with tempfile.TemporaryDirectory() as tmp, patch.object(execution, 'stop_container') as stop:
            root = pathlib.Path(tmp)
            old = signal.getsignal(signal.SIGTERM)
            # Signal the test process only while stream_build owns the handler.
            script = 'import os, signal; os.kill(os.getppid(), signal.SIGTERM)'
            with self.assertRaises(execution.BuildCancelled):
                execution.stream_build([sys.executable, '-c', script], root / 'build.log', root, 5)
            self.assertEqual(signal.getsignal(signal.SIGTERM), old)
            stop.assert_called_once_with(root)

    def test_cleanup_requires_ownership_and_confirmed_absence(self):
        root = pathlib.Path('/tmp/fixture-engine')
        okay = subprocess.CompletedProcess([], 0)
        absent = subprocess.CompletedProcess([], 1)
        with patch.object(execution.subprocess, 'check_output', return_value=json.dumps(
                {'org.nuvio.webkit.work': execution.container_name(root)})), \
                patch.object(execution.subprocess, 'run', side_effect=[okay, okay, absent]) as run:
            execution.stop_container(root)
            self.assertEqual(run.call_args_list[1].args[0], ['podman', 'rm', '--force', execution.container_name(root)])
            self.assertEqual([call.kwargs['timeout'] for call in run.call_args_list], [30, 60, 30])
        with patch.object(execution.subprocess, 'check_output', return_value='{}'), \
                patch.object(execution.subprocess, 'run', return_value=okay) as run:
            with self.assertRaisesRegex(ValueError, 'ownership label'):
                execution.stop_container(root)
            self.assertEqual(run.call_count, 1)
        with patch.object(execution.subprocess, 'run', return_value=subprocess.CompletedProcess([], 125)):
            with self.assertRaises(subprocess.CalledProcessError):
                execution.stop_container(root)
        with patch.object(execution.subprocess, 'check_output', return_value=json.dumps(
                {'org.nuvio.webkit.work': execution.container_name(root)})), \
                patch.object(execution.subprocess, 'run', return_value=okay):
            with self.assertRaisesRegex(ValueError, 'not confirmed stopped'):
                execution.stop_container(root)

    def test_snapshot_excludes_tmp_config_locks_partial_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(execution, 'stop_container') as stop:
            root = pathlib.Path(tmp)
            cache = root / 'ccache'
            for name in ['a/0/resultR', 'a/0/manifestM', 'a/0/stats', 'tmp/activeR',
                         'ccache.conf', 'a/0/resultR.tmp.worker', 'a/0/lock', 'a/tmp/activeR']:
                path = cache / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            (cache / 'a/0/linkR').symlink_to(cache / 'ccache.conf')
            execution.cache_snapshot(root)
            stop.assert_called_once_with(root)
            snapshot = root / 'ccache-snapshot'
            actual = sorted(str(p.relative_to(snapshot)) for p in snapshot.rglob('*') if p.is_file())
            self.assertEqual(actual, ['a/0/manifestM', 'a/0/resultR', 'a/0/stats'])
            restored = root / 'restored'
            execution.copy_cache(snapshot, restored)
            self.assertEqual((restored / 'a/0/resultR').read_text(), 'a/0/resultR')
            self.assertTrue((cache / 'tmp/activeR').exists())  # Existing data is never discarded.

    def test_cache_snapshot_roundtrip_into_fresh_cache_preserves_completed_entries(self):
        completed = {'a/0/012abcR': b'\x00result\xff\n', 'a/0/012abcM': b'\x00manifest\xfe',
                     'a/0/stats': b'1\n2\n', 'f/9/345defR': bytes(range(256)),
                     'f/9/345defM': b'another manifest'}

        def add_excluded(cache):
            for name in ['tmp/activeR', 'ccache.conf', 'a/0/ccache.conf', 'a/0/012abcR.tmp.worker',
                         'a/0/012abcR.lock', 'a/0/lock', 'a/tmp/activeM']:
                path = cache / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'excluded')
            (cache / 'a/0/linkR').symlink_to('012abcR')
            (cache / 'a/0/danglingM').symlink_to('missing')
            (cache / 'a/f').symlink_to('0', target_is_directory=True)
            (cache / 'b').symlink_to('a', target_is_directory=True)

        def assert_completed(cache):
            paths = list(cache.rglob('*'))
            self.assertFalse(any(p.is_symlink() for p in paths))
            self.assertEqual({str(p.relative_to(cache)): p.read_bytes() for p in paths if p.is_file()}, completed)
            self.assertEqual({str(p.relative_to(cache)) for p in paths if p.is_dir()}, {'a', 'a/0', 'f', 'f/9'})
            for name in completed:
                self.assertEqual((cache / name).stat().st_mode & 0o777, 0o640)

        with tempfile.TemporaryDirectory() as tmp, patch.object(execution, 'stop_container') as stop:
            root = pathlib.Path(tmp)
            work = root / 'first-attempt'
            cache = work / 'ccache'
            for name, content in completed.items():
                path = cache / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                path.chmod(0o640)
            add_excluded(cache)
            execution.cache_snapshot(work)
            stop.assert_called_once_with(work)
            assert_completed(work / 'ccache-snapshot')

            retry = root / 'fresh-attempt'
            shutil.copytree(work / 'ccache-snapshot', retry / 'ccache-snapshot')
            # Restore must filter independently, even if a downloaded snapshot
            # contains debris. Exercise the same CLI used by the workflow.
            add_excluded(retry / 'ccache-snapshot')
            self.assertFalse((retry / 'ccache').exists())
            with patch.object(sys, 'argv', ['execution.py', 'restore', '--work-dir', str(retry)]), \
                    patch.object(execution.subprocess, 'run', side_effect=AssertionError('Unexpected external command')):
                runpy.run_path(str(engine.HERE / 'execution.py'), run_name='__main__')
            assert_completed(retry / 'ccache')
            for name, content in completed.items():
                self.assertEqual((cache / name).read_bytes(), content)
            self.assertEqual((cache / 'tmp/activeR').read_bytes(), b'excluded')
            self.assertTrue((cache / 'a/0/linkR').is_symlink())

    def test_no_snapshot_when_cleanup_fails_or_compiler_lock_is_held(self):
        import fcntl
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            with patch.object(execution, 'stop_container', side_effect=ValueError('not stopped')), \
                    patch.object(execution, 'copy_cache') as copy:
                with self.assertRaisesRegex(ValueError, 'not stopped'):
                    execution.cache_snapshot(root)
                copy.assert_not_called()
            with (root / '.lock').open('w') as lock, patch.object(execution, 'stop_container') as stop:
                fcntl.flock(lock, fcntl.LOCK_EX)
                with self.assertRaises(BlockingIOError):
                    execution.cache_snapshot(root)
                stop.assert_not_called()

    def test_cleanup_timeout_and_failed_copy_never_publish_new_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            old = root / 'ccache-snapshot/old'
            old.parent.mkdir()
            old.write_text('previous successful snapshot')
            with patch.object(execution, 'stop_container', side_effect=subprocess.TimeoutExpired('podman', 60)), \
                    patch.object(execution, 'copy_cache') as copy:
                with self.assertRaises(subprocess.TimeoutExpired):
                    execution.cache_snapshot(root)
                copy.assert_not_called()
            with patch.object(execution, 'stop_container'), \
                    patch.object(execution, 'copy_cache', side_effect=OSError('disk full')):
                with self.assertRaisesRegex(OSError, 'disk full'):
                    execution.cache_snapshot(root)
            self.assertEqual(old.read_text(), 'previous successful snapshot')


class EngineDriverTest(unittest.TestCase):
    def test_resource_failure_prevents_seed_import_and_container_build(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(sys, 'argv', ['build.py', '--builder-seed', tmp + '/seed', '--work-dir', tmp + '/engine']), \
                patch.dict(os.environ, DISPLAY='', WAYLAND_DISPLAY='', NUVIO_RUN_LIVE_DISPLAY_TESTS=''), \
                patch.object(engine, 'preflight', side_effect=ValueError('Insufficient resources')), \
                patch.object(engine, 'load_seed') as load, patch.object(engine.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'Insufficient resources'):
                engine.main()
            load.assert_not_called()
            run.assert_not_called()

    def test_standalone_failure_uses_verified_base_and_never_publishes_completion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            args = ['build.py', '--builder-seed', str(root / 'seed'), '--builder-seed-sha256', 'f' * 64,
                    '--work-dir', str(root / 'engine'), '--result-file', str(root / 'result.json')]
            (root / 'seed').mkdir()
            (root / 'seed/builder-seed.json').write_text('{}')
            (root / 'result.json').write_text('stale')
            with patch.object(sys, 'argv', args), patch.object(engine, 'load_seed', return_value='a' * 64) as load, \
                    patch.dict(os.environ, DISPLAY='', WAYLAND_DISPLAY='', NUVIO_RUN_LIVE_DISPLAY_TESTS=''), \
                    patch.object(engine, 'preflight'), patch('fetch.fetch'), \
                    patch.object(engine.subprocess, 'run') as run, \
                    patch.object(engine.subprocess, 'check_output', return_value='b' * 64), \
                    patch.object(engine, 'stream_build', side_effect=TimeoutError('fixture budget')) as stream:
                with self.assertRaisesRegex(TimeoutError, 'fixture budget'):
                    engine.main()
                load.assert_called_once_with(root / 'seed', 'f' * 64)
                self.assertIn('PACKAGING_BUILDER_IMAGE=' + 'a' * 64, run.call_args.args[0])
                command = stream.call_args.args[0]
                for flag in ['--network=none', '--userns=keep-id', '--cap-drop=all', '--memory=18g',
                             '--memory-swap=18g', '--pids-limit=512', 'CCACHE_TEMPDIR=/tmp/nuvio-ccache']:
                    self.assertIn(flag, command)
                self.assertFalse(any(arg.startswith('--cpus') for arg in command))
            generation = root / 'engine/generations' / engine.recipe_identity('a' * 64)
            self.assertFalse((root / 'result.json').exists())
            self.assertFalse(engine.valid_cache(generation, generation.name))
            self.assertFalse((root / 'canonical').exists())

    def test_export_rejects_incomplete_and_wrong_recipe_candidates(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(export_candidate, 'inspect_image') as inspect:
            root = pathlib.Path(tmp)
            identity = 'a' * 64
            (root / 'engine-result.json').write_text(json.dumps(dict(recipe_identity=identity)))
            with self.assertRaises(FileNotFoundError):
                export_candidate.export(root)
            generation = root / 'generations' / identity
            generation.mkdir(parents=True)
            (generation / 'build-complete.json').write_text(json.dumps(dict(packaging_builder_image_id='b' * 64)))
            with self.assertRaisesRegex(ValueError, 'current recipe'):
                export_candidate.export(root)
            inspect.assert_not_called()

    def test_verified_engine_export_archives_checksums_and_rejects_invalid_candidates(self):
        base, image = 'a' * 64, 'b' * 64
        identity = engine.recipe_identity(base)
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            template = root / 'template'
            generation = template / 'generations' / identity
            payload = {'install/opt/nuvio-webkit/' + name: b'\x7fELFfixture: ' + name.encode()
                       for name in ENGINE_FILES + PROCESSES}
            payload['corresponding-source/source.tar.xz'] = b'pinned source fixture'
            payload['objects/CMakeCache.txt'] = textwrap.dedent('''\
                DEVELOPER_MODE:BOOL=OFF
                ENABLE_BUBBLEWRAP_SANDBOX:BOOL=ON
                CMAKE_BUILD_TYPE:STRING=Release
                USE_GTK4:BOOL=OFF
                USE_SOUP2:BOOL=OFF
                CMAKE_C_FLAGS_RELEASE:STRING=-fstack-protector-strong -D_FORTIFY_SOURCE=2
                CMAKE_CXX_FLAGS_RELEASE:STRING=-fstack-protector-strong -D_FORTIFY_SOURCE=2
                CMAKE_SHARED_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now
                CMAKE_MODULE_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now
                CMAKE_EXE_LINKER_FLAGS:STRING=-Wl,-z,relro,-z,now
                ''').encode()
            for name, content in payload.items():
                path = generation / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                path.chmod(0o755 if name.startswith('install/') else 0o644)
            record = dict(recipe_identity=identity, packaging_builder_image_id=base, builder_image_id=image,
                          source_sha256=engine.MANIFEST['source']['sha256'], acceptance='BUILT_ONLY',
                          files=engine.inventory(generation / 'install/opt/nuvio-webkit'),
                          build_policy=engine.build_policy(generation),
                          corresponding_source=engine.inventory(generation / 'corresponding-source'))
            payload['build-complete.json'] = json.dumps(record).encode()
            (generation / 'build-complete.json').write_bytes(payload['build-complete.json'])
            (template / 'engine-result.json').write_text(json.dumps(dict(recipe_identity=identity)))
            (generation / 'objects/unfinished.o').write_bytes(b'not exportable')
            (generation / 'build.log').write_text('not part of the engine archive')
            work = root / 'success'
            shutil.copytree(template, work)
            output = work / 'export'
            # Podman's archive bytes are opaque here; check the exact immutable
            # image/OCI command and preservation of its output, not a real image.
            oci = b'fixture OCI archive from the verified image'

            def save(command, **kwargs):
                self.assertEqual(command, ['podman', 'save', '--format', 'oci-archive', '--output',
                                           str(output / 'engine-builder.oci'), image])
                self.assertEqual(kwargs, {'check': True})
                (output / 'engine-builder.oci').write_bytes(oci)

            # Keep recipe, inventory, build-policy and archive validation real.
            with patch.object(export_candidate.subprocess, 'check_output', return_value='sha256:' + image) as inspect, \
                    patch.object(export_candidate.subprocess, 'run', side_effect=save) as run:
                export_candidate.export(work)
                inspect.assert_called_once_with(['podman', 'image', 'inspect', '--format', '{{.Id}}', image], text=True)
                run.assert_called_once()
            self.assertEqual({p.name for p in output.iterdir()}, {'engine.tar.gz', 'engine-builder.oci', 'SHA256SUMS'})
            with tarfile.open(output / 'engine.tar.gz', 'r:gz') as archive:
                members = [member for member in archive.getmembers() if not member.isdir()]
                self.assertEqual({member.name for member in members}, {identity + '/' + name for name in payload})
                self.assertEqual(len(members), len(payload))
                for member in members:
                    relative = member.name.removeprefix(identity + '/')
                    self.assertTrue(member.isfile())
                    self.assertEqual(archive.extractfile(member).read(), payload[relative])
                    self.assertEqual(member.mode, (generation / relative).stat().st_mode & 0o7777)
            self.assertEqual((output / 'engine-builder.oci').read_bytes(), oci)
            self.assertEqual((output / 'SHA256SUMS').read_text(), ''.join(
                hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name + '\n'
                for name in ['engine.tar.gz', 'engine-builder.oci']))

            for failure, message in [('incomplete', 'build-complete.json'), ('recipe', 'current recipe'),
                                     ('provenance', 'provenance mismatch'), ('payload', 'inventory mismatch'),
                                     ('source', 'corresponding-source inventory'), ('policy', 'production/sandbox'),
                                     ('image', 'immutable image ID')]:
                with self.subTest(failure=failure):
                    candidate = root / failure
                    shutil.copytree(template, candidate)
                    changed = candidate / 'generations' / identity
                    if failure == 'incomplete':
                        (changed / 'build-complete.json').unlink()
                    elif failure in ['recipe', 'provenance']:
                        key = 'packaging_builder_image_id' if failure == 'recipe' else 'source_sha256'
                        (changed / 'build-complete.json').write_text(json.dumps(dict(record, **{key: 'c' * 64})))
                    elif failure == 'payload':
                        (changed / 'install/opt/nuvio-webkit' / ENGINE_FILES[0]).write_bytes(b'tampered')
                    elif failure == 'source':
                        (changed / 'corresponding-source/source.tar.xz').write_bytes(b'tampered')
                    elif failure == 'policy':
                        path = changed / 'objects/CMakeCache.txt'
                        path.write_text(path.read_text().replace('ENABLE_BUBBLEWRAP_SANDBOX:BOOL=ON',
                                                                'ENABLE_BUBBLEWRAP_SANDBOX:BOOL=OFF'))
                    with patch.object(export_candidate.subprocess, 'check_output', return_value='c' * 64) as inspect, \
                            patch.object(export_candidate.subprocess, 'run') as run:
                        with self.assertRaisesRegex(FileNotFoundError if failure == 'incomplete' else ValueError, message):
                            export_candidate.export(candidate)
                        run.assert_not_called()
                        if failure == 'image':
                            inspect.assert_called_once()
                        else:
                            inspect.assert_not_called()
                    self.assertFalse((candidate / 'export').exists())

    def test_workflows_keep_independent_gate_and_save_only_after_quiescence(self):
        repo = seed.REPO
        for name in ['linux-packages.yml', 'linux-webkit.yml']:
            text = (repo / '.github/workflows' / name).read_text()
            self.assertIn('webkit/execution.py snapshot', text)
            self.assertIn("quiescent.outcome == 'success'", text)
            self.assertNotIn('build/linux-webkit/ccache\n', text)
            self.assertIn('build/linux-webkit/ccache-snapshot', text)
        packages = (repo / '.github/workflows/linux-packages.yml').read_text()
        self.assertIn('verify.py artifacts build/linux-packaging --formats all', packages)
        self.assertIn("steps.appimage.outcome }}' == success", packages)
        workflow = (repo / '.github/workflows/linux-webkit.yml').read_text()
        self.assertNotIn('build.sh --jobs', workflow)
        self.assertNotIn('--format deb', workflow)
        self.assertNotIn('sudo', '\n'.join(line for line in workflow.splitlines() if not line.lstrip().startswith('#')))
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('  push:', workflow)


if __name__ == '__main__':
    unittest.main()
