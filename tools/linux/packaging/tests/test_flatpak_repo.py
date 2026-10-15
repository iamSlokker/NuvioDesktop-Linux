#!/usr/bin/env python3
"""Self-hosted Flatpak repository contracts; no network, display, or real keyring."""
import base64
import configparser
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import flatpak_bundle as fb  # noqa: E402
import flatpak_repo  # noqa: E402

REF = 'app/com.nuvio.media.desktop/x86_64/stable'
# GLib.Variant('a{sv}', {'ref': <'app/x/x86_64/stable'>, 'gpg-keys': <b'\x99\x00\x01'>,
# 'size': <uint64 7>}).get_data_as_bytes(), generated with PyGObject 3.x.
GOLDEN = bytes.fromhex(
    '72656600000000006170702f782f7838365f36342f737461626c6500007304006770672d6b65797300000000'
    '00000000990001006179090073697a650000000007000000000000000074051f374b')


def key_packet(tag, body, new_format=False):
    if new_format:
        return bytes([0xc0 | tag, len(body)]) + body
    return bytes([0x80 | (tag << 2) | 1]) + len(body).to_bytes(2, 'big') + body


def v4_key(seed):
    return b'\x04' + seed.to_bytes(4, 'big') + b'\x01' + bytes([seed % 256]) * 20


def fingerprint(body):
    return hashlib.sha1(b'\x99' + len(body).to_bytes(2, 'big') + body).hexdigest().upper()


class GVariantTest(unittest.TestCase):
    def test_golden_glib_serialization_round_trips(self):
        expected = {'ref': ('s', 'app/x/x86_64/stable'), 'gpg-keys': ('ay', b'\x99\x00\x01'), 'size': ('t', 7)}
        self.assertEqual(expected, fb.decode('a{sv}', GOLDEN))
        self.assertEqual(GOLDEN, fb.encode('a{sv}', expected))

    def test_offset_widths_and_nested_containers_round_trip(self):
        for sig, value in [('aay', [b'', b'x' * 300, b'y']), ('aay', [b'z' * 70000, b'']),
                           ('as', ['a', 'bb', '']), ('a(yt)', [(1, 2), (3, 4)]), ('(sty)', ('a', 7, 1)),
                           (fb.COMMIT, ({}, b'', [], 'subject', 'body', 9, bytes(32), bytes(32)))]:
            with self.subTest(sig=sig):
                self.assertEqual(value, fb.decode(sig, fb.encode(sig, value)))

    def test_malformed_framing_is_rejected(self):
        data = bytearray(GOLDEN)
        data[-1] = 0xff
        with self.assertRaises(ValueError):
            fb.decode('a{sv}', bytes(data))


class BundleHeaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / 'fixture.flatpak'

    def read(self, **metadata):
        self.path.write_bytes(fb.fixture_bundle(REF, timestamp=1791978405, **metadata))
        return fb.read_bundle(self.path)

    def test_reads_ref_commit_timestamp_and_links(self):
        key = key_packet(6, v4_key(1))
        info = self.read(origin=('s', 'https://example.invalid/repo/'), runtime_repo=('s', 'https://rt'),
                         gpg_keys=('ay', key), signatures=[b'one', b'two'],
                         **{'deltas/fixture/0': ('ay', bytes(1000))})
        self.assertEqual(REF, info['ref'])
        self.assertEqual(1791978405, info['timestamp'])
        self.assertEqual(64, len(info['commit']))
        self.assertEqual('https://example.invalid/repo/', info['origin'])
        self.assertEqual(key, info['gpg_keys'])
        self.assertEqual(fingerprint(v4_key(1)), info['gpg_fingerprint'])
        self.assertEqual(2, info['signatures'])

    def test_commit_checksum_must_match_declared_target(self):
        data = bytearray(fb.fixture_bundle(REF, timestamp=5))
        index = data.index(b'Export fixture')
        data[index] ^= 1
        self.path.write_bytes(bytes(data))
        with self.assertRaisesRegex(ValueError, 'does not match its declared checksum'):
            fb.read_bundle(self.path)

    def test_ci_contract_accepts_only_unlinked_unsigned_bundles(self):
        self.path.write_bytes(fb.fixture_bundle(REF, runtime_repo=('s', 'https://rt')))
        self.assertIsNone(fb.check_unpublished_bundle(self.path, REF, 'https://rt')['origin'])
        bad = [dict(origin=('s', 'https://x/')), dict(gpg_keys=('ay', key_packet(6, v4_key(2)))),
               dict(signatures=[b's'])]
        for extra in bad:
            with self.subTest(extra=sorted(extra)):
                self.path.write_bytes(fb.fixture_bundle(REF, runtime_repo=('s', 'https://rt'), **extra))
                with self.assertRaisesRegex(ValueError, 'must not embed'):
                    fb.check_unpublished_bundle(self.path, REF, 'https://rt')
        with self.assertRaisesRegex(ValueError, 'ref'):
            fb.check_unpublished_bundle(self.path, REF.replace('stable', 'nightly'), 'https://rt')
        self.path.write_bytes(fb.fixture_bundle(REF))
        with self.assertRaisesRegex(ValueError, 'runtime repository'):
            fb.check_unpublished_bundle(self.path, REF, 'https://rt')


