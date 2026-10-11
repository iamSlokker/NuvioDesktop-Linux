#!/usr/bin/env python3
"""Only immutable, SHA-256-checked inputs; never execute a mutable latest tool."""
import hashlib
import json
import pathlib
import subprocess
import sys

CONFIG = json.loads((pathlib.Path(__file__).parent / 'config.json').read_text())


def fetch(spec, output):
    output = pathlib.Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.is_file() or hashlib.sha256(output.read_bytes()).hexdigest() != spec['sha256']:
        temporary = output.with_suffix('.part')
        subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--tlsv1.2',
                        '--retry', '3', '--output', str(temporary), spec['url']], check=True)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != spec['sha256']:
            temporary.unlink()
            raise ValueError('Input checksum mismatch: ' + spec['url'])
        temporary.replace(output)
    return output


if __name__ == '__main__':
    fetch(CONFIG['tools'][sys.argv[1]], sys.argv[2])
