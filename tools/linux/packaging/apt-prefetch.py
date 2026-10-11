#!/usr/bin/env python3
"""Parallel transport only; APT still verifies signed-index hashes at install."""
import concurrent.futures
import pathlib
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request

output = subprocess.check_output(['apt-get', '--print-uris', '--yes', '--no-install-recommends',
                                  'install', *sys.argv[1:]], text=True)
inputs = [shlex.split(line) for line in output.splitlines() if line.startswith("'https://")]


def download(row):
    url, filename = row[:2]
    if pathlib.Path(filename).name != filename:
        raise ValueError('Unexpected APT cache name')
    dest = pathlib.Path('/var/cache/apt/archives') / filename
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(url, timeout=180) as response, dest.open('wb') as f:
                while data := response.read(1024 * 1024):
                    f.write(data)
            print('Prefetched', filename, flush=True)
            return
        except (urllib.error.URLError, TimeoutError):
            dest.unlink(missing_ok=True)
            if attempt == 5:
                raise
            time.sleep(min(2 ** attempt, 30))


with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    for result in pool.map(download, inputs):
        pass
