#!/usr/bin/env python3
"""Rootless native packages, then optional Flatpak, from one private app image.

Never installs a host package, registers a remote, or launches the application.
"""
import argparse
import concurrent.futures
import fcntl
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

from fetch import CONFIG, fetch

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
MEDIA = REPO / 'tools/linux/media-runtime'


def run(*args, **kw):
    subprocess.run([str(a) for a in args], check=True, **kw)


def git(*args):
    return subprocess.check_output(['git', '-C', str(REPO), *args]).decode().strip()


# Not redistributed: the app fetches and verifies this pinned dataset at runtime
# (AnimeListDownload.kt), so it stays out of the snapshot, the JAR and the source tarball.
RUNTIME_DOWNLOADED = {'composeApp/src/desktopMain/resources/anime-list-mini.json'}


def source_tree(destination, channel):
    # Explicit tracked source list, plus this task's new packaging/test files.
    # Never copy the checkout recursively: it includes caches and local secrets.
    names = git('ls-files', '-z').split('\0')
    # Development packaging needs this task's new files, not arbitrary
    # untracked files (which may contain personal configuration or credentials).
    untracked = git('ls-files', '--others', '--exclude-standard', '-z').split('\0')
    names += [name for name in untracked if development_source(name)]
    excluded = {'.git', '.agents', '.codex', '.aws', 'MPVKit', 'Docs', 'keystore', 'analysis'}
    files = []
    for name in sorted(set(names)):
        p = pathlib.Path(name)
        if not name or p.parts[0] in excluded or p.name == 'local.properties' or name in RUNTIME_DOWNLOADED:
            continue
        src = REPO / p
        if not src.is_file():
            continue
        if src.is_symlink():
            raise ValueError('Unexpected source symlink: ' + name)
        files.append((name, hashlib.sha256(src.read_bytes()).hexdigest()))
    dirty = bool(git('status', '--porcelain'))
    if channel == 'stable' and dirty:
        raise ValueError('Stable packaging requires a clean committed source tree')
    shutil.rmtree(destination, ignore_errors=True)
    for name, _ in files:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / name, target)
    (destination.parent / 'source-files.json').write_text(json.dumps(files, indent=2) + '\n')
    commit = git('rev-parse', 'HEAD')
    epoch = int(git('show', '-s', '--format=%ct', 'HEAD'))
    version = dict(line.split('=', 1) for line in
                   (destination / 'composeApp/Configuration/DesktopVersion.properties').read_text().splitlines()
                   if '=' in line and not line.startswith('#'))
    return dict(application_id=CONFIG['application_id'], display_name=CONFIG['display_name'],
                version=version['VERSION_NAME'], version_code=int(version['VERSION_CODE']),
                git_commit=commit, source_date_epoch=epoch, channel=channel, dirty=dirty,
                build_kind='release' if channel == 'stable' else 'development',
                source_sha256=hashlib.sha256(json.dumps(files).encode()).hexdigest())


def development_source(name):
    return name.startswith('tools/linux/packaging/') or name in {
        '.github/workflows/linux-packages.yml',
        'composeApp/src/desktopTest/kotlin/com/nuvio/app/features/player/desktop/LinuxPackagedNativeRuntimeTest.kt',
    }


