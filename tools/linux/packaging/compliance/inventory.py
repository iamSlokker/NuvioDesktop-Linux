#!/usr/bin/env python3
"""Offline evidence inventory, not a license scanner or legal certification.

Reads extracted packages and immutable input metadata. Never loads a library,
executes a JAR, contacts a service, or reads user application configuration.
"""
import argparse
import fnmatch
import hashlib
import json
from pathlib import Path
import re
import subprocess
import struct
import xml.etree.ElementTree as ET
import zipfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_relative(name):
    p = Path(name)
    if not name or name == '.' or p.is_absolute() or '..' in p.parts or '\\' in name:
        raise ValueError('Unsafe inventory path: ' + name)
    return p


def check_inventory(root, files):
    for name, record in files.items():
        p = root / safe_relative(name)
        if 'target' in record:
            if not p.is_symlink() or str(p.readlink()) != record['target'] or not p.resolve().is_relative_to(root.resolve()):
                raise ValueError('Payload symlink mismatch: ' + name)
        elif p.is_symlink() or not p.is_file() or sha(p) != record['sha256']:
            raise ValueError('Payload inventory mismatch: ' + name)
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() or p.is_symlink()}
    if actual != files.keys():
        raise ValueError('Unlisted/missing payload files')


def verify_checksums(directory):
    result = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([^/\\]+)', line)
        if not match or match[2] in {'.', '..', 'SHA256SUMS'} or match[2] in result:
            raise ValueError('Malformed/duplicate artifact checksum')
        name = match[2]
        p = directory / name
        if not p.is_file() or p.is_symlink() or sha(p) != match[1]:
            raise ValueError('Artifact checksum mismatch: ' + name)
        result[name] = match[1]
    if set(result) != {p.name for p in directory.iterdir() if p.name != 'SHA256SUMS'}:
        raise ValueError('Unlisted artifact')
    return result


def jar_details(path):
    notices, natives = [], []
    with zipfile.ZipFile(path) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            if re.search(r'(^|/)([^/]*(?:license|notice|copyright)[^/]*)$', name, re.I) and not name.endswith('.class'):
                notices.append({'path': name, 'sha256': hashlib.sha256(z.read(entry)).hexdigest()})
            if name.endswith(('.so', '.dll', '.dylib', '.jnilib')) or '.so.' in name:
                data = z.read(entry)
                natives.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)})
    return notices, natives


def subset_modification(original, packaged):
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(packaged) as after:
        a = {e.filename for e in before.infolist() if not e.is_dir()}
        b = {e.filename for e in after.infolist() if not e.is_dir()}
        if not b <= a or any(before.read(n) != after.read(n) for n in b):
            raise ValueError('Repacked JAR has changed/added content: ' + packaged.name)
        return {'type': 'entries_removed', 'removed_entries': len(a - b),
                'recipe': 'composeApp/build.gradle.kts: trimMaterialIconsExtendedJar / trimOnnxRuntimeJar'}


def pom_details(path, cache=None, seen=()):
    if path in seen or len(seen) >= 8:
        raise ValueError('Cyclic/deep POM license inheritance')
    tree = ET.parse(path).getroot()
    def text(name):
        return tree.findtext('./{*}' + name)
    result = {'sha256': sha(path), 'declared_project_url': text('url'),
            'declared_scm_url': tree.findtext('./{*}scm/{*}url'),
            'declared_licenses': [dict(name=l.findtext('{*}name'), url=l.findtext('{*}url'))
                                  for l in tree.findall('./{*}licenses/{*}license')]}
    result['effective_licenses'] = result['declared_licenses']
    parent = [tree.findtext('./{*}parent/{*}' + k) for k in ['groupId', 'artifactId', 'version']]
    if cache is not None and not result['effective_licenses'] and all(
            v and re.fullmatch('[A-Za-z0-9_.+-]+', v) and v not in {'.', '..'} for v in parent):
        candidates = list((cache / parent[0] / parent[1] / parent[2]).glob('*/*.pom'))
        if len(candidates) == 1:
            inherited = pom_details(candidates[0], cache, (*seen, path))
            result['parent_license_evidence'] = dict(coordinates=':'.join(parent), **inherited)
            result['effective_licenses'] = inherited['effective_licenses']
    return result


