import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import formats
from compliance import inventory as inv
from compliance import collect_media_sources as media


def digest(data):
    return hashlib.sha256(data).hexdigest()


def jar(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        for name, data in entries.items():
            z.writestr(name, data)


class ComplianceInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_checksums_bind_actual_artifacts_and_reject_tampering(self):
        (self.root / 'a.deb').write_bytes(b'fixture')
        sums = self.root / 'SHA256SUMS'
        sums.write_text(digest(b'fixture') + '  a.deb\n')
        self.assertEqual(inv.verify_checksums(self.root), {'a.deb': digest(b'fixture')})
        (self.root / 'a.deb').write_bytes(b'tampered')
        with self.assertRaises(ValueError): inv.verify_checksums(self.root)

    def test_checksums_reject_duplicate_unlisted_and_unsafe_members(self):
        (self.root / 'a').write_bytes(b'a')
        sums = self.root / 'SHA256SUMS'
        line = digest(b'a') + '  a\n'
        for contents in [line + line, digest(b'a') + '  ../a\n', digest(b'a') + '  .\n']:
            sums.write_text(contents)
            with self.subTest(contents=contents), self.assertRaises(ValueError): inv.verify_checksums(self.root)
        sums.write_text(line)
        (self.root / 'unexpected').write_bytes(b'x')
        with self.assertRaises(ValueError): inv.verify_checksums(self.root)

    def test_payload_inventory_rejects_extra_changed_and_escaping_files(self):
        (self.root / 'lib.so').write_bytes(b'library')
        records = {'lib.so': {'sha256': digest(b'library')}}
        inv.check_inventory(self.root, records)
        link = self.root / 'alias.so'; link.symlink_to('lib.so')
        with self.assertRaises(ValueError): inv.check_inventory(self.root, records)
        records['alias.so'] = {'target': 'lib.so'}
        inv.check_inventory(self.root, records)
        link.unlink(); link.symlink_to('../outside')
        with self.assertRaises(ValueError): inv.check_inventory(self.root, records)
        link.unlink(); (self.root / 'lib.so').write_bytes(b'changed')
        with self.assertRaises(ValueError): inv.check_inventory(self.root, {'lib.so': records['lib.so']})

    def test_jar_inventory_hashes_notices_and_embedded_binaries(self):
        p = self.root / 'fixture.jar'
        jar(p, {'META-INF/LICENSE': b'license', 'native/windows/a.dll': b'MZ-fixture', 'X.class': b'class'})
        notices, binaries = inv.jar_details(p)
        self.assertEqual(notices, [{'path': 'META-INF/LICENSE', 'sha256': digest(b'license')}])
        self.assertEqual(binaries[0]['sha256'], digest(b'MZ-fixture'))

    def test_maven_identity_requires_matching_actual_bytes(self):
        cache = self.root / 'cache'
        original = cache / 'example/artifact/1.2/hash/artifact-1.2.jar'
        jar(original, {'X.class': b'class'})
        pom = original.with_suffix('.pom')
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>MIT</name><url>https://example.test/LICENSE</url></license></licenses></project>')
        packaged = self.root / 'artifact-1.2-jpackage.jar'; shutil.copyfile(original, packaged)
        result = inv.maven_component(packaged, cache)
        self.assertEqual(result['id'], 'maven:example:artifact:1.2')
        self.assertIsNone(result['source']['sha256'])
        self.assertIn('MAVEN_SOURCES_NOT_RETAINED', result['gaps'])
        self.assertEqual(result['license_evidence'][0]['name'], 'MIT')
        jar(packaged, {'X.class': b'modified'})
        self.assertIn('MAVEN_IDENTITY_MISSING', inv.maven_component(packaged, cache)['gaps'])

    def test_known_repacking_must_preserve_every_retained_entry(self):
        before, after = self.root / 'before.jar', self.root / 'after.jar'
        jar(before, {'A': b'a', 'B': b'b'}); jar(after, {'A': b'a'})
        self.assertEqual(inv.subset_modification(before, after)['removed_entries'], 1)
        jar(after, {'A': b'changed'})
        with self.assertRaises(ValueError): inv.subset_modification(before, after)
        jar(after, {'new': b'a'})
        with self.assertRaises(ValueError): inv.subset_modification(before, after)


class ApplicationSourceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.source = self.root / 'source'; self.source.mkdir()
        self.work = self.root / 'work'; self.work.mkdir()
        self.artifacts = self.root / 'artifacts'; self.artifacts.mkdir()
        (self.work / 'source-info.json').write_text('{}')
        self.info = {'git_commit': 'a' * 40}

    def tearDown(self): self.tmp.cleanup()

    def build(self, name, data=b'fixture'):
        (self.work / 'source-files.json').write_text(json.dumps([[name, digest(data)]]))
        formats.archive_application_source(self.work, self.artifacts, self.info, self.source)

    def test_actual_source_archive_preserves_bytes_modes_and_documents_limitations(self):
        p = self.source / 'gradlew'; p.write_bytes(b'fixture'); p.chmod(0o755)
        self.build('gradlew')
        with tarfile.open(next(self.artifacts.glob('*.tar.gz'))) as t:
            self.assertEqual(t.extractfile('./application/gradlew').read(), b'fixture')
            self.assertEqual(t.getmember('./application/gradlew').mode, 0o755)
            text = t.extractfile('./SOURCE-README.txt').read().decode()
            self.assertIn('NOT a complete third-party', text)
            self.assertIn('requires Git metadata', text)
            self.assertIn('Preserve the sandbox', text)

    def test_source_archive_rejects_tampering_unsafe_paths_private_files_and_links(self):
        (self.source / 'file').write_bytes(b'changed')
        for name in ['file', '../outside', '/etc/passwd', 'local.properties', '.aws/credentials', 'C:\\private']:
            with self.subTest(name=name), self.assertRaises(ValueError): self.build(name)
        (self.source / 'link').symlink_to('file')
        with self.assertRaises(ValueError): self.build('link', b'changed')
        self.assertFalse(list(self.artifacts.iterdir()))


class MediaSourceCollectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.recipe = self.root / 'recipe'; self.recipe.mkdir()
        self.cache = self.root / 'cache'; self.cache.mkdir()
        self.spec = {'name': 'fixture', 'version': '1', 'url': 'https://example.test/source.tar', 'sha256': digest(b'source')}
        self.write_manifest()
        (self.cache / (self.spec['sha256'] + '.archive')).write_bytes(b'source')
        self.output = self.root / 'sources.tar.gz'

    def tearDown(self): self.tmp.cleanup()

    def write_manifest(self):
        (self.recipe / 'manifest.json').write_text(json.dumps({'sources': [self.spec]}))

    def collect(self):
        return media.collect(self.recipe, self.cache, self.output, ['manifest.json'])

    def test_collects_verified_sources_and_exact_recipes_without_building(self):
        result = self.collect()
        self.assertEqual(result['inputs'][0]['sha256'], digest(b'source'))
        with tarfile.open(self.output) as t:
            self.assertEqual(t.extractfile('archives/fixture.archive').read(), b'source')
            self.assertEqual(t.extractfile('recipe/manifest.json').read(), (self.recipe / 'manifest.json').read_bytes())
            self.assertIn('not complete', json.load(t.extractfile('source-inputs.json'))['scope'])

    def test_missing_tampered_or_symlinked_sources_produce_no_archive(self):
        p = self.cache / (self.spec['sha256'] + '.archive'); p.unlink()
        with self.assertRaises(ValueError): self.collect()
        p.write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.collect()
        p.unlink(); other = self.root / 'other'; other.write_bytes(b'source'); p.symlink_to(other)
        with self.assertRaises(ValueError): self.collect()
        self.assertFalse(self.output.exists())

    def test_does_not_overwrite_existing_evidence(self):
        self.output.write_bytes(b'existing')
        with self.assertRaises(FileExistsError): self.collect()
        self.assertEqual(self.output.read_bytes(), b'existing')

    def test_rejects_duplicate_or_unsafe_source_records(self):
        with self.assertRaises(ValueError): media.required_inputs({'sources': [self.spec, self.spec]})
        for key, value in [('name', '../secret'), ('url', 'http://example.test'), ('sha256', 'bad')]:
            with self.subTest(key=key), self.assertRaises(ValueError): media.required_inputs({'sources': [dict(self.spec, **{key: value})]})


class CommonLicenseTextsTest(unittest.TestCase):
    def test_copies_full_texts_and_resolves_internal_aliases(self):
        from webkit.stage import copy_common_licenses
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'original'; source.mkdir()
            (source / 'GPL-3').write_text('Full license fixture\n')
            (source / 'GPL').symlink_to('GPL-3')
            dest = root / 'packaged'
            self.assertEqual(copy_common_licenses(source, dest), ['GPL', 'GPL-3'])
            self.assertEqual((dest / 'GPL').read_bytes(), (source / 'GPL-3').read_bytes())
            self.assertFalse((dest / 'GPL').is_symlink())

    def test_rejects_missing_external_links_and_binary_inputs(self):
        from webkit.stage import copy_common_licenses
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'original'; source.mkdir()
            with self.assertRaises(ValueError): copy_common_licenses(source, root / 'dest')
            (root / 'outside').write_text('secret')
            (source / 'escape').symlink_to('../outside')
            with self.assertRaises(ValueError): copy_common_licenses(source, root / 'dest')
            (source / 'escape').unlink()
            (source / 'ELF').write_bytes(b'\x7fELFfixture')
            with self.assertRaises(ValueError): copy_common_licenses(source, root / 'dest')


class RuntimePrefixTest(unittest.TestCase):
    def test_only_the_named_appimage_digest_section_may_change(self):
        import struct
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); runtime = root / 'runtime'; image = root / 'AppImage'
            data = bytearray(512); data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<Q', data, 40, 64)
            struct.pack_into('<HHH', data, 58, 64, 3, 1)
            names = b'\0.shstrtab\0.digest_md5\0'
            struct.pack_into('<IIQQQQIIQQ', data, 128, 1, 3, 0, 0, 256, len(names), 0, 0, 1, 0)
            struct.pack_into('<IIQQQQIIQQ', data, 192, 11, 1, 0, 0, 320, 16, 0, 0, 1, 0)
            data[256:256+len(names)] = names; runtime.write_bytes(data)
            patched = data.copy(); patched[320:336] = b'1234567890abcdef'; image.write_bytes(patched + b'squashfs fixture')
            result = inv.verify_appimage_runtime(runtime, image, digest(data))
            self.assertEqual(result['modification']['section'], '.digest_md5')
            self.assertEqual(result['embedded_prefix_sha256'], digest(patched))
            patched[300] = 1; image.write_bytes(patched)
            with self.assertRaises(ValueError): inv.verify_appimage_runtime(runtime, image, digest(data))
            with self.assertRaises(ValueError): inv.verify_appimage_runtime(runtime, image, '0' * 64)


