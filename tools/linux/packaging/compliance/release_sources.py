"""Produce the third-party source bundles that accompany a Linux release.

Writes, next to the packages in <work>/artifacts:
  Nuvio-media-source-<commit12>.tar.gz          FFmpeg/mpv/libplacebo/... inputs and recipes
  Nuvio-AppImage-Ubuntu-source-<commit12>.tar   exact Ubuntu sources of libraries bundled in the AppImage
  graal-<commit>.tar.gz, labs-openjdk-<commit>.tar.gz, mx-<commit>.tar.gz
                                                GraalVM CE source (Substrate VM, compiler, JDK, build
                                                tool) for the native executable and its JDK libraries
  SOURCE-MAP.json                               every package -> the source assets that cover it

The application, TorrServer and private WebKit/GTK source archives are already produced by
formats.py (torrserver/build.py)/webkit.stage. All inputs are verified against pinned hashes; nothing
here is uploaded. This is the delivery mechanism, not a legal determination.
Packages no longer contain Temurin (it is only the build JDK inside the builder
OCI, a separately reviewed scope), so its source is no longer a package asset.
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


GRAALVM_SOURCES = ('graal-', 'labs-openjdk-', 'mx-')


def graalvm_source_specs():
    """The pinned GraalVM CE source archives (oracle/graal, graalvm/labs-openjdk, graalvm/mx)."""
    rows = json.loads(RETENTION.read_text())['additional_retained_inputs']
    specs = []
    for prefix in GRAALVM_SOURCES:
        matches = [row for row in rows if row['path'].startswith('graalvm/' + prefix)
                   and row['path'].endswith('.tar.gz') and row['url'].startswith('https://codeload.github.com/')]
        if len(matches) != 1:
            raise ValueError('Expected exactly one pinned GraalVM source archive: ' + prefix)
        specs.append(matches[0])
    return specs


def archive_member(tar, name):
    """The single regular file `name` in a make_archive() tarball (stored as ./name)."""
    found = [m for m in tar.getmembers() if m.name.removeprefix('./') == name]
    if len(found) != 1 or not found[0].isfile():
        raise ValueError('Expected one regular file in the WebKit source archive: ' + name)
    return tar.extractfile(found[0]).read()


def engine_builder_versions(work, runtime):
    """Exact dpkg versions in the engine builder that assembled this AppImage.

    webkit.stage refuses to assemble unless the container's own dpkg inventory
    equals the engine generation's corresponding-source/webkit-builder-packages.tsv,
    and webkit.stage.archive_source ships that file (engine/) with its
    engine-build.json in the Nuvio-WebKit-source archive next to the AppImage.
    """
    archives = sorted((work / 'artifacts').glob('Nuvio-WebKit-source-*.tar.gz'))
    if len(archives) != 1:
        raise ValueError('Expected exactly one Nuvio-WebKit-source archive for the AppImage')
    with tarfile.open(archives[0], 'r:gz') as tar:
        record = json.loads(archive_member(tar, 'engine-build.json'))
        table = archive_member(tar, 'engine/webkit-builder-packages.tsv')
    if (record['builder_image_id'], record['recipe_identity']) != (runtime['builder_image_id'], runtime['recipe_identity']):
        raise ValueError('WebKit source archive does not describe the AppImage engine')
    if hashlib.sha256(table).hexdigest() != record['corresponding_source']['webkit-builder-packages.tsv']['sha256']:
        raise ValueError('Engine builder package list does not match its build record')
    versions = {}
    for line in table.decode().splitlines():
        package, tab, version = line.partition('\t')
        if not tab or not package or not version or '\t' in version or versions.get(package, version) != version:
            raise ValueError('Malformed engine builder package list: ' + line)
        versions[package] = version
    return versions


def check_appimage_lock(work, lock):
    """The Ubuntu source lock must cover the exact Ubuntu binaries bundled in this AppImage.

    Bound by package version, not by builder image ID: a rebuilt engine builder
    from the same pinned snapshot keeps every version (and its locked source);
    any version change fails closed until the lock is regenerated.
    """
    root = work / APPIMAGE_ROOT
    runtime = json.loads((root / 'share/nuvio-webkit/runtime.json').read_text())
    # packages.json lists the Ubuntu libraries actually copied into the AppImage. GTK and
    # WebKitGTK are the private patched builds; their source is Nuvio-WebKit-source-*.
    bundled = sorted(set(json.loads((root / 'share/licenses/Ubuntu-AppImage/packages.json').read_text())))
    installed = engine_builder_versions(work, runtime)
    locked = {}
    for row in lock['source_packages']:
        for binary in row['binaries']:
            locked.setdefault(binary['package'], set()).add(binary['version'])
    missing = [name for name in bundled if name not in locked]
    if missing:
        raise ValueError('Bundled Ubuntu packages without locked source: ' + ', '.join(missing))
    unrecorded = [name for name in bundled if name not in installed]
    if unrecorded:
        raise ValueError('Bundled Ubuntu packages absent from the engine builder package list: ' + ', '.join(unrecorded))
    different = [f'{name} {installed[name]} (locked {", ".join(sorted(locked[name]))})'
                 for name in bundled if installed[name] not in locked[name]]
    if different:
        raise ValueError('Bundled Ubuntu package versions differ from the source lock; regenerate the lock: '
                         + '; '.join(different))
    return bundled


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

    produced['graalvm'] = [fetch(dict(url=spec['url'], sha256=spec['sha256']), artifacts / Path(spec['path']).name)
                           for spec in graalvm_source_specs()]

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
    # The exact JARs/arguments native-image compiled: what rebuilding or relinking the
    # executable with a modified library needs, next to the corresponding sources.
    native_inputs = sorted(artifacts.glob('Nuvio-native-image-inputs-*.tar'))
    if len(native_inputs) != 1:
        raise ValueError('Expected exactly one Nuvio-native-image-inputs archive')
    torrserver = sorted(artifacts.glob('Nuvio-TorrServer-source-*.tar.gz'))
    if len(torrserver) != 1:
        raise ValueError('Expected exactly one Nuvio-TorrServer-source archive')
    innertubex = sorted(artifacts.glob('Nuvio-InnerTubeX-source-*.tar.gz'))
    if len(innertubex) != 1:
        raise ValueError('Expected exactly one Nuvio-InnerTubeX-source archive')
    engine = sorted(artifacts.glob('Nuvio-Engine-source-*.tar.gz'))
    if len(engine) != 1:
        raise ValueError('Expected exactly one Nuvio-Engine-source archive')
    common = [application, produced['media'], *produced['graalvm'], native_inputs[0], torrserver[0], innertubex[0],
              engine[0]]
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
            maven='Maven JARs are compiled into the native executable, not shipped. Their notice files ship under '
                  'share/licenses/jar-notices; the exact input JARs, native-image arguments and metadata hashes are in '
                  'Nuvio-native-image-inputs-*.tar. The last reviewed set (125 JARs) was Apache-2.0 or MIT; '
                  'the current set needs the same review. InnerTubeX is GPL-3.0, with its source in '
                  'Nuvio-InnerTubeX-source-*.tar.gz',
            graalvm='GraalVM CE 25.0.2 sources are the pinned upstream commits; upstream publishes no source bundle and '
                    'its labsjdk-builder recipe is not public (see dependency-source-retention.md)',
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
