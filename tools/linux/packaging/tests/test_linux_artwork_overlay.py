"""Linux artwork overlay in the source snapshot; no network, container, display or native build."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artwork_contract as contract
import build
import source_policy

TARGET = 'composeApp/src/commonMain/composeResources/drawable/rating_fixture.png'


def sha(data):
    return hashlib.sha256(data).hexdigest()


class SyntheticOverlayTest(unittest.TestCase):
    """A throwaway Git checkout exercises build.source_tree() + source_policy.apply() as packaging does."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / 'repo'
        self.overlay = self.repo / 'tools/linux/packaging/artwork/overlay'
        self.files = {
            TARGET: b'upstream original bytes',
            'composeApp/Configuration/DesktopVersion.properties': b'VERSION_NAME=1.0\nVERSION_CODE=1\n',
            'tools/linux/packaging/artwork/overlay/' + TARGET: b'original linux replacement',
        }
        for name, data in self.files.items():
            (self.repo / name).parent.mkdir(parents=True, exist_ok=True)
            (self.repo / name).write_bytes(data)
        self.git('init', '-q'); self.commit()

    def git(self, *args):
        subprocess.run(['git', '-C', str(self.repo), '-c', 'user.name=fixture', '-c', 'user.email=fixture@invalid',
                        '-c', 'commit.gpgsign=false', *args], check=True, capture_output=True)

    def commit(self):
        self.git('add', '-A'); self.git('commit', '-q', '-m', 'fixture', '--allow-empty')

    def snapshot(self):
        destination = Path(self.temp.name) / 'work/source'
        with patch.object(build, 'REPO', self.repo):
            info = source_policy.apply(destination, build.source_tree(destination, 'nightly'))
        return destination, dict(json.loads((destination.parent / 'source-files.json').read_text())), info

    def test_overlay_bytes_replace_tracked_target_and_are_recorded(self):
        destination, recorded, info = self.snapshot()
        replacement = self.files['tools/linux/packaging/artwork/overlay/' + TARGET]
        self.assertEqual(replacement, (destination / TARGET).read_bytes())
        self.assertEqual(sha(replacement), recorded[TARGET])
        # The overlay itself stays in the snapshot, but the original bytes appear nowhere.
        self.assertEqual(replacement, (destination / 'tools/linux/packaging/artwork/overlay' / TARGET).read_bytes())
        snapshot = {sha(p.read_bytes()) for p in destination.rglob('*') if p.is_file()}
        self.assertNotIn(sha(self.files[TARGET]), snapshot)
        self.assertEqual(set(recorded), {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()})
        for name, digest in recorded.items():
            self.assertEqual(digest, sha((destination / name).read_bytes()))
        # The release identity binds the substituted inventory, not the checkout bytes.
        self.assertEqual(sha(json.dumps(sorted(recorded.items())).encode()), info['source_sha256'])
        # The checkout original is untouched.
        self.assertEqual(self.files[TARGET], (self.repo / TARGET).read_bytes())

    def test_overlay_for_untracked_or_missing_target_fails(self):
        stray = self.overlay / 'composeApp/src/commonMain/composeResources/drawable/not_tracked.png'
        stray.write_bytes(b'replacement without target'); self.commit()
        with self.assertRaisesRegex(ValueError, 'not a tracked source file'):
            self.snapshot()
        (self.repo / 'composeApp/src/commonMain/composeResources/drawable/not_tracked.png').write_bytes(b'untracked')
        with self.assertRaisesRegex(ValueError, 'not a tracked source file'):
            self.snapshot()

    def test_original_bytes_copied_elsewhere_fail(self):
        (self.repo / 'docs').mkdir()
        (self.repo / 'docs/renamed.bin').write_bytes(self.files[TARGET]); self.commit()
        with self.assertRaisesRegex(ValueError, 'original bytes remain'):
            self.snapshot()


class RepositoryOverlayTest(unittest.TestCase):
    """The actual checkout: overlaid snapshot and a JAR built from its composeResources."""

    def test_actual_snapshot_carries_47_replacements_and_passes_payload_gate(self):
        release = json.loads(contract.release_provenance().read_text())
        rows = release['linux_overlay']['resources']
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / 'work/source'
            source_policy.apply(destination, build.source_tree(destination, 'nightly'))
            recorded = dict(json.loads((destination.parent / 'source-files.json').read_text()))
            originals = {row['original']['sha256'] for row in rows}
            self.assertEqual(46, len(originals))  # foreign and people_choice share original bytes
            for row in rows:
                data = (destination / row['resource']).read_bytes()
                self.assertEqual(row['replacement']['sha256'], sha(data))
                self.assertEqual(row['replacement']['sha256'], recorded[row['resource']])
            for path in destination.rglob('*'):
                if path.is_file():
                    self.assertNotIn(sha(path.read_bytes()), originals, path)
            # Fixture JAR from the snapshot's own resources (not a Gradle/container build).
            image = Path(temporary) / 'image'; (image / 'lib/app').mkdir(parents=True)
            with zipfile.ZipFile(image / 'lib/app/application.jar', 'w') as jar:
                for name in contract.expected_resources():
                    relative = ('composeApp/src/commonMain/composeResources/' + name.removeprefix(contract.PREFIX)
                                if name.startswith(contract.PREFIX) else 'composeApp/src/desktopMain/resources/' + name)
                    jar.writestr(name, (destination / relative).read_bytes())
            report = contract.verify_packaged_artwork(image)
            self.assertEqual(47, report['linux_overlay_replacements'])
            self.assertEqual(0, report['unresolved_exact_path_exceptions'])


if __name__ == '__main__':
    unittest.main()
