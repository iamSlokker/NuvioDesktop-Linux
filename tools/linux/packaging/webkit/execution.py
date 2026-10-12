"""Headless compiler supervision and quiescent ccache snapshots."""
import fcntl
import hashlib
import json
import os
import pathlib
import re
import selectors
import shutil
import signal
import subprocess
import sys
import time

GIB = 1024**3


class BuildCancelled(RuntimeError):
    pass


def resources(work):
    memory = dict((line.split(':')[0], int(line.split()[1]) * 1024)
                  for line in pathlib.Path('/proc/meminfo').read_text().splitlines())
    available = memory['MemAvailable']
    # Respect a containing cgroup as well as physical RAM on a hosted runner.
    root = pathlib.Path('/sys/fs/cgroup')
    groups = {root}
    for line in pathlib.Path('/proc/self/cgroup').read_text().splitlines():
        if line.startswith('0::'):
            group = (root / line[3:].lstrip('/')).resolve()
            while group.is_relative_to(root):
                groups.add(group)
                group = group.parent
    for group in groups:
        limit = group / 'memory.max'
        if limit.exists() and limit.read_text().strip() != 'max':
            available = min(available, max(0, int(limit.read_text()) - int((group / 'memory.current').read_text())))
    return dict(ram_total_gib=round(memory['MemTotal'] / GIB, 2),
                ram_available_gib=round(available / GIB, 2),
                cpus=len(os.sched_getaffinity(0)), disk_free_gib=round(shutil.disk_usage(work).free / GIB, 2))


def preflight(work, manifest):
    report = resources(work)
    print('WebKit resource preflight: ' + json.dumps(report), flush=True)
    errors = []
    for key, minimum in [('ram_available_gib', manifest['minimum_available_memory_gib']),
                         ('cpus', manifest['minimum_cpus']),
                         ('disk_free_gib', manifest['minimum_free_disk_gib'])]:
        if report[key] < minimum:
            errors.append(f'{key}={report[key]} requires >= {minimum}')
    if errors:
        raise ValueError('Insufficient WebKit build resources: ' + '; '.join(errors) +
                         '. Use a larger/dedicated runner; do not remove sandbox or memory limits.')
    return report


def container_name(work):
    return 'nuvio-webkit-' + hashlib.sha256(str(work.resolve()).encode()).hexdigest()[:20]


def stop_container(work):
    """Only our work directory's named compiler, and fail closed on Podman errors."""
    name = container_name(work)
    exists = subprocess.run(['podman', 'container', 'exists', name], timeout=30)
    if exists.returncode == 1:
        return
    exists.check_returncode()
    labels = json.loads(subprocess.check_output(
        ['podman', 'inspect', '--format', '{{json .Config.Labels}}', name], text=True, timeout=30))
    if labels.get('org.nuvio.webkit.work') != name:
        raise ValueError('Refusing cleanup of a container without our ownership label')
    subprocess.run(['podman', 'rm', '--force', name], check=True, timeout=60)
    absent = subprocess.run(['podman', 'container', 'exists', name], timeout=30)
    if absent.returncode != 1:
        raise ValueError('Compiler container is not confirmed stopped; do not save ccache')


