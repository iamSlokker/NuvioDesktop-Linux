"""Inherited legacy-mobile automation must never close this desktop fork's work."""
import pathlib
import re
import unittest


class LegacyWorkflowGuardTest(unittest.TestCase):
    def test_every_destructive_job_excludes_the_linux_repository(self):
        repo = pathlib.Path(__file__).resolve().parents[4]
        text = (repo / '.github/workflows/discontinue-legacy-main.yml').read_text()
        jobs = re.findall(r'^  ([a-z-]+):\n    if: (.+)$', text.split('jobs:\n', 1)[1], re.MULTILINE)
        self.assertEqual({name for name, _ in jobs},
                         {'bulk-close', 'auto-close-issue', 'auto-close-pull-request'})
        self.assertEqual(len(jobs), len(re.findall(r'^  [a-z-]+:', text.split('jobs:\n', 1)[1], re.MULTILINE)))
        events = {'bulk-close': 'workflow_dispatch', 'auto-close-issue': 'issues',
                  'auto-close-pull-request': 'pull_request_target'}
        for name, condition in jobs:
            self.assertTrue(condition.startswith("github.repository != 'iamSlokker/NuvioDesktop-Linux' && "))
            for repository in ['iamSlokker/NuvioDesktop-Linux', 'legacy-owner/legacy-mobile']:
                for event in [*events.values(), 'push']:
                    with self.subTest(job=name, repository=repository, event=event):
                        expression = condition.replace('github.repository', repr(repository))
                        expression = expression.replace('github.event_name', repr(event)).replace('&&', 'and')
                        expected = repository != 'iamSlokker/NuvioDesktop-Linux' and event == events[name]
                        self.assertEqual(eval(expression, {'__builtins__': {}}), expected)


if __name__ == '__main__':
    unittest.main()
