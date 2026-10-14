#!/usr/bin/env python3
"""Reproducible source build of the AppImage type2 runtime.

Every input is pinned by URL and SHA-256 in appimage-runtime/manifest.json and
verified before use; the toolchain image is digest-pinned Alpine; compilation
runs with --network=none. Output: runtime-x86_64 (+ .debug), a receipt, the
runtime's license notices and a corresponding-source/relink archive.
Never launches an AppImage or touches a display.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile

from fetch import fetch

HERE = pathlib.Path(__file__).resolve().parent
RECIPE = HERE / 'appimage-runtime'
MANIFEST = json.loads((RECIPE / 'manifest.json').read_text())
RUNTIME = 'runtime-x86_64'
DEBUG = RUNTIME + '.debug'
RECEIPT = 'appimage-runtime-receipt.json'
ARCHIVE_PREFIX = 'Nuvio-AppImage-runtime-source-'
# AppDir location of the notices (outside the shared DEB/Flatpak image tree).
NOTICE_DIR = 'usr/share/licenses/appimage-runtime'
BUILD_INPUTS = ['zstd', 'zlib', 'mimalloc', 'libfuse', 'squashfuse', 'type2-runtime']
RECIPE_FILES = ['Containerfile', 'build-runtime.sh', 'relink.sh', 'manifest.json']
KIT_LIBS = ['libsquashfuse.a', 'libsquashfuse_ll.a', 'libzstd.a', 'libz.a', 'libfuse3.a', 'libmimalloc.a']


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def expected_sha256():
    return MANIFEST['runtime_sha256']


def archive_name(runtime_sha):
    return ARCHIVE_PREFIX + runtime_sha[:12] + '.tar.gz'


def required_notices(manifest=MANIFEST):
    names = ['README.txt']
    for key, spec in manifest['sources'].items():
        names += [key + '/' + name for name in spec.get('license_files', [])]
    return sorted(names)


def verify_inputs(inputs, manifest=MANIFEST):
    """Refuse missing, non-regular or modified source inputs."""
    inputs = pathlib.Path(inputs)
    for key, spec in manifest['sources'].items():
        path = inputs / spec['file']
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing AppImage runtime input: ' + key)
        if sha256(path) != spec['sha256']:
            raise ValueError('AppImage runtime input checksum mismatch: ' + key)
    # Cross-check the aports files against the recipe's own sha512sums.
    apkbuild = next((inputs / spec['file'] for key, spec in manifest['sources'].items()
                     if key == 'aports-musl/APKBUILD'), None)
    if apkbuild is not None:
        block = re.search(r'sha512sums="\n(.*?)\n"', apkbuild.read_text(), re.S)
        if not block:
            raise ValueError('APKBUILD without sha512sums')
        listed = dict(reversed(line.split(None, 1)) for line in block.group(1).splitlines() if line.strip())
        local = {pathlib.PurePosixPath(spec['file']).name: inputs / spec['file']
                 for key, spec in manifest['sources'].items() if key.startswith('aports-musl/') or key == 'musl'}
        for name, digest in listed.items():
            if name not in local or hashlib.sha512(local[name].read_bytes()).hexdigest() != digest:
                raise ValueError('musl aports input differs from APKBUILD sha512sums: ' + name)
    return inputs


def fetch_inputs(inputs, manifest=MANIFEST):
    for spec in manifest['sources'].values():
        fetch(spec, pathlib.Path(inputs) / spec['file'])
    return verify_inputs(inputs, manifest)


def image_tag():
    identity = hashlib.sha256((RECIPE / 'Containerfile').read_bytes()).hexdigest()
    return 'localhost/nuvio-appimage-runtime-toolchain:' + identity[:32]


def build_image():
    tag = image_tag()
    base = MANIFEST['image']['base']
    first = next(line for line in (RECIPE / 'Containerfile').read_text().splitlines() if line.startswith('FROM '))
    if first != 'FROM ' + base:
        raise ValueError('Containerfile base differs from manifest pin')
    subprocess.run(['podman', 'build', '--network=host', '--pull=missing', '--platform', 'linux/amd64',
                    '--file', str(RECIPE / 'Containerfile'), '--tag', tag, str(RECIPE)], check=True,
                   stdout=subprocess.DEVNULL)
    return tag, subprocess.check_output(['podman', 'image', 'inspect', '--format', '{{.Id}}', tag], text=True).strip()


def compile_runtime(inputs, stage, image, jobs):
    staged = stage / 'inputs'
    out = stage / 'out'
    staged.mkdir(parents=True)
    out.mkdir()
    for key in BUILD_INPUTS:
        spec = MANIFEST['sources'][key]
        shutil.copyfile(inputs / spec['file'], staged / key)
        if sha256(staged / key) != spec['sha256']:
            raise ValueError('AppImage runtime input changed while staging: ' + key)
    subprocess.run(['podman', 'run', '--rm', '--network=none', '--cap-drop=all',
                    '--security-opt', 'no-new-privileges', '--security-opt', 'label=disable',
                    '--env', 'SOURCE_DATE_EPOCH=' + str(MANIFEST['source_date_epoch']),
                    '--env', 'RUNTIME_VERSION=' + MANIFEST['runtime_version'], '--env', 'JOBS=' + str(jobs),
                    '--mount', f'type=bind,src={staged},dst=/inputs,ro',
                    '--mount', f'type=bind,src={RECIPE},dst=/recipe,ro',
                    '--mount', f'type=bind,src={out},dst=/out', image, 'sh', '/recipe/build-runtime.sh'],
                   check=True, stdout=subprocess.DEVNULL)
    return out


def write_licenses(inputs, dest, runtime_sha):
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    rows = []
    for key, spec in MANIFEST['sources'].items():
        if not spec.get('license_files'):
            continue
        (dest / key).mkdir()
        for name in spec['license_files']:
            (dest / key / name).write_bytes(tar_member(inputs / spec['file'], name))
        rows.append(f"  {key} {spec['version']}: {spec['license']}" +
                    (f"\n    modified: {spec['modification']}" if spec.get('modification') else ''))
    (dest / 'README.txt').write_text(f'''AppImage type2 runtime notices

The ELF runtime at the start of this AppImage (SHA-256 {runtime_sha}) is
built from source by Nuvio from AppImage/type2-runtime {MANIFEST['sources']['type2-runtime']['version'][:12]}
and statically links:
{chr(10).join(rows)}
    (musl as packaged by Alpine musl-dev {MANIFEST['musl']['apk']}; its aports recipe and patches are in the source archive)
  libgcc/crt support objects of GCC (GPL-3.0 with the GCC Runtime Library Exception)

Complete corresponding source, the libfuse patch, the build recipe and
relink materials (runtime.o and every static library) are published next to
the AppImage as {archive_name(runtime_sha)}.
See RELINK.md in that archive to link the runtime against a modified libfuse.
''')


RELINK = '''# Rebuilding and relinking the Nuvio AppImage runtime

This archive is the corresponding source of the AppImage type2 runtime
`runtime-x86_64` (SHA-256 `{sha}`) embedded at the start of Nuvio AppImages.

* `sources/` - every pinned input (URL/SHA-256 in `recipe/tools/linux/packaging/appimage-runtime/manifest.json`):
  type2-runtime, libfuse, squashfuse, zstd, zlib, mimalloc, the upstream musl
  tarball and the Alpine aports musl recipe/patches matching the libc in the image.
* `patches/libfuse/mount.c.diff` - the upstream runtime's libfuse modification.
* `relink/` - `runtime.o`, `data_sections.ld`, `lib/*.a` and `relink.sh`: the
  exact objects the shipped runtime was linked from.
* `licenses/` - notices; `BUILD-RECEIPT.json` - toolchain apk versions and hashes.

The toolchain is `{base}` plus the exact apk versions in
`recipe/tools/linux/packaging/appimage-runtime/Containerfile` (rebuild it with Podman or Docker).
musl libc, crt objects and libgcc come from that image.

## Relink with a modified libfuse (LGPL-2.1 section 6)

```sh
cd {top}
podman build -t nuvio-appimage-runtime-toolchain -f recipe/tools/linux/packaging/appimage-runtime/Containerfile \\
    recipe/tools/linux/packaging/appimage-runtime
# 1. Build a modified static libfuse (edit fuse-3.15.0/ between patch and meson).
podman run --rm --network=none --security-opt label=disable -v "$PWD:/kit" -w /kit \\
    nuvio-appimage-runtime-toolchain sh -euc '
  tar --no-same-owner -xf sources/fuse-3.15.0.tar.xz -C /tmp
  cd /tmp/fuse-3.15.0
  patch -p1 < /kit/patches/libfuse/mount.c.diff
  # ... apply your modifications here ...
  meson setup build --prefix=/usr -Ddefault_library=static -Dutils=false -Dexamples=false -Dtests=false
  ninja -C build lib/libfuse3.a
  cp build/lib/libfuse3.a /kit/relink/lib/libfuse3.a'
# 2. Relink (also strips, adds the debug link and the AppImage magic bytes).
podman run --rm --network=none --security-opt label=disable -v "$PWD:/kit" -w /kit \\
    nuvio-appimage-runtime-toolchain sh relink/relink.sh relink out
# 3. Use out/runtime-x86_64 with appimagetool --runtime-file, or replace the
#    first {size} bytes of an AppImage with it (sizes must match; otherwise
#    rebuild the AppImage from its extracted squashfs-root).
```

Relinking the unmodified kit reproduces `runtime-x86_64` byte for byte.

## Full rebuild from source

```sh
python3 recipe/tools/linux/packaging/appimage_runtime.py build --inputs sources --output out
```

The driver re-verifies every input hash, builds the pinned toolchain image (the
only networked step: apk downloads), compiles with `--network=none` and
compares the result with `runtime_sha256` pinned in manifest.json. Alpine removes
superseded apk versions from its mirrors, so the toolchain image may need the
recorded package files from another archive; the receipt lists them exactly.
'''


def deterministic_tar(output, top, files, epoch):
    """files: {archive-relative name: bytes or path}. Byte-stable gzip tar."""
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.PAX_FORMAT) as tar:
        directories = sorted({str(p) for name in files for p in pathlib.PurePosixPath(name).parents if str(p) != '.'})
        for name in directories:
            info = tarfile.TarInfo(top + '/' + name)
            info.type, info.mode, info.mtime = tarfile.DIRTYPE, 0o755, epoch
            tar.addfile(info)
        for name in sorted(files):
            content = files[name]
            data = content if isinstance(content, bytes) else pathlib.Path(content).read_bytes()
            executable = not isinstance(content, bytes) and os.access(content, os.X_OK)
            info = tarfile.TarInfo(top + '/' + name)
            info.size, info.mtime, info.mode = len(data), epoch, 0o755 if executable else 0o644
            tar.addfile(info, io.BytesIO(data))
    with open(output, 'wb') as stream:
        with gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0, compresslevel=9) as compressed:
            compressed.write(raw.getvalue())


def receipt_from(out, inputs, runtime_sha):
    apk = (out / 'apk-info.txt').read_text().split()
    commits, current = {}, None
    for line in (out / 'apk-commits.txt').read_text().splitlines():
        key, value = line.split(':', 1)
        if key == 'P':
            current = commits.setdefault(value, {})
        else:
            current[{'V': 'version', 'c': 'aports_commit'}[key]] = value
    for name in ['musl', 'musl-dev']:
        if name + '-' + MANIFEST['musl']['apk'] not in apk or commits[name] != dict(
                version=MANIFEST['musl']['apk'], aports_commit=MANIFEST['musl']['aports_commit']):
            raise ValueError('Toolchain musl differs from manifest pin: ' + name)
    linked = sorted({re.sub(r'^/out/relink/', 'relink/', line.strip()) for line in
                     (out / 'linked-inputs.txt').read_text().splitlines() if line.strip().startswith(('/', '('))})
    kit = out / 'relink'
    return {
        'schema_version': 1,
        'runtime': {'file': RUNTIME, 'sha256': runtime_sha, 'size': (out / RUNTIME).stat().st_size,
                    'version_string': MANIFEST['runtime_version']},
        'debug': {'file': DEBUG, 'sha256': sha256(out / DEBUG)},
        'image': dict(MANIFEST['image'], containerfile_sha256=sha256(RECIPE / 'Containerfile')),
        'apk_installed': apk,
        'apk_commits': commits,
        'toolchain': (out / 'toolchain.txt').read_text().splitlines(),
        'musl': MANIFEST['musl'],
        'source_date_epoch': MANIFEST['source_date_epoch'],
        'inputs': {key: {k: spec[k] for k in ['file', 'url', 'sha256', 'role', 'version'] if k in spec}
                   for key, spec in MANIFEST['sources'].items()},
        'linked_inputs': linked,
        'relink_kit': {p.relative_to(kit).as_posix(): sha256(p) for p in sorted(kit.rglob('*')) if p.is_file()},
        'recipe': {name: sha256(RECIPE / name) for name in RECIPE_FILES},
    }


def build(output, inputs, jobs=4, check_pin=True):
    output, inputs = pathlib.Path(output).resolve(), pathlib.Path(inputs).resolve()
    if (output.exists() and not (output / RECEIPT).is_file()
            and any(p.name != '.stage' for p in output.iterdir())):
        raise ValueError('Refusing to replace a non-runtime directory: ' + str(output))
    fetch_inputs(inputs)
    tag, image_id = build_image()
    shutil.rmtree(output, ignore_errors=True)
    stage = output / '.stage'
    stage.mkdir(parents=True)
    out = compile_runtime(inputs, stage, tag, jobs)
    runtime_sha = sha256(out / RUNTIME)
    if check_pin and runtime_sha != expected_sha256():
        raise ValueError(f'Built runtime {runtime_sha} differs from manifest runtime_sha256 pin {expected_sha256()}')
    receipt = receipt_from(out, inputs, runtime_sha)
    licenses = output / 'licenses'
    write_licenses(inputs, licenses, runtime_sha)
    top = archive_name(runtime_sha)[:-len('.tar.gz')]
    files = {'RELINK.md': RELINK.format(sha=runtime_sha, base=MANIFEST['image']['base'], top=top,
                                        size=receipt['runtime']['size']).encode(),
             'BUILD-RECEIPT.json': (json.dumps(receipt, indent=2, sort_keys=True) + '\n').encode(),
             'patches/libfuse/mount.c.diff': tar_member(inputs / MANIFEST['sources']['type2-runtime']['file'],
                                                             'patches/libfuse/mount.c.diff')}
    for spec in MANIFEST['sources'].values():
        files['sources/' + spec['file']] = inputs / spec['file']
    for name in ['appimage_runtime.py', 'fetch.py', 'config.json']:
        files['recipe/tools/linux/packaging/' + name] = HERE / name
    for name in RECIPE_FILES:
        files['recipe/tools/linux/packaging/appimage-runtime/' + name] = RECIPE / name
    for path in sorted((out / 'relink').rglob('*')):
        if path.is_file():
            files['relink/' + path.relative_to(out / 'relink').as_posix()] = path
    for path in sorted(licenses.rglob('*')):
        if path.is_file():
            files['licenses/' + path.relative_to(licenses).as_posix()] = path
    archive = output / archive_name(runtime_sha)
    deterministic_tar(archive, top, files, MANIFEST['source_date_epoch'])
    shutil.copy2(out / RUNTIME, output / RUNTIME)
    shutil.copy2(out / DEBUG, output / DEBUG)
    receipt['image']['image_id'] = image_id
    receipt['source_archive'] = {'file': archive.name, 'sha256': sha256(archive)}
    receipt['licenses'] = {name: sha256(licenses / name) for name in required_notices()}
    (output / RECEIPT).write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    shutil.rmtree(stage)
    return verify_output(output, runtime_sha)


def tar_member(archive, relative):
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            parts = pathlib.PurePosixPath(member.name).parts
            if parts[1:] == pathlib.PurePosixPath(relative).parts and member.isfile():
                return tar.extractfile(member).read()
    raise ValueError(f'{archive.name} lacks {relative}')


def verify_output(directory, expected=None):
    """Validate a built/prebuilt runtime directory against the configured pin."""
    directory = pathlib.Path(directory)
    expected = expected or expected_sha256()
    runtime = directory / RUNTIME
    if runtime.is_symlink() or not runtime.is_file() or sha256(runtime) != expected:
        raise ValueError('AppImage runtime differs from configured SHA-256 ' + expected)
    receipt = json.loads((directory / RECEIPT).read_text())
    if receipt['runtime']['sha256'] != expected:
        raise ValueError('AppImage runtime receipt describes another runtime')
    for name in required_notices():
        path = directory / 'licenses' / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError('Missing AppImage runtime notice: ' + name)
        if receipt['licenses'].get(name) != sha256(path):
            raise ValueError('AppImage runtime notice differs from receipt: ' + name)
    archives = sorted(directory.glob(ARCHIVE_PREFIX + '*.tar.gz'))
    if [p.name for p in archives] != [archive_name(expected)] or receipt['source_archive']['file'] != archives[0].name:
        raise ValueError('Missing/ambiguous AppImage runtime source archive')
    if sha256(archives[0]) != receipt['source_archive']['sha256']:
        raise ValueError('AppImage runtime source archive differs from receipt')
    return dict(runtime=runtime, licenses=directory / 'licenses', source_archive=archives[0], receipt=receipt)


def prepare(destination, inputs, prebuilt=None, jobs=4):
    """Provide a verified runtime directory at destination (build unless reusable)."""
    destination = pathlib.Path(destination)
    if prebuilt:
        prebuilt = pathlib.Path(prebuilt).resolve()
        verify_output(prebuilt)
        if prebuilt != destination.resolve():
            shutil.rmtree(destination, ignore_errors=True)
            shutil.copytree(prebuilt, destination, ignore=shutil.ignore_patterns('.stage'))
        return verify_output(destination)
    try:
        return verify_output(destination)
    except (OSError, ValueError, KeyError, json.JSONDecodeError):
        return build(destination, inputs, jobs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    b = commands.add_parser('build')
    b.add_argument('--output', type=pathlib.Path, required=True)
    b.add_argument('--inputs', type=pathlib.Path, help='Input cache (default: OUTPUT/../appimage-runtime-sources)')
    b.add_argument('--jobs', type=int, default=4)
    b.add_argument('--no-pin-check', action='store_true', help='Report, do not enforce, the manifest runtime_sha256')
    v = commands.add_parser('verify')
    v.add_argument('directory', type=pathlib.Path)
    args = parser.parse_args()
    if args.command == 'build':
        inputs = args.inputs or args.output.resolve().parent / 'appimage-runtime-sources'
        result = build(args.output, inputs, args.jobs, check_pin=not args.no_pin_check)
    else:
        result = verify_output(args.directory)
    print(json.dumps({'runtime_sha256': result['receipt']['runtime']['sha256'],
                      'source_archive': result['source_archive'].name}))


if __name__ == '__main__':
    main()