def heartbeat(work, started, last_output, byte_count):
    print(f'\n[WebKit progress] elapsed={int(time.monotonic() - started)}s '
          f'log_bytes_this_attempt={byte_count} last_output={int(time.monotonic() - last_output)}s ago '
          f'host={json.dumps(resources(work))}', flush=True)
    try:
        result = subprocess.run(['podman', 'stats', '--no-stream', container_name(work)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10)
        print(result.stdout, end='', flush=True)
    except subprocess.TimeoutExpired:
        print('[WebKit progress] Podman stats timed out; compiler remains supervised', flush=True)


def stream_build(command, log_path, work, timeout_seconds, interval=60):
    """Tee raw output, heartbeat even during a quiet link, kill container on exit.

    An internal deadline leaves time for always() cache/artifact uploads before
    GitHub's step/job timeout. SIGKILL/runner loss still needs the workflow guard.
    """
    def interrupted(signum, frame):
        # selectors.select catches InterruptedError as a retryable syscall.
        # Cancellation must escape that retry path and reach container cleanup.
        raise BuildCancelled(f'WebKit interrupted by signal {signum}')

    old = {sig: signal.signal(sig, interrupted) for sig in [signal.SIGINT, signal.SIGTERM]}
    process = None
    try:
        with log_path.open('ab', buffering=0) as log, selectors.DefaultSelector() as selector:
            log.write(f'\n=== WebKit attempt {time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())} ===\n'.encode())
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            selector.register(process.stdout, selectors.EVENT_READ)
            started = last_output = last_report = time.monotonic()
            byte_count = 0
            while selector.get_map():
                now = time.monotonic()
                if now - started >= timeout_seconds:
                    raise TimeoutError('WebKit attempt budget reached; stopping compiler before cache snapshot')
                if now - last_report >= interval:
                    heartbeat(work, started, last_output, byte_count)
                    last_report = time.monotonic()
                for key, _ in selector.select(timeout=1):
                    block = os.read(key.fileobj.fileno(), 65536)
                    if not block:
                        selector.unregister(key.fileobj)
                        break
                    log.write(block)
                    sys.stdout.buffer.write(block)
                    sys.stdout.buffer.flush()
                    byte_count += len(block)
                    last_output = time.monotonic()
            code = process.wait(timeout=30)
            if code:
                raise subprocess.CalledProcessError(code, command)
    finally:
        # Don't let a second cancellation interrupt the bounded cleanup.
        for sig in old:
            signal.signal(sig, signal.SIG_IGN)
        try:
            stop_container(work)
        finally:
            try:
                if process:
                    if process.poll() is None:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass  # Client exited between poll and kill.
                    process.wait(timeout=30)
                    process.stdout.close()
            finally:
                for sig, handler in old.items():
                    signal.signal(sig, handler)


def copy_cache(source, destination):
    """Only completed ccache 4 result/manifest entries, never tmp/config/locks.

    Stats are harmless useful counters. Do not copy arbitrary cache-root files
    (including configurations that can override the controlled build settings).
    """
    if not source.exists():
        return
    for bucket in source.iterdir():
        if not re.fullmatch('[0-9a-f]', bucket.name) or bucket.is_symlink() or not bucket.is_dir():
            continue
        for root, dirs, files in os.walk(bucket, followlinks=False):
            root = pathlib.Path(root)
            dirs[:] = [d for d in dirs if re.fullmatch('[0-9a-f]', d) and not (root / d).is_symlink()]
            for name in files:
                path = root / name
                if path.is_symlink() or not path.is_file():
                    continue
                if name != 'stats' and not re.fullmatch('[0-9a-z]+[MR]', name):
                    continue
                target = destination / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)


def cache_snapshot(work):
    work.mkdir(parents=True, exist_ok=True)
    with (work / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        stop_container(work)
        target = work / 'ccache-snapshot'
        temporary = work / 'ccache-snapshot.new'
        shutil.rmtree(temporary, ignore_errors=True)
        temporary.mkdir()
        copy_cache(work / 'ccache', temporary)
        shutil.rmtree(target, ignore_errors=True)
        temporary.replace(target)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['snapshot', 'restore', 'preflight'])
    parser.add_argument('--work-dir', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    if args.action == 'snapshot':
        cache_snapshot(args.work_dir.resolve())
    elif args.action == 'restore':
        with (args.work_dir / '.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            copy_cache(args.work_dir / 'ccache-snapshot', args.work_dir / 'ccache')
    else:
        preflight(args.work_dir, json.loads((pathlib.Path(__file__).parent / 'manifest.json').read_text()))
