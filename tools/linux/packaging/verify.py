#!/usr/bin/env python3
"""Headless package/ELF/inventory checks. Never invokes an app launcher."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import zipfile
from formats import PLATFORM
import appimage_runtime
from compliance.inventory import verify_appimage_runtime
from webkit.stage import linked_paths
from client_config import verify_native_backend, verify_packaged_backend
from onnx_contract import NATIVES as ONNX_NATIVES, verify_packaged_onnx
from artwork_contract import release_rules, verify_packaged_artwork
from notice_contract import TARGET as NOTICE_TARGET, expected as notice_files, verify_packaged_notices
from flatpak_bundle import check_unpublished_bundle
import native_evidence
from stage import TORRSERVER
from torrserver.build import check_static as check_static_torrserver

FORBIDDEN = [b'/home/hmacedo', b'/var/home/hmacedo', b'/home/linuxbrew', b'/opt/homebrew',
             b'/source/', b'/work/prefix', b'/recipes/']

# A bare substring search also rejects mandatory copyright URLs/file globs and
# libyuv's explicitly relative __FILE__ diagnostics. Recognize those bounded
# forms; retain rejection of absolute paths, nested build paths and ambiguous
# text. Never exempt a whole file or rewrite a library/license to pass the audit.
SOURCE_REFERENCES = [
    rb'(?m)^(?:[ \t]*|Source:[ \t]+)(?P<path>https?://[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]+)?/[^\x00\s\"\'<>]+)(?=\r?$)',
    rb'(?m)^Files:[ \t]+(?P<path>[A-Za-z0-9_.*?+-][A-Za-z0-9_./*?+-]*)(?=[ \t\r\n]|\Z)',
    rb'(?:\A|\x00)(?P<path>\.\.?/[A-Za-z0-9_./+-]+)(?=\x00|\Z)',
]


def scan_bytes(data, name):
    for token in FORBIDDEN:
        if token in data:
            if token == b'/source/':
                references = [match.span('path') for pattern in SOURCE_REFERENCES
                              for match in re.finditer(pattern, data)]
                if all(any(start <= hit.start() and hit.end() <= end for start, end in references)
                       for hit in re.finditer(re.escape(token), data)):
                    continue
            raise ValueError(f'Build-machine path in {name}: {token!r}')
    if re.search(rb'(?<![A-Za-z0-9_./])/(?:var/)?home/[^/\s\x00]+/', data):
        raise ValueError('Absolute developer home path in ' + name)


def entries(root):
    result = {}
    for p in sorted(root.rglob('*')):
        name = p.relative_to(root).as_posix()
        if p.is_symlink():
            if not p.exists() or os.path.isabs(os.readlink(p)) or not p.resolve().is_relative_to(root.resolve()):
                raise ValueError('Escaping/broken package link: ' + name)
            result[name] = dict(target=os.readlink(p))
        elif p.is_file():
            result[name] = dict(sha256=hashlib.sha256(p.read_bytes()).hexdigest(), mode=p.stat().st_mode & 0o777)
        elif not p.is_dir():
            raise ValueError('Special file in image: ' + name)
    return result


# Experimental HDR capability helper, started by the app as a separate process.
# It must resolve the bundled Wayland client and Vulkan loader through its own
# RUNPATH (same layout as libplayer_bridge.so), never through the caller's
# environment, and carry no build-only media SDK inputs alongside it.
HDR_PROBE = 'lib/native/nuvio-hdr-probe'
HDR_PROBE_RUNPATH = '$ORIGIN:$ORIGIN/../runtime/lib'
HDR_PROBE_PRIVATE = ('libwayland-client.so.0', 'libvulkan.so.1')
HDR_PROBE_NEEDED = frozenset(HDR_PROBE_PRIVATE + ('libstdc++.so.6', 'libgcc_s.so.1', 'libc.so.6', 'libm.so.6'))
HDR_BUILD_ONLY = ('share/wayland-protocols', 'bin/wayland-scanner', 'include/vulkan', 'include/vk_video')


def check_hdr_probe(root):
    path = root / HDR_PROBE
    if (path.is_symlink() or not path.is_file() or not os.access(path, os.X_OK)
            or path.read_bytes()[:4] != b'\x7fELF'):
        raise ValueError('Missing/non-executable HDR capability helper: ' + HDR_PROBE)
    dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
    needed = set(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
    runpath = re.findall(r'\((?:RUNPATH|RPATH)\).*?\[(.*?)\]', dynamic)
    if runpath != [HDR_PROBE_RUNPATH] or '(RPATH)' in dynamic:
        raise ValueError('HDR capability helper RUNPATH differs from bundled native layout: ' + repr(runpath))
    if not set(HDR_PROBE_PRIVATE) <= needed or not needed <= HDR_PROBE_NEEDED:
        raise ValueError('Unexpected HDR capability helper dependencies: ' + repr(sorted(needed)))
    env = {k: v for k, v in os.environ.items() if k not in ('LD_LIBRARY_PATH', 'LD_PRELOAD')}
    linked = subprocess.check_output(['ldd', str(path)], text=True, env=env, stderr=subprocess.STDOUT)
    if 'not found' in linked:
        raise ValueError('Unresolved HDR capability helper dependency:\n' + linked)
    resolved = dict(linked_paths(linked))
    native = (root / 'lib/native').resolve()
    for dep in HDR_PROBE_PRIVATE:
        if dep not in resolved or not pathlib.Path(resolved[dep]).resolve().is_relative_to(native):
            raise ValueError('HDR capability helper resolves ' + dep + ' outside the bundled runtime')
    for relative in HDR_BUILD_ONLY:
        if os.path.lexists(root / relative):
            raise ValueError('Build-only media SDK input in image: ' + relative)
    return dict(file=HDR_PROBE, needed=sorted(needed), runpath=runpath[0])


def flatpak_executable(repository, ref, relative, label):
    """The exported bundle, not only staging, carries `relative` as an executable file."""
    target = '/files/lib/nuvio-desktop/' + relative
    listing = subprocess.check_output(['ostree', '--repo=' + str(repository), 'ls', ref, target], text=True)
    fields = listing.strip().split(maxsplit=4)
    # ostree ls: type+octal-mode, uid, gid, size, path (see flatpak_jar_names).
    if (len(listing.strip().splitlines()) != 1 or len(fields) != 5 or fields[4] != target
            or not re.fullmatch(r'-[0-7]{5}', fields[0]) or int(fields[0][1:], 8) & 0o7111 != 0o111):
        raise ValueError(f'Missing/non-executable {label} in Flatpak bundle')
    return dict(file=relative, mode=fields[0])


def flatpak_hdr_probe(repository, ref):
    return flatpak_executable(repository, ref, HDR_PROBE, 'HDR capability helper')


def check_webkit_relocation(image, required=False):
    if (required or (image / 'lib/webkit2gtk-4.1').exists()
            or (image / 'lib/native/libwebkit2gtk-4.1.so.0').exists()
            or (image / 'share/nuvio-webkit/runtime.json').exists()):
        from webkit.contract import check_runtime
        check_runtime(image)


def check_abi(path, label):
    header = subprocess.check_output(['readelf', '-h', str(path)], text=True)
    if 'Advanced Micro Devices X86-64' not in header or 'ELF64' not in header:
        raise ValueError('Wrong ELF architecture: ' + label)
    versions = subprocess.check_output(['readelf', '--version-info', str(path)], text=True)
    for tag, ceiling in [('GLIBC', (2, 35)), ('GLIBCXX', (3, 4, 30))]:
        found = re.findall(r'\b' + tag + r'_(\d+(?:\.\d+)+)', versions)
        if any(tuple(map(int, v.split('.'))) > ceiling for v in found):
            raise ValueError('ABI above Ubuntu 22.04 floor: ' + label + ': ' + tag)


FLATPAK_IMAGE = '/files/lib/nuvio-desktop'


def flatpak_tree(repository, ref):
    """Recursive listing of the exported image: {relative path: type+mode}; no JAR may appear."""
    listing = subprocess.check_output(
        ['ostree', '--repo=' + str(repository), 'ls', '-R', ref, FLATPAK_IMAGE], text=True)
    tree = {}
    for line in listing.splitlines():
        # ostree ls: type+octal-mode, uid, gid, size, path. Split only the
        # metadata columns so literal spaces in a filename are preserved.
        fields = line.split(maxsplit=4)
        if (len(fields) != 5 or not re.fullmatch(r'[dl-][0-7]{5}', fields[0])
                or any(not field.isdigit() for field in fields[1:4])):
            raise ValueError('Unrecognized Flatpak application inventory entry: ' + line)
        if fields[0][0] == 'l' and '.jar' in fields[4]:
            # Symlink formatting uses " -> ", which is also legal filename text.
            # Never let an ambiguous link name hide a JAR.
            raise ValueError('JAR link in the Flatpak bundle: ' + fields[4])
        path = fields[4].split(' -> ', 1)[0] if fields[0][0] == 'l' else fields[4]
        if path != FLATPAK_IMAGE and not path.startswith(FLATPAK_IMAGE + '/'):
            raise ValueError('Unexpected Flatpak application inventory path: ' + path)
        name = path[len(FLATPAK_IMAGE) + 1:]
        if fields[0][0] != 'd' and name.lower().endswith('.jar'):
            raise ValueError('JAR in the Flatpak bundle (native packages ship none): ' + path)
        if name in tree:
            raise ValueError('Duplicate Flatpak inventory entry: ' + path)
        if name:
            tree[name] = fields[0]
    for name in native_evidence.FORBIDDEN_JVM:
        if name in tree:
            raise ValueError('JVM runtime/launcher in the Flatpak bundle: ' + name)
    return tree


def check_native_payloads(root, native, embedded, onnx, artwork):
    """Reviewed JAR payloads are embedded verbatim in lib/nuvio; removed ones are not.

    The contracts above checked them in the exact input JARs; native-image's report
    must list each as embedded and its bytes must occur in the shipped executable.
    The anime-mapping notices are not app resources; they ship as files instead.
    """
    anime = {name for name in artwork['resources'] if name.startswith('anime-mapping/')}
    names = (set(onnx['files']) | set(artwork['resources'])) - anime
    payloads = {}
    for jar in native.jars:
        with zipfile.ZipFile(jar) as archive:
            for name in names & set(archive.namelist()):
                payloads[name] = archive.read(name)
    for name in sorted(names):
        if name not in embedded:
            raise ValueError('Reviewed payload is not embedded in the native image: ' + name)
        if not native.contained(payloads[name]):
            raise ValueError('Reviewed payload bytes are absent from lib/nuvio: ' + name)
    for name in sorted(anime):
        path = root / native_evidence.ANIME_NOTICES / pathlib.PurePosixPath(name).name
        if (path.is_symlink() or not path.is_file()
                or hashlib.sha256(path.read_bytes()).hexdigest() != artwork['resources'][name]):
            raise ValueError('Missing/different anime-mapping notice: ' + str(path.relative_to(root)))
    removed = release_rules()[0]
    for name in embedded:
        if pathlib.PurePosixPath(name).name.casefold() in removed:
            raise ValueError('Removed font/artwork/dataset embedded in the native image: ' + name)
    return dict(embedded_verbatim=len(names), anime_notice_files=len(anime), embedded_resources=len(embedded),
                scope='Exact bytes in lib/nuvio; rights and source gates unchanged')


REQUIRED = ['bin/nuvio', 'bin/nuvio-desktop', 'lib/nuvio', 'lib/runtime/lib/libjawt.so',
            'lib/native/libplayer_bridge.so', 'lib/native/libquickjs.so', 'lib/native/libmpv.so.2', 'lib/app/build-info.properties',
            'release-info.json', str(native_evidence.PACKAGED_MANIFEST),
            'share/applications/com.nuvio.media.desktop.desktop',
            'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png',
            'share/licenses/Nuvio/COPYING', 'share/licenses/quickjs-kt/LICENSE', 'share/licenses/mpv/LICENSE.GPL',
            TORRSERVER, 'share/licenses/TorrServer/LICENSE', 'share/licenses/TorrServer/NOTICE.txt',
            'lib/native/libnuvio_engine.so', 'share/licenses/NuvioEngine/LICENSE', 'share/licenses/NuvioEngine/NOTICE.txt',
            str(native_evidence.GRAALVM_NOTICES / 'LICENSE.txt')]

SCHEME_HANDLER = 'x-scheme-handler/nuvio'


def check_desktop_entry(text, label):
    """nuvio:// links (Watch Party invites, "Open in Nuvio") must reach the app: the entry claims
    the scheme and its Exec hands the URL (%u/%U) to the launcher, which forwards it to lib/nuvio."""
    group, fields = None, {}
    for line in text.splitlines():
        if line.startswith('['):
            group = line.strip()
        elif group == '[Desktop Entry]' and '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            fields[key.strip()] = value.strip()
    if SCHEME_HANDLER not in fields.get('MimeType', '').split(';'):
        raise ValueError(label + ' does not register ' + SCHEME_HANDLER)
    if not re.search(r'(?:^|\s)%[uU](?:\s|$)', fields.get('Exec', '')):
        raise ValueError(label + ' Exec does not pass the link (%u): ' + fields.get('Exec', ''))
    return dict(mime_type=fields['MimeType'], exec=fields['Exec'])


def library_path(root):
    # lib/ last: the emitted JDK libraries load from the executable's directory, and
    # libjawt.so resolves libawt.so to that already-loaded copy (an audit fixture, as
    # the ONNX alias below; the launchers never put lib/ on the search path).
    return f'{root}/lib/native:{root}/lib/runtime/lib:{root}/lib'


def embedded_elf(native, embedded, env, elf_records):
    """Linux x86_64 JNI libraries the image embeds as resources (e.g. quickjs), from the input JARs."""
    for jar in native.jars:
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                if entry.is_dir() or entry.filename not in embedded:
                    continue
                label = 'lib/nuvio:' + entry.filename
                if entry.filename in ONNX_NATIVES:
                    # Verified by the ONNX contract as a pinned pair, matching ORT's core-before-JNI load order.
                    elf_records.append(dict(file=label, embedded=True, component='onnxruntime'))
                    continue
                payload = archive.read(entry)
                if 'linux' in entry.filename.lower() and payload.startswith(b'\x7fELF') and payload[18:20] == b'\x3e\x00':
                    if not native.contained(payload):
                        raise ValueError('Embedded JNI bytes are absent from lib/nuvio: ' + entry.filename)
                    with tempfile.NamedTemporaryFile(suffix='.so') as copy:
                        copy.write(payload)
                        copy.flush()
                        check_abi(copy.name, label)
                        linked = subprocess.check_output(['ldd', copy.name], text=True, env=env, stderr=subprocess.STDOUT)
                        if 'not found' in linked:
                            raise ValueError('Unresolved embedded JNI: ' + label + '\n' + linked)
                    elf_records.append(dict(file=label, embedded=True))


def check_image(root, evidence, require_smoke=False, smoke_identity=False):
    """`evidence`: <work>/native-image (native_evidence). `smoke_identity`: the payload must be
    byte-identical to the image the smoke gate launched (DEB); otherwise the executable is."""
    root = root.resolve()
    check_webkit_relocation(root)
    for rel in REQUIRED:
        if not (root / rel).is_file() or (root / rel).stat().st_size == 0:
            raise ValueError('Missing/empty required artifact: ' + rel)
    for rel in ['bin/nuvio', 'bin/nuvio-desktop', 'lib/nuvio', TORRSERVER]:
        if not os.access(root / rel, os.X_OK):
            raise ValueError('Non-executable launcher: ' + rel)
    native = native_evidence.verify(root, evidence)
    embedded, module_resources = native_evidence.embedded_resources(evidence, native)
    # JAR contracts on the exact native-image inputs, then the shipped bytes.
    verify_packaged_backend(root, native.jars)
    backend = verify_native_backend(native.executable)
    onnx = verify_packaged_onnx(root, check_abi, native.jars)
    artwork = verify_packaged_artwork(root, native.jars)
    payloads = check_native_payloads(root, native, embedded, onnx, artwork)
    notices = verify_packaged_notices(root)
    hdr_probe = check_hdr_probe(root)
    info = json.loads((root / 'release-info.json').read_text())
    if not re.fullmatch('[a-f0-9]{40}', info['git_commit']):
        raise ValueError('Missing full source revision')
    if info.get('graalvm_release') != {key: native.manifest['graalvm'][key] for key in native_evidence.RELEASE_KEYS}:
        raise ValueError('Release identity names a different GraalVM than the native-image manifest')
    stamp = (root / 'lib/app/build-info.properties').read_text()
    if f'GIT_COMMIT={info["git_commit"]}\n' not in stamp or f'VERSION_NAME={info["version"]}\n' not in stamp:
        raise ValueError('Inconsistent source/version stamp')
    subprocess.run(['desktop-file-validate', str(root / 'share/applications/com.nuvio.media.desktop.desktop')], check=True)
    desktop_entry = check_desktop_entry((root / 'share/applications/com.nuvio.media.desktop.desktop').read_text(),
                                        'share/applications/com.nuvio.media.desktop.desktop')
    smoke = None
    if require_smoke:
        smoke = native_evidence.check_smoke(
            evidence, root, native_evidence.image_identity(root) if smoke_identity else None)
    records = entries(root)
    elf_records = []
    env = dict(os.environ, LD_LIBRARY_PATH=library_path(root))
    embedded_elf(native, embedded, env, elf_records)
    for rel in records:
        p = root / rel
        if p.is_symlink():
            continue
        if any(part in ['.git', '.aws', '.ssh', '.gradle', '__pycache__'] for part in p.relative_to(root).parts):
            raise ValueError('Development/personal directory: ' + rel)
        if p.name in ['local.properties', 'id_rsa', 'id_ed25519', '.env'] or p.suffix in ['.key', '.pem']:
            raise ValueError('Personal configuration/credential file: ' + rel)
        data = p.read_bytes()
        # Covers lib/nuvio's embedded resources too: native-image stores them verbatim.
        scan_bytes(data, rel)
        if not data.startswith(b'\x7fELF'):
            continue
        if rel.startswith('lib/native/') and PLATFORM.match(p.name):
            raise ValueError('Bundled platform ABI/graphics driver: ' + rel)
        check_abi(p, rel)
        if rel == TORRSERVER:
            # The one static ELF (Go, CGO off): nothing to resolve, and ldd refuses it.
            check_static_torrserver(p)
            elf_records.append(dict(file=rel, static=True))
            continue
        dynamic = subprocess.check_output(['readelf', '-d', str(p)], text=True)
        if (root / 'share/nuvio-webkit/runtime.json').is_file():
            from webkit.contract import ENGINE_FILES, PROCESSES
            if rel in ENGINE_FILES + PROCESSES:
                segments = subprocess.check_output(['readelf', '-lW', str(p)], text=True)
                if 'GNU_RELRO' not in segments or not re.search(r'\b(?:BIND_NOW|NOW)\b', dynamic):
                    raise ValueError('Private engine lacks full RELRO: ' + rel)
                if any('E' in line.split()[-2] for line in segments.splitlines() if line.strip().startswith('GNU_STACK')):
                    raise ValueError('Private engine requests executable stack: ' + rel)
        needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
        for dep in needed:
            if '/' in dep or re.match(r'lib(cuda|nvcuvid|nvidia|cudart)', dep):
                raise ValueError('Absolute/mandatory vendor dependency: ' + rel)
        for rpath in re.findall(r'\((?:RUNPATH|RPATH)\).*?\[(.*?)\]', dynamic):
            if any(item and not item.startswith('$ORIGIN') for item in rpath.split(':')):
                raise ValueError('Non-relocatable ELF search path: ' + rel + ': ' + rpath)
        result = subprocess.check_output(['ldd', str(p)], text=True, env=env, stderr=subprocess.STDOUT)
        if 'not found' in result:
            raise ValueError('Unresolved image dependency: ' + rel + '\n' + result)
        for dep, path in linked_paths(result):
            private_webkit = (root / 'share/nuvio-webkit/runtime.json').is_file() and dep.startswith(
                ('libwebkit', 'libjavascriptcore', 'libgtk-3', 'libgdk-3'))
            if private_webkit or dep in ['libmpv.so.2', 'libplayer_bridge.so'] or dep.startswith(('libav', 'libsw', 'libplacebo')):
                if not pathlib.Path(path).resolve().is_relative_to(root):
                    raise ValueError('Private media resolved outside image: ' + dep)
        elf_records.append(dict(file=rel, needed=needed))
    return dict(source=info, files=records, elf=elf_records, onnx=onnx, artwork=artwork,
                notices=notices, hdr_probe=hdr_probe, native_image=native_summary(native, module_resources),
                backend=backend, native_payloads=payloads, smoke=smoke, desktop_entry=desktop_entry)


def native_summary(native, module_resources):
    graalvm = native.manifest['graalvm']
    return dict(executable=native.manifest['executable'], graalvm=graalvm['GRAALVM_VERSION'],
                java=graalvm['JAVA_RUNTIME_VERSION'], input_jars=len(native.jars),
                non_jar_resources=module_resources,
                scope='Contracts ran on the exact retained native-image input JARs; payload bytes checked in lib/nuvio')


def check_appimage_runtime(work, appimage, root):
    """The AppImage prefix must be the pinned source-built runtime, with its notices and source."""
    runtime = appimage_runtime.verify_output(work / 'appimage-runtime')
    embedding = verify_appimage_runtime(runtime['runtime'], appimage, appimage_runtime.expected_sha256())
    notices = root / appimage_runtime.NOTICE_DIR
    for name in appimage_runtime.required_notices():
        path = notices / name
        if path.is_symlink() or not path.is_file() or path.read_bytes() != (runtime['licenses'] / name).read_bytes():
            raise ValueError('Missing/different AppImage runtime notice: ' + name)
    published = sorted((work / 'artifacts').glob(appimage_runtime.ARCHIVE_PREFIX + '*.tar.gz'))
    if ([p.name for p in published] != [runtime['source_archive'].name]
            or published[0].read_bytes() != runtime['source_archive'].read_bytes()):
        raise ValueError('Missing/ambiguous AppImage runtime source archive in artifacts')
    return dict(embedding, source_archive=published[0].name, notices=appimage_runtime.NOTICE_DIR)


def flatpak_native_image(repository, ref, tree, work, evidence):
    """Rebuild, from the exported bundle's bytes, what native_evidence.verify reads, and verify it."""
    audit = pathlib.Path(repository) / 'native-audit'  # Inside the disposable repository directory.
    shutil.rmtree(audit, ignore_errors=True)
    staging = work / 'flatpak-build/files/lib/nuvio-desktop'

    def cat(relative):
        if tree.get(relative, ' ')[0] != '-':
            raise ValueError('Missing regular file in the Flatpak bundle: ' + relative)
        data = subprocess.check_output(['ostree', '--repo=' + str(repository), 'cat', ref,
                                        FLATPAK_IMAGE + '/' + relative])
        target = audit / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(int(tree[relative][1:], 8) & 0o777)
        return data

    manifest = json.loads(cat(str(native_evidence.PACKAGED_MANIFEST)))
    if cat(native_evidence.EXECUTABLE) != (staging / native_evidence.EXECUTABLE).read_bytes():
        raise ValueError('Flatpak native executable differs from the inspected sandbox')
    for name in manifest['jdk_files']:
        cat(name)
    for name in manifest['graalvm']['notices']:
        cat(str(native_evidence.GRAALVM_NOTICES / name))
    prefix = str(native_evidence.JAR_NOTICES) + '/'
    for name in sorted(n for n, kind in tree.items() if n.startswith(prefix) and kind[0] != 'd'):
        cat(name)
    for name in native_evidence.ANIME_FILES:
        cat(str(native_evidence.ANIME_NOTICES / name))
    return audit, native_evidence.verify(audit, evidence)


