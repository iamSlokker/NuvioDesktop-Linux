#!/usr/bin/env python3
"""Build TorrServer (YouROK, Go, GPL-3.0) for the Linux packages, inside the pinned builder.

The desktop P2P backend (TorrServerP2pBackend.kt) runs TorrServer as a child process and finds a
packaged copy through PackagedResources: `<dir of lib/nuvio>/resources/torrserver/linux-amd64/
TorrServer`. It is the other engine choice in Settings, beside Nuvio Engine (nuvio_engine/build.py).

Runs in native-build.sh's build phase (network for Go modules). Every input is pinned by SHA-256
(manifest.json); Go modules are verified against the tag's go.sum and the Go checksum database,
vendored, and the binary is built from that vendor tree only, so the source archive written here
is exactly what was compiled:

  <work>/torrserver/TorrServer                  static linux/amd64 executable (CGO off)
  <work>/torrserver/licenses/                   TorrServer, Go and vendored module notices
  <work>/torrserver/Nuvio-TorrServer-source.tar.gz
                                                the pinned upstream tarball (original bytes),
                                                the vendored modules and the exact build steps
  <work>/torrserver/build.json                  versions, hashes, modules without a notice file

Before anything ships, the binary must start on a loopback port and answer /echo with its version.
"""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from fetch import fetch  # noqa: E402

MANIFEST = json.loads((HERE / 'manifest.json').read_text())
SOURCE_ARCHIVE = 'Nuvio-TorrServer-source.tar.gz'
# The names `go mod vendor` copies from a module root next to each vendored package.
NOTICE_PREFIXES = ('license', 'licence', 'copying', 'copyright', 'copyleft', 'notice', 'patents', 'legal')

BUILD_TXT = """TorrServer {version} as built for Nuvio Desktop (Linux x86_64)

upstream/{tarball}   the tag archive from {url}
                     (SHA-256 {source_sha256}), unmodified
server-vendor/       the Go modules the build used: `go mod vendor` in server/, checked
                     against the tag's go.sum and the Go checksum database
manifest.json        the pinned inputs

Rebuild with Go {go_version} ({go_url}, SHA-256 {go_sha256}):

  tar -xzf upstream/{tarball}
  cp -r server-vendor {top}/server/vendor
  cd {top}/server
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOAMD64=v1 GOFLAGS= GOTOOLCHAIN=local \\
    go build -mod=vendor -trimpath -buildvcs=false -tags={tags} \\
    -ldflags='{ldflags}' -o TorrServer {package}

This is upstream's own release build (ts_release.yml) with the Go version pinned, the vendored
modules and an empty build ID. Nuvio ships the result as
lib/resources/torrserver/linux-amd64/TorrServer and starts it as a separate process on 127.0.0.1;
it replaces that file with a modified build of the same name without any other change.
"""


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def extract(archive, destination, top):
    """Unpack a tarball whose members all live under `top`; no links that leave it, no devices."""
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or path.parts[0] != top:
                raise ValueError(f'Unexpected path in {archive.name}: {member.name}')
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError(f'Special file in {archive.name}: {member.name}')
            if member.issym() or member.islnk():
                target = PurePosixPath(member.linkname)
                resolved = PurePosixPath(os.path.normpath(target if member.islnk() else path.parent / target))
                if target.is_absolute() or resolved.parts[0] != top:
                    raise ValueError(f'Escaping link in {archive.name}: {member.name}')
        # Python's own extraction filter as well, where it exists (3.10.12+, 3.12+).
        options = {'filter': 'data'} if hasattr(tarfile, 'data_filter') else {}
        tar.extractall(destination, members=members, **options)
    return destination / top


def go_environment(work, go_root):
    environment = {key: value for key, value in os.environ.items() if not key.startswith('GO')}
    environment.update(
        PATH=f'{go_root}/bin:{environment.get("PATH", "/usr/bin:/bin")}',
        GOROOT=str(go_root), GOPATH=str(work / 'gopath'), GOCACHE=str(work / 'gocache'),
        GOTOOLCHAIN='local', GOFLAGS='-modcacherw', CGO_ENABLED='0',
        GOOS='linux', GOARCH='amd64', GOAMD64='v1',
        # Upstream defaults, stated so a runner's environment cannot redirect them.
        GOPROXY='https://proxy.golang.org,direct', GOSUMDB='sum.golang.org', GONOSUMDB='',
        GONOSUMCHECK='', GOPRIVATE='', GOINSECURE='')
    return environment


