#!/usr/bin/env python3
"""Build Nuvio Engine (NuvioMedia/nuvio-engine, GPL-3.0) for the Linux packages, inside the pinned builder.

The same in-process engine the Windows app uses, as lib/native/libnuvio_engine.so with the JNI bridge
(composeApp/src/desktopMain/native/nuvio-engine). TorrServer stays as the other choice in Settings.

Runs in native-build.sh's build phase (network for the inputs). The engine tag and Boost are pinned by
SHA-256 here; the engine's cmake/Dependencies.cmake pins libtorrent by commit, which this checks against
manifest.json. OpenSSL is the system libssl.so.3 that FFmpeg already uses in every format.

  <work>/nuvio-engine/libnuvio_engine.so
  <work>/nuvio-engine/licenses/                  Engine, libtorrent, Boost notices
  <work>/nuvio-engine/Nuvio-Engine-source.tar.gz the tag, the desktop patches, the patched libtorrent, Boost
"""
import gzip
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from fetch import fetch  # noqa: E402
from torrserver.build import add_bytes, add_tree, extract  # noqa: E402

MANIFEST = json.loads((HERE / 'manifest.json').read_text())
REPOSITORY = HERE.parents[3]
DESKTOP = REPOSITORY / 'composeApp/src/desktopMain/native/nuvio-engine'
EXPORT_PREFIXES = ('Java_com_nuvio_engine_internal_NativeBridge_', 'nuvio_engine_')
BUILD_TXT = """Nuvio Engine {version} as built for the Linux packages.

upstream/{engine}   the tag archive of {homepage}
upstream/{boost}    Boost headers, from {boost_url}
libtorrent/         libtorrent at {commit} with the engine's cmake/patches applied, as compiled
desktop/            the JNI bridge, the CMake project and the desktop patches applied to the tag

Rebuild: tools/linux/packaging/nuvio_engine/build.py in the application source, or by hand:
  apply desktop/patches/*.patch to the tag, then
  cmake -S desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \\
    -DNUVIO_ENGINE_ROOT=<tag> -DNUVIO_JNI_INCLUDE_DIR=<jdk>/include
  cmake --build build
"""


def check_libtorrent_pin(engine):
    text = (engine / 'cmake/Dependencies.cmake').read_text()
    if f"GIT_TAG {MANIFEST['libtorrent_commit']}" not in text:
        raise ValueError('cmake/Dependencies.cmake pins another libtorrent than manifest.json')
    if MANIFEST['boost']['sha256'] not in text:
        raise ValueError('cmake/Dependencies.cmake pins another Boost than manifest.json')


def apply_patches(engine):
    for patch in sorted((DESKTOP / 'patches').glob('*.patch')):
        subprocess.run(['git', 'apply', '--whitespace=nowarn', str(patch)], cwd=engine, check=True)


def configure_command(engine, build, deps, boost, jni_include):
    # libtorrent is cloned at the pinned commit. Boost comes from the tarball fetched above, so that
    # exact file goes into the source archive.
    return ['cmake', '-S', str(DESKTOP), '-B', str(build), '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
            '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', f'-DNUVIO_ENGINE_ROOT={engine}',
            f'-DNUVIO_JNI_INCLUDE_DIR={jni_include}', f'-DFETCHCONTENT_BASE_DIR={deps}',
            f'-DFETCHCONTENT_SOURCE_DIR_NUVIO_BOOST={boost}']


def check_exports(library):
    """The JNI bridge must be visible and nothing else leak (libtorrent and Boost stay hidden)."""
    output = subprocess.check_output(['nm', '-D', '--defined-only', str(library)], text=True)
    names = [line.split()[-1] for line in output.splitlines() if line.split()]
    if not any(name.startswith(EXPORT_PREFIXES[0]) for name in names):
        raise ValueError('libnuvio_engine.so exports no JNI bridge')
    stray = [name for name in names if not name.startswith(EXPORT_PREFIXES)]
    if stray:
        raise ValueError(f'libnuvio_engine.so exports more than its API: {stray[:5]}')


