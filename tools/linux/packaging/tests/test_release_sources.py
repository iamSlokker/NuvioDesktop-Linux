"""Release source bundles and binary->source map; fixtures only, no network or packages."""
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'compliance'))
import release_sources as rs


class ReleaseSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name) / 'work'
        self.artifacts = self.work / 'artifacts'; self.artifacts.mkdir(parents=True)
        (self.work / 'canonical').mkdir()
        (self.work / 'canonical/release-info.json').write_text(json.dumps(
            dict(git_commit='a' * 40, version='1.0', channel='nightly')))
        root = self.work / rs.APPIMAGE_ROOT
        (root / 'share/nuvio-webkit').mkdir(parents=True)
        (root / 'share/licenses/Ubuntu-AppImage').mkdir(parents=True)
        self.lock = dict(builder_image_id='b' * 64, source_packages=[
            dict(binaries=[dict(package='libgtk-3-0', version='1'), dict(package='libwebkit2gtk-4.1-0', version='1')],
                 inputs=[dict(filename='gtk.dsc', sha256='c' * 64, size=1, url='https://x')]),
            dict(binaries=[dict(package='libfoo1', version='1')],
                 inputs=[dict(filename='foo.dsc', sha256='d' * 64, size=1, url='https://x')])])
        self.runtime(builder='b' * 64, packages=['libfoo1'])
        for name in ('app.deb', 'app.flatpak', 'app.AppImage', 'Nuvio-source-aaaaaaaaaaaa.tar.gz',
                     'Nuvio-WebKit-source-x.tar.gz', 'Nuvio-AppImage-runtime-source-y.tar.gz'):
            (self.artifacts / name).write_bytes(name.encode())

    def runtime(self, builder, packages):
        root = self.work / rs.APPIMAGE_ROOT
        (root / 'share/nuvio-webkit/runtime.json').write_text(json.dumps(dict(builder_image_id=builder)))
        (root / 'share/licenses/Ubuntu-AppImage/packages.json').write_text(json.dumps(packages))

    def test_lock_must_match_the_appimage_engine_builder_and_cover_every_bundled_package(self):
        self.assertEqual(['libfoo1', 'libgtk-3-0', 'libwebkit2gtk-4.1-0'], rs.check_appimage_lock(self.work, self.lock))
        self.runtime(builder='e' * 64, packages=['libfoo1'])
        with self.assertRaisesRegex(ValueError, 'builder differs'):
            rs.check_appimage_lock(self.work, self.lock)
        self.runtime(builder='b' * 64, packages=['libfoo1', 'libunlocked0'])
        with self.assertRaisesRegex(ValueError, 'libunlocked0'):
            rs.check_appimage_lock(self.work, self.lock)

    def test_pinned_jdk_source_is_the_single_temurin_source_archive(self):
        spec = rs.jdk_source_spec()
        self.assertTrue(spec['url'].startswith('https://github.com/adoptium/temurin17-binaries/releases/download/'))
        self.assertRegex(spec['sha256'], '^[0-9a-f]{64}$')
        self.assertTrue(spec['path'].endswith('.tar.gz') and 'jdk-sources' in spec['path'])

    def test_source_map_binds_each_package_to_existing_source_assets(self):
        def media(recipe, cache, output):
            output.write_bytes(b'media source')
        def fetch(spec, output):
            Path(output).write_bytes(b'jdk source'); return Path(output)
        def archive(lock, cache, output):
            output.write_bytes(b'ubuntu source')
        with patch.object(rs, 'collect_media', side_effect=media), patch.object(rs, 'fetch', side_effect=fetch), \
                patch.object(rs, 'APPIMAGE_LOCK') as lock_path, patch.object(rs.ubuntu_sources, 'retain'), \
                patch.object(rs, 'archive_ubuntu', side_effect=archive):
            lock_path.read_text.return_value = json.dumps(self.lock)
            result = rs.build(self.work, self.work / 'media', self.work / 'ubuntu')
        written = json.loads((self.artifacts / 'SOURCE-MAP.json').read_text())
        self.assertEqual(result, written)
        self.assertEqual({'app.deb', 'app.flatpak', 'app.AppImage'}, set(written['packages']))
        names = lambda package: {s['name'] for s in written['packages'][package]['sources']}
        common = {'Nuvio-source-aaaaaaaaaaaa.tar.gz', 'Nuvio-media-source-aaaaaaaaaaaa.tar.gz',
                  'OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz'}
        self.assertEqual(common, names('app.deb'))
        self.assertEqual(common, names('app.flatpak'))
        self.assertEqual(common | {'Nuvio-WebKit-source-x.tar.gz', 'Nuvio-AppImage-runtime-source-y.tar.gz',
                                   'Nuvio-AppImage-Ubuntu-source-aaaaaaaaaaaa.tar'},
                         names('app.AppImage'))
        for record in written['packages'].values():
            for source in record['sources']:
                self.assertEqual(source['sha256'], rs.sha(self.artifacts / source['name']))

    def test_ubuntu_archive_contains_the_lock_and_exact_cached_inputs(self):
        cache = self.work / 'cache'; cache.mkdir()
        for row in self.lock['source_packages']:
            for spec in row['inputs']:
                (cache / spec['sha256']).write_bytes(spec['filename'].encode())
        output = self.work / 'ubuntu.tar'
        rs.archive_ubuntu(self.lock, cache, output)
        with tarfile.open(output) as tar:
            self.assertEqual(['ubuntu-source-lock.json', 'sources/foo.dsc', 'sources/gtk.dsc'], tar.getnames())
        with self.assertRaises(FileExistsError):
            rs.archive_ubuntu(self.lock, cache, output)


if __name__ == '__main__':
    unittest.main()
