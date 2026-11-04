#!/usr/bin/env python3
"""Build whisper.cpp (ggml-org/whisper.cpp, MIT) for the Linux packages, inside the pinned builder.

The speech engine of voice control, the same version and CPU flags the Windows app uses, as
lib/native/libnuvio_whisper.so with the JNI bridge (composeApp/src/desktopMain/native/whisper-jni).
The models are not bundled: the person downloads one from Settings > Voice control.

Runs in native-build.sh's build phase (network for the input), pinned by SHA-256 here.

  <work>/whisper/libnuvio_whisper.so
  <work>/whisper/licenses/                   whisper.cpp and ggml notices
  <work>/whisper/Nuvio-Whisper-source.tar.gz the tag and the JNI bridge
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
DESKTOP = REPOSITORY / 'composeApp/src/desktopMain/native/whisper-jni'
EXPORT_PREFIX = 'Java_com_nuvio_app_features_voice_WhisperNative_'
BUILD_TXT = """whisper.cpp {version} as built for the Linux packages.

upstream/{tarball}   the tag archive of {homepage}
desktop/             the JNI bridge and the CMake project

Rebuild: tools/linux/packaging/whisper/build.py in the application source, or by hand:
  cmake -S desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \\
    -DNUVIO_WHISPER_ROOT=<tag> -DNUVIO_JNI_INCLUDE_DIR=<jdk>/include
  cmake --build build --target nuvio_whisper
"""


def configure_command(whisper, build, jni_include):
    return ['cmake', '-S', str(DESKTOP), '-B', str(build), '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
            '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', f'-DNUVIO_WHISPER_ROOT={whisper}',
            f'-DNUVIO_JNI_INCLUDE_DIR={jni_include}']


def check_exports(library):
    """The JNI bridge must be visible and nothing else leak (whisper and ggml stay hidden)."""
    output = subprocess.check_output(['nm', '-D', '--defined-only', str(library)], text=True)
    names = [line.split()[-1] for line in output.splitlines() if line.split()]
    if not any(name.startswith(EXPORT_PREFIX) for name in names):
        raise ValueError('libnuvio_whisper.so exports no JNI bridge')
    stray = [name for name in names if not name.startswith(EXPORT_PREFIX)]
    if stray:
        raise ValueError(f'libnuvio_whisper.so exports more than its API: {stray[:5]}')


def collect_notices(whisper, licenses):
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(whisper / 'LICENSE', licenses / 'LICENSE')
    (licenses / 'NOTICE.txt').write_text(
        f"whisper.cpp {MANIFEST['version']} (MIT), with ggml compiled in. Its source is the release asset\n"
        'ending in -source-whisper.tar.gz.\n')


def archive_source(output, tarball, epoch):
    text = BUILD_TXT.format(version=MANIFEST['version'], tarball=tarball.name, homepage=MANIFEST['homepage'])
    with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
        add_bytes(tar, 'BUILD.txt', text.encode(), epoch)
        add_bytes(tar, 'manifest.json', (HERE / 'manifest.json').read_bytes(), epoch)
        info = tarfile.TarInfo('upstream/' + tarball.name)
        info.size, info.mode, info.mtime, info.uid, info.gid, info.uname, info.gname = (
            tarball.stat().st_size, 0o644, epoch, 0, 0, '', '')
        with tarball.open('rb') as stream:
            tar.addfile(info, stream)
        add_tree(tar, DESKTOP, 'desktop', epoch)


def build(work):
    out = work / 'whisper'
    scratch = work / 'whisper-build'
    shutil.rmtree(scratch, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    source = MANIFEST['source']
    tarball = fetch(source, scratch / 'downloads' / (source['top'] + '.tar.gz'))
    whisper = extract(tarball, scratch / 'src', source['top'])
    build_dir = scratch / 'build'
    jni_include = pathlib.Path(os.environ.get('GRAALVM_HOME', '/opt/graalvm')) / 'include'
    subprocess.run(configure_command(whisper, build_dir, jni_include), check=True)
    subprocess.run(['cmake', '--build', str(build_dir), '-j', os.environ.get('NUVIO_BUILD_JOBS', '4'),
                    '--target', 'nuvio_whisper'], check=True)
    library = out / 'libnuvio_whisper.so'
    subprocess.run(['strip', '--strip-unneeded', '-o', str(library), str(build_dir / 'libnuvio_whisper.so')], check=True)
    check_exports(library)
    collect_notices(whisper, out / 'licenses')
    archive_source(out / 'Nuvio-Whisper-source.tar.gz', tarball, int(os.environ.get('SOURCE_DATE_EPOCH', '0')))
    print('WHISPER_BUILD_PASS', MANIFEST['version'], flush=True)
    shutil.rmtree(scratch, ignore_errors=True)


if __name__ == '__main__':
    build(pathlib.Path(sys.argv[1]))
