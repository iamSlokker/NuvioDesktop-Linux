#!/usr/bin/env python3
"""Retain exact Ubuntu source inputs; never install, build, extract or upload.

The index command runs ONLY inside the already verified immutable builder.
The lock command independently checks Ubuntu signatures and signed SHA256s.
The fetch command consumes that reviewed lock, retaining original source bytes.
This is source evidence, not proof of complete build/relink or license closure.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import urllib.request

SNAPSHOT = 'https://snapshot.ubuntu.com/ubuntu/20261001T000000Z/'
SIGNER = 'F6ECB3762474EDA9D21B7022871920D1991BC93C'
SUITES = ('jammy', 'jammy-updates', 'jammy-security')
SECTIONS = ('main', 'universe')



def file_digest(stream, algorithm):
    # hashlib.file_digest needs Python 3.11; the CI runner's host Python is 3.10.
    digest = hashlib.new(algorithm)
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest

def sha(path):
    with path.open('rb') as f:
        return file_digest(f, 'sha256').hexdigest()


def paragraphs(text):
    result, row, key = [], {}, None
    for line in text.splitlines() + ['']:
        if not line.strip():
            if row:
                result.append(row)
            row, key = {}, None
        elif line[0].isspace():
            if key is None:
                raise ValueError('Unattached source index continuation')
            row[key] += '\n' + line[1:]
        else:
            key, sep, value = line.partition(':')
            if not sep or key in row:
                raise ValueError('Malformed/duplicate index field')
            row[key] = value.lstrip()
    return result


def safe_path(name):
    p = PurePosixPath(name)
    if (not name or not p.parts or name != p.as_posix() or p.is_absolute() or '..' in p.parts
            or '\\' in name or not re.fullmatch(r'[A-Za-z0-9_./+~:@-]+', name)):
        raise ValueError('Unsafe source path: ' + name)
    return p


def hash_rows(value):
    rows = {}
    for line in value.strip().splitlines():
        digest, size, name = line.split()
        safe_path(name)
        if name in rows or not re.fullmatch('[a-f0-9]{64}', digest) or not size.isdigit():
            raise ValueError('Malformed/duplicate source SHA256')
        rows[name] = {'sha256': digest, 'size': int(size)}
    if not rows:
        raise ValueError('Empty SHA256 index')
    return rows


def prepare_indexes(output):
    """APT checks signatures; retain plaintext indexes for independent verification."""
    output.mkdir(parents=True, exist_ok=False)
    lists = output / 'apt-lists'; lists.mkdir(); (lists / 'partial').mkdir()
    sources = output / 'sources.list'
    sources.write_text(''.join(f'deb-src {SNAPSHOT} {s} main universe\n' for s in SUITES))
    command = ['apt-get', '-o', 'Acquire::Check-Valid-Until=false',
               '-o', 'APT::Update::Error-Mode=any', '-o', 'APT::Sandbox::User=root',
               '-o', 'Dir::Etc::sourcelist=' + str(sources),
               '-o', 'Dir::Etc::sourceparts=-', '-o', 'Dir::State::lists=' + str(lists), 'update']
    with (output / 'apt-update.log').open('wb') as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
    shutil.copyfile('/usr/share/keyrings/ubuntu-archive-keyring.gpg', output / 'ubuntu-archive-keyring.gpg')
    for suite in SUITES:
        matches = list(lists.glob('*_dists_' + suite + '_InRelease'))
        if len(matches) != 1:
            raise ValueError('Missing/ambiguous signed release: ' + suite)
        shutil.copyfile(matches[0], output / (suite + '.InRelease'))
        for section in SECTIONS:
            matches = list(lists.glob('*_dists_' + suite + '_' + section + '_source_Sources*'))
            if len(matches) != 1:
                raise ValueError('Missing/ambiguous Sources index')
            with (output / (suite + '-' + section + '.Sources')).open('wb') as f:
                subprocess.run(['/usr/lib/apt/apt-helper', 'cat-file', str(matches[0])], stdout=f, check=True)


def verify_indexes(directory):
    records, evidence = [], []
    for suite in SUITES:
        signed = directory / (suite + '.InRelease')
        # --output - gives only the cryptographically verified cleartext; never
        # parse attacker-supplied cleartext independently of signature success.
        result = subprocess.run(['gpgv', '--homedir', str(directory.resolve()), '--status-fd', '2', '--keyring',
                                 str((directory / 'ubuntu-archive-keyring.gpg').resolve()),
                                 '--output', '-', str(signed)], capture_output=True, check=True)
        status = result.stderr.decode()
        if not any(line.startswith('[GNUPG:] VALIDSIG ' + SIGNER + ' ') for line in status.splitlines()):
            raise ValueError('Unexpected Ubuntu signing key')
        release = paragraphs(result.stdout.decode())
        if len(release) != 1 or release[0].get('Codename') != 'jammy' or release[0].get('Suite') != suite:
            raise ValueError('Wrong signed release identity')
        hashes = hash_rows(release[0]['SHA256'])
        item = {'suite': suite, 'signer': SIGNER, 'inrelease_sha256': sha(signed), 'indexes': []}
        for section in SECTIONS:
            name = section + '/source/Sources'
            path = directory / (suite + '-' + section + '.Sources')
            expected = hashes[name]
            if path.is_symlink() or path.stat().st_size != expected['size'] or sha(path) != expected['sha256']:
                raise ValueError('Source index differs from signed release: ' + name)
            item['indexes'].append(dict(path=path.name, **expected))
            records += paragraphs(path.read_text())
        evidence.append(item)
    return records, evidence


def source_lock(provenance, records, evidence):
    wanted = {}
    for binary, row in provenance['packages'].items():
        key = (row['source_package'], row['source_version'])
        wanted.setdefault(key, []).append({'package': binary, 'version': row['binary_version']})
    result, missing = [], []
    for (package, version), binaries in sorted(wanted.items()):
        candidates = [r for r in records if r.get('Package') == package and r.get('Version') == version]
        if not candidates:
            missing.append(package + '=' + version)
            continue
        selections = []
        for row in candidates:
            directory = safe_path(row['Directory'])
            if directory.parts[0] != 'pool':
                raise ValueError('Source outside archive pool')
            files = hash_rows(row['Checksums-Sha256'])
            if sum(n.endswith('.dsc') for n in files) != 1:
                raise ValueError('Source package missing dsc or has multiple dsc inputs')
            selection = []
            for name, record in sorted(files.items()):
                if len(safe_path(name).parts) != 1:
                    raise ValueError('Non-basename source input')
                selection.append(dict(filename=name, url=SNAPSHOT + str(directory / name), **record))
            selections.append(selection)
        # A source may migrate between main/universe with identical version and
        # bytes. Location aliases are safe only when every filename/hash/size agrees.
        identities = [[{k: v for k, v in f.items() if k != 'url'} for f in s] for s in selections]
        if any(s != identities[0] for s in identities):
            raise ValueError('Conflicting authenticated source records: ' + package)
        result.append({'package': package, 'version': version, 'binaries': sorted(binaries, key=lambda r: r['package']),
                       'inputs': selections[0]})
    if missing:
        raise ValueError('Exact source versions absent; never substitute: ' + ', '.join(missing))
    return {'schema_version': 1, 'scope': 'exact Ubuntu source inputs for supplied package inventory; not full OCI/native static closure',
            'builder_image_id': provenance['builder_image_id'], 'snapshot': SNAPSHOT,
            'authenticated_indexes': evidence, 'source_packages': result,
            'availability': 'local retention only; no recipient offer or legal certification'}


def verify_file(path, spec):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != spec['size'] or sha(path) != spec['sha256']:
        raise ValueError('Missing/tampered source archive: ' + spec['filename'])


def source_control_text(data):
    """Parse .dsc cleartext already authenticated by the signed index SHA256.

    The maintainer's optional clearsign is not our trust anchor; do not execute
    GPG/decrypt or extract source archives to read these RFC822 control fields.
    """
    text = data.decode('utf-8').replace('\r\n', '\n')
    if text.startswith('-----BEGIN PGP SIGNED MESSAGE-----\n'):
        _, separator, signed = text.partition('\n\n')
        if not separator or '\n-----BEGIN PGP SIGNATURE-----\n' not in signed:
            raise ValueError('Malformed clearsigned dsc')
        text, signature = signed.split('\n-----BEGIN PGP SIGNATURE-----\n', 1)
        if '-----END PGP SIGNATURE-----' not in signature:
            raise ValueError('Truncated clearsigned dsc')
        text = '\n'.join(line[2:] if line.startswith('- ') else line for line in text.splitlines())
    rows = paragraphs(text)
    if len(rows) != 1:
        raise ValueError('Malformed dsc control fields')
    return rows[0]


def verify_dsc_closure(row, output):
    inputs = row['inputs']
    descriptors = [item for item in inputs if item['filename'].endswith('.dsc')]
    if len(descriptors) != 1 or len({item['filename'] for item in inputs}) != len(inputs):
        raise ValueError('Source package requires exactly one dsc and unique input names')
    descriptor = descriptors[0]
    path = output / descriptor['sha256']
    verify_file(path, descriptor)
    control = source_control_text(path.read_bytes())
    if control.get('Source') != row['package'] or control.get('Version') != row['version']:
        raise ValueError('dsc source identity differs from signed source index')
    expected = hash_rows(control.get('Checksums-Sha256', ''))
    if any(len(safe_path(name).parts) != 1 for name in expected):
        raise ValueError('Non-basename dsc source input')
    actual = {item['filename']: {'sha256': item['sha256'], 'size': item['size']}
              for item in inputs if item is not descriptor}
    if expected != actual:
        raise ValueError('dsc input closure differs from signed source index')
    for item in inputs:
        verify_file(output / item['sha256'], item)


def safe_output_directory(output):
    # Do not follow even an ancestor link into an unrelated/user data directory.
    absolute = output.absolute()
    for component in reversed((absolute, *absolute.parents)):
        if component.is_symlink():
            raise ValueError('Symlinked source output directory: ' + str(component))
    output.mkdir(parents=True, exist_ok=True)


def retain(lock, output, offline=False):
    safe_output_directory(output)
    unique = {}
    for row in lock['source_packages']:
        for spec in row['inputs']:
            safe_path(spec['filename'])
            if len(PurePosixPath(spec['filename']).parts) != 1 or not re.fullmatch('[a-f0-9]{64}', spec['sha256']):
                raise ValueError('Unsafe source lock')
            if not spec['url'].startswith(SNAPSHOT + 'pool/') or '..' in spec['url'].split('/'):
                raise ValueError('Source URL outside pinned snapshot')
            if not isinstance(spec['size'], int) or spec['size'] <= 0:
                raise ValueError('Invalid source size')
            if spec['sha256'] in unique and unique[spec['sha256']]['size'] != spec['size']:
                raise ValueError('Conflicting source size')
            unique[spec['sha256']] = spec
    def get(spec):
        path = output / spec['sha256']
        if path.exists() or path.is_symlink() or offline:
            verify_file(path, spec)
            return
        partial = output / (spec['sha256'] + '.partial')
        created = False
        try:
            with urllib.request.urlopen(spec['url'], timeout=90) as src, partial.open('xb') as dst:
                created = True
                if not src.url.startswith('https://'):
                    raise ValueError('Insecure source redirect')
                remaining = spec['size']
                while chunk := src.read(min(1024 * 1024, remaining + 1)):
                    remaining -= len(chunk)
                    if remaining < 0:
                        raise ValueError('Source exceeds signed size')
                    dst.write(chunk)
            verify_file(partial, spec)
            # rename() would overwrite a final entry created by another process
            # after the initial exists() check. link() atomically refuses that.
            os.link(partial, path, follow_symlinks=False)
        finally:
            if created:
                partial.unlink(missing_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(get, unique.values()))
    for row in lock['source_packages']:
        verify_dsc_closure(row, output)
    return {'verified_inputs': len(unique), 'bytes': sum(s['size'] for s in unique.values()),
            'verified_source_packages': len(lock['source_packages']),
            'scope': lock['scope'], 'uploaded': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    i = sub.add_parser('indexes'); i.add_argument('--output', type=Path, required=True)
    l = sub.add_parser('lock'); l.add_argument('--indexes', type=Path, required=True)
    l.add_argument('--provenance', type=Path, required=True); l.add_argument('--output', type=Path, required=True)
    f = sub.add_parser('fetch'); f.add_argument('--lock', type=Path, required=True)
    f.add_argument('--output', type=Path, required=True); f.add_argument('--offline', action='store_true')
    a = p.parse_args()
    if a.command == 'indexes':
        prepare_indexes(a.output)
    elif a.command == 'lock':
        records, evidence = verify_indexes(a.indexes)
        result = source_lock(json.loads(a.provenance.read_text()), records, evidence)
        with a.output.open('x') as stream:
            stream.write(json.dumps(result, indent=2, sort_keys=True) + '\n')
        print('Locked', len(result['source_packages']), 'exact source packages')
    else:
        print(json.dumps(retain(json.loads(a.lock.read_text()), a.output, a.offline), indent=2))


if __name__ == '__main__':
    main()