def build_command(output):
    build = MANIFEST['build']
    return ['go', 'build', '-mod=vendor', '-trimpath', '-buildvcs=false', '-tags=' + build['tags'],
            '-ldflags=' + build['ldflags'], '-o', str(output), build['package']]


def check_static(binary):
    """CGO off gives a static executable: no interpreter and no dynamic section to resolve."""
    header = subprocess.check_output(['readelf', '-h', str(binary)], text=True)
    if 'ELF64' not in header or 'Advanced Micro Devices X86-64' not in header:
        raise ValueError('TorrServer is not a linux/amd64 ELF')
    segments = subprocess.check_output(['readelf', '-lW', str(binary)], text=True)
    if 'INTERP' in segments or 'DYNAMIC' in segments:
        raise ValueError('TorrServer is dynamically linked; it must be built with CGO_ENABLED=0')


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def self_test(binary, timeout=60):
    """Start the binary the way TorrServerP2pBackend does and require /echo to name the version."""
    port = free_port()
    base = f'http://127.0.0.1:{port}'
    with tempfile.TemporaryDirectory() as config, open(Path(config) / 'console.log', 'w+b') as console:
        # Its console goes to a file: a full pipe would stall the server mid-test.
        process = subprocess.Popen([str(binary), '--port', str(port), '--ip', '127.0.0.1', '--path', config],
                                   cwd=config, stdout=console, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    console.seek(0)
                    raise ValueError(f'TorrServer exited on startup ({process.returncode}):\n'
                                     + console.read().decode(errors='replace')[-4000:])
                try:
                    with urllib.request.urlopen(base + '/echo', timeout=5) as response:
                        echo = response.read().decode().strip()
                    break
                except OSError:
                    time.sleep(0.5)
            else:
                raise ValueError('TorrServer did not answer /echo within %d s' % timeout)
            if MANIFEST['version'] not in echo:
                raise ValueError(f'TorrServer /echo names {echo!r}, expected {MANIFEST["version"]}')
            try:
                urllib.request.urlopen(base + '/shutdown', timeout=5).close()
            except OSError:
                pass
            process.wait(timeout=15)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
    return echo


def vendored_modules(vendor):
    """Module paths from vendor/modules.txt (`# path version [=> replacement version]`)."""
    modules = []
    for line in (vendor / 'modules.txt').read_text().splitlines():
        if line.startswith('# '):
            modules.append(line[2:].split()[0])
    if not modules:
        raise ValueError('vendor/modules.txt lists no modules')
    return modules


def is_notice(name):
    return name.lower().startswith(NOTICE_PREFIXES)


def collect_notices(source, vendor, go_root, licenses):
    """TorrServer's licence, Go's (its runtime and standard library are in the binary) and every
    notice file `go mod vendor` kept. Returns the vendored modules that have none."""
    shutil.rmtree(licenses, ignore_errors=True)
    licenses.mkdir(parents=True)
    shutil.copy2(source / 'LICENSE', licenses / 'LICENSE')
    for name in ('LICENSE', 'PATENTS'):
        (licenses / 'go').mkdir(exist_ok=True)
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
        f'TorrServer {MANIFEST["version"]} ({MANIFEST["homepage"]}), licensed under the GNU GPL\n'
        'version 3 (LICENSE). Nuvio runs it as a separate program. Its complete corresponding\n'
        'source, with the Go modules it was built from and the build steps, is the release asset\n'
        'NuvioDesktop-Linux-<version>-source-torrserver.tar.gz.\n\n'
        'go/: the Go toolchain licence (its runtime and standard library are in the executable).\n'
        'vendor/: the licence and notice files of the Go modules compiled in (modules.txt lists\n'
        'them all).\n')
    return missing


def add_tree(tar, root, prefix, epoch):
    """Deterministic members: sorted, fixed owner and time, modes reduced to 0644/0755."""
    for path in [root, *sorted(root.rglob('*'))]:
        info = tarfile.TarInfo(prefix if path == root else f'{prefix}/{path.relative_to(root).as_posix()}')
        info.mtime, info.uid, info.gid, info.uname, info.gname = epoch, 0, 0, '', ''
        if path.is_symlink():
            raise ValueError('Symbolic link in TorrServer source archive input: ' + str(path))
        if path.is_dir():
            info.type, info.mode = tarfile.DIRTYPE, 0o755
            tar.addfile(info)
        else:
            info.size = path.stat().st_size
            info.mode = 0o755 if os.access(path, os.X_OK) else 0o644
            with path.open('rb') as stream:
                tar.addfile(info, stream)


