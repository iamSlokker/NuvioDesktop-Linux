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
from client_config import verify_packaged_backend
from onnx_contract import NATIVES as ONNX_NATIVES, verify_packaged_onnx
from font_contract import verify_packaged_fonts
from artwork_contract import verify_packaged_artwork

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


def flatpak_jar_names(repository, ref):
    directory = '/files/lib/nuvio-desktop/lib/app'
    listing = subprocess.check_output(
        ['ostree', '--repo=' + str(repository), 'ls', ref, directory], text=True)
    names = set()
    for line in listing.splitlines():
        # ostree ls: type+octal-mode, uid, gid, size, path. Split only the
        # metadata columns so literal spaces in a JAR filename are preserved.
        fields = line.split(maxsplit=4)
        if (len(fields) != 5 or not re.fullmatch(r'[dl-][0-7]{5}', fields[0])
                or any(not field.isdigit() for field in fields[1:4])):
            raise ValueError('Unrecognized Flatpak application inventory entry: ' + line)
        if fields[0][0] == 'l' and '.jar' in fields[4]:
            # Symlink formatting uses " -> ", which is also legal filename text.
            # Never let an ambiguous link name hide an additional classpath JAR.
            raise ValueError('Non-regular/duplicate Flatpak JAR: ' + fields[4])
        path = fields[4].split(' -> ', 1)[0] if fields[0][0] == 'l' else fields[4]
        if path == directory:
            continue
        if not path.startswith(directory + '/'):
            raise ValueError('Unexpected Flatpak application inventory path: ' + path)
        name = path[len(directory) + 1:]
        if '/' in name:
            raise ValueError('Unexpected nested Flatpak application inventory path: ' + path)
        if name.endswith('.jar'):
            if fields[0][0] != '-' or name in names:
                raise ValueError('Non-regular/duplicate Flatpak JAR: ' + path)
            names.add(name)
    return names


def check_image(root):
    root = root.resolve()
    check_webkit_relocation(root)
    required = ['bin/nuvio', 'bin/nuvio-desktop', 'lib/runtime/lib/libjawt.so',
                'lib/runtime/lib/server/libjvm.so', 'lib/native/libplayer_bridge.so',
                'lib/native/libmpv.so.2', 'lib/app/build-info.properties', 'release-info.json',
                'share/applications/com.nuvio.media.desktop.desktop',
                'share/icons/hicolor/512x512/apps/com.nuvio.media.desktop.png',
                'share/licenses/Nuvio/COPYING', 'share/licenses/mpv/LICENSE.GPL']
    for rel in required:
        if not (root / rel).is_file() or (root / rel).stat().st_size == 0:
            raise ValueError('Missing/empty required artifact: ' + rel)
    for rel in ['bin/nuvio', 'bin/nuvio-desktop']:
        if not os.access(root / rel, os.X_OK):
            raise ValueError('Non-executable launcher: ' + rel)
    if not list((root / 'lib/app').glob('*.jar')):
        raise ValueError('No application jars')
    verify_packaged_backend(root)
    onnx = verify_packaged_onnx(root, check_abi)
    fonts = verify_packaged_fonts(root)
    artwork = verify_packaged_artwork(root)
    info = json.loads((root / 'release-info.json').read_text())
    if not re.fullmatch('[a-f0-9]{40}', info['git_commit']):
        raise ValueError('Missing full source revision')
    stamp = (root / 'lib/app/build-info.properties').read_text()
    if f'GIT_COMMIT={info["git_commit"]}\n' not in stamp or f'VERSION_NAME={info["version"]}\n' not in stamp:
        raise ValueError('Inconsistent source/version stamp')
    subprocess.run(['desktop-file-validate', str(root / required[8])], check=True)
    records = entries(root)
    elf_records = []
    env = dict(os.environ, LD_LIBRARY_PATH=f'{root}/lib/native:{root}/lib/runtime/lib:{root}/lib/runtime/lib/server')
    for rel in records:
        p = root / rel
        if p.is_symlink():
            continue
        if any(part in ['.git', '.aws', '.ssh', '.gradle', '__pycache__'] for part in p.relative_to(root).parts):
            raise ValueError('Development/personal directory: ' + rel)
        if p.name in ['local.properties', 'id_rsa', 'id_ed25519', '.env'] or p.suffix in ['.key', '.pem']:
            raise ValueError('Personal configuration/credential file: ' + rel)
        data = p.read_bytes()
        if p.suffix == '.jar':
            with zipfile.ZipFile(p) as jar:
                if jar.testzip():
                    raise ValueError('Corrupt jar: ' + rel)
                for entry in jar.infolist():
                    if pathlib.PurePosixPath(entry.filename).is_absolute() or '..' in pathlib.PurePosixPath(entry.filename).parts:
                        raise ValueError('Unsafe jar entry: ' + rel + ':' + entry.filename)
                    if not entry.is_dir():
                        payload = jar.read(entry)
                        label = rel + ':' + entry.filename
                        scan_bytes(payload, label)
                        # Multi-platform jars also contain other architectures.
                        # Inspect only Linux x86_64 payloads that can run here.
                        if entry.filename in ONNX_NATIVES:
                            # Verified above as a pinned pair, matching ORT's core-before-JNI load order.
                            elf_records.append(dict(file=label, embedded=True, component='onnxruntime'))
                        elif 'linux' in entry.filename.lower() and payload.startswith(b'\x7fELF') and payload[18:20] == b'\x3e\x00':
                            with tempfile.NamedTemporaryFile(suffix='.so') as embedded:
                                embedded.write(payload)
                                embedded.flush()
                                check_abi(embedded.name, label)
                                linked = subprocess.check_output(['ldd', embedded.name], text=True, env=env, stderr=subprocess.STDOUT)
                                if 'not found' in linked:
                                    raise ValueError('Unresolved embedded JNI: ' + label + '\n' + linked)
                                elf_records.append(dict(file=label, embedded=True))
        else:
            scan_bytes(data, rel)
        if not data.startswith(b'\x7fELF'):
            continue
        if rel.startswith('lib/native/') and PLATFORM.match(p.name):
            raise ValueError('Bundled platform ABI/graphics driver: ' + rel)
        check_abi(p, rel)
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
    return dict(source=info, files=records, elf=elf_records, onnx=onnx, fonts=fonts, artwork=artwork)


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


