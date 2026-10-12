"""Verified CI engine handoff; no network, Podman, compilers or display."""
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import build as packaging
import canonical_from_seed as canonical
from webkit import build as engine, export_candidate, import_candidate as importer
from webkit.contract import ENGINE_FILES, PROCESSES


class EngineHandoffTest(unittest.TestCase):
    def test_assembly_helpers_import_without_repository_but_seed_validation_fails_closed(self):
        # Match /packaging/webkit: importing inventory/build-policy helpers must
        # not try to walk four parents to a checkout that isn't mounted there.
        with tempfile.TemporaryDirectory(dir='/tmp') as tmp:
            root = pathlib.Path(tmp)
            shutil.copytree(packaging.HERE, root, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__'))
            script = '''
from pathlib import Path
from formats import build_appimage
from verify import artifacts
from webkit.stage import archive_source
from webkit.build import inventory
from webkit.builder_seed import inputs, REPO
assert REPO is None
fixture = Path('fixture')
fixture.mkdir()
(fixture / 'payload').write_bytes(b'fixture')
(fixture / 'payload').chmod(0o644)
assert inventory(fixture)['payload']['mode'] == 0o644
try:
    inputs()
except ValueError as error:
    assert 'repository' in str(error)
else:
    raise AssertionError('Seed validation accepted a missing repository')
'''
            result = subprocess.run([sys.executable, '-B', '-c', script], cwd=root, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def candidate(self, root):
        base, image = 'a' * 64, 'b' * 64
        identity = engine.recipe_identity(base)
        generation = root / 'built/generations' / identity
        prefix = generation / 'install/opt/nuvio-webkit'
        for name in ENGINE_FILES + PROCESSES:
            path = prefix / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'\x7fELFfixture ' + name.encode())
            path.chmod(0o755)
        (prefix / 'lib/native/libwebkit2gtk-4.1.so').symlink_to('libwebkit2gtk-4.1.so.0')
        (generation / 'objects').mkdir()
        policy = dict(DEVELOPER_MODE='OFF', ENABLE_BUBBLEWRAP_SANDBOX='ON', CMAKE_BUILD_TYPE='Release',
                      USE_GTK4='OFF', USE_SOUP2='OFF', CMAKE_C_FLAGS_RELEASE='-fstack-protector-strong -D_FORTIFY_SOURCE=2',
                      CMAKE_CXX_FLAGS_RELEASE='-fstack-protector-strong -D_FORTIFY_SOURCE=2',
                      CMAKE_SHARED_LINKER_FLAGS='-Wl,-z,relro,-z,now', CMAKE_MODULE_LINKER_FLAGS='-Wl,-z,relro,-z,now',
                      CMAKE_EXE_LINKER_FLAGS='-Wl,-z,relro,-z,now')
        (generation / 'objects/CMakeCache.txt').write_text(''.join(k + ':STRING=' + v + '\n' for k, v in policy.items()))
        (generation / 'corresponding-source').mkdir()
        (generation / 'corresponding-source/source.tar.xz').write_bytes(b'source fixture')
        record = dict(recipe_identity=identity, packaging_builder_image_id=base, builder_image_id=image,
                      source_sha256=engine.MANIFEST['source']['sha256'], files=engine.inventory(prefix),
                      build_policy=engine.build_policy(generation), acceptance='BUILT_ONLY',
                      corresponding_source=engine.inventory(generation / 'corresponding-source'))
        (generation / 'build-complete.json').write_text(json.dumps(record))
        (root / 'built/engine-result.json').write_text(json.dumps(dict(recipe_identity=identity)))
        def save(command, **kwargs):
            pathlib.Path(command[command.index('--output') + 1]).write_bytes(b'OCI fixture')
        with patch.object(export_candidate, 'inspect_image'), patch.object(export_candidate.subprocess, 'run', side_effect=save):
            export_candidate.export(root / 'built')
        source = root / 'built/export'
        sha = hashlib.sha256((source / 'SHA256SUMS').read_bytes()).hexdigest()
        return source, sha, base, image, identity

    def test_export_import_roundtrip_preserves_policy_sources_modes_links_and_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source, sha, base, image, identity = self.candidate(root)
            with patch.object(importer.subprocess, 'run') as run, \
                    patch.object(importer.subprocess, 'check_output', return_value='sha256:' + image) as inspect:
                actual = importer.import_candidate(source, sha, root / 'fresh', base)
                run.assert_called_once_with(['podman', 'load', '--input', str(source / 'engine-builder.oci')],
                                            check=True, stdout=sys.stderr)
                inspect.assert_called_once_with(['podman', 'image', 'inspect', '--format', '{{.Id}}', image], text=True)
            self.assertEqual(actual, root / 'fresh/generations' / identity)
            self.assertTrue(engine.valid_cache(actual, identity))
            self.assertEqual(engine.inventory(actual), engine.inventory(root / 'built/generations' / identity))
            self.assertEqual(json.loads((actual / 'build-complete.json').read_text())['acceptance'], 'BUILT_ONLY')

    def test_checksum_damage_wrong_base_or_loaded_image_never_publishes_generation(self):
        for failure in ['anchor', 'archive', 'oci', 'base', 'image']:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                source, sha, base, image, identity = self.candidate(root)
                if failure == 'anchor':
                    sha = 'f' * 64
                elif failure in ['archive', 'oci']:
                    (source / ('engine.tar.gz' if failure == 'archive' else 'engine-builder.oci')).write_bytes(b'corrupt')
                elif failure == 'base':
                    base = 'c' * 64
                with patch.object(importer.subprocess, 'run') as run, \
                        patch.object(importer.subprocess, 'check_output', return_value='c' * 64):
                    with self.assertRaises(ValueError):
                        importer.import_candidate(source, sha, root / 'fresh', base)
                    if failure != 'image':
                        run.assert_not_called()
                self.assertFalse((root / 'fresh/generations' / identity).exists())
                self.assertEqual(list((root / 'fresh/generations').glob('.import-*')), [])

    def test_resealed_invalid_engine_still_fails_production_validation(self):
        for failure in ['incomplete', 'sandbox', 'inventory', 'builder']:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                source, sha, base, image, identity = self.candidate(root)
                generation = root / 'built/generations' / identity
                record_path = generation / 'build-complete.json'
                if failure == 'incomplete':
                    record_path.unlink()
                elif failure == 'sandbox':
                    path = generation / 'objects/CMakeCache.txt'
                    path.write_text(path.read_text().replace('SANDBOX:STRING=ON', 'SANDBOX:STRING=OFF'))
                elif failure == 'inventory':
                    (generation / 'install/opt/nuvio-webkit' / ENGINE_FILES[0]).write_bytes(b'bad')
                else:
                    record = json.loads(record_path.read_text())
                    record['packaging_builder_image_id'] = 'c' * 64
                    record_path.write_text(json.dumps(record))
                with tarfile.open(source / 'engine.tar.gz', 'w:gz') as tar:
                    for name in ['build-complete.json', 'install', 'corresponding-source', 'objects/CMakeCache.txt']:
                        if (generation / name).exists():
                            tar.add(generation / name, arcname=identity + '/' + name)
                (source / 'SHA256SUMS').write_text(''.join(hashlib.sha256((source / name).read_bytes()).hexdigest() +
                    '  ' + name + '\n' for name in ['engine.tar.gz', 'engine-builder.oci']))
                sha = hashlib.sha256((source / 'SHA256SUMS').read_bytes()).hexdigest()
                with patch.object(importer.subprocess, 'run') as run, self.assertRaises(ValueError):
                    importer.import_candidate(source, sha, root / 'fresh', base)
                run.assert_not_called()

    def test_archive_rejects_traversal_links_special_files_and_duplicate_names(self):
        identity = 'a' * 64
        for failure in ['absolute', 'parent', 'symlink', 'through_link', 'hardlink', 'fifo', 'duplicate', 'setuid']:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                path = root / 'engine.tar.gz'
                member = tarfile.TarInfo(identity + '/install/file')
                if failure == 'absolute':
                    member.name = str(root / 'escaped')
                elif failure == 'parent':
                    member.name = identity + '/../escaped'
                elif failure in ['symlink', 'through_link']:
                    member.type = tarfile.SYMTYPE
                    member.linkname = '../../escaped' if failure == 'symlink' else 'target'
                elif failure == 'hardlink':
                    member.type, member.linkname = tarfile.LNKTYPE, identity + '/install/target'
                elif failure == 'fifo':
                    member.type = tarfile.FIFOTYPE
                elif failure == 'setuid':
                    member.mode = 0o4755
                with tarfile.open(path, 'w:gz') as tar:
                    tar.addfile(member, io.BytesIO())
                    if failure == 'duplicate':
                        tar.addfile(member, io.BytesIO())
                    elif failure == 'through_link':
                        tar.addfile(tarfile.TarInfo(member.name + '/child'), io.BytesIO())
                with self.assertRaises(ValueError):
                    importer.extract_engine(path, root / 'output', identity)
                self.assertFalse((root / 'escaped').exists())


class CanonicalSeedTest(unittest.TestCase):
    def test_seeded_build_preserves_existing_native_recipe_and_audits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            image = 'sha256:' + 'a' * 64
            runtime = root / 'runtime'
            (runtime / 'share/nuvio-media-runtime').mkdir(parents=True)
            (runtime / 'share/nuvio-media-runtime/build-info.json').write_text(json.dumps(dict(builder_image_id='media-fixture')))
            args = SimpleNamespace(runtime=runtime, jobs=2, channel='nightly', gradle_cache=None, offline=False,
                                   builder_seed=root / 'seed', builder_seed_sha256='b' * 64)
            commands = []
            def capture(*command, **kwargs):
                commands.append(command)
                if 'stdout' in kwargs:
                    kwargs['stdout'].close()
            def snapshot(destination, channel):
                destination.mkdir()
                return dict(git_commit='c' * 40, source_date_epoch=1234, channel=channel)
            def fetch(spec, destination):
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(b'input fixture')
            old, new = root / 'old', root / 'new'
            old.mkdir()
            new.mkdir()
            with patch.object(packaging, 'run', side_effect=capture), patch.object(packaging, 'fetch', side_effect=fetch), \
                    patch.object(packaging, 'source_tree', side_effect=snapshot), \
                    patch.object(packaging.subprocess, 'check_output', return_value=image):
                packaging.native(args, old)
            original = next(command for command in commands if command[:2] == ('podman', 'run'))
            commands.clear()
            with patch.object(canonical, 'run', side_effect=capture), patch.object(canonical, 'fetch', side_effect=fetch), \
                    patch.object(canonical, 'source_tree', side_effect=snapshot), \
                    patch.object(canonical, 'load_seed', return_value=image) as load:
                canonical.canonical(args, new)
                load.assert_called_once_with(args.builder_seed.resolve(), args.builder_seed_sha256)
            actual = commands[-1]
            self.assertEqual(actual[-3:], (image, 'bash', '/source/tools/linux/packaging/native-build.sh'))
            self.assertFalse(any(command[:2] == ('podman', 'build') for command in commands))
            self.assertIn('--validated', commands[0])
            self.assertIn('audit-runtime.py', str(commands[1][1]))
            # All runtime flags, source stamps and mounts match the canonical
            # path. Only the builder selection differs (immutable verified ID).
            normalized = tuple(str(arg).replace(str(new), str(old)) for arg in actual)
            expected = tuple(str(arg) for arg in original[:-3]) + (image, *original[-2:])
            self.assertEqual(normalized, expected)
            self.assertEqual(json.loads((new / 'source-info.json').read_text())['builder_image_id'], image)

    def test_invalid_seed_prevents_fetch_audit_and_application_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            args = SimpleNamespace(builder_seed=root / 'seed', builder_seed_sha256='c' * 64)
            with patch.object(canonical, 'load_seed', side_effect=ValueError('mismatch')), \
                    patch.object(canonical, 'run') as run, patch.object(canonical, 'fetch') as fetch:
                with self.assertRaises(ValueError):
                    canonical.canonical(args, root)
                run.assert_not_called()
                fetch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
