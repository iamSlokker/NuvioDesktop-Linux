#!/usr/bin/env python3
"""Read a single-file Flatpak bundle's header without installing or importing it.

A Flatpak bundle is one serialized OSTree static-delta superblock GVariant:
``(a{sv} metadata, t, ay from, ay to, commit, ...)``. Flatpak stores the ref,
``--repo-url`` (``origin``), ``--runtime-repo``, ``--gpg-keys`` and the commit's
detached metadata (signatures) in that metadata dictionary. This reader decodes
only that subset, lazily, from a memory map. It needs no PyGObject, network,
display or Flatpak installation. ``encode`` exists for test fixtures.
"""
import hashlib
import mmap
import pathlib

SUPERBLOCK = '(a{sv}tayay(a{sv}aya(say)sstayay)aya(uayttay)a(yaytt))'
COMMIT = '(a{sv}aya(say)sstayay)'
_FIXED = {'y': 1, 'b': 1, 'n': 2, 'q': 2, 'i': 4, 'u': 4, 'h': 4, 'x': 8, 't': 8, 'd': 8}


def _end(sig, i):
    """Index just past the single complete type starting at sig[i]."""
    c = sig[i]
    if c in _FIXED or c in 'sogv':
        return i + 1
    if c == 'a':
        return _end(sig, i + 1)
    if c in '({':
        i += 1
        while sig[i] not in ')}':
            i = _end(sig, i)
        return i + 1
    raise ValueError('Unsupported GVariant type: ' + sig[i:])


def _members(sig):
    inner, i, out = sig[1:-1], 0, []
    while i < len(inner):
        j = _end(inner, i)
        out.append(inner[i:j])
        i = j
    return out


def _align(sig):
    if sig[0] in _FIXED:
        return _FIXED[sig[0]]
    if sig[0] == 'v':
        return 8
    if sig[0] == 'a':
        return _align(sig[1:])
    if sig[0] in '({':
        return max([_align(m) for m in _members(sig)] or [1])
    return 1


def _fixed(sig):
    if sig[0] in _FIXED:
        return _FIXED[sig[0]]
    if sig[0] not in '({':
        return None
    size = 0
    for member in _members(sig):
        fixed = _fixed(member)
        if fixed is None:
            return None
        size = _up(size, _align(member)) + fixed
    return _up(size, _align(sig)) or 1


