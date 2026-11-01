#!/usr/bin/env python3
"""Allowlisted canonical image; never stage a checkout or a developer runtime tree."""
import datetime
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

from fetch import CONFIG
from notice_contract import stage_notices
import native_evidence

TORRSERVER = 'lib/resources/torrserver/linux-amd64/TorrServer'


def remove_build_data(destination, contract):
    """Build-only media SDK data (HDR probe protocol XML) never enters the image."""
    share = destination / 'share'
    for name in contract['build_data']:
        path = destination / name
        if not name.startswith('share/') or '..' in pathlib.PurePosixPath(name).parts:
            raise ValueError('Unexpected media build-data location: ' + name)
        path.unlink()
        for parent in path.parents:
            if parent == share or any(parent.iterdir()):
                break
            parent.rmdir()


def stage(work, source=pathlib.Path('/source'), media=pathlib.Path('/media'),
          graalvm=pathlib.Path('/opt/graalvm'), build_jdk=pathlib.Path('/opt/jdk')):
    info = json.loads((work / 'source-info.json').read_text())
    # createLinuxNativeDistributable's layout (composeApp/native-image-linux/README.md): the
    # GraalVM native executable lib/nuvio, the JDK libraries it emitted, lib/app without
    # JARs and lib/runtime/lib (libjawt.so, font properties). No JVM, no application JAR.
    app = source / 'composeApp/build/native-image-linux/nuvio'
    if (not (app / 'lib/nuvio').is_file() or not (app / 'bin/nuvio').is_file()
            or not (app / 'lib/runtime/lib/libjawt.so').is_file()):
        raise ValueError('Missing native-image layout, executable or JAWT')
    if (app / 'lib/native-qa').exists() or (app / 'lib/native').exists():
        raise ValueError('Refusing a QA native image or a developer lib/native')
    destination = work / 'canonical'
    shutil.rmtree(destination, ignore_errors=True)
    shutil.copytree(app, destination, symlinks=True)
    native = destination / 'lib/native'
    native.mkdir()
    contract = json.loads((media / 'share/nuvio-media-runtime/manifest.json').read_text())['artifact_contract']
    for name, record in contract['libraries'].items():
        src = media / name
        shutil.copy2(src, native / src.name)
        if record['soname'] != src.name:
            (native / record['soname']).symlink_to(src.name)
    shutil.copy2(work / 'native/libplayer_bridge.so', native / 'libplayer_bridge.so')
    # Experimental HDR capability helper; same RUNPATH layout as the bridge.
    shutil.copy2(work / 'native/nuvio-hdr-probe', native / 'nuvio-hdr-probe')
    # quickjs-kt's JNI library rebuilt with the binding local-frame patch (quickjs-kt/README.md).
    shutil.copy2(work / 'native/libquickjs.so', native / 'libquickjs.so')
    # TorrServer (torrserver/build.py): PackagedResources reads lib/resources/ beside lib/nuvio,
    # and the P2P backend starts it from there; Nuvio Engine is Windows-only.
    torrserver = destination / TORRSERVER
    torrserver.parent.mkdir(parents=True)
    shutil.copy2(work / 'torrserver/TorrServer', torrserver)
    torrserver.chmod(0o755)
    here = source / 'tools/linux/packaging'
    shutil.copy2(here / 'nuvio-desktop', destination / 'bin/nuvio-desktop')
    (destination / 'bin/nuvio-desktop').chmod(0o755)
    share = destination / 'share'
    shutil.copytree(media / 'share', share, dirs_exist_ok=True)
    remove_build_data(destination, contract)
    licenses = share / 'licenses/Nuvio'
    licenses.mkdir(parents=True)
    shutil.copy2(source / 'LICENSE', licenses / 'COPYING')
    quickjs = source / 'composeApp/src/desktopMain/native/quickjs-kt'
    quickjs_licenses = share / 'licenses/quickjs-kt'
    quickjs_licenses.mkdir(parents=True)
    for src, name in [('LICENSE.txt', 'LICENSE'), ('quickjs/LICENSE', 'LICENSE.QuickJS'), ('c-vector/LICENSE', 'LICENSE.c-vector')]:
        shutil.copy2(quickjs / src, quickjs_licenses / name)
    shutil.copytree(work / 'torrserver/licenses', share / 'licenses/TorrServer')
    shutil.copytree(source / 'composeApp/src/desktopMain/resources/player-shaders',
                    licenses / 'player-shaders', ignore=shutil.ignore_patterns('*.glsl'))
    # GraalVM CE notices, the manifest of the exact native-image inputs, the input
    # JARs' notice files (the JARs themselves no longer ship) and the anime notices.
    native_evidence.stage(destination, work, source, graalvm, CONFIG)
    stage_notices(destination)
    shutil.copy2('/builder-packages.tsv', share / 'nuvio-media-runtime/package-builder-packages.tsv')
    info['media_inventory_sha256'] = hashlib.sha256((media / 'share/nuvio-media-runtime/inventory.json').read_bytes()).hexdigest()
    info['graalvm_release'] = native_evidence.release_fields(graalvm)
    # Build-only JDK (Gradle, Kotlin, jpackage); it is not part of any package.
    info['build_jdk_release'] = {k: v.strip('"') for k, v in (
        line.split('=', 1) for line in (build_jdk / 'release').read_text().splitlines() if '=' in line)
        if k in ('IMPLEMENTOR', 'JAVA_VERSION', 'JAVA_RUNTIME_VERSION', 'SOURCE')}
    (destination / 'release-info.json').write_text(json.dumps(info, indent=2, sort_keys=True) + '\n')
    instant = datetime.datetime.fromtimestamp(info['source_date_epoch'], datetime.timezone.utc)
    stamp = dict(BUILD_ID=instant.strftime('%Y%m%d-%H%M%S') + '.' + info['source_sha256'][:12],
                 BUILD_TIME=instant.strftime('%Y-%m-%dT%H:%M:%SZ'), VERSION_NAME=info['version'],
                 VERSION_CODE=info['version_code'], CHANNEL=info['channel'], GIT_COMMIT=info['git_commit'],
                 BUILD_KIND=info['build_kind'], SOURCE_SHA256=info['source_sha256'], DIRTY=str(info['dirty']).lower())
    (destination / 'lib/app/build-info.properties').write_text(''.join(f'{k}={v}\n' for k,v in stamp.items()))
    desktop = share / 'applications' / (CONFIG['application_id'] + '.desktop')
    desktop.parent.mkdir(parents=True)
    shutil.copy2(here / desktop.name, desktop)
    icon = share / 'icons/hicolor/512x512/apps' / (CONFIG['application_id'] + '.png')
    icon.parent.mkdir(parents=True)
    subprocess.run(['java', '-Djava.awt.headless=true', str(here / 'ResizeIcon.java'),
                    str(source / 'composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png'), str(icon)], check=True)
    subprocess.run(['desktop-file-validate', str(desktop)], check=True)
    # SDK/header/pkg-config artifacts never enter the application image.
    if any(p.suffix in ['.a', '.h', '.pc', '.la'] for p in native.rglob('*')):
        raise ValueError('Development artifact in private native directory')


if __name__ == '__main__':
    stage(pathlib.Path(sys.argv[1]))