class OpenPgpTest(unittest.TestCase):
    def test_primary_and_subkey_fingerprints_old_and_new_format(self):
        primary, sub = v4_key(3), v4_key(4)
        data = key_packet(6, primary) + key_packet(13, b'uid') + key_packet(14, sub, new_format=True)
        self.assertEqual(dict(primary=fingerprint(primary), subkeys=[fingerprint(sub)]), fb.openpgp_keys(data))

    def test_rejects_armor_secret_material_versions_and_ambiguity(self):
        cases = [(b'-----BEGIN PGP PUBLIC KEY BLOCK-----\n', 'binary'),
                 (key_packet(6, v4_key(5)) + key_packet(5, v4_key(5)), 'secret'),
                 (key_packet(7, v4_key(5)), 'secret'),
                 (key_packet(6, b'\x05' + v4_key(5)[1:]), 'v4'),
                 (key_packet(6, v4_key(5)) + key_packet(6, v4_key(6)), 'exactly one'),
                 (key_packet(13, b'uid'), 'exactly one'),
                 (key_packet(6, v4_key(5))[:-3], 'Truncated'),
                 (b'\x00', 'Malformed')]
        for data, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    fb.openpgp_keys(data)


class PublisherGateTest(unittest.TestCase):
    def setUp(self):
        self.config = flatpak_repo.load_config()
        self.key = key_packet(6, v4_key(7))
        self.info = dict(application_id='com.nuvio.media.desktop', channel='stable', dirty=False,
                         build_kind='release', version='1.16.0', version_code=27,
                         source_date_epoch=1800000000, git_commit='a' * 40)
        self.header = dict(ref=REF, commit='b' * 64, timestamp=1800000000, origin=None,
                           gpg_keys=None, runtime_repo=self.config['runtime_repo'])
        self.current = dict(commit='c' * 64, info=dict(self.info, version='1.15.0', version_code=26,
                                                        source_date_epoch=1790000000))

    def check(self, header=None, info=None, current='default', expected=None):
        flatpak_repo.check_candidate(self.config, self.key, header or self.header, info or self.info,
                                     self.current if current == 'default' else current, expected)

    def test_committed_configuration_is_https_stable_and_uses_application_id(self):
        self.assertEqual(REF, self.config['ref'])
        self.assertTrue(self.config['repo_url'].startswith('https://iamslokker.github.io/NuvioDesktop-Linux/'))
        self.assertTrue(self.config['repo_url'].startswith(self.config['site_url']))
        with tempfile.TemporaryDirectory() as tmp:
            local = pathlib.Path(tmp) / 'repo.json'
            local.write_text(json.dumps(dict(json.loads(flatpak_repo.DEFAULT_CONFIG.read_text()),
                                             repo_url='file:///tmp/repo/')))
            with self.assertRaisesRegex(ValueError, 'https'):
                flatpak_repo.load_config(local)
            self.assertEqual('file:///tmp/repo/', flatpak_repo.load_config(local, allow_local_url=True)['repo_url'])

    def test_newer_clean_release_is_accepted_including_first_publish(self):
        self.check()
        self.check(current=None)
        self.check(info=dict(self.info, version_code=26))  # same version code, later source
        self.check(header=dict(self.header, origin=self.config['repo_url'], gpg_keys=self.key))

    def test_refuses_older_equal_republished_or_time_regressing_commits(self):
        cases = [(dict(current=dict(self.current, commit='b' * 64)), 'already published'),
                 (dict(info=dict(self.info, version_code=25)), 'older/equal'),
                 (dict(info=dict(self.info, version_code=26, source_date_epoch=1790000000),
                       header=dict(self.header, timestamp=1790000000)), 'older/equal'),
                 (dict(info=dict(self.info, source_date_epoch=1780000000),
                       header=dict(self.header, timestamp=1780000000)), 'older/equal')]
        for kwargs, message in cases:
            with self.subTest(message=message, kwargs=sorted(kwargs)):
                with self.assertRaisesRegex(ValueError, message):
                    self.check(**kwargs)

    def test_refuses_wrong_identity_development_builds_and_foreign_links(self):
        cases = [(dict(header=dict(self.header, ref=REF.replace('stable', 'nightly'))), 'published ref'),
                 (dict(info=dict(self.info, channel='nightly')), 'stable build'),
                 (dict(info=dict(self.info, dirty=True)), 'clean release'),
                 (dict(info=dict(self.info, build_kind='development')), 'clean release'),
                 (dict(header=dict(self.header, timestamp=1)), 'timestamp'),
                 (dict(header=dict(self.header, origin='https://evil.invalid/repo/')), 'repo URL'),
                 (dict(header=dict(self.header, gpg_keys=key_packet(6, v4_key(8)))), 'GPG key'),
                 (dict(header=dict(self.header, runtime_repo='https://other/')), 'runtime repository'),
                 (dict(expected=dict(self.info, git_commit='d' * 40)), 'release-info')]
        for kwargs, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    self.check(**kwargs)

    def test_signing_key_must_be_the_full_committed_fingerprint(self):
        public = fb.openpgp_keys(self.key)
        with self.assertRaisesRegex(ValueError, '40-hex'):
            flatpak_repo.signing_key(public['primary'][-16:], public)
        with self.assertRaisesRegex(ValueError, 'not the committed'):
            flatpak_repo.signing_key('A' * 40, public)

    def test_committed_public_key_is_valid_or_publishing_fails_closed(self):
        path = flatpak_repo.REPO_DIR / self.config['public_key']
        if path.exists():
            data, public = flatpak_repo.load_public_key(path)
            self.assertEqual(40, len(public['primary']))
            self.assertNotIn(b'PRIVATE', data)
        else:
            with self.assertRaisesRegex(ValueError, 'create-signing-key.sh'):
                flatpak_repo.load_public_key(path)


