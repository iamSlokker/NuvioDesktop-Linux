"""Compile the backport's actual paint body, with a recording Skia boundary."""
import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = pathlib.Path(__file__).resolve().parents[1] / 'webkit'
spec = importlib.util.spec_from_file_location('bitmap_probe', HERE / 'test-bitmap-copy.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

# Release-body fixture. The separate archive probe verifies these exact lines
# against the checksum-pinned 2.50.4 tarball, applying the patch with zero fuzz.
ORIGINAL = '''void ShareableBitmap::paint(GraphicsContext& context, float scaleFactor, const IntPoint& dstPoint, const IntRect& srcRect)
{
    FloatRect scaledSrcRect(srcRect);
    scaledSrcRect.scale(scaleFactor);
    FloatRect scaledDestRect(dstPoint, srcRect.size());
    scaledDestRect.scale(scaleFactor);
    auto image = createPlatformImage(BackingStoreCopy::DontCopyBackingStore);
    context.platformContext()->drawImageRect(image.get(), scaledSrcRect, scaledDestRect, { }, nullptr, { });
}
'''


class WebKitBitmapTest(unittest.TestCase):
    def test_original_fails_backport_passes_and_copy_and_noncopy_regressions_fail(self):
        compiler = shutil.which('c++')
        if not compiler:
            self.skipTest('C++ compiler required for the headless paint-contract fixture')
        lines = (HERE / 'patches/0002-skia-bitmap-copy.patch').read_text().splitlines(keepends=True)
        before = ''.join(line[1:] for line in lines if line.startswith('-') and not line.startswith('---'))
        after = ''.join(line[1:] for line in lines if line.startswith('+') and not line.startswith('+++'))
        self.assertEqual(ORIGINAL.count(before), 1)
        corrected = ORIGINAL.replace(before, after)
        cases = [(ORIGINAL, 1), (corrected, 0),
                 (corrected.replace('paint.setBlendMode(SkBlendMode::kSrc);',
                                    'paint.setBlendMode(SkBlendMode::kSrcOver);'), 1),
                 (corrected.replace('if (context.compositeMode().operation == CompositeOperator::Copy)', ''), 2)]
        with tempfile.TemporaryDirectory() as tmp:
            for index, (source, expected) in enumerate(cases):
                with self.subTest(case=index):
                    self.assertEqual(expected, probe.exercise(source, compiler, pathlib.Path(tmp) / str(index)))

    def test_archive_probe_rejects_unverified_inputs_before_patch_or_compile(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = pathlib.Path(tmp) / 'tampered.tar.xz'
            archive.write_bytes(b'not the pinned WebKit release')
            with patch.object(sys, 'argv', ['test-bitmap-copy.py', str(archive)]), \
                    patch.object(probe.subprocess, 'run') as run, \
                    self.assertRaisesRegex(ValueError, 'Not the pinned WebKit'):
                probe.main()
            run.assert_not_called()
