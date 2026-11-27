#!/usr/bin/env python3
"""Build the built-in Usenet engine (NuvioMedia/nuvio-usenet-engine, Go + cgo, GPL-3.0) for the Linux
packages, inside the pinned builder.

Settings > Usenet plays a release through this engine, run as a child process
(features/usenet/UsenetEngine.kt): lib/native/nuvio-usenet. The source is the copy the app itself
carries, composeApp/src/desktopMain/native/nuvio-usenet-engine (manifest.json's source.path): the
engine at the commit the Windows app pins (usenetExpectedCommit in composeApp/build.gradle.kts)
plus the Windows fork's own changes, which live only there (its NOTICE.md lists them). Umbra
vendored it in df0c02b2; Windows builds the same directory.

Runs in native-build.sh's build phase (network for Go modules). The engine source is the source
snapshot's own; Go is pinned by SHA-256 (manifest.json). RapidYenc's static library is compiled
here from the engine's own third_party/rapidyenc-native, as its Android build does, and replaces
the prebuilt archive the tree carries for Linux (in a scratch copy: the snapshot is not changed).
Go modules are verified against go.sum and the Go checksum database, vendored, and the binary is
built from that vendor tree only. libstdc++ and libgcc are linked in, so the executable needs only
the C library:

  <work>/usenet/nuvio-usenet                   linux/amd64 executable (needs libc.so.6 only)
  <work>/usenet/licenses/                      the engine's, Go's and every vendored module's notices
  <work>/usenet/Nuvio-Usenet-source.tar.gz     the engine tree as the app carries it, the vendored
                                               modules and the exact build steps

Before anything ships, the binary must start the way UsenetEngine does, report protocol 1 on
127.0.0.1, refuse a request without the token, and stop when its stdin closes.
"""
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import gzip
import hashlib
import tarfile
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(HERE.parent))
from fetch import fetch  # noqa: E402
from torrserver.build import add_bytes, add_tree, extract, go_environment, is_notice, sha256, vendored_modules  # noqa: E402

MANIFEST = json.loads((HERE / 'manifest.json').read_text())
SOURCE = REPO / MANIFEST['source']['path']
SOURCE_ARCHIVE = 'Nuvio-Usenet-source.tar.gz'
EXECUTABLE = 'nuvio-usenet'
PREBUILT = 'third_party/rapidyenc/librapidyenc_linux_amd64.a'
# The C library alone: libstdc++ and libgcc are linked statically (manifest's -extldflags).
ALLOWED_NEEDED = {'libc.so.6'}

BUILD_TXT = """nuvio-usenet-engine {version} with Nuvio Desktop's changes, as built for Nuvio Desktop
(Linux x86_64)

{top}/   the engine source exactly as Nuvio Desktop carries it ({path}):
                      {homepage} at {version}
                      plus the changes its NOTICE.md lists under "NuvioDesktop fork changes"
                      (all files SHA-256 {tree_sha256}, see build.json)
vendor/               the Go modules the build used: `go mod vendor`, checked against go.sum and
                      the Go checksum database
manifest.json         the pinned inputs

Rebuild with Go {go_version} ({go_url}, SHA-256 {go_sha256}),
CMake and a C++ compiler:

  cd {top}
  cmake -S third_party/rapidyenc-native -B rapidyenc-build -DCMAKE_BUILD_TYPE=Release \\
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DDISABLE_SHARED=ON -DDISABLE_TOOL=ON -DDISABLE_CRC=ON
  cmake --build rapidyenc-build --target rapidyenc_static
  cp rapidyenc-build/rapidyenc_static/librapidyenc.a {prebuilt}
  cp -r ../vendor vendor
  CGO_ENABLED=1 GOOS=linux GOARCH=amd64 GOAMD64=v1 GOFLAGS= GOTOOLCHAIN=local \\
    go build -mod=vendor -trimpath -buildvcs=false -ldflags="{ldflags}" -o nuvio-usenet {package}

RapidYenc is rebuilt from the checkout's own third_party/rapidyenc-native (as its Android build
does) in place of the prebuilt Linux archive. Nuvio ships the result as lib/native/nuvio-usenet and
starts it as a separate process on 127.0.0.1; it replaces that file with a modified build of the
same name without any other change.
"""


