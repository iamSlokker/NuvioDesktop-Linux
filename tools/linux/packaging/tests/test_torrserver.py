"""TorrServer build recipe (torrserver/build.py): pins, isolation, notices and the source archive.

Fixtures only: no download, no Go toolchain and no TorrServer process.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGING))
from torrserver import build as ts  # noqa: E402
import stage  # noqa: E402

REPO = PACKAGING.parents[2]
DESKTOP = REPO / 'composeApp/src/desktopMain/kotlin/com/nuvio/app'


def tarball(path, members):
    """members: {name: bytes | None (directory) | ('link', target)}."""
    with tarfile.open(path, 'w:gz') as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            if data is None:
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            elif isinstance(data, tuple):
                info.type, info.linkname = tarfile.SYMTYPE, data[1]
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
    return path


class TorrServerRecipeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_inputs_are_immutable_and_name_one_version(self):
        manifest = ts.MANIFEST
        for spec in (manifest['source'], manifest['go']):
            self.assertTrue(spec['url'].startswith('https://'), spec['url'])
            self.assertRegex(spec['sha256'], '^[0-9a-f]{64}$')
        version = manifest['version']
        self.assertTrue(manifest['source']['url'].endswith('/refs/tags/' + version))
        self.assertEqual('TorrServer-' + version, manifest['source']['top'])
        self.assertIn('-X server/version.Version=' + version, manifest['build']['ldflags'])
        # A reproducible binary: no build ID, no VCS stamp, no build paths.
        self.assertIn('-buildid=', manifest['build']['ldflags'])
        self.assertTrue(manifest['go']['url'].endswith('/' + manifest['go']['version'] + '.linux-amd64.tar.gz'))

    def test_the_binary_is_built_from_the_vendor_tree_only(self):
        command = ts.build_command(Path('/out/TorrServer'))
        for flag in ('-mod=vendor', '-trimpath', '-buildvcs=false', '-tags=nosqlite'):
            self.assertIn(flag, command)
        with patch.dict(os.environ, {'GOFLAGS': '-mod=mod', 'GOPROXY': 'https://evil.example', 'GOTOOLCHAIN': 'auto',
                                     'GOINSECURE': '*', 'CGO_ENABLED': '1'}):
            environment = ts.go_environment(self.root, self.root / 'go')
        self.assertEqual('local', environment['GOTOOLCHAIN'])
        self.assertEqual('0', environment['CGO_ENABLED'])
        self.assertEqual('sum.golang.org', environment['GOSUMDB'])
        self.assertEqual('https://proxy.golang.org,direct', environment['GOPROXY'])
        self.assertEqual('', environment['GOINSECURE'])
        self.assertNotIn('-mod=mod', environment['GOFLAGS'])

    def test_extraction_refuses_paths_and_links_outside_the_top_directory(self):
        good = tarball(self.root / 'good.tgz', {'top/': None, 'top/a.txt': b'a', 'top/link': ('link', 'a.txt')})
        self.assertEqual(b'a', (ts.extract(good, self.root / 'out', 'top') / 'a.txt').read_bytes())
        for name, members in [('other-top', {'elsewhere/a': b'a'}),
                              ('dotdot', {'top/../a': b'a'}),
                              ('absolute-link', {'top/l': ('link', '/etc/passwd')}),
                              ('escaping-link', {'top/l': ('link', '../../x')})]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                ts.extract(tarball(self.root / (name + '.tgz'), members), self.root / ('x-' + name), 'top')

    def vendor_tree(self):
        source = self.root / 'src'
        source.mkdir()
        (source / 'LICENSE').write_text('GPL-3.0 fixture\n')
        go_root = self.root / 'go'
        go_root.mkdir()
        (go_root / 'LICENSE').write_text('Go BSD fixture\n')
        (go_root / 'PATENTS').write_text('Go patents fixture\n')
        vendor = self.root / 'vendor'
        for path, text in {'github.com/anacrolix/torrent/LICENSE': 'MPL fixture',
                           'github.com/anacrolix/torrent/bencode/LICENSE': 'MPL fixture',
                           'github.com/anacrolix/torrent/client.go': 'package torrent',
                           'golang.org/x/sys/unix/PATENTS': 'patents',
                           'golang.org/x/sys/unix/LICENSE': 'BSD',
                           'example.com/unlicensed/code.go': 'package unlicensed'}.items():
            (vendor / path).parent.mkdir(parents=True, exist_ok=True)
            (vendor / path).write_text(text)
        (vendor / 'modules.txt').write_text(
            '# github.com/anacrolix/torrent v1.59.1 => github.com/tsynik/torrent v1.2.34\n## explicit; go 1.24\n'
            'github.com/anacrolix/torrent\n# golang.org/x/sys v0.30.0\n## explicit\ngolang.org/x/sys/unix\n'
            '# example.com/unlicensed v1.0.0\nexample.com/unlicensed\n')
        return source, vendor, go_root

    def test_notices_cover_torrserver_go_and_every_vendored_notice(self):
        source, vendor, go_root = self.vendor_tree()
        licenses = self.root / 'licenses'
        missing = ts.collect_notices(source, vendor, go_root, licenses)
        self.assertEqual(['example.com/unlicensed'], missing)
        self.assertEqual('GPL-3.0 fixture\n', (licenses / 'LICENSE').read_text())
        self.assertTrue((licenses / 'go/LICENSE').is_file() and (licenses / 'go/PATENTS').is_file())
        self.assertTrue((licenses / 'vendor/github.com/anacrolix/torrent/bencode/LICENSE').is_file())
        self.assertTrue((licenses / 'vendor/golang.org/x/sys/unix/PATENTS').is_file())
        self.assertFalse((licenses / 'vendor/github.com/anacrolix/torrent/client.go').exists())
        self.assertEqual((vendor / 'modules.txt').read_text(), (licenses / 'modules.txt').read_text())
        self.assertIn('-source-torrserver.tar.gz', (licenses / 'NOTICE.txt').read_text())

    def test_source_archive_is_deterministic_and_carries_the_original_tarball(self):
        _, vendor, _ = self.vendor_tree()
        upstream = self.root / (ts.MANIFEST['source']['top'] + '.tar.gz')
        upstream.write_bytes(b'upstream tag archive bytes')
        first, second = self.root / 'one.tar.gz', self.root / 'two.tar.gz'
        ts.archive_source(first, upstream, vendor, 1700000000)
        os.utime(vendor / 'modules.txt', (1, 1))
        ts.archive_source(second, upstream, vendor, 1700000000)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with tarfile.open(first) as tar:
            names = tar.getnames()
            self.assertEqual(b'upstream tag archive bytes', tar.extractfile('upstream/' + upstream.name).read())
            self.assertIn('server-vendor/modules.txt', names)
            self.assertIn('server-vendor/golang.org/x/sys/unix/LICENSE', names)
            self.assertEqual(ts.MANIFEST, json.loads(tar.extractfile('manifest.json').read()))
            text = tar.extractfile('BUILD.txt').read().decode()
            self.assertIn(ts.MANIFEST['go']['sha256'], text)
            self.assertIn("-ldflags='" + ts.MANIFEST['build']['ldflags'] + "'", text)
            self.assertTrue(all(m.uid == 0 and m.gid == 0 and m.mtime == 1700000000 for m in tar.getmembers()))

    def test_staged_path_is_where_the_p2p_backend_looks(self):
        # PackagedResources: <dir of jpackage.app-path (lib/nuvio)>/resources/<classpath path>.
        resources = (DESKTOP / 'core/build/PackagedResources.kt').read_text()
        self.assertIn('File(it).parentFile?.resolve("resources")', resources)
        backend = (DESKTOP / 'features/p2p/TorrServerP2pBackend.kt').read_text()
        self.assertIn('val resource = "torrserver/${platform.resourceDir}/${platform.binaryName}"', backend)
        self.assertIn('resourceDir = "${os.resourceName}-$archName"', backend)
        self.assertEqual('lib/resources/torrserver/linux-amd64/TorrServer', stage.TORRSERVER)

    def test_native_build_compiles_torrserver_with_network_before_gradle(self):
        script = (PACKAGING / 'native-build.sh').read_text()
        build_phase = script[script.index('[[ $phase == build ]]'):]
        self.assertLess(build_phase.index('torrserver/build.py /output'), build_phase.index('./gradlew'))
        self.assertNotIn('torrserver', script[:script.index('[[ $phase == build ]]')])

    def test_recipe_files_are_not_builder_seed_inputs(self):
        from webkit import builder_seed
        with patch.object(builder_seed, 'digest', side_effect=lambda path: hashlib.sha256(b'').hexdigest()):
            names = builder_seed.inputs()
        self.assertFalse([name for name in names if 'torrserver' in name])


if __name__ == '__main__':
    unittest.main()
