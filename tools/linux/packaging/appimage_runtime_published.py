#!/usr/bin/env python3
"""Recreate the pinned AppImage runtime directory from an already published release.

appimage_runtime.py builds the type2 runtime in a digest-pinned Alpine image with exact apk
versions, and Alpine drops superseded apk versions from its mirrors: once one is gone (musl
1.2.5-r11, 2026-10-21) the toolchain image can no longer be built, although the runtime has not
changed. Every published AppImage starts with that runtime and every vX.Y.Z-source release
carries its source archive, so the same directory is recreated without Alpine:

- the runtime is the AppImage's leading ELF image with the sections appimagetool fills
  (.digest_md5, .upd_info, .sha256_sig, .sig_key) zeroed again; it must hash to runtime_sha256;
- licenses and the build receipt come from the published source archive, which is reused as is.

appimage_runtime.verify_output() then checks the result exactly as it checks a fresh build.
"""
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import struct
import sys
import tarfile
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import appimage_runtime  # noqa: E402

APPIMAGETOOL_SECTIONS = ('.digest_md5', '.upd_info', '.sha256_sig', '.sig_key')
RUNTIME_HEAD_BYTES = 4 * 1024 * 1024  # the runtime is ~1 MB; only this much of an AppImage is fetched
DEFAULT_REPOSITORY = 'iamSlokker/NuvioDesktop-Linux'


def runtime_from_appimage(head):
    """The AppImage's leading ELF image as the runtime build produced it."""
    head = bytes(head)
    if head[:4] != b'\x7fELF' or head[4] != 2 or head[5] != 1:
        raise ValueError('AppImage does not start with a little-endian ELF64 runtime')
    shoff = struct.unpack_from('<Q', head, 0x28)[0]
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', head, 0x3A)
    end = shoff + shentsize * shnum
    if shentsize != 64 or not 0 < end <= len(head):
        raise ValueError('Runtime section headers are outside the fetched AppImage head')
    runtime = bytearray(head[:end])

    def section(index):
        name, _, _, _, offset, size = struct.unpack_from('<IIQQQQ', runtime, shoff + index * shentsize)
        return name, offset, size

    names = section(shstrndx)[1]
    found = set()
    for index in range(shnum):
        name, offset, size = section(index)
        label = bytes(runtime[names + name:runtime.index(b'\0', names + name)]).decode()
        if label in APPIMAGETOOL_SECTIONS:
            if offset + size > end:
                raise ValueError('AppImage section %s lies outside the runtime' % label)
            runtime[offset:offset + size] = bytes(size)
            found.add(label)
    if '.digest_md5' not in found:
        raise ValueError('Runtime lacks the .digest_md5 section appimagetool fills')
    return bytes(runtime)


def recreate(destination, runtime, source_archive, provenance):
    """Writes the runtime directory verify_output() expects and verifies it."""
    expected = appimage_runtime.expected_sha256()
    if hashlib.sha256(runtime).hexdigest() != expected:
        raise ValueError('Published runtime is not the pinned runtime ' + expected)
    top = appimage_runtime.archive_name(expected)[:-len('.tar.gz')]
    destination = pathlib.Path(destination)
    shutil.rmtree(destination, ignore_errors=True)
    licenses = destination / 'licenses'
    licenses.mkdir(parents=True)
    with tarfile.open(source_archive) as tar:
        receipt = None
        for member in tar.getmembers():
            parts = pathlib.PurePosixPath(member.name).parts
            if not parts or parts[0] != top:
                raise ValueError('Source archive is not ' + top)
            if not member.isfile():
                continue
            if parts[1:] == ('BUILD-RECEIPT.json',):
                receipt = json.loads(tar.extractfile(member).read())
            elif len(parts) > 2 and parts[1] == 'licenses':
                target = licenses.joinpath(*parts[2:])
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(member).read())
    if receipt is None or receipt['runtime']['sha256'] != expected:
        raise ValueError('Source archive receipt does not describe the pinned runtime')
    (destination / appimage_runtime.RUNTIME).write_bytes(runtime)
    (destination / appimage_runtime.RUNTIME).chmod(0o755)
    archive = destination / appimage_runtime.archive_name(expected)
    shutil.copyfile(source_archive, archive)
    receipt['source_archive'] = {'file': archive.name, 'sha256': appimage_runtime.sha256(archive)}
    receipt['licenses'] = {name: appimage_runtime.sha256(licenses / name)
                           for name in appimage_runtime.required_notices()}
    receipt['recreated_from'] = provenance
    (destination / appimage_runtime.RECEIPT).write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    return appimage_runtime.verify_output(destination, expected)


def _get(url, token=None, byte_range=None):
    request = urllib.request.Request(url, headers={'User-Agent': 'nuvio-linux-packaging'})
    if token and url.startswith('https://api.github.com/'):
        request.add_header('Authorization', 'Bearer ' + token)
    if byte_range:
        request.add_header('Range', 'bytes=%d-%d' % byte_range)
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def from_github(destination, work, repository=None, token=None, releases=15):
    """Recreates the runtime from the newest published release whose AppImage carries it."""
    repository = repository or os.environ.get('GITHUB_REPOSITORY') or DEFAULT_REPOSITORY
    token = token if token is not None else os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    listing = json.loads(_get('https://api.github.com/repos/%s/releases?per_page=50' % repository, token))
    by_tag = {release['tag_name']: release for release in listing if not release.get('draft')}
    tried = []
    for release in listing:
        tag = release['tag_name']
        if release.get('draft') or release.get('prerelease') or not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
            continue
        if len(tried) >= releases:
            break
        tried.append(tag)
        source = by_tag.get(tag + '-source')
        appimage = [a for a in release['assets'] if a['name'].endswith('-x86_64.AppImage')]
        archive = [a for a in (source or {}).get('assets', []) if a['name'].endswith('-source-appimage-runtime.tar.gz')]
        if len(appimage) != 1 or len(archive) != 1:
            continue
        head = _get(appimage[0]['browser_download_url'], byte_range=(0, RUNTIME_HEAD_BYTES - 1))
        try:
            runtime = runtime_from_appimage(head)
        except ValueError:
            continue
        if hashlib.sha256(runtime).hexdigest() != appimage_runtime.expected_sha256():
            continue
        path = pathlib.Path(work) / archive[0]['name']
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_get(archive[0]['browser_download_url']))
        return recreate(destination, runtime, path, {
            'release': tag, 'appimage': appimage[0]['name'], 'source_archive': archive[0]['name'],
            'source_archive_sha256': appimage_runtime.sha256(path)})
    raise ValueError('No published release carries runtime %s (checked %s)'
                     % (appimage_runtime.expected_sha256(), ', '.join(tried) or 'none'))


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--work', type=pathlib.Path, required=True)
    parser.add_argument('--repository')
    args = parser.parse_args()
    result = from_github(args.output, args.work, args.repository)
    print(json.dumps({'runtime_sha256': result['receipt']['runtime']['sha256'],
                      'recreated_from': result['receipt']['recreated_from']}, indent=2))


if __name__ == '__main__':
    main()