class SiteFilesTest(unittest.TestCase):
    def test_repo_ref_and_index_files_carry_url_key_and_escaped_text(self):
        config = dict(flatpak_repo.load_config(), title='Nuvio <Desktop>')
        key = key_packet(6, v4_key(9))
        public = fb.openpgp_keys(key)
        info = dict(version='1.16.0', git_commit='e' * 40, version_code=27, source_date_epoch=1)
        with tempfile.TemporaryDirectory() as tmp:
            pages = pathlib.Path(tmp)
            (pages / '.gitignore').write_text('existing\n')
            flatpak_repo.write_site(pages, config, key, public, info, 'f' * 64)
            site = pages / config['site_dir']
            repo = configparser.ConfigParser(interpolation=None)
            repo.read_string((site / 'nuvio.flatpakrepo').read_text())
            ref = configparser.ConfigParser(interpolation=None)
            ref.read_string((site / 'com.nuvio.media.desktop.flatpakref').read_text())
            for section in (repo['Flatpak Repo'], ref['Flatpak Ref']):
                self.assertEqual(config['repo_url'], section['Url'])
                self.assertEqual(key, base64.b64decode(section['GPGKey']))
            self.assertEqual('stable', ref['Flatpak Ref']['Branch'])
            self.assertEqual('nuvio', ref['Flatpak Ref']['SuggestRemoteName'])
            self.assertEqual(config['runtime_repo'], ref['Flatpak Ref']['RuntimeRepo'])
            self.assertEqual(key, (site / config['public_key']).read_bytes())
            page = (site / 'index.html').read_text()
            self.assertIn('Nuvio &lt;Desktop&gt;', page)
            self.assertNotIn('<Desktop>', page)
            self.assertIn(public['primary'], page)
            self.assertIn('flatpak install --user ' + config['site_url'] + 'com.nuvio.media.desktop.flatpakref', page)
            self.assertTrue((pages / '.nojekyll').is_file())
            ignored = (pages / '.gitignore').read_text().splitlines()
            self.assertEqual(['existing', '/flatpak/repo/tmp/', '/flatpak/repo/state/', '/flatpak/repo/.lock'], ignored)
            self.assertEqual('f' * 64, json.loads((site / 'release.json').read_text())['commit'])

    def test_site_size_limits_follow_github(self):
        config = flatpak_repo.load_config()
        with tempfile.TemporaryDirectory() as tmp:
            pages = pathlib.Path(tmp)
            (pages / '.git').mkdir()
            with open(pages / '.git/huge', 'wb') as stream:
                stream.truncate(flatpak_repo.MAX_FILE_BYTES + 1)  # sparse; git internals are ignored
            (pages / 'small').write_bytes(b'x' * 10)
            self.assertEqual(10, flatpak_repo.check_site_size(pages, config))
            with open(pages / 'big', 'wb') as stream:
                stream.truncate(flatpak_repo.MAX_FILE_BYTES)
            with self.assertRaisesRegex(ValueError, '100 MiB'):
                flatpak_repo.check_site_size(pages, config)


