"""Exercise the integration workflow's real guards without CI, network or Podman."""
import json
import os
import pathlib
import re
import subprocess
import tempfile
import unittest

from test_webkit_ci import named_step, run_script, seed


INTEGRATION_REF = 'refs/heads/integration/umbra-linux-snapshot-2026-10-13'
FIX_BRANCH = 'fix/linux-cloud-player-2026-10-12'
FIX_COMMIT = 'a5568593562ba18dac4b7d7efe294081801ae74e'
ENGINE_RUN = '38327066209'
ENGINE_SHA = '3709311586e9fc58dcd460e712f03accc8e9d8170787ef8db530c9224a9ef288'
SEED_RUN = '38265810825'
SEED_SHA = '848a8fadf6957bb04d5702ae63639904c0e4f7977f54eb6aea8a14dce29fd311'


class IntegrationEngineTrustTest(unittest.TestCase):
    def test_packaging_job_allows_only_named_branches_and_tags(self):
        workflow = (seed.REPO / '.github/workflows/linux-packages.yml').read_text()
        condition = re.search(r'^    if: (.+)$', workflow, re.MULTILINE).group(1)
        cases = [('refs/heads/linux', True), ('refs/heads/' + FIX_BRANCH, True),
                 (INTEGRATION_REF, True), ('refs/tags/v0.0.1', True),
                 ('refs/heads/main', False), ('refs/heads/fix/other', False),
                 (INTEGRATION_REF + '-other', False), ('refs/pull/2/merge', False),
                 ('refs/heads/sync/nuvio-dev-2026-10-12', False)]
        for ref, expected in cases:
            with self.subTest(ref=ref):
                expression = condition.replace('github.ref', repr(ref)).replace('||', 'or')
                actual = eval(expression, {'__builtins__': {}, 'startsWith': str.startswith})
                self.assertEqual(actual, expected)

    def input_result(self, **changes):
        values = dict(ENGINE_RUN=ENGINE_RUN, ENGINE_SHA=ENGINE_SHA, SEED_RUN=SEED_RUN,
                      SEED_SHA=SEED_SHA, BOOTSTRAP_ONLY='false',
                      GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF=INTEGRATION_REF)
        values.update(changes)
        script = run_script(named_step('linux-packages.yml', 'Validate optional verified engine inputs'))
        return subprocess.run(['bash', '-ec', script], env=dict(os.environ, **values), capture_output=True)

    def test_integration_requires_the_exact_engine_and_seed_tuple(self):
        self.assertEqual(self.input_result().returncode, 0)
        for name, wrong in [('ENGINE_RUN', '38327066210'), ('ENGINE_SHA', '0' * 64),
                            ('SEED_RUN', '38265810826'), ('SEED_SHA', '0' * 64)]:
            for value in [wrong, '']:
                with self.subTest(name=name, value=value):
                    self.assertNotEqual(self.input_result(**{name: value}).returncode, 0)
        self.assertNotEqual(self.input_result(ENGINE_RUN='', ENGINE_SHA='', SEED_RUN='', SEED_SHA='').returncode, 0)
        self.assertNotEqual(self.input_result(BOOTSTRAP_ONLY='true').returncode, 0)
        self.assertNotEqual(self.input_result(GITHUB_EVENT_NAME='push').returncode, 0)

    def test_existing_normal_tag_and_bootstrap_modes_remain_available(self):
        empty = dict(ENGINE_RUN='', ENGINE_SHA='', SEED_RUN='', SEED_SHA='')
        for ref in ['refs/heads/linux', 'refs/heads/' + FIX_BRANCH]:
            for bootstrap in ['false', 'true']:
                with self.subTest(ref=ref, bootstrap=bootstrap):
                    result = self.input_result(**empty, GITHUB_REF=ref, BOOTSTRAP_ONLY=bootstrap)
                    self.assertEqual(result.returncode, 0, result.stderr)
        result = self.input_result(**empty, GITHUB_REF='refs/tags/v0.0.1', GITHUB_EVENT_NAME='push')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_integration_engine_metadata_is_pinned_to_the_reviewed_attempt(self):
        script = run_script(named_step('linux-packages.yml', 'Download trusted engine and its canonical builder seed'))
        guard = script[script.index('jq -e --arg ref'):script.index('attempt=$(jq')]
        good = dict(id=int(ENGINE_RUN), run_attempt=1, path='.github/workflows/linux-webkit.yml',
                    conclusion='success', event='workflow_dispatch', head_branch=FIX_BRANCH,
                    head_sha=FIX_COMMIT)
        cases = [(good, True)]
        for field, wrong in [('id', int(ENGINE_RUN) + 1), ('run_attempt', 2),
                             ('path', '.github/workflows/linux-packages.yml'),
                             ('conclusion', 'failure'), ('event', 'pull_request'),
                             ('head_branch', 'linux'), ('head_sha', 'b' * 40)]:
            cases += [(dict(good, **{field: wrong}), False),
                      ({key: value for key, value in good.items() if key != field}, False)]
        with tempfile.TemporaryDirectory() as tmp:
            for record, expected in cases:
                with self.subTest(record=record):
                    (pathlib.Path(tmp) / 'engine-run.json').write_text(json.dumps(record))
                    result = subprocess.run(['bash', '-ec', guard], capture_output=True,
                        env=dict(os.environ, RUNNER_TEMP=tmp, GITHUB_REF=INTEGRATION_REF,
                                 GITHUB_SHA='c' * 40))
                    self.assertEqual(result.returncode == 0, expected, result.stderr)
            # The cross-commit exception must not apply to another integration
            # branch or a PR merge ref.
            (pathlib.Path(tmp) / 'engine-run.json').write_text(json.dumps(good))
            for ref in [INTEGRATION_REF + '-other', 'refs/pull/2/merge']:
                result = subprocess.run(['bash', '-ec', guard], capture_output=True,
                    env=dict(os.environ, RUNNER_TEMP=tmp, GITHUB_REF=ref, GITHUB_SHA='c' * 40))
                self.assertNotEqual(result.returncode, 0, ref)
            # linux accepts exactly the reviewed run (byte-identical WebKit recipe)
            # and nothing else from the fix branch.
            linux_cases = [(good, True)] + [(dict(good, **{field: wrong}), False) for field, wrong in
                [('id', int(ENGINE_RUN) + 1), ('run_attempt', 2), ('head_sha', 'b' * 40),
                 ('head_branch', 'fix/other'), ('conclusion', 'failure'), ('event', 'pull_request'),
                 ('path', '.github/workflows/linux-packages.yml')]]
            for record, expected in linux_cases:
                with self.subTest(linux_record=record):
                    (pathlib.Path(tmp) / 'engine-run.json').write_text(json.dumps(record))
                    result = subprocess.run(['bash', '-ec', guard], capture_output=True,
                        env=dict(os.environ, RUNNER_TEMP=tmp, GITHUB_REF='refs/heads/linux', GITHUB_SHA='c' * 40))
                    self.assertEqual(result.returncode == 0, expected, result.stderr)

    def test_seed_run_still_requires_successful_canonical_linux_workflow(self):
        script = run_script(named_step('linux-packages.yml', 'Download trusted engine and its canonical builder seed'))
        guard = script[script.index("jq -e '"):script.index('gh run download')]
        good = dict(path='.github/workflows/linux-packages.yml', head_branch='linux',
                    conclusion='success', event='workflow_dispatch')
        cases = [(good, True), (dict(good, event='push'), True)]
        cases += [(dict(good, **{field: wrong}), False) for field, wrong in
                  [('path', '.github/workflows/other.yml'), ('head_branch', FIX_BRANCH),
                   ('conclusion', 'failure'), ('event', 'pull_request')]]
        with tempfile.TemporaryDirectory() as tmp:
            for record, expected in cases:
                with self.subTest(record=record):
                    (pathlib.Path(tmp) / 'seed-run.json').write_text(json.dumps(record))
                    result = subprocess.run(['bash', '-ec', guard], capture_output=True,
                                            env=dict(os.environ, RUNNER_TEMP=tmp))
                    self.assertEqual(result.returncode == 0, expected, result.stderr)


if __name__ == '__main__':
    unittest.main()
