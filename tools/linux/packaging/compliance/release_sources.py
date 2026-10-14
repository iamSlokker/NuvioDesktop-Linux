"""Produce the third-party source bundles that accompany a Linux release.

Writes, next to the packages in <work>/artifacts:
  Nuvio-media-source-<commit12>.tar.gz          FFmpeg/mpv/libplacebo/... inputs and recipes
  Nuvio-AppImage-Ubuntu-source-<commit12>.tar   exact Ubuntu sources of libraries bundled in the AppImage
  OpenJDK17U-jdk-sources_<version>.tar.gz       Temurin source for the bundled Java runtime
  SOURCE-MAP.json                               every package -> the source assets that cover it

The application and private WebKit/GTK source archives are already produced by
formats.py/webkit.stage. All inputs are verified against pinned hashes; nothing
here is uploaded. This is the delivery mechanism, not a legal determination.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tarfile

HERE = Path(__file__).resolve().parent
PACKAGING = HERE.parent
sys.path.insert(0, str(PACKAGING))
sys.path.insert(0, str(HERE))
from collect_media_sources import collect as collect_media  # noqa: E402
from fetch import fetch  # noqa: E402
import ubuntu_sources  # noqa: E402

REPO = PACKAGING.parents[2]
EVIDENCE = REPO / 'docs/compliance/evidence'
APPIMAGE_LOCK = EVIDENCE / 'ubuntu-source-lock.json'
RETENTION = EVIDENCE / 'dependency-source-retention.json'
APPIMAGE_ROOT = 'Nuvio.AppDir/usr/lib/nuvio-desktop'


def sha(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def jdk_source_spec():
    rows = json.loads(RETENTION.read_text())['additional_retained_inputs']
    matches = [row for row in rows if row['path'].startswith('temurin/OpenJDK17U-jdk-sources_')
               and row['path'].endswith('.tar.gz')]
    if len(matches) != 1:
        raise ValueError('Expected exactly one pinned Temurin source archive')
    return matches[0]


def check_appimage_lock(work, lock):
    """The Ubuntu source lock must describe the libraries actually bundled in this AppImage."""
    root = work / APPIMAGE_ROOT
    runtime = json.loads((root / 'share/nuvio-webkit/runtime.json').read_text())
    if runtime['builder_image_id'] != lock['builder_image_id']:
        raise ValueError('AppImage engine builder differs from the Ubuntu source lock; regenerate the lock')
    # packages.json lists the Ubuntu libraries actually copied into the AppImage. GTK and
    # WebKitGTK are the private patched builds; their source is Nuvio-WebKit-source-*.
    bundled = set(json.loads((root / 'share/licenses/Ubuntu-AppImage/packages.json').read_text()))
    locked = {binary['package'] for row in lock['source_packages'] for binary in row['binaries']}
    missing = sorted(bundled - locked)
    if missing:
        raise ValueError('Bundled Ubuntu packages without locked source: ' + ', '.join(missing))
    return sorted(bundled)


def archive_ubuntu(lock, cache, output):
    with output.open('xb') as stream, tarfile.open(fileobj=stream, mode='w') as tar:
        tar.add(APPIMAGE_LOCK, 'ubuntu-source-lock.json', recursive=False)
        for row in sorted(lock['source_packages'], key=lambda r: r['inputs'][0]['filename']):
            for spec in row['inputs']:
                tar.add(cache / spec['sha256'], 'sources/' + spec['filename'], recursive=False)


def build(work, media_cache, ubuntu_cache, offline=False):
    work, artifacts = Path(work), Path(work) / 'artifacts'
    info = json.loads((work / 'canonical/release-info.json').read_text())
    commit = info['git_commit'][:12]
    produced = {}

    media = artifacts / f'Nuvio-media-source-{commit}.tar.gz'
    collect_media(PACKAGING.parent / 'media-runtime', Path(media_cache), media)
    produced['media'] = media

    jdk = jdk_source_spec()
    produced['jdk'] = fetch(dict(url=jdk['url'], sha256=jdk['sha256']), artifacts / Path(jdk['path']).name)

    appimage = sorted(artifacts.glob('*.AppImage'))
    if appimage:
        lock = json.loads(APPIMAGE_LOCK.read_text())
        bundled = check_appimage_lock(work, lock)
        ubuntu_sources.retain(lock, Path(ubuntu_cache), offline=offline)
        ubuntu = artifacts / f'Nuvio-AppImage-Ubuntu-source-{commit}.tar'
        archive_ubuntu(lock, Path(ubuntu_cache), ubuntu)
        produced['ubuntu'] = ubuntu
    else:
        bundled = []

    application = artifacts / f'Nuvio-source-{commit}.tar.gz'
    webkit = sorted(artifacts.glob('Nuvio-WebKit-source-*.tar.gz'))
    runtime = sorted(artifacts.glob('Nuvio-AppImage-runtime-source-*.tar.gz'))
    common = [application, produced['media'], produced['jdk']]
    packages = {}
    for pattern, kind in (('*.deb', 'deb'), ('*.flatpak', 'flatpak'), ('*.AppImage', 'appimage')):
        for package in sorted(artifacts.glob(pattern)):
            sources = common + (webkit + runtime + [produced['ubuntu']] if kind == 'appimage' else [])
            packages[package.name] = dict(sha256=sha(package), sources=[
                dict(name=p.name, sha256=sha(p)) for p in sources])
    for record in packages.values():
        for source in record['sources']:
            if not (artifacts / source['name']).is_file():
                raise ValueError('Missing source asset: ' + source['name'])
    source_map = dict(
        schema_version=1, git_commit=info['git_commit'], version=info['version'], channel=info['channel'],
        packages=packages, appimage_ubuntu_binaries=bundled,
        not_included=dict(
            flatpak_runtime='org.gnome.Platform is installed separately from Flathub and is not part of the bundle',
            maven='All 125 packaged Maven JARs are Apache-2.0 or MIT; their notices ship in the JARs and share/licenses',
            notices='License texts for every bundled component ship inside each package under share/licenses'),
        instructions='docs/compliance/modified-build-and-install.md in the application source archive')
    (artifacts / 'SOURCE-MAP.json').write_text(json.dumps(source_map, indent=2, sort_keys=True) + '\n')
    return source_map


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--work', type=Path, default=REPO / 'build/linux-packaging')
    parser.add_argument('--media-cache', type=Path, default=REPO / 'build/linux-media-runtime/downloads')
    parser.add_argument('--ubuntu-cache', type=Path, default=REPO / 'build/linux-packaging/ubuntu-sources')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    args.ubuntu_cache.mkdir(parents=True, exist_ok=True)
    result = build(args.work, args.media_cache, args.ubuntu_cache, args.offline)
    print(json.dumps({name: [s['name'] for s in rec['sources']] for name, rec in result['packages'].items()}, indent=2))


if __name__ == '__main__':
    main()
