import os
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import flatpak  # noqa: E402
import flatpak_repo  # noqa: E402


class FlatpakObjectSizeTest(unittest.TestCase):
    """The build must refuse what GitHub Pages would refuse at publishing (1.16.0 candidate, 2026-10-19)."""

    def test_limit_matches_the_publisher(self):
        self.assertEqual(flatpak.MAX_REPO_OBJECT_BYTES, flatpak_repo.MAX_FILE_BYTES)

    def test_an_object_at_the_limit_fails_the_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            objects = pathlib.Path(tmp, 'objects', '3b')
            objects.mkdir(parents=True)
            small = objects / 'small.filez'
            small.write_bytes(b'x')
            flatpak.check_repo_object_sizes(tmp)
            big = objects / 'big.filez'
            with open(big, 'wb') as stream:
                stream.truncate(flatpak.MAX_REPO_OBJECT_BYTES)
            with self.assertRaisesRegex(ValueError, r'objects/3b/big\.filez is 104857600 bytes'):
                flatpak.check_repo_object_sizes(tmp)

    def test_symlinks_are_not_followed(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as outside:
            target = pathlib.Path(outside, 'huge')
            with open(target, 'wb') as stream:
                stream.truncate(flatpak.MAX_REPO_OBJECT_BYTES)
            os.symlink(target, pathlib.Path(tmp, 'link'))
            flatpak.check_repo_object_sizes(tmp)


if __name__ == '__main__':
    unittest.main()