def _up(value, alignment):
    return -(-value // alignment) * alignment


def _offset_size(size):
    return 0 if size == 0 else 1 if size <= 0xff else 2 if size <= 0xffff else 4 if size <= 0xffffffff else 8


def _read(buf, pos, size):
    return int.from_bytes(buf[pos:pos + size], 'little')


def _tuple_ranges(sig, buf, start, end):
    members, osz = _members(sig), _offset_size(end - start)
    pos, framing, ranges = start, end, []
    for index, member in enumerate(members):
        pos = start + _up(pos - start, _align(member))
        fixed = _fixed(member)
        if fixed is not None:
            stop = pos + fixed
        elif index == len(members) - 1:
            stop = framing
        else:
            framing -= osz
            stop = start + _read(buf, framing, osz)
        if not start <= pos <= stop <= end:
            raise ValueError('Malformed GVariant tuple framing')
        ranges.append((member, pos, stop))
        pos = stop
    return ranges


def _array_ranges(sig, buf, start, end):
    element = sig[1:]
    fixed = _fixed(element)
    if fixed is not None:
        if (end - start) % fixed:
            raise ValueError('Malformed fixed-size GVariant array')
        return [(element, p, p + fixed) for p in range(start, end, fixed)]
    if end == start:
        return []
    osz = _offset_size(end - start)
    table = start + _read(buf, end - osz, osz)
    if not start <= table <= end - osz or (end - table) % osz:
        raise ValueError('Malformed GVariant array framing')
    pos, ranges = start, []
    for slot in range(table, end, osz):
        pos = start + _up(pos - start, _align(element))
        stop = start + _read(buf, slot, osz)
        if not start <= pos <= stop <= table:
            raise ValueError('Malformed GVariant array element')
        ranges.append((element, pos, stop))
        pos = stop
    return ranges


def _variant(buf, start, end):
    split = buf.rfind(b'\0', start, end)
    if split < start:
        raise ValueError('Malformed GVariant variant')
    return bytes(buf[split + 1:end]).decode('ascii'), start, split


def decode(sig, buf, start=0, end=None):
    """Fully decode one value; dictionaries become Python dicts."""
    end = len(buf) if end is None else end
    c = sig[0]
    if c in _FIXED:
        if end - start != _FIXED[c]:
            raise ValueError('Malformed GVariant number')
        return _read(buf, start, end - start)
    if c in 'sog':
        if end <= start or buf[end - 1] != 0:
            raise ValueError('Malformed GVariant string')
        return bytes(buf[start:end - 1]).decode('utf-8')
    if c == 'v':
        inner, vstart, vend = _variant(buf, start, end)
        return inner, decode(inner, buf, vstart, vend)
    if sig == 'ay':
        return bytes(buf[start:end])
    if c == 'a':
        items = [decode(t, buf, s, e) for t, s, e in _array_ranges(sig, buf, start, end)]
        return dict(items) if sig[1] == '{' else items
    if c in '({':
        return tuple(decode(t, buf, s, e) for t, s, e in _tuple_ranges(sig, buf, start, end))
    raise ValueError('Unsupported GVariant type: ' + sig)


def _dict_sv(buf, start, end, wanted):
    """Decode only selected keys of an a{sv}; returns {key: (type, value)}."""
    found = {}
    for entry, s, e in _array_ranges('a{sv}', buf, start, end):
        (_, ks, ke), (_, vs, ve) = _tuple_ranges(entry, buf, s, e)
        key = decode('s', buf, ks, ke)
        if wanted(key):
            inner, start_v, end_v = _variant(buf, vs, ve)
            found[key] = inner, decode(inner, buf, start_v, end_v)
    return found


def _offsets(size, count):
    for osz in (1, 2, 4, 8):
        if _offset_size(size + count * osz) <= osz:
            return osz
    raise ValueError('GVariant container too large')


def encode(sig, value):
    """Serialize ``value`` as normal-form little-endian GVariant (fixtures only)."""
    c = sig[0]
    if c in _FIXED:
        return int(value).to_bytes(_FIXED[c], 'little')
    if c in 'sog':
        return value.encode('utf-8') + b'\0'
    if c == 'v':
        inner, inner_value = value
        return encode(inner, inner_value) + b'\0' + inner.encode('ascii')
    if sig == 'ay':
        return bytes(value)
    if c == 'a':
        element = sig[1:]
        items = list(value.items()) if element[0] == '{' else list(value)
        body, ends = bytearray(), []
        for item in items:
            body += b'\0' * (_up(len(body), _align(element)) - len(body))
            body += encode(element, item)
            ends.append(len(body))
        if _fixed(element) is not None or not ends:
            return bytes(body)
        osz = _offsets(len(body), len(ends))
        return bytes(body) + b''.join(e.to_bytes(osz, 'little') for e in ends)
    if c in '({':
        members, body, ends = _members(sig), bytearray(), []
        for index, (member, item) in enumerate(zip(members, value)):
            body += b'\0' * (_up(len(body), _align(member)) - len(body))
            body += encode(member, item)
            if _fixed(member) is None and index != len(members) - 1:
                ends.append(len(body))
        if _fixed(sig) is not None:
            return bytes(body) + b'\0' * (_fixed(sig) - len(body))
        osz = _offsets(len(body), len(ends))
        return bytes(body) + b''.join(e.to_bytes(osz, 'little') for e in reversed(ends))
    raise ValueError('Unsupported GVariant type: ' + sig)


def openpgp_keys(data):
    """v4 fingerprints of primary/sub public keys in a binary OpenPGP keyring.

    Rejects ASCII armor, secret-key packets and unsupported key versions so
    that a mistaken export can never be embedded or published.
    """
    data = bytes(data)
    if not data or data.lstrip().startswith(b'-----BEGIN'):
        raise ValueError('Public key must be a binary OpenPGP export (gpg --export, without --armor)')
    try:
        keys = _packets(data)
    except IndexError:
        raise ValueError('Truncated OpenPGP data') from None
    primaries = [fpr for kind, fpr in keys if kind == 'primary']
    if len(primaries) != 1:
        raise ValueError('Public key file must contain exactly one primary key, found %d' % len(primaries))
    return dict(primary=primaries[0], subkeys=[fpr for kind, fpr in keys if kind == 'subkey'])


def _packets(data):
    pos, keys = 0, []
    while pos < len(data):
        header = data[pos]
        if not header & 0x80:
            raise ValueError('Malformed OpenPGP packet')
        if header & 0x40:
            tag, first = header & 0x3f, data[pos + 1]
            if first < 192:
                length, pos = first, pos + 2
            elif first < 224:
                length, pos = ((first - 192) << 8) + data[pos + 2] + 192, pos + 3
            elif first == 255:
                length, pos = int.from_bytes(data[pos + 2:pos + 6], 'big'), pos + 6
            else:
                raise ValueError('Unsupported partial-length OpenPGP packet')
        else:
            tag, kind = (header >> 2) & 0xf, header & 3
            if kind == 3:
                raise ValueError('Unsupported indeterminate-length OpenPGP packet')
            size = 1 << kind
            length, pos = int.from_bytes(data[pos + 1:pos + 1 + size], 'big'), pos + 1 + size
        body = data[pos:pos + length]
        if len(body) != length:
            raise ValueError('Truncated OpenPGP packet')
        pos += length
        if tag in (5, 7):
            raise ValueError('Refusing an OpenPGP keyring that contains secret key material')
        if tag in (6, 14):
            if body[0] != 4:
                raise ValueError('Only OpenPGP v4 keys are supported (found v%d)' % body[0])
            digest = hashlib.sha1(b'\x99' + len(body).to_bytes(2, 'big') + body).hexdigest().upper()
            keys.append(('primary' if tag == 6 else 'subkey', digest))
    return keys


def read_bundle(path):
    """Header summary of a .flatpak bundle (ref, commit, repo/runtime URLs, key, signatures)."""
    with open(path, 'rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as buf:
        ranges = _tuple_ranges(SUPERBLOCK, buf, 0, len(buf))
        _, ms, me = ranges[0]
        meta = _dict_sv(buf, ms, me, lambda key: not key.startswith('deltas/') or key.endswith('/commitmeta'))
        to = decode('ay', buf, *ranges[3][1:])
        _, cs, ce = ranges[4]
        commit_bytes = bytes(buf[cs:ce])
    if meta.get('ostree.endianness', ('y', ord('l')))[1] != ord('l'):
        raise ValueError('Unsupported big-endian Flatpak bundle')
    if len(to) != 32 or hashlib.sha256(commit_bytes).digest() != to:
        raise ValueError('Bundle commit object does not match its declared checksum')
    commit = decode(COMMIT, commit_bytes)
    values = {key: value for key, (sig, value) in meta.items()}
    for key, sig in [('ref', 's'), ('origin', 's'), ('runtime-repo', 's'), ('gpg-keys', 'ay'), ('metadata', 's')]:
        if key in meta and meta[key][0] != sig:
            raise ValueError('Unexpected bundle metadata type for ' + key)
    if 'ref' not in values:
        raise ValueError('Bundle has no ref')
    detached = [value for key, (sig, value) in meta.items() if key.endswith('/commitmeta') and sig == 'a{sv}']
    signatures = sum(len(d.get('ostree.gpgsigs', ('aay', []))[1]) for d in detached)
    gpg_keys = values.get('gpg-keys') or None
    try:
        fingerprint = openpgp_keys(gpg_keys)['primary'] if gpg_keys else None
    except ValueError:
        fingerprint = 'invalid'
    # OSTree stores the commit timestamp big-endian inside the little-endian variant.
    timestamp = int.from_bytes(commit[5].to_bytes(8, 'little'), 'big')
    return dict(ref=values['ref'], commit=to.hex(), subject=commit[3], timestamp=timestamp,
                origin=values.get('origin') or None, runtime_repo=values.get('runtime-repo') or None,
                gpg_keys=gpg_keys, gpg_fingerprint=fingerprint,
                signatures=signatures, metadata=values.get('metadata'))


def fixture_bundle(ref, timestamp=0, **metadata):
    """Bytes of a minimal, structurally valid bundle header (tests only).

    ``metadata`` maps names to (type, value), e.g. origin=('s', url); a
    ``signatures`` list becomes the commit's detached GPG signatures.
    """
    signatures = metadata.pop('signatures', None)
    # OSTree keeps the commit timestamp big-endian; see read_bundle.
    fields = ({}, b'', [], 'Export fixture', '', int.from_bytes(timestamp.to_bytes(8, 'big'), 'little'),
              bytes(32), bytes(32))
    meta = {'ref': ('s', ref), 'ostree.endianness': ('y', ord('l'))}
    meta.update({key.replace('_', '-'): value for key, value in metadata.items()})
    if signatures is not None:
        meta['deltas/fixture/commitmeta'] = ('a{sv}', {'ostree.gpgsigs': ('aay', signatures)})
    digest = hashlib.sha256(encode(COMMIT, fields)).digest()
    return encode(SUPERBLOCK, (meta, 0, b'', digest, fields, [], [], []))


def check_unpublished_bundle(path, ref, runtime_repo):
    """Contract for a CI-exported bundle: unsigned, so no repo URL or key embedded.

    An embedded key makes Flatpak require a signature on install; an embedded
    repo URL without a key would subscribe installs to unverified updates.
    Only the owner-side signed rebundle (flatpak_repo.py) carries both.
    """
    info = read_bundle(path)
    if info['ref'] != ref:
        raise ValueError('Flatpak bundle ref %r differs from expected %r' % (info['ref'], ref))
    if info['origin'] or info['gpg_keys'] or info['signatures']:
        raise ValueError('CI Flatpak bundle must not embed a repository URL, key or signature')
    if info['runtime_repo'] != runtime_repo:
        raise ValueError('Flatpak bundle runtime repository differs from %s' % runtime_repo)
    return {key: info[key] for key in ['ref', 'commit', 'timestamp', 'runtime_repo', 'origin', 'signatures']}


if __name__ == '__main__':
    import json
    import sys
    summary = read_bundle(pathlib.Path(sys.argv[1]))
    summary['gpg_keys'] = len(summary['gpg_keys']) if summary['gpg_keys'] else None
    summary.pop('metadata')
    print(json.dumps(summary, indent=2))