def add_bytes(tar, name, data, epoch, mode=0o644):
    info = tarfile.TarInfo(name)
    info.size, info.mode, info.mtime, info.uid, info.gid, info.uname, info.gname = len(data), mode, epoch, 0, 0, '', ''
    tar.addfile(info, io.BytesIO(data))


def archive_source(output, upstream, vendor, epoch):
    source = MANIFEST['source']
    text = BUILD_TXT.format(version=MANIFEST['version'], tarball=upstream.name, url=source['url'],
                            source_sha256=source['sha256'], go_version=MANIFEST['go']['version'],
                            go_url=MANIFEST['go']['url'], go_sha256=MANIFEST['go']['sha256'],
                            top=source['top'], **MANIFEST['build'])
    with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, 'BUILD.txt', text.encode(), epoch)
        add_bytes(tar, 'manifest.json', (HERE / 'manifest.json').read_bytes(), epoch)
        info = tarfile.TarInfo('upstream/' + upstream.name)
        info.size, info.mode, info.mtime, info.uid, info.gid, info.uname, info.gname = (
            upstream.stat().st_size, 0o644, epoch, 0, 0, '', '')
        with upstream.open('rb') as stream:
            tar.addfile(info, stream)
        add_tree(tar, vendor, 'server-vendor', epoch)


def build(work):
    root = work / 'torrserver'
    shutil.rmtree(root, ignore_errors=True)
    scratch = root / 'build'
    epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '0'))
    upstream = fetch(MANIFEST['source'], scratch / 'downloads' / (MANIFEST['source']['top'] + '.tar.gz'))
    toolchain = fetch(MANIFEST['go'], scratch / 'downloads' / Path(MANIFEST['go']['url']).name)
    go_root = extract(toolchain, scratch, 'go')
    source = extract(upstream, scratch / 'src', MANIFEST['source']['top'])
    server = source / 'server'
    if (server / 'vendor').exists():
        raise ValueError('The pinned TorrServer source already carries a vendor tree')
    environment = go_environment(scratch, go_root)
    reported = subprocess.check_output(['go', 'env', 'GOVERSION'], env=environment, text=True).strip()
    if reported != MANIFEST['go']['version']:
        raise ValueError(f'Go toolchain reports {reported}, expected {MANIFEST["go"]["version"]}')
    subprocess.run(['go', 'mod', 'download'], cwd=server, env=environment, check=True)
    subprocess.run(['go', 'mod', 'verify'], cwd=server, env=environment, check=True)
    subprocess.run(['go', 'mod', 'vendor'], cwd=server, env=environment, check=True)
    binary = root / 'TorrServer'
    # From here the module cache is not needed: the build must use the vendor tree only.
    offline = dict(environment, GOPROXY='off', GOFLAGS='-mod=vendor')
    subprocess.run(build_command(binary), cwd=server, env=offline, check=True)
    binary.chmod(0o755)
    check_static(binary)
    echo = self_test(binary)
    print(f'TORRSERVER_ECHO_PASS {echo}', flush=True)
    missing = collect_notices(source, server / 'vendor', go_root, root / 'licenses')
    archive = root / SOURCE_ARCHIVE
    archive_source(archive, upstream, server / 'vendor', epoch)
    record = dict(schema_version=1, version=MANIFEST['version'], go=MANIFEST['go']['version'],
                  echo=echo, binary_sha256=sha256(binary), source_archive_sha256=sha256(archive),
                  upstream_sha256=MANIFEST['source']['sha256'],
                  modules=len(vendored_modules(server / 'vendor')), modules_without_notice=missing)
    (root / 'build.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    if missing:
        print('TorrServer modules without a notice file: ' + ', '.join(missing), flush=True)
    # The toolchain, module cache and build cache (~1 GB) are not outputs.
    shutil.rmtree(scratch)
    return record


if __name__ == '__main__':
    print(json.dumps(build(Path(sys.argv[1]).resolve()), indent=2))