def artifacts(work, formats):
    dest = work / 'artifacts'
    wanted = {'deb': ['deb'], 'appimage': ['AppImage'], 'native': ['deb', 'AppImage'],
              'flatpak': ['flatpak'], 'all': ['deb', 'AppImage', 'flatpak']}[formats]
    for suffix in wanted:
        files = list(dest.glob('*.' + suffix))
        if len(files) != 1 or files[0].stat().st_size < 4096:
            raise ValueError('Missing/ambiguous/empty package: ' + suffix)
        path = files[0]
        if suffix == 'deb':
            subprocess.run(['dpkg-deb', '--info', str(path)], check=True)
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                subprocess.run(['dpkg-deb', '--extract', str(path), tmp], check=True)
                report = check_image(pathlib.Path(tmp) / 'opt/nuvio-desktop')
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
                report = check_image(payload)
                if report['source'] != json.loads((work / 'canonical/release-info.json').read_text()):
                    raise ValueError('AppImage source/version differs from canonical image')
                report['appimage_runtime'] = check_appimage_runtime(work, path, root)
                (dest / 'appimage-audit.json').write_text(json.dumps(report, indent=2) + '\n')
        else:
            # Parse/import the actual bundle into a disposable repository, never
            # into a user's Flatpak installation. Rejects malformed static deltas.
            with tempfile.TemporaryDirectory(dir=work) as tmp:
                subprocess.run(['ostree', '--repo=' + tmp, 'init', '--mode=archive-z2'], check=True)
                subprocess.run(['flatpak', 'build-import-bundle', tmp, str(path)], check=True)
                info = json.loads((work / 'canonical/release-info.json').read_text())
                ref = f'app/{info["application_id"]}/x86_64/{info["channel"]}'
                actual_info = subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref,
                                                       '/files/lib/nuvio-desktop/release-info.json'])
                if json.loads(actual_info) != info:
                    raise ValueError('Flatpak source/version differs from canonical image')
                metadata = subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref, '/metadata'])
                if metadata != (work / 'flatpak-build/metadata').read_bytes():
                    raise ValueError('Flatpak exported metadata differs from inspected sandbox')
                # Inspect exported JAR bytes, not just the canonical input or
                # release stamp: empty/inadvertently overridden defaults fail.
                jars = sorted((work / 'flatpak-build/files/lib/nuvio-desktop/lib/app').glob('*.jar'))
                expected_jars = {jar.name for jar in jars}
                actual_jars = flatpak_jar_names(tmp, ref)
                if actual_jars != expected_jars:
                    raise ValueError('Flatpak JAR inventory differs from staging: extra=' +
                                     repr(sorted(actual_jars - expected_jars)) + ' missing=' +
                                     repr(sorted(expected_jars - actual_jars)))
                image = pathlib.Path(tmp) / 'client-audit'
                (image / 'lib/app').mkdir(parents=True)
                for jar in jars:
                    payload = subprocess.check_output(['ostree', '--repo=' + tmp, 'cat', ref,
                        '/files/lib/nuvio-desktop/lib/app/' + jar.name])
                    (image / 'lib/app' / jar.name).write_bytes(payload)
                verify_packaged_backend(image)
                onnx = verify_packaged_onnx(image, check_abi)
                fonts = verify_packaged_fonts(image)
                artwork = verify_packaged_artwork(image)
                (dest / 'flatpak-audit.json').write_text(json.dumps(
                    dict(source=info, ref=ref, metadata=metadata.decode(), onnx=onnx, fonts=fonts, artwork=artwork), indent=2) + '\n')
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
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test environment')
    if args.kind == 'image':
        report = check_image(args.path)
        print(json.dumps(dict(files=len(report['files']), elf=len(report['elf']), source=report['source'])))
    else:
        artifacts(args.path.resolve(), args.formats)