def module_source(path, binary_sha256, directory_url):
    """Published source hashes are evidence even when source bytes are not retained."""
    data = json.loads(path.read_text())
    variants = data.get('variants', [])
    if not any(f.get('sha256') == binary_sha256 for v in variants for f in v.get('files', [])):
        raise ValueError('Module metadata does not bind the original binary')
    sources = {f['url']: f for v in variants if v.get('attributes', {}).get('org.gradle.docstype') == 'sources'
               for f in v.get('files', [])}
    if len(sources) != 1:
        return None
    name, source = next(iter(sources.items()))
    if name in {'', '.', '..'} or Path(name).name != name or '\\' in name or not re.fullmatch('[0-9a-f]{64}', source.get('sha256', '')):
        raise ValueError('Unsafe/unhashed published source entry')
    return {'url': directory_url + name, 'sha256': source['sha256'],
            'hash_origin': 'published Gradle module metadata, source bytes not yet verified',
            'metadata_sha256': sha(path), 'metadata_url': directory_url + path.name}


def maven_component(packaged, cache):
    """Require a byte match, or prove the two known entry-removal transformations."""
    matches = []
    for p in cache.glob('*/*/*/*/*.jar'):
        if packaged.name.startswith(p.stem + '-'):
            matches.append(p)
    packaged_sha = sha(packaged)
    exact = [p for p in matches if sha(p) == packaged_sha]
    if exact:
        original = sorted(exact)[0]
        modification = None
    elif len(matches) == 1 and packaged.name.startswith(('onnxruntime-', 'material-icons-extended-desktop-', 'skiko-awt-runtime-')):
        original = matches[0]
        modification = subset_modification(original, packaged)
        if packaged.name.startswith('skiko-awt-runtime-'):
            modification['recipe'] = 'Compose createDistributable: extract native library and SHA256 sidecar beside JARs'
            with zipfile.ZipFile(original) as z, zipfile.ZipFile(packaged) as after:
                for n in set(z.namelist()) - set(after.namelist()):
                    if n.endswith('.so'):
                        loose = packaged.parent / n
                        if not loose.is_file() or sha(loose) != hashlib.sha256(z.read(n)).hexdigest():
                            raise ValueError('Extracted Skiko native differs from upstream JAR')
    else:
        return {'id': 'unresolved-jar:' + packaged.name, 'version': None, 'source': None,
                'gaps': ['MAVEN_IDENTITY_MISSING'], 'license_evidence': []}
    group, artifact, version = original.relative_to(cache).parts[:3]
    base = ('https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.')
            else 'https://repo.maven.apache.org/maven2/')
    stem = base + group.replace('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version
    poms = sorted((cache / group / artifact / version).glob('*/*.pom'))
    pom = pom_details(poms[0], cache) if len(poms) == 1 else None
    sources = sorted((cache / group / artifact / version).glob('*/*-sources.jar'))
    source = {'url': stem + '-sources.jar', 'sha256': sha(sources[0]) if len(sources) == 1 else None}
    modules = list((cache / group / artifact / version).glob('*/*.module'))
    if len(modules) == 1:
        published = module_source(modules[0], sha(original), stem.rsplit('/', 1)[0] + '/')
        if published:
            if sources and published['sha256'] != source['sha256']:
                raise ValueError('Cached source JAR differs from published digest')
            source.update(published)
    return {'id': 'maven:' + ':'.join([group, artifact, version]), 'version': version,
            'upstream_binary_sha256': sha(original), 'binary_url': stem + '.jar',
            'modification': modification,
            'pom': dict(pom, url=stem + '.pom') if pom else None,
            'source': {**source,
                       'retained': bool(sources), 'native_source_coverage': 'not established by a Java sources JAR'},
            'license_evidence': pom['effective_licenses'] if pom else [],
            'gaps': ['MAVEN_SOURCES_NOT_RETAINED'] if not sources else []}


def native_owner(path, media, engine, owner_rows, package_rows, versions):
    name = Path(path).name
    if path == 'lib/native/libplayer_bridge.so':
        return 'application'
    if path.startswith('lib/runtime/') or path == 'bin/nuvio' or name == 'libapplauncher.so':
        return 'temurin'
    if name.startswith('libskiko-'):
        return 'skiko-native'
    for entry, record in media['artifact_contract']['libraries'].items():
        if name in {Path(entry).name, record['soname']}:
            return 'media:' + record['component']
    installed = set(engine['files'])
    if path in installed:
        return 'webkit' if name.startswith(('libwebkit', 'libjavascriptcore', 'WebKit')) else 'gtk'
    if path == 'bin/nuvio-gtk-query':
        return 'gtk'
    candidates = {r['package'] for r in owner_rows.get(path, [])
                  if r['package'] in package_rows and package_rows[r['package']]['binary_version'] == versions.get(r['package'])}
    return 'ubuntu:' + next(iter(candidates)) if len(candidates) == 1 else 'unresolved-native:' + path



def verify_sources(repo, inputs, info, wm):
    root = inputs / 'source'
    rows = json.loads((root / 'source-files.json').read_text())
    if hashlib.sha256(json.dumps(rows).encode()).hexdigest() != info['source_sha256']:
        raise ValueError('Application source inventory identity differs')
    source_info = json.loads((root / 'source-info.json').read_text())
    if any(info.get(k) != v for k, v in source_info.items()):
        raise ValueError('Source/binary metadata differs')
    git = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-r', '-z', info['git_commit']])
    objects = {}
    excluded = {'.git', '.agents', '.codex', '.aws', 'MPVKit', 'Docs', 'keystore', 'analysis'}
    for record in git.split(b'\0'):
        if not record:
            continue
        meta, name = record.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        name = name.decode()
        if kind == 'blob' and Path(name).parts[0] not in excluded and Path(name).name != 'local.properties':
            objects[name] = oid
    if len({n for n, _ in rows}) != len(rows) or set(objects) != {n for n, _ in rows}:
        raise ValueError('Application source does not match canonical Git inputs')
    for name, expected in rows:
        path = root / 'application' / safe_relative(name)
        if path.is_symlink() or not path.is_file() or sha(path) != expected:
            raise ValueError('Missing/changed application source: ' + name)
        data = path.read_bytes()
        if hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != objects[name]:
            raise ValueError('Source differs from packaged Git commit: ' + name)
    actual = {p.relative_to(root / 'application').as_posix() for p in (root / 'application').rglob('*') if p.is_file() or p.is_symlink()}
    if actual != set(objects):
        raise ValueError('Unlisted application source files')
    w = inputs / 'webkit-source/engine'
    engine = json.loads((inputs / 'webkit-source/engine-build.json').read_text())
    check_inventory(w, engine['corresponding_source'])
    archive = w / ('webkitgtk-' + wm['version'] + '.tar.xz')
    if sha(archive) != wm['source']['sha256']:
        raise ValueError('WebKit source archive differs')
    for name, spec in wm['gtk_sources'].items():
        if sha(w / 'gtk-inputs' / name) != spec['sha256']:
            raise ValueError('GTK source archive differs')
    patches = {}
    for patch in (repo / 'tools/linux/packaging/webkit/patches').glob('*.patch'):
        if patch.read_bytes() != (w / 'recipe/patches' / patch.name).read_bytes():
            raise ValueError('WebKit source patch differs')
        patches[patch.name] = sha(patch)
    media = 'tools/linux/media-runtime/manifest.json'
    if (root / 'application' / media).read_bytes() != (repo / media).read_bytes():
        raise ValueError('Audited media manifest differs from packaged source')
    return {'application_git_objects_verified': len(rows), 'webkit_source_sha256': sha(archive),
            'gtk_source_inputs_verified': len(wm['gtk_sources']), 'webkit_patches': patches,
            'scope': 'application and WebKit/GTK input integrity only, not whole third-party source closure'}



def verify_appimage_runtime(runtime, appimage, expected):
    data = runtime.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected or data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Wrong pinned x86_64 AppImage runtime')
    shoff = struct.unpack_from('<Q', data, 40)[0]
    size, count, strings = struct.unpack_from('<HHH', data, 58)
    if size != 64 or strings >= count or shoff + count * size > len(data):
        raise ValueError('Malformed runtime section table')
    headers = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * size) for i in range(count)]
    h = headers[strings]; names = data[h[4]:h[4] + h[5]]
    fields = [h for h in headers if names[h[0]:].split(b'\0', 1)[0] == b'.digest_md5']
    if len(fields) != 1 or fields[0][5] != 16:
        raise ValueError('Missing runtime AppImage digest field')
    offset, length = fields[0][4:6]
    with appimage.open('rb') as stream:
        actual = stream.read(len(data))
    if len(actual) != len(data) or actual[:offset] != data[:offset] or actual[offset+length:] != data[offset+length:]:
        raise ValueError('Embedded runtime differs outside .digest_md5')
    return {'input_sha256': expected, 'embedded_prefix_sha256': hashlib.sha256(actual).hexdigest(),
            'prefix_bytes': len(data), 'modification': {'section': '.digest_md5', 'offset': offset, 'bytes': length}}