def rapidyenc_command(source, build):
    return ['cmake', '-S', str(source / 'third_party/rapidyenc-native'), '-B', str(build), '-G', 'Ninja',
            '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DDISABLE_SHARED=ON',
            '-DDISABLE_TOOL=ON', '-DDISABLE_CRC=ON',
            f'-DCMAKE_CXX_FLAGS=-ffile-prefix-map={source}=.', f'-DCMAKE_C_FLAGS=-ffile-prefix-map={source}=.']


def build_command(output):
    build = MANIFEST['build']
    return ['go', 'build', '-mod=vendor', '-trimpath', '-buildvcs=false', '-ldflags=' + build['ldflags'],
            '-o', str(output), build['package']]


def cgo_environment(work, go_root, source):
    environment = go_environment(work, go_root)
    for name in ('CGO_CFLAGS', 'CGO_CPPFLAGS', 'CGO_CXXFLAGS', 'CGO_LDFLAGS', 'CPATH', 'C_INCLUDE_PATH',
                 'LIBRARY_PATH', 'PKG_CONFIG_PATH'):
        environment.pop(name, None)
    environment.update(CGO_ENABLED='1', CC='gcc', CXX='g++',
                       CGO_CFLAGS=f'-O2 -ffile-prefix-map={source}=.')
    return environment


def check_dynamic(binary):
    """A linux/amd64 executable whose only shared library is the C library."""
    header = subprocess.check_output(['readelf', '-h', str(binary)], text=True)
    if 'ELF64' not in header or 'Advanced Micro Devices X86-64' not in header:
        raise ValueError('nuvio-usenet is not a linux/amd64 ELF')
    dynamic = subprocess.check_output(['readelf', '-d', str(binary)], text=True)
    needed = {line.split('[', 1)[1].rstrip(']') for line in dynamic.splitlines() if '(NEEDED)' in line}
    if not needed <= ALLOWED_NEEDED:
        raise ValueError(f'nuvio-usenet needs more than the C library: {sorted(needed)}')
    if '(RPATH)' in dynamic or '(RUNPATH)' in dynamic:
        raise ValueError('nuvio-usenet carries a library search path')
    return sorted(needed)