class PackagingIntegrationTest(unittest.TestCase):
    def test_ci_export_links_runtime_but_never_the_unsigned_repository(self):
        code = '\n'.join(line for line in (HERE / 'flatpak.py').read_text().splitlines()
                         if not line.strip().startswith('#'))
        self.assertIn("'--runtime-repo=' + runtime_repo", code)
        self.assertNotIn('--repo-url', code)
        self.assertNotIn('--gpg-keys', code)

    def test_release_hook_never_pushes_and_shell_syntax_is_valid(self):
        for name in ['publish-release.sh', 'create-signing-key.sh']:
            script = HERE / 'flatpak-repo' / name
            subprocess.run(['bash', '-n', str(script)], check=True)
            self.assertTrue(os.access(script, os.X_OK))
        hook = (HERE / 'flatpak-repo/publish-release.sh').read_text()
        commands = [line.strip() for line in hook.splitlines() if not line.strip().startswith(('#', 'echo'))]
        self.assertFalse([line for line in commands if 'push' in line])
        self.assertIn('--orphan', hook)

    def test_key_helper_creates_exports_and_backs_up_in_a_disposable_keyring(self):
        with tempfile.TemporaryDirectory(prefix='nuvio-gpg-') as tmp:
            tmp = pathlib.Path(tmp)
            home = tmp / 'gnupg'
            home.mkdir(mode=0o700)
            env = {k: v for k, v in os.environ.items() if k not in flatpak_repo.DISPLAY_VARS}
            env.update(GNUPGHOME=str(home))
            helper = HERE / 'flatpak-repo/create-signing-key.sh'
            args = [str(helper), '--no-passphrase', '--backup-dir', str(tmp / 'backup'),
                    '--public-key-out', str(tmp / 'repo.pub.gpg'), '--uid', 'Fixture <fixture@example.invalid>']
            try:
                result = subprocess.run(args, env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                public = fb.openpgp_keys((tmp / 'repo.pub.gpg').read_bytes())
                self.assertIn('Created signing key ' + public['primary'], result.stdout)
                backups = sorted(p.name for p in (tmp / 'backup').iterdir())
                self.assertIn('nuvio-flatpak-%s.secret.asc' % public['primary'], backups)
                self.assertIn('nuvio-flatpak-%s.revocation.asc' % public['primary'], backups)
                self.assertEqual(0o600, (tmp / 'backup' / backups[0]).stat().st_mode & 0o777)
                self.assertEqual(public['primary'],
                                 flatpak_repo.signing_key(public['primary'], public, gpg_homedir=home))
                again = subprocess.run(args, env=env, capture_output=True, text=True)
                self.assertNotEqual(0, again.returncode)
                self.assertIn('already exists', again.stderr)
            finally:
                subprocess.run(['gpgconf', '--kill', 'gpg-agent'], env=env, capture_output=True)


if __name__ == '__main__':
    unittest.main()
