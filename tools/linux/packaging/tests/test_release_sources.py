"""Release source bundles and binary->source map; fixtures only, no network or packages."""
import hashlib
import io
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
        self.runtime(packages=['libfoo1'])
        self.engine({'libfoo1': '1', 'libgtk-3-0': '1', 'libunbundled0': '9'})
        for name in ('app.deb', 'app.flatpak', 'app.AppImage', 'Nuvio-source-aaaaaaaaaaaa.tar.gz',
                     'Nuvio-AppImage-runtime-source-y.tar.gz', 'Nuvio-native-image-inputs-aaaaaaaaaaaa.tar',
                     'Nuvio-TorrServer-source-aaaaaaaaaaaa.tar.gz', 'Nuvio-InnerTubeX-source-aaaaaaaaaaaa.tar.gz', 'Nuvio-Engine-source-aaaaaaaaaaaa.tar.gz'):
            (self.artifacts / name).write_bytes(name.encode())

    def runtime(self, packages, builder='e' * 64, recipe='f' * 64):
        # The AppImage engine identity deliberately differs from the lock's historical builder_image_id.
        root = self.work / rs.APPIMAGE_ROOT
        (root / 'share/nuvio-webkit/runtime.json').write_text(json.dumps(dict(builder_image_id=builder, recipe_identity=recipe)))
        (root / 'share/licenses/Ubuntu-AppImage/packages.json').write_text(json.dumps(packages))

    def engine(self, versions, builder='e' * 64, recipe='f' * 64, table=None, name='Nuvio-WebKit-source-x.tar.gz'):
        """A webkit.stage.archive_source()-shaped archive: ./engine-build.json + ./engine/webkit-builder-packages.tsv."""
        for old in self.artifacts.glob('Nuvio-WebKit-source-*.tar.gz'):
            old.unlink()
        if table is None:
            table = ''.join(f'{package}\t{version}\n' for package, version in sorted(versions.items())).encode()
        record = dict(builder_image_id=builder, recipe_identity=recipe, corresponding_source={
            'webkit-builder-packages.tsv': dict(sha256=hashlib.sha256(table).hexdigest(), mode=0o644)})
        with tarfile.open(self.artifacts / name, 'w:gz') as tar:
            for member, data in (('./engine-build.json', json.dumps(record).encode()),
                                 ('./engine/webkit-builder-packages.tsv', table)):
                info = tarfile.TarInfo(member); info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        return record

    def test_bundled_ubuntu_versions_must_equal_locked_binary_versions(self):
        # Private GTK/WebKitGTK are covered by the WebKit source archive, not the Ubuntu lock.
        # A different engine builder image ID is accepted when every version matches.
        self.assertEqual(['libfoo1'], rs.check_appimage_lock(self.work, self.lock))
        self.engine({'libfoo1': '2'})
        with self.assertRaisesRegex(ValueError, r'versions differ.*regenerate the lock: libfoo1 2 \(locked 1\)'):
            rs.check_appimage_lock(self.work, self.lock)

    def test_missing_package_in_lock_or_engine_builder_fails_closed(self):
        self.runtime(packages=['libfoo1', 'libunlocked0'])
        self.engine({'libfoo1': '1', 'libunlocked0': '1'})
        with self.assertRaisesRegex(ValueError, 'without locked source: libunlocked0'):
            rs.check_appimage_lock(self.work, self.lock)
        self.runtime(packages=['libfoo1', 'libgtk-3-0'])
        self.engine({'libfoo1': '1'})
        with self.assertRaisesRegex(ValueError, 'absent from the engine builder package list: libgtk-3-0'):
            rs.check_appimage_lock(self.work, self.lock)

    def test_engine_version_evidence_must_belong_to_this_appimage(self):
        for kwargs, error in ((dict(builder='b' * 64), 'does not describe the AppImage engine'),
                              (dict(recipe='0' * 64), 'does not describe the AppImage engine'),
                              (dict(table=b'libfoo1 1\n'), 'Malformed'),
                              (dict(table=b'libfoo1\t1\nlibfoo1\t2\n'), 'Malformed')):
            with self.subTest(kwargs=kwargs):
                self.engine({'libfoo1': '1'}, **kwargs)
                with self.assertRaisesRegex(ValueError, error):
                    rs.check_appimage_lock(self.work, self.lock)
        record = self.engine({'libfoo1': '1'})
        with tarfile.open(self.artifacts / 'Nuvio-WebKit-source-x.tar.gz', 'w:gz') as tar:
            for member, data in (('./engine-build.json', json.dumps(record).encode()),
                                 ('./engine/webkit-builder-packages.tsv', b'libfoo1\t2\n')):
                info = tarfile.TarInfo(member); info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        with self.assertRaisesRegex(ValueError, 'does not match its build record'):
            rs.check_appimage_lock(self.work, self.lock)
        self.engine({'libfoo1': '1'})
        self.engine({'libfoo1': '1'}, name='Nuvio-WebKit-source-z.tar.gz')
        (self.artifacts / 'Nuvio-WebKit-source-x.tar.gz').write_bytes(b'second archive')
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            rs.check_appimage_lock(self.work, self.lock)
        (self.artifacts / 'Nuvio-WebKit-source-x.tar.gz').unlink()
        (self.artifacts / 'Nuvio-WebKit-source-z.tar.gz').unlink()
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            rs.check_appimage_lock(self.work, self.lock)

    def test_pinned_graalvm_sources_are_the_commits_the_pinned_binary_names(self):
        specs = rs.graalvm_source_specs()
        self.assertEqual(3, len(specs))
        retention = json.loads(rs.RETENTION.read_text())
        graalvm = retention['graalvm']
        # The binary the builder pins, and the commits its release file records.
        self.assertEqual(json.loads((rs.PACKAGING / 'config.json').read_text())['tools']['graalvm'], graalvm['binary'])
        commits = {spec['path'].split('/', 1)[1].rsplit('-', 1)[1].removesuffix('.tar.gz') for spec in specs}
        self.assertEqual({graalvm['source']['graal']['commit'], graalvm['source']['labs-openjdk']['commit'],
                          graalvm['source']['mx']['commit']}, commits)
        self.assertIn('compiler:' + graalvm['source']['graal']['commit'], graalvm['release']['SOURCE'])
        self.assertTrue(graalvm['release']['SOURCE'].startswith('.:git:' + graalvm['source']['labs-openjdk']['commit'][:12]))
        for spec in specs:
            self.assertTrue(spec['url'].startswith('https://codeload.github.com/'))
            self.assertTrue(spec['url'].endswith(spec['path'].rsplit('-', 1)[1].removesuffix('.tar.gz')))
            self.assertRegex(spec['sha256'], '^[0-9a-f]{64}$')
        # Temurin no longer ships in a package: its source is no longer a package asset.
        self.assertFalse(hasattr(rs, 'jdk_source_spec'))

    def test_source_map_binds_each_package_to_existing_source_assets(self):
        def media(recipe, cache, output):
            output.write_bytes(b'media source')
        def fetch(spec, output):
            Path(output).write_bytes(spec['url'].encode()); return Path(output)
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
                  'Nuvio-native-image-inputs-aaaaaaaaaaaa.tar', 'Nuvio-TorrServer-source-aaaaaaaaaaaa.tar.gz', 'Nuvio-InnerTubeX-source-aaaaaaaaaaaa.tar.gz', 'Nuvio-Engine-source-aaaaaaaaaaaa.tar.gz',
                  *(Path(spec['path']).name for spec in rs.graalvm_source_specs())}
        self.assertEqual(common, names('app.deb'))
        self.assertFalse(any('jdk-sources' in name for name in names('app.deb')))
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