def self_test(binary, timeout=15):
    """Start the engine as UsenetEngine.start does: a bootstrap line on stdin, the ready record on stdout."""
    token = secrets.token_hex(16)
    with tempfile.TemporaryDirectory() as cache:
        process = subprocess.Popen([str(binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL)
        try:
            bootstrap = dict(token=token, nzbCacheDir=cache, memoryMiB=96, certificates=[])
            process.stdin.write((json.dumps(bootstrap) + '\n').encode())
            process.stdin.flush()
            line = process.stdout.readline()
            if not line:
                raise ValueError(f'nuvio-usenet exited on startup ({process.wait(timeout)})')
            record = json.loads(line)
            if record.get('protocol') != 1 or not 0 < int(record.get('port', 0)) < 65536:
                raise ValueError(f'nuvio-usenet ready record is {record!r}')
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{record["port"]}/sessions', timeout=5).close()
                raise ValueError('nuvio-usenet answered a control request without the token')
            except urllib.error.HTTPError as refused:
                if refused.code != 401:
                    raise ValueError(f'nuvio-usenet answered {refused.code} without the token') from refused
            # Nuvio's end of the pipe closing is what stops the engine.
            started = time.monotonic()
            process.stdin.close()
            code = process.wait(timeout)
            if code != 0:
                raise ValueError(f'nuvio-usenet exited {code} when its stdin closed')
            return dict(protocol=1, stopped_seconds=round(time.monotonic() - started, 2))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def collect_notices(source, vendor, go_root, licenses):
    """The engine's licence and provenance, Go's, and every notice file `go mod vendor` kept (the
    replaced modules of third_party included). Returns the vendored modules that have none."""
    shutil.rmtree(licenses, ignore_errors=True)
    licenses.mkdir(parents=True)
    shutil.copy2(source / 'LICENSE', licenses / 'LICENSE')
    shutil.copy2(source / 'NOTICE.md', licenses / 'NOTICE.md')
    shutil.copytree(source / 'licenses', licenses / 'engine')
    (licenses / 'rapidyenc').mkdir()
    shutil.copy2(source / 'third_party/rapidyenc/LICENSE', licenses / 'rapidyenc/LICENSE')
    for path in sorted((source / 'third_party/rapidyenc-native').iterdir()):
        if path.is_file() and is_notice(path.name):
            shutil.copy2(path, licenses / 'rapidyenc' / ('native-' + path.name))
    (licenses / 'go').mkdir()
    for name in ('LICENSE', 'PATENTS'):
        shutil.copy2(go_root / name, licenses / 'go' / name)
    for path in sorted(vendor.rglob('*')):
        if path.is_file() and is_notice(path.name):
            target = licenses / 'vendor' / path.relative_to(vendor)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    shutil.copy2(vendor / 'modules.txt', licenses / 'modules.txt')
    covered = {p.parent.relative_to(licenses / 'vendor').as_posix()
               for p in (licenses / 'vendor').rglob('*') if p.is_file()} if (licenses / 'vendor').is_dir() else set()
    missing = [module for module in vendored_modules(vendor)
               if not any(path == module or path.startswith(module + '/') for path in covered)]
    (licenses / 'NOTICE.txt').write_text(
        f'nuvio-usenet-engine {MANIFEST["version"]} ({MANIFEST["homepage"]}) with Nuvio\n'
        "Desktop's changes, licensed under the GNU GPL version 3 (LICENSE); NOTICE.md and engine/ are\n"
        'its provenance, the changes and the adapted notices.\n'
        'Nuvio runs it as a separate program. Its complete corresponding source, with the Go modules\n'
        'it was built from and the build steps, is the release asset ending in -source-usenet.tar.gz.\n\n'
        'rapidyenc/: the yEnc wrapper and kernels, compiled from the checkout.\n'
        'go/: the Go toolchain licence (its runtime and standard library are in the executable).\n'
        'vendor/: the licence and notice files of the Go modules compiled in (modules.txt lists\n'
        'them all). libstdc++ and libgcc are linked in under the GCC Runtime Library Exception.\n')
    return missing


def check_tree(tree):
    """The engine directory of the source snapshot: the command, its module, no links, no vendor
    tree, and a NOTICE.md naming the commit it was carried onto."""
    if not (tree / 'cmd/nuvio-usenet').is_dir() or not (tree / 'go.mod').is_file():
        raise ValueError(f'No nuvio-usenet-engine sources under {tree}')
    if (tree / 'vendor').exists():
        raise ValueError('The engine source already carries a vendor tree')
    links = [path for path in tree.rglob('*') if path.is_symlink()]
    if links:
        raise ValueError(f'Symbolic link in the engine source: {links[0]}')
    if MANIFEST['version'][:7] not in (tree / 'NOTICE.md').read_text(encoding='utf-8'):
        raise ValueError(f'NOTICE.md does not name {MANIFEST["version"][:7]}, the commit manifest.json pins')


def tree_digest(tree):
    """SHA-256 over every file's path and SHA-256, in path order: one value for the whole tree."""
    lines = ''.join(f'{sha256(path)}  {path.relative_to(tree).as_posix()}\n'
                    for path in sorted(tree.rglob('*')) if path.is_file())
    return hashlib.sha256(lines.encode()).hexdigest()


def archive_source(output, tree, vendor, epoch):
    source = MANIFEST['source']
    text = BUILD_TXT.format(version=MANIFEST['version'], homepage=MANIFEST['homepage'], path=source['path'],
                            tree_sha256=tree_digest(tree), go_version=MANIFEST['go']['version'],
                            go_url=MANIFEST['go']['url'], go_sha256=MANIFEST['go']['sha256'],
                            top=source['top'], prebuilt=PREBUILT, **MANIFEST['build'])
    with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, 'BUILD.txt', text.encode(), epoch)
        add_bytes(tar, 'manifest.json', (HERE / 'manifest.json').read_bytes(), epoch)
        add_tree(tar, tree, source['top'], epoch)
        add_tree(tar, vendor, 'vendor', epoch)


def build(work):
    root = work / 'usenet'
    shutil.rmtree(root, ignore_errors=True)
    scratch = root / 'build'
    epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '0'))
    check_tree(SOURCE)
    toolchain = fetch(MANIFEST['go'], scratch / 'downloads' / Path(MANIFEST['go']['url']).name)
    go_root = extract(toolchain, scratch, 'go')
    # A scratch copy: RapidYenc's rebuilt archive and the vendor tree go there, not into the snapshot.
    source = scratch / 'src' / MANIFEST['source']['top']
    shutil.copytree(SOURCE, source)
    rapidyenc = scratch / 'rapidyenc'
    subprocess.run(rapidyenc_command(source, rapidyenc), check=True)
    subprocess.run(['cmake', '--build', str(rapidyenc), '--target', 'rapidyenc_static'], check=True)
    shutil.copy2(rapidyenc / 'rapidyenc_static/librapidyenc.a', source / PREBUILT)
    environment = cgo_environment(scratch, go_root, source)
    reported = subprocess.check_output(['go', 'env', 'GOVERSION'], env=environment, text=True).strip()
    if reported != MANIFEST['go']['version']:
        raise ValueError(f'Go toolchain reports {reported}, expected {MANIFEST["go"]["version"]}')
    subprocess.run(['go', 'mod', 'download'], cwd=source, env=environment, check=True)
    subprocess.run(['go', 'mod', 'verify'], cwd=source, env=environment, check=True)
    subprocess.run(['go', 'mod', 'vendor'], cwd=source, env=environment, check=True)
    binary = root / EXECUTABLE
    # From here the module cache is not needed: the build must use the vendor tree only.
    offline = dict(environment, GOPROXY='off', GOFLAGS='-mod=vendor')
    subprocess.run(build_command(binary), cwd=source, env=offline, check=True)
    binary.chmod(0o755)
    needed = check_dynamic(binary)
    started = self_test(binary)
    print(f'USENET_ENGINE_PASS {MANIFEST["version"][:12]} stopped={started["stopped_seconds"]}s', flush=True)
    missing = collect_notices(source, source / 'vendor', go_root, root / 'licenses')
    archive = root / SOURCE_ARCHIVE
    archive_source(archive, SOURCE, source / 'vendor', epoch)
    record = dict(schema_version=2, version=MANIFEST['version'], go=MANIFEST['go']['version'], needed=needed,
                  binary_sha256=sha256(binary), source_archive_sha256=sha256(archive),
                  source_path=MANIFEST['source']['path'], source_tree_sha256=tree_digest(SOURCE),
                  modules=len(vendored_modules(source / 'vendor')), modules_without_notice=missing)
    (root / 'build.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    if missing:
        print('Usenet engine modules without a notice file: ' + ', '.join(missing), flush=True)
    # The toolchain, module cache and build cache are not outputs.
    shutil.rmtree(scratch)
    return record


if __name__ == '__main__':
    print(json.dumps(build(Path(sys.argv[1]).resolve()), indent=2))