def artifacts(work, formats):
    dest = work / 'artifacts'
    evidence = work / 'native-image'
    wanted = {'deb': ['deb'], 'appimage': ['AppImage'], 'native': ['deb', 'AppImage'],
              'flatpak': ['flatpak'], 'all': ['deb', 'AppImage', 'flatpak']}[formats]
    archives = sorted(dest.glob('Nuvio-native-image-inputs-*.tar'))
    if len(archives) != 1:
        raise ValueError('Missing/ambiguous native-image evidence archive in artifacts')
    evidence_archive = native_evidence.check_archive(work, archives[0])
    # Corresponding source of the bundled TorrServer (GPL-3.0), from torrserver/build.py.
    torrserver_sources = sorted(dest.glob('Nuvio-TorrServer-source-*.tar.gz'))
    if len(torrserver_sources) != 1 or torrserver_sources[0].stat().st_size < 4096:
        raise ValueError('Missing/ambiguous TorrServer source archive in artifacts')
    engine_sources = sorted(dest.glob('Nuvio-Engine-source-*.tar.gz'))
    if len(engine_sources) != 1 or engine_sources[0].stat().st_size < 4096:
        raise ValueError('Missing/ambiguous Nuvio Engine source archive in artifacts')
    innertubex_sources = sorted(dest.glob('Nuvio-InnerTubeX-source-*.tar.gz'))
    if len(innertubex_sources) != 1 or innertubex_sources[0].stat().st_size < 4096:
        raise ValueError('Missing/ambiguous InnerTubeX source archive in artifacts')
    for suffix in wanted:
        files = list(dest.glob('*.' + suffix))
        if len(files) != 1 or files[0].stat().st_size < 4096:
            raise ValueError('Missing/ambiguous/empty package: ' + suffix)
        path = files[0]
        if suffix == 'deb':
            subprocess.run(['dpkg-deb', '--info', str(path)], check=True)
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                subprocess.run(['dpkg-deb', '--extract', str(path), tmp], check=True)
                # The DEB payload must be byte-identical to the image the smoke gate launched.
                report = check_image(pathlib.Path(tmp) / 'opt/nuvio-desktop', evidence,
                                     require_smoke=True, smoke_identity=True)
                report['native_image_evidence'] = evidence_archive
                (dest / 'deb-audit.json').write_text(json.dumps(report, indent=2) + '\n')
        elif suffix == 'AppImage':
            # Runtime inspection/extraction only: never executes AppRun/the app.
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                subprocess.run([str(path), '--appimage-extract'], cwd=tmp, check=True, stdout=subprocess.DEVNULL)
                root = pathlib.Path(tmp) / 'squashfs-root'
                if not os.access(root / 'AppRun', os.X_OK):
                    raise ValueError('Missing AppRun')
                payload = root / 'usr/lib/nuvio-desktop'
                check_webkit_relocation(payload, required=True)
                entries(root)  # Include outer AppRun/desktop/icon links in the path audit.
                report = check_image(payload, evidence, require_smoke=True)
                report['native_image_evidence'] = evidence_archive
                if report['source'] != json.loads((work / 'canonical/release-info.json').read_text()):
                    raise ValueError('AppImage source/version differs from canonical image')
                report['appimage_runtime'] = check_appimage_runtime(work, path, root)
                (dest / 'appimage-audit.json').write_text(json.dumps(report, indent=2) + '\n')
        else:
            info = json.loads((work / 'canonical/release-info.json').read_text())
            ref = f'app/{info["application_id"]}/x86_64/{info["channel"]}'
            # Header contract: unsigned CI output, no repo URL/key; only the
            # owner-side signed rebundle subscribes installs to updates.
            runtime_repo = json.loads((pathlib.Path(__file__).resolve().parent /
                                       'flatpak-repo/repo.json').read_text())['runtime_repo']
            bundle = check_unpublished_bundle(path, ref, runtime_repo)
            # Parse/import the actual bundle into a disposable repository, never
            # into a user's Flatpak installation. Rejects malformed static deltas.
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                subprocess.run(['ostree', '--repo=' + tmp, 'init', '--mode=archive-z2'], check=True)
                subprocess.run(['flatpak', 'build-import-bundle', tmp, str(path)], check=True)
                actual_info = subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref,
                                                       '/files/lib/nuvio-desktop/release-info.json'])
                if json.loads(actual_info) != info:
                    raise ValueError('Flatpak source/version differs from canonical image')
                metadata = subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref, '/metadata'])
                if metadata != (work / 'flatpak-build/metadata').read_bytes():
                    raise ValueError('Flatpak exported metadata differs from inspected sandbox')
                # build-export rewrites Exec to `flatpak run ... @@u %u @@`; the link must survive it.
                exported = '/export/share/applications/' + info['application_id'] + '.desktop'
                desktop_entry = check_desktop_entry(subprocess.check_output(
                    ['ostree', '--repo=' + tmp, 'cat', ref, exported]).decode(), 'Flatpak ' + exported)
                hdr_probe = flatpak_hdr_probe(tmp, ref)
                torrserver = flatpak_executable(tmp, ref, TORRSERVER, 'TorrServer')
                # Inspect the exported bytes, not just the canonical input or release
                # stamp: no JAR or JVM may ship, and the exported executable, manifest
                # and notices must be the ones bound to the retained native-image inputs.
                tree = flatpak_tree(tmp, ref)
                image, native = flatpak_native_image(tmp, ref, tree, work, evidence)
                embedded, module_resources = native_evidence.embedded_resources(evidence, native)
                verify_packaged_backend(image, native.jars)
                backend = verify_native_backend(native.executable)
                onnx = verify_packaged_onnx(image, check_abi, native.jars)
                artwork = verify_packaged_artwork(image, native.jars)
                payloads = check_native_payloads(image, native, embedded, onnx, artwork)
                smoke = native_evidence.check_smoke(evidence, image)
                for name in notice_files():
                    target = image / NOTICE_TARGET / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref,
                        FLATPAK_IMAGE + '/' + str(NOTICE_TARGET / name)]))
                notices = verify_packaged_notices(image)
                (dest / 'flatpak-audit.json').write_text(json.dumps(
                    dict(source=info, ref=ref, metadata=metadata.decode(), onnx=onnx, artwork=artwork,
                         notices=notices, hdr_probe=hdr_probe, torrserver=torrserver, bundle=bundle, backend=backend,
                         native_image=native_summary(native, module_resources), native_payloads=payloads,
                         smoke=smoke, native_image_evidence=evidence_archive, desktop_entry=desktop_entry), indent=2) + '\n')
            if not (work / 'flatpak-build/metadata').is_file():
                raise ValueError('Missing exported Flatpak metadata')
    checksums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
                        for p in sorted(dest.iterdir()) if p.is_file() and p.name != 'SHA256SUMS')
    (dest / 'SHA256SUMS').write_text(checksums)
    subprocess.run(['sha256sum', '--check', 'SHA256SUMS'], cwd=dest, check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=['image', 'artifacts'])
    parser.add_argument('path', type=pathlib.Path)
    parser.add_argument('--formats', choices=['deb', 'appimage', 'native', 'flatpak', 'all'], default='all')
    parser.add_argument('--native-evidence', type=pathlib.Path,
                        help='image: <work>/native-image with the retained native-image inputs (required)')
    parser.add_argument('--require-smoke', action='store_true', help='image: require the passing smoke record')
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test environment')
    if args.kind == 'image':
        if args.native_evidence is None:
            parser.error('image verification needs --native-evidence')
        report = check_image(args.path, args.native_evidence, require_smoke=args.require_smoke)
        print(json.dumps(dict(files=len(report['files']), elf=len(report['elf']), source=report['source'])))
    else:
        artifacts(args.path.resolve(), args.formats)