def collect_notices(engine, libtorrent, boost, licenses):
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(engine / 'LICENSE', licenses / 'LICENSE')
    shutil.copy2(engine / 'THIRD_PARTY_NOTICES.md', licenses / 'THIRD_PARTY_NOTICES.md')
    shutil.copy2(libtorrent / 'LICENSE', licenses / 'LICENSE.libtorrent')
    shutil.copy2(boost / 'LICENSE_1_0.txt', licenses / 'LICENSE.Boost')
    (licenses / 'NOTICE.txt').write_text(
        f"Nuvio Engine {MANIFEST['version']} (GPL-3.0), with libtorrent and Boost compiled in. Their complete\n"
        'source is the release asset ending in -source-nuvio-engine.tar.gz.\n')


def archive_source(output, engine_tarball, boost_tarball, libtorrent, epoch):
    text = BUILD_TXT.format(version=MANIFEST['version'], engine=engine_tarball.name, boost=boost_tarball.name,
                            homepage=MANIFEST['homepage'], boost_url=MANIFEST['boost']['url'],
                            commit=MANIFEST['libtorrent_commit'])
    with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, 'BUILD.txt', text.encode(), epoch)
        add_bytes(tar, 'manifest.json', (HERE / 'manifest.json').read_bytes(), epoch)
        for tarball in (engine_tarball, boost_tarball):
            info = tarfile.TarInfo('upstream/' + tarball.name)
            info.size, info.mode, info.mtime, info.uid, info.gid, info.uname, info.gname = (
                tarball.stat().st_size, 0o644, epoch, 0, 0, '', '')
            with tarball.open('rb') as stream:
                tar.addfile(info, stream)
        add_tree(tar, DESKTOP, 'desktop', epoch)
        add_tree(tar, libtorrent, 'libtorrent', epoch)


def build(work):
    out = work / 'nuvio-engine'
    scratch = work / 'nuvio-engine-build'
    shutil.rmtree(scratch, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    source = MANIFEST['source']
    engine_tarball = fetch(source, scratch / 'downloads' / (source['top'] + '.tar.gz'))
    boost_tarball = fetch(MANIFEST['boost'], scratch / 'downloads' / pathlib.Path(MANIFEST['boost']['url']).name)
    engine = extract(engine_tarball, scratch / 'src', source['top'])
    check_libtorrent_pin(engine)
    apply_patches(engine)
    boost_root = scratch / 'boost'
    boost_root.mkdir()
    with tarfile.open(boost_tarball, 'r:bz2') as tar:
        options = {'filter': 'data'} if hasattr(tarfile, 'data_filter') else {}
        tar.extractall(boost_root, **options)
    boost = next(boost_root.iterdir())
    build_dir, deps = scratch / 'build', scratch / 'deps'
    jni_include = pathlib.Path(os.environ.get('GRAALVM_HOME', '/opt/graalvm')) / 'include'
    subprocess.run(configure_command(engine, build_dir, deps, boost, jni_include), check=True)
    subprocess.run(['cmake', '--build', str(build_dir), '-j', os.environ.get('NUVIO_BUILD_JOBS', '4')], check=True)
    built = next(p for p in build_dir.rglob('libnuvio_engine.so.*') if p.is_file() and not p.is_symlink())
    library = out / 'libnuvio_engine.so'
    subprocess.run(['strip', '--strip-unneeded', '-o', str(library), str(built)], check=True)
    check_exports(library)
    libtorrent = deps / 'nuvio_libtorrent-src'
    collect_notices(engine, libtorrent, boost, out / 'licenses')
    shutil.rmtree(libtorrent / '.git', ignore_errors=True)
    archive_source(out / 'Nuvio-Engine-source.tar.gz', engine_tarball, boost_tarball, libtorrent,
                   int(os.environ.get('SOURCE_DATE_EPOCH', '0')))
    print('NUVIO_ENGINE_BUILD_PASS', MANIFEST['version'], flush=True)
    shutil.rmtree(scratch, ignore_errors=True)


if __name__ == '__main__':
    build(pathlib.Path(sys.argv[1]))