class InheritedPomLicenseTest(unittest.TestCase):
    def test_parent_license_is_evidenced_by_its_bytes_and_cycles_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            parent = cache / 'example/parent/1/hash/parent-1.pom'; parent.parent.mkdir(parents=True)
            parent.write_text('<project><licenses><license><name>MIT</name></license></licenses></project>')
            child = cache / 'child.pom'
            child.write_text('<project><parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent></project>')
            result = inv.pom_details(child, cache)
            self.assertEqual(result['effective_licenses'][0]['name'], 'MIT')
            self.assertEqual(result['parent_license_evidence']['sha256'], digest(parent.read_bytes()))
            parent.write_text(child.read_text())
            with self.assertRaises(ValueError): inv.pom_details(child, cache)


class PublishedSourceHashTest(unittest.TestCase):
    def test_published_source_hash_requires_binary_binding_and_safe_source_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'module.module'
            data = {'variants': [{'files': [{'url': 'library.jar', 'sha256': digest(b'binary')}]},
                                 {'attributes': {'org.gradle.docstype': 'sources'}, 'files': [
                                     {'url': 'library-sources.jar', 'sha256': digest(b'source')}]}]}
            p.write_text(json.dumps(data))
            result = inv.module_source(p, digest(b'binary'), 'https://example.test/')
            self.assertEqual(result['sha256'], digest(b'source'))
            self.assertIn('not yet verified', result['hash_origin'])
            with self.assertRaises(ValueError): inv.module_source(p, digest(b'other'), 'https://example.test/')
            data['variants'][1]['files'][0]['url'] = '../secret'
            p.write_text(json.dumps(data))
            with self.assertRaises(ValueError): inv.module_source(p, digest(b'binary'), 'https://example.test/')