def build_inventory(repo, inputs, cache, package_rows, owner_rows, runtime=None, evidence=None, audit_base=None):
    artifacts = inputs / 'artifacts'
    checksums = verify_checksums(artifacts)
    info = json.loads((artifacts / 'release-info.json').read_text())
    media = json.loads((repo / 'tools/linux/media-runtime/manifest.json').read_text())
    config = json.loads((repo / 'tools/linux/packaging/config.json').read_text())
    engine = json.loads((inputs / 'webkit-source/engine-build.json').read_text())
    wm = json.loads((repo / 'tools/linux/packaging/webkit/manifest.json').read_text())
    source_evidence = verify_sources(repo, inputs, info, wm)
    provenance = json.loads((artifacts / 'flatpak-provenance.json').read_text())
    flatpak_meta = inputs / 'flatpak/files/lib/nuvio-desktop/flatpak-provenance.json'
    if json.loads(flatpak_meta.read_text()) != provenance or provenance['runtime_commit'] != config['flatpak_runtime_commit']:
        raise ValueError('Flatpak provenance differs')
    images = {'deb': inputs / 'deb/opt/nuvio-desktop',
              'appimage': inputs / 'appimage/squashfs-root/usr/lib/nuvio-desktop',
              'flatpak': inputs / 'flatpak/files/lib/nuvio-desktop'}
    versions = dict(line.split('\t') for line in (inputs / 'webkit-source/engine/webkit-builder-packages.tsv').read_text().splitlines())
    components = {'application': {'id': 'application', 'version': info['version'], 'commit': info['git_commit'],
                                  'license_evidence': ['LICENSE: GPL version 3; or-later election requires review'],
                                  'source_archive': next(n for n in checksums if n.startswith('Nuvio-source-'))},
                  'temurin': {'id': 'temurin', 'version': info['jdk_release']['FULL_VERSION'].strip('"'),
                              'binary': config['tools']['jdk'], 'source': {'repository': info['jdk_release']['SOURCE_REPO'].strip('"'),
                              'revision': info['jdk_release']['SOURCE'].strip('"'), 'archive_sha256': None},
                              'license_evidence': ['share/licenses/Java: per-module licenses, GPLv2/ClassPath exception and third-party notices'],
                              'modifications': ['jlink module selection; jpackage launcher/runtime image'], 'gaps': ['JDK_CORRESPONDING_SOURCE_MISSING']},
                  'webkit': {'id': 'webkit', 'version': wm['version'], 'source': wm['source'],
                             'license_evidence': ['engine/notices/WebKit: mixed LGPL/BSD/third-party terms'],
                             'recipe_identity': engine['recipe_identity'], 'modifications': ['two pinned WebKit patches; production flags in engine-build.json'],
                             'source_retained': True},
                  'gtk': {'id': 'gtk', 'version': wm['gtk_version'], 'sources': wm['gtk_sources'],
                          'license_evidence': ['engine/notices/GTK: LGPL and bundled component notices'],
                          'modifications': ['Ubuntu patch series; gtk-private-resources.patch; generated GTK resource-path patch'], 'source_retained': True},
                  'skiko-native': {'id': 'skiko-native', 'version': 'see matching skiko runtime Maven component',
                                   'license_evidence': ['requires Skiko/Skia and statically incorporated third-party review'],
                                   'source': None, 'gaps': ['SKIKO_NATIVE_SOURCE_AND_NOTICES_INCOMPLETE']}}
    for s in media['sources']:
        components['media:' + s['name']] = dict(s, id='media:' + s['name'], source_retained_in_ci_artifact=False,
                                              gaps=['MEDIA_SOURCE_ARCHIVE_MISSING'],
                                              modification_recipe='tools/linux/media-runtime/scripts/build-runtime.sh; source_patches.py; RPATH/debug stripping')
    ubuntu_notices = images['appimage'] / 'share/licenses/Ubuntu-AppImage'
    for name in json.loads((ubuntu_notices / 'packages.json').read_text()):
        row = package_rows.get(name)
        matched = row and row['binary_version'] == versions.get(name)
        notice = ubuntu_notices / (name + '.copyright')
        text = notice.read_text(errors='replace')
        components['ubuntu:' + name] = {'id': 'ubuntu:' + name, 'version': versions.get(name),
            'source_package': row['source_package'] if matched else None,
            'source_version': row['source_version'] if matched else None,
            'source_url_evidence': re.findall(r'^Source:\s*(.+)$', text, re.M), 'source_archive_sha256': None,
            'source_package_record_url': 'https://launchpad.net/ubuntu/+source/' + row['source_package'] + '/' + row['source_version'] if matched else None,
            'license_evidence': sorted(set(re.findall(r'^License:\s*(.+)$', text, re.M))),
            'notice': {'path': 'share/licenses/Ubuntu-AppImage/' + notice.name, 'sha256': sha(notice)},
            'mapping_evidence': 'version-matched immutable engine builder dpkg metadata' if matched else 'engine TSV; exact source-package mapping missing',
            'modifications': ['AppImage RPATH rewrite; resource transformations separately inventoried'],
            'gaps': ['UBUNTU_SOURCE_PACKAGE_ARCHIVES_MISSING'] + ([] if matched else ['UBUNTU_SOURCE_IDENTITY_MISSING'])}
    files, jars, deb_elf = [], [], {}
    for fmt, image in images.items():
        if json.loads((image / 'release-info.json').read_text()) != info:
            raise ValueError('Package source identity differs: ' + fmt)
        if fmt != 'flatpak':
            audit = json.loads((artifacts / (fmt + '-audit.json')).read_text())
            if audit['source'] != info:
                raise ValueError('Audit source identity differs')
            check_inventory(image, audit['files'])
        # Inspect actual bytes, not just the CI audit's ELF list.
        for p in sorted(image.rglob('*')):
            if not p.is_file() or p.is_symlink():
                continue
            with p.open('rb') as stream:
                elf = stream.read(4) == b'\x7fELF'
            if not elf:
                continue
            rel = p.relative_to(image).as_posix()
            digest = sha(p)
            if fmt == 'deb':
                deb_elf[rel] = digest
            elif rel in deb_elf and digest != deb_elf[rel]:
                raise ValueError('Canonical ELF differs across packages: ' + fmt + '/' + rel)
            elif fmt == 'flatpak' and rel not in deb_elf:
                raise ValueError('Unexpected Flatpak ELF')
            owner = native_owner(rel, media, engine, owner_rows, package_rows, versions)
            components.setdefault(owner, {'id': owner, 'version': None, 'source': None, 'gaps': ['NATIVE_OWNER_MISSING']})
            files.append({'format': fmt, 'path': rel, 'sha256': sha(p), 'component': owner})
        current = {p.name: sha(p) for p in (image / 'lib/app').glob('*.jar')}
        if fmt == 'deb':
            jars = current
            for name in sorted(current):
                p = image / 'lib/app' / name
                notices, embedded = jar_details(p)
                if name.startswith('composeApp-desktop-'):
                    component = components['application']
                else:
                    component = maven_component(p, cache)
                    components[component['id']] = component
                    if not notices:
                        component.setdefault('gaps', []).append('JAR_NOTICE_COVERAGE_REVIEW')
                files.append({'format': 'all', 'path': 'lib/app/' + name, 'sha256': current[name],
                              'component': component['id'], 'notices': notices, 'embedded_binaries': embedded})
        elif current != jars:
            raise ValueError('Actual JARs differ across formats: ' + fmt)
    for name, license_name in [('u2netp.onnx', 'LICENSE-U2NET.txt'), ('yunet.onnx', 'LICENSE-YUNET.txt')]:
        path = 'backdrop-framing/' + name
        jar = next(p for p in (images['deb'] / 'lib/app').glob('composeApp-desktop-*.jar'))
        with zipfile.ZipFile(jar) as z:
            components['model:' + name] = {'id': 'model:' + name, 'version': 'exact model SHA256',
                'sha256': hashlib.sha256(z.read(path)).hexdigest(), 'source': 'https://github.com/UmbraProjects/NuvioDesktop/blob/6f155d267a478b3cebd8320de5a8bedc8e5de52a/composeApp/src/desktopMain/resources/' + path,
                'license_evidence': {'path': 'backdrop-framing/' + license_name, 'sha256': hashlib.sha256(z.read('backdrop-framing/' + license_name)).hexdigest()},
                'modifications': 'none', 'gaps': ['MODEL_EXPORT_PREFERRED_FORM_REVIEW']}
            components['application'].setdefault('font_assets', []).extend(
                {'path': n, 'sha256': hashlib.sha256(z.read(n)).hexdigest(), 'license': None}
                for n in z.namelist() if n.endswith(('.ttf', '.otf')) and name == 'u2netp.onnx')
    components['appimage-runtime'] = {'id': 'appimage-runtime', 'version': '20251108',
        'binary': config['tools']['appimage-runtime'], 'source': {'url': 'https://github.com/AppImage/type2-runtime/tree/20251108', 'archive_sha256': None},
        'license_evidence': ['upstream LICENSE: MIT plus statically linked musl, libfuse, squashfuse, zstd, zlib'],
        'gaps': ['APPIMAGE_RUNTIME_SOURCE_BUILD_AND_NOTICES_MISSING']}
    if runtime is not None:
        runtime_evidence = verify_appimage_runtime(runtime, next(artifacts.glob('*.AppImage')), config['tools']['appimage-runtime']['sha256'])
        components['appimage-runtime']['embedding'] = runtime_evidence
        files.append({'format': 'appimage', 'path': '<outer AppImage ELF runtime prefix>',
                      'sha256': runtime_evidence['embedded_prefix_sha256'], 'component': 'appimage-runtime'})
    components['skiko-native']['version'] = next((v['version'] for k,v in components.items() if k.startswith('maven:org.jetbrains.skiko:skiko-awt-runtime-linux-x64:')), None)
    components['skiko-native']['original_jni_bytes_verified'] = components['skiko-native']['version'] is not None
    evidence = evidence or {}
    if evidence.get('run', {}).get('head_sha', info['git_commit']) != info['git_commit']:
        raise ValueError('CI evidence does not describe these packages')
    if evidence.get('runtime_source'):
        components['appimage-runtime']['source'] = evidence['runtime_source']
        components['appimage-runtime']['incorporated_components'] = [
            {'name':'libfuse', 'version':'3.15.0', 'source_url':'https://github.com/libfuse/libfuse/releases/download/fuse-3.15.0/fuse-3.15.0.tar.xz', 'source_sha256':'70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0', 'modification':'runtime patch patches/libfuse/mount.c.diff', 'source_retained':False},
            {'name':'squashfuse', 'version':'0.5.2', 'source_url':'https://github.com/vasi/squashfuse/archive/0.5.2.tar.gz', 'source_sha256':'db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3', 'source_retained':False},
            *[{'name':name, 'version':None, 'source_sha256':None, 'gap':'exact Alpine build dependency version/input not established'} for name in ['musl','zstd','zlib','mimalloc']]]
    source_evidence['supplemental_local_sources'] = evidence.get('media_source_collection')
    for font in components['application'].get('font_assets', []):
        font['metadata'] = evidence.get('font_metadata', {}).get(Path(font['path']).name)
    for key, component in components.items():
        if key.startswith('ubuntu:'):
            component['presence'] = ('bundled AppImage ELF owner' if any(f['component'] == key for f in files)
                                     else 'resource or build-tool owner in staging record; no standalone bundled ELF mapped')
        elif key.startswith('maven:'):
            component['presence'] = 'bundled runtime JAR'
        elif key.startswith('media:'):
            component['presence'] = ('incorporated code/data from build input' if component.get('incorporated_code') or component.get('incorporated_data')
                                     else component.get('classification'))
    control = (inputs / 'deb-control.txt').read_text()
    depends = next(line.removeprefix('Depends: ') for line in control.splitlines() if line.startswith('Depends: '))
    properties = (repo / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    gradle_url = next(line.split('=',1)[1].replace('\\:', ':') for line in properties.splitlines() if line.startswith('distributionUrl='))
    source_evidence['gradle_wrapper'] = {'selected_distribution_url': gradle_url,
                                        'wrapper_jar_sha256': sha(inputs / 'source/application/gradle/wrapper/gradle-wrapper.jar'),
                                        'scope': 'build-only binary included in application source archive; source/license coverage review pending'}
    return {'schema_version': 1, 'audit_base': audit_base,
        'ci_evidence': evidence.get('run'), 'artifact_bundles': evidence.get('artifact_bundles'),
        'source_verification': source_evidence,
        'package_commit': info['git_commit'], 'artifacts': checksums, 'components': components, 'binary_files': files,
        'source_artifact_scope': 'application inputs and WebKit/GTK only; not complete corresponding source',
        'external_dependencies': {'deb': {'depends': depends, 'scope': 'host dependencies; versions are resolved on recipient system, no exemption assumed'},
            'flatpak': {'runtime': config['flatpak_runtime'], 'branch': config['flatpak_branch'], 'commit': config['flatpak_runtime_commit'],
                        'scope': 'separate GNOME runtime; exact runtime source closure not audited', 'metadata_sha256': sha(inputs / 'flatpak/metadata')},
            'host_drivers': 'not bundled; no legal System Library conclusion'},
        'build_only': {name: dict(value, scope='tool only; JDK also supplies bundled runtime') for name,value in config['tools'].items() if name != 'appimage-runtime'},
        'assessment': {fmt: 'BLOCKED' for fmt in images},
        'assessment_basis': 'Missing retained corresponding sources/notices and reviewed source availability; no automatic legal certification'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--audit-base', required=True, help='Full reviewed repository baseline SHA')
    p.add_argument('--ci-evidence', type=Path, required=True)
    p.add_argument('--inputs', type=Path, required=True, help='Extracted artifact/input layout documented in compliance report')
    p.add_argument('--maven-cache', type=Path, required=True)
    p.add_argument('--ubuntu-packages', type=Path, required=True)
    p.add_argument('--ubuntu-owners', type=Path, required=True)
    p.add_argument('--appimage-runtime', type=Path, help='Optional verified original runtime input for prefix comparison')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--check', action='store_true', help='Exit 2 while redistribution blockers remain')
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[4]
    if not re.fullmatch('[a-f0-9]{40}', args.audit_base):
        raise ValueError('A full audit baseline SHA is required')
    data = build_inventory(repo, args.inputs, args.maven_cache,
                           (lambda d: d.get('packages', d))(json.loads(args.ubuntu_packages.read_text())),
                           (lambda d: d.get('file_owners', d))(json.loads(args.ubuntu_owners.read_text())), args.appimage_runtime, json.loads(args.ci_evidence.read_text()), args.audit_base)
    args.output.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')
    print('Inventory written; all formats BLOCKED pending source/notices/availability review')
    return 2 if args.check else 0


if __name__ == '__main__':
    raise SystemExit(main())
