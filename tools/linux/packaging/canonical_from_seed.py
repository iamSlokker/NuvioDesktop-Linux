#!/usr/bin/env python3
"""Build current application sources in a verified canonical builder seed.

Keep build.py byte-identical: existing seed receipts pin that driver too. This
adapter uses the same native-build.sh, source snapshot, media audit and package
checks, but selects the already verified immutable toolchain instead of creating
a different image ID. It never accepts a prebuilt application or media runtime
without the ordinary audit.
"""
import argparse
import concurrent.futures
import fcntl
import json
import os
import pathlib

from build import HERE, MEDIA, REPO, CONFIG, fetch, run, source_tree
from webkit.builder_seed import load_seed


def canonical(args, work):
    if any((work / name).exists() for name in ['canonical', 'native', 'source']):
        raise ValueError('Use a fresh packaging output directory for a seeded canonical build')
    image = load_seed(args.builder_seed.resolve(), args.builder_seed_sha256)
    runtime = args.runtime.resolve()
    run('python3', MEDIA / 'scripts/artifact_contract.py', runtime, '--validated')
    with (work / 'media-audit.json').open('w') as report:
        run('python3', MEDIA / 'scripts/audit-runtime.py', runtime, stdout=report)
    inputs = work / 'inputs'
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for job in [pool.submit(fetch, spec, inputs / name) for name, spec in CONFIG['tools'].items()]:
            job.result()
    info = source_tree(work / 'source', args.channel)
    info['builder_image_id'] = image
    (work / 'source-info.json').write_text(json.dumps(info, indent=2) + '\n')
    (work / 'gradle').mkdir(exist_ok=True)
    command = ['podman', 'run', '--rm', '--network=host', '--userns=keep-id',
               '--security-opt', 'label=disable', '--cap-drop=all', '--security-opt', 'no-new-privileges',
               '--env', 'HOME=/tmp/nuvio-build', '--env', 'GRADLE_USER_HOME=/gradle',
               '--env', 'SOURCE_DATE_EPOCH=' + str(info['source_date_epoch']),
               '--env', 'NUVIO_BUILD_CHANNEL=' + args.channel, '--env', 'NUVIO_PACKAGE_FORMAT=deb',
               '--env', 'NUVIO_BUILD_JOBS=' + str(args.jobs)]
    for src, dest, ro in [(work / 'source', '/source', False), (runtime, '/media', True),
                          (inputs, '/inputs', True), (work, '/output', False), (work / 'gradle', '/gradle', False)]:
        command += ['--mount', f'type=bind,src={src},dst={dest}' + (',ro' if ro else '')]
    run(*command, image, 'bash', '/source/tools/linux/packaging/native-build.sh')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--builder-seed', type=pathlib.Path, required=True)
    parser.add_argument('--builder-seed-sha256', required=True)
    parser.add_argument('--runtime', type=pathlib.Path, default=REPO / 'build/linux-media-runtime/runtime')
    parser.add_argument('--work-dir', type=pathlib.Path, default=REPO / 'build/linux-packaging')
    parser.add_argument('--channel', choices=['stable', 'nightly'], default='nightly')
    parser.add_argument('--jobs', type=int, default=2)
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test variables')
    work = args.work_dir.resolve()
    if os.uname().machine != 'x86_64' or args.jobs < 1:
        parser.error('Linux x86_64 and positive job count required')
    if work == REPO or REPO.is_relative_to(work) or work.is_relative_to(HERE):
        parser.error('Unsafe output directory')
    work.mkdir(parents=True, exist_ok=True)
    with (work / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        canonical(args, work)


if __name__ == '__main__':
    main()