def native(args, work):
    # A failed rebuild must not leave a previous revision eligible for export.
    for directory in ['canonical', 'artifacts', 'native']:
        shutil.rmtree(work / directory, ignore_errors=True)
    runtime = pathlib.Path(args.runtime).resolve() if args.runtime else REPO / 'build/linux-media-runtime/runtime'
    if not runtime.exists():
        run(MEDIA / 'build.sh', '--jobs', args.jobs)
    run('python3', MEDIA / 'scripts/artifact_contract.py', runtime, '--validated')
    run('python3', MEDIA / 'scripts/audit-runtime.py', runtime, stdout=(work / 'media-audit.json').open('w'))
    inputs = work / 'inputs'
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs = [pool.submit(fetch, spec, inputs / name) for name, spec in CONFIG['tools'].items()]
        for job in jobs:
            job.result()
    manifest = json.loads((MEDIA / 'manifest.json').read_text())
    context = work / 'container'
    context.mkdir(exist_ok=True)
    for src, dst in [(MEDIA / 'manifest.json', 'manifest.json'), (HERE / 'config.json', 'config.json'),
                     (HERE / 'apt-prefetch.py', 'apt-prefetch.py'),
                     (HERE / 'install-builder-tools.py', 'install-builder-tools.py'),
                     (inputs / 'jdk', 'jdk.tar.gz'), (inputs / 'cmake', 'cmake.whl')]:
        shutil.copy2(src, context / dst)
    media_builder = json.loads((runtime / 'share/nuvio-media-runtime/build-info.json').read_text())['builder_image_id']
    identity = hashlib.sha256((HERE / 'Containerfile').read_bytes() + (HERE / 'apt-prefetch.py').read_bytes() +
                              (HERE / 'install-builder-tools.py').read_bytes() +
                              media_builder.encode() +
                              (HERE / 'config.json').read_bytes() + (MEDIA / 'manifest.json').read_bytes()).hexdigest()
    image = 'localhost/nuvio-package-builder:' + identity
    run('podman', 'build', '--network=host', '--file', HERE / 'Containerfile',
        '--build-arg', 'MEDIA_BUILDER_IMAGE=' + media_builder, '--tag', image, context)
    info = source_tree(work / 'source', args.channel)
    info['builder_image_id'] = subprocess.check_output(['podman', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip()
    (work / 'source-info.json').write_text(json.dumps(info, indent=2) + '\n')
    (work / 'gradle').mkdir(exist_ok=True)
    if args.gradle_cache:
        # Optional seed of input artifacts only. Never consume compiled project output.
        for directory in ['caches/modules-2', 'wrapper']:
            src = pathlib.Path(args.gradle_cache) / directory
            if src.is_dir():
                shutil.copytree(src, work / 'gradle' / directory, dirs_exist_ok=True)
    command = ['podman', 'run', '--rm', '--network=host', '--userns=keep-id',
               '--security-opt', 'label=disable', '--cap-drop=all', '--security-opt', 'no-new-privileges',
               '--env', 'HOME=/tmp/nuvio-build', '--env', 'GRADLE_USER_HOME=/gradle',
               '--env', 'SOURCE_DATE_EPOCH=' + str(info['source_date_epoch']),
               '--env', 'NUVIO_BUILD_CHANNEL=' + args.channel,
               '--env', 'NUVIO_PACKAGE_FORMAT=deb',
               '--env', 'NUVIO_BUILD_JOBS=' + str(args.jobs)]
    if args.offline:
        command += ['--env', 'NUVIO_GRADLE_OFFLINE=1']
    for src, dest, ro in [(work / 'source', '/source', False), (runtime, '/media', True),
                           (inputs, '/inputs', True), (work, '/output', False),
                           (work / 'gradle', '/gradle', False)]:
        command += ['--mount', f'type=bind,src={src},dst={dest}' + (',ro' if ro else '')]
    run(*command, image, 'bash', '/source/tools/linux/packaging/native-build.sh')


def appimage(args, work):
    for name in ['appimagetool', 'appimage-runtime']:
        fetch(CONFIG['tools'][name], work / 'inputs' / name)
    generation = args.webkit_generation
    if not generation:
        result = work / 'engine-result.json'
        command = ['python3', HERE / 'webkit/build.py', '--packaging-work-dir', work, '--result-file', result]
        if args.webkit_source_archive:
            command += ['--source-archive', args.webkit_source_archive]
        run(*command)
        generation = json.loads(result.read_text())['generation']
    run('python3', HERE / 'webkit/package.py', '--packaging-work-dir', work,
        '--engine-generation', pathlib.Path(generation).resolve())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--format', choices=['native', 'deb', 'flatpak', 'all', 'webkit', 'appimage'], default='all')
    p.add_argument('--runtime', help='Previously audited private runtime; omitted: build from canonical recipe')
    p.add_argument('--work-dir', default=str(REPO / 'build/linux-packaging'))
    p.add_argument('--channel', choices=['stable', 'nightly'], default='nightly')
    p.add_argument('--jobs', type=int, default=4)
    p.add_argument('--gradle-cache', help='Optional Gradle dependency/wrapper cache to seed the isolated build')
    p.add_argument('--offline', action='store_true')
    p.add_argument('--webkit-source-archive', help='Optional pinned WebKit source archive for the isolated engine stage')
    p.add_argument('--webkit-generation', help='Optional verified engine generation; otherwise build/resume the pinned engine')
    args = p.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        p.error('Unset DISPLAY, WAYLAND_DISPLAY and NUVIO_RUN_LIVE_DISPLAY_TESTS before packaging')
    if os.uname().machine != 'x86_64' or args.jobs < 1:
        p.error('Linux x86_64 and positive job count required')
    work = pathlib.Path(args.work_dir).resolve()
    if work == REPO or REPO.is_relative_to(work) or work.is_relative_to(HERE):
        p.error('Unsafe output directory')
    work.mkdir(parents=True, exist_ok=True)
    with (work / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        failures = []
        if args.format == 'appimage':
            appimage(args, work)
            return
        if args.format == 'webkit':
            command = ['python3', HERE / 'webkit/build.py', '--packaging-work-dir', work]
            if args.webkit_source_archive:
                command += ['--source-archive', args.webkit_source_archive]
            run(*command)
            return
        if args.format in ['native', 'deb', 'all']:
            try:
                native(args, work)
            except (subprocess.CalledProcessError, ValueError) as error:
                failures.append('native: ' + str(error))
        if args.format in ['flatpak', 'all']:
            try:
                run('python3', HERE / 'flatpak.py', work)
                # DEB/AppImage are checked in their pinned builder, not against
                # whatever tools/libraries happen to be on the host.
                run('python3', HERE / 'verify.py', 'artifacts', work, '--formats', 'flatpak')
            except (subprocess.CalledProcessError, ValueError) as error:
                failures.append('flatpak: ' + str(error))
        if args.format in ['native', 'all']:
            try:
                appimage(args, work)
            except (subprocess.CalledProcessError, ValueError) as error:
                failures.append('appimage: ' + str(error))
        if failures:
            raise SystemExit('\n'.join(failures))


if __name__ == '__main__':
    main()
