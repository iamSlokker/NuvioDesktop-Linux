#!/usr/bin/env python3
"""Owner-side signed Flatpak repository for GitHub Pages. Never pushes or launches.

``publish`` takes a verified CI bundle (unsigned; see flatpak_bundle.py) and a
local checkout of the Pages branch. It imports the exact CI commit into
``<pages>/<site_dir>/repo`` (OSTree archive), signs that commit and the summary,
generates static deltas, prunes unreachable objects and writes the
.flatpakrepo/.flatpakref/key/index.html. Optionally it writes a signed rebundle
of the same commit that carries --repo-url/--gpg-keys, so installing it
subscribes to the hosted repository. Everything is then verified offline in a
disposable Flatpak user installation through a file:// copy of the repo file.

It refuses: a missing/invalid committed public key, a signing key that is not
that key, non-release or wrong-ref bundles, mismatching embedded repo URL/key,
and anything not strictly newer than the published commit. Git commit/push of
the Pages checkout is left to flatpak-repo/publish-release.sh and the owner.
"""
import argparse
import base64
import configparser
import html
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import flatpak_bundle  # noqa: E402
from fetch import CONFIG  # noqa: E402

REPO_DIR = HERE / 'flatpak-repo'
DEFAULT_CONFIG = REPO_DIR / 'repo.json'
RELEASE_INFO = '/files/lib/nuvio-desktop/release-info.json'
# GitHub rejects pushed blobs over 100 MiB and limits a Pages site to 1 GB.
MAX_FILE_BYTES = 100 * 1024 * 1024
MAX_SITE_BYTES = 1000 * 1000 * 1000
DISPLAY_VARS = ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS')


def load_config(path=DEFAULT_CONFIG, allow_local_url=False):
    config = json.loads(pathlib.Path(path).read_text())
    for key in ['arch', 'branch', 'site_url', 'repo_url', 'site_dir', 'remote_name', 'title',
                'comment', 'homepage', 'runtime_repo', 'public_key', 'prune_depth']:
        if key not in config:
            raise ValueError('Flatpak repository config lacks ' + key)
    schemes = ('https://', 'file://') if allow_local_url else ('https://',)
    if not config['repo_url'].startswith(schemes) or not config['repo_url'].endswith('/'):
        raise ValueError('Repository URL must be https:// and end with "/": ' + config['repo_url'])
    if not isinstance(config['prune_depth'], int) or config['prune_depth'] < 0:
        raise ValueError('prune_depth must be a non-negative integer')
    config['app_id'] = CONFIG['application_id']
    config['ref'] = 'app/%s/%s/%s' % (config['app_id'], config['arch'], config['branch'])
    return config


def load_public_key(path):
    """Fail closed: publishing needs the committed, binary, public-only key."""
    path = pathlib.Path(path)
    if not path.is_file():
        raise ValueError('Missing Flatpak repository public key %s. Create the signing key with '
                         'tools/linux/packaging/flatpak-repo/create-signing-key.sh and commit the '
                         'exported public key; publishing is refused until then.' % path)
    data = path.read_bytes()
    return data, flatpak_bundle.openpgp_keys(data)


def child_env(extra=None, drop=()):
    env = {k: v for k, v in os.environ.items() if k not in DISPLAY_VARS and k not in drop}
    env.update(extra or {})
    return env


def signing_env():
    # gpg-agent may need the owner's display/TTY for a passphrase pinentry;
    # nothing here starts the application or a GUI test.
    return {k: v for k, v in os.environ.items() if k != 'NUVIO_RUN_LIVE_DISPLAY_TESTS'}


def run(*args, env=None):
    subprocess.run([str(a) for a in args], check=True, env=env or child_env())


def output(*args, env=None):
    return subprocess.check_output([str(a) for a in args], text=True, env=env or child_env()).strip()


def signing_key(fingerprint, public, gpg_homedir=None):
    """The requested secret key must be the committed public key's primary key."""
    fingerprint = fingerprint.upper().replace(' ', '')
    if len(fingerprint) != 40 or any(c not in '0123456789ABCDEF' for c in fingerprint):
        raise ValueError('Use the full 40-hex-digit signing key fingerprint, not a short key ID')
    if fingerprint != public['primary']:
        raise ValueError('Signing key %s is not the committed repository key %s' % (fingerprint, public['primary']))
    home = ['--homedir', str(gpg_homedir)] if gpg_homedir else []
    listing = subprocess.run(['gpg', *home, '--batch', '--with-colons', '--list-secret-keys', '--', fingerprint],
                             capture_output=True, text=True, env=child_env())
    secret = [line.split(':')[9] for line in listing.stdout.splitlines() if line.startswith('fpr:')]
    if listing.returncode or fingerprint not in secret:
        raise ValueError('The secret key %s is not available in this GnuPG home' % fingerprint)
    return fingerprint


def gpg_args(fingerprint, gpg_homedir=None):
    return ['--gpg-sign=' + fingerprint] + (['--gpg-homedir=' + str(gpg_homedir)] if gpg_homedir else [])


def repo_refs(repo):
    return set(output('ostree', '--repo=' + str(repo), 'refs').split())


def release_info(repo, ref):
    return json.loads(output('ostree', '--repo=' + str(repo), 'cat', ref, RELEASE_INFO))


def check_candidate(config, public_bytes, header, info, current=None, expected_info=None):
    """Pure release gate; ``current`` is {commit, info} of the published ref or None."""
    if header['ref'] != config['ref']:
        raise ValueError('Bundle ref %s is not the published ref %s' % (header['ref'], config['ref']))
    if info.get('application_id') != config['app_id'] or info.get('channel') != config['branch']:
        raise ValueError('Bundle release-info is not a %s %s build' % (config['app_id'], config['branch']))
    if info.get('dirty') is not False or info.get('build_kind') != 'release':
        raise ValueError('Only clean release builds may be published')
    if header['timestamp'] != info.get('source_date_epoch'):
        raise ValueError('Bundle commit timestamp differs from its source epoch')
    if header['origin'] and header['origin'] != config['repo_url']:
        raise ValueError('Bundle repo URL %s differs from %s' % (header['origin'], config['repo_url']))
    if header['gpg_keys'] and header['gpg_keys'] != public_bytes:
        raise ValueError('Bundle embeds a GPG key other than the committed repository key')
    if header['runtime_repo'] and header['runtime_repo'] != config['runtime_repo']:
        raise ValueError('Bundle runtime repository differs from ' + config['runtime_repo'])
    if expected_info is not None and expected_info != info:
        raise ValueError('Bundle release-info differs from the verified CI release-info.json')
    if current:
        old = current['info']
        if current['commit'] == header['commit']:
            raise ValueError('Commit %s is already published' % header['commit'])
        new_order = (int(info['version_code']), int(info['source_date_epoch']))
        old_order = (int(old['version_code']), int(old['source_date_epoch']))
        # Flatpak clients also reject an update whose commit is chronologically older.
        if new_order <= old_order or new_order[1] < old_order[1]:
            raise ValueError('Refusing to replace published %s (code %s, epoch %s) with older/equal %s '
                             '(code %s, epoch %s)' % (old['version'], old['version_code'], old['source_date_epoch'],
                                                       info['version'], info['version_code'], info['source_date_epoch']))


def repo_file(config, public_bytes, url=None):
    key = base64.b64encode(public_bytes).decode()
    return ('[Flatpak Repo]\nTitle=%s\nUrl=%s\nHomepage=%s\nComment=%s\nDefaultBranch=%s\nGPGKey=%s\n' %
            (config['title'], url or config['repo_url'], config['homepage'], config['comment'], config['branch'], key))


def ref_file(config, public_bytes, url=None):
    key = base64.b64encode(public_bytes).decode()
    return ('[Flatpak Ref]\nName=%s\nBranch=%s\nTitle=%s\nUrl=%s\nSuggestRemoteName=%s\nRuntimeRepo=%s\n'
            'IsRuntime=false\nHomepage=%s\nComment=%s\nGPGKey=%s\n' %
            (config['app_id'], config['branch'], config['title'], url or config['repo_url'], config['remote_name'],
             config['runtime_repo'], config['homepage'], config['comment'], key))


def index_html(config, public, info, commit):
    e = html.escape
    site, remote = config['site_url'], config['remote_name']
    ref_url = site + config['app_id'] + '.flatpakref'
    repo_url = site + remote + '.flatpakrepo'
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{e(config['title'])} Flatpak repository</title>
<style>
:root {{ color-scheme: light dark; --bg: #fff; --fg: #1d1d1f; --muted: #5f6368; --code: #f2f2f4; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg: #141416; --fg: #ececf1; --muted: #a0a0aa; --code: #24242a; }} }}
body {{ background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 46rem; padding: 1.5rem 1rem; }}
pre {{ background: var(--code); border-radius: 6px; overflow-x: auto; padding: .75rem; }}
code, pre {{ font-family: ui-monospace, monospace; font-size: .9rem; }}
.muted {{ color: var(--muted); }}
</style>
</head>
<body>
<h1>{e(config['title'])}</h1>
<p>{e(config['comment'])}. Current {e(config['branch'])} release: <strong>{e(str(info['version']))}</strong>
(source <code>{e(info['git_commit'][:12])}</code>, OSTree commit <code>{e(commit[:12])}</code>).</p>
<h2>Install</h2>
<pre>flatpak install --user {e(ref_url)}</pre>
<p>Or add the repository, then install from it:</p>
<pre>flatpak remote-add --user --if-not-exists {e(remote)} {e(repo_url)}
flatpak install --user {e(remote)} {e(config['app_id'])}</pre>
<p>Updates then arrive through <code>flatpak update</code>, GNOME Software/Discover, or the app's update prompt.
The GNOME Platform runtime comes from Flathub.</p>
<h2>Moving from a downloaded .flatpak bundle</h2>
<p>Bundles from earlier releases were not linked to this repository and never update.
Uninstall once (your profile in <code>~/.var/app/{e(config['app_id'])}</code> is kept), then install as above:</p>
<pre>flatpak uninstall --user {e(config['app_id'])}
flatpak install --user {e(ref_url)}</pre>
<h2>Signing key</h2>
<p>Commits and the repository summary are signed with
<a href="{e(config['public_key'])}">this key</a>, fingerprint
<code>{e(public['primary'])}</code>.</p>
<p class="muted">Source and other formats: <a href="{e(config['homepage'])}">{e(config['homepage'])}</a></p>
</body>
</html>
'''


def write_atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name('.' + path.name + '.tmp')
    tmp.write_bytes(data if isinstance(data, bytes) else data.encode())
    os.replace(tmp, path)


def write_site(pages, config, public_bytes, public, info, commit):
    site = pages / config['site_dir']
    write_atomic(site / (config['remote_name'] + '.flatpakrepo'), repo_file(config, public_bytes))
    write_atomic(site / (config['app_id'] + '.flatpakref'), ref_file(config, public_bytes))
    write_atomic(site / config['public_key'], public_bytes)
    write_atomic(site / 'index.html', index_html(config, public, info, commit))
    write_atomic(site / 'release.json', json.dumps(dict(
        ref=config['ref'], commit=commit, version=info['version'], version_code=info['version_code'],
        git_commit=info['git_commit'], source_date_epoch=info['source_date_epoch'],
        key_fingerprint=public['primary']), indent=2) + '\n')
    write_atomic(pages / '.nojekyll', b'')
    ignore = pages / '.gitignore'
    lines = ignore.read_text().splitlines() if ignore.exists() else []
    wanted = ['/%s/repo/%s' % (config['site_dir'], name) for name in ('tmp/', 'state/', '.lock')]
    if any(line not in lines for line in wanted):
        write_atomic(ignore, '\n'.join(lines + [w for w in wanted if w not in lines]) + '\n')


def check_site_size(pages, config):
    total, ignored = 0, [pages / config['site_dir'] / 'repo' / name for name in ('tmp', 'state')]
    for root, dirs, files in os.walk(pages):
        dirs[:] = [d for d in dirs if d != '.git' and pathlib.Path(root, d) not in ignored]
        for name in files:
            size = os.lstat(os.path.join(root, name)).st_size
            if size >= MAX_FILE_BYTES:
                raise ValueError('%s is %d bytes; GitHub rejects files of 100 MiB or more' % (os.path.join(root, name), size))
            total += size
    if total > MAX_SITE_BYTES:
        raise ValueError('Pages tree is %d bytes; GitHub Pages sites are limited to 1 GB' % total)
    return total


def isolated_env(tmp):
    tmp = pathlib.Path(tmp)
    dirs = dict(HOME=tmp / 'home', XDG_DATA_HOME=tmp / 'home/.local/share', XDG_CONFIG_HOME=tmp / 'home/.config',
                XDG_CACHE_HOME=tmp / 'home/.cache', FLATPAK_USER_DIR=tmp / 'user', FLATPAK_SYSTEM_DIR=tmp / 'system')
    for path in dirs.values():
        path.mkdir(parents=True, exist_ok=True)
    return child_env({k: str(v) for k, v in dirs.items()}, drop=('DBUS_SESSION_BUS_ADDRESS',))


def remote_options(env):
    rows = output('flatpak', 'remotes', '--user', '--show-disabled', '--columns=name,url,options', env=env)
    return [row.split('\t') + [''] * (3 - len(row.split('\t'))) for row in rows.splitlines() if row.strip()]


def verify_repo(pages, config, public_bytes, commit, work):
    """Offline: the published .flatpakrepo, pointed at file://, verifies and pulls the signed commit."""
    repo = (pages / config['site_dir'] / 'repo').resolve()
    site = pages / config['site_dir']
    parsed = configparser.ConfigParser(interpolation=None)
    parsed.read_string((site / (config['remote_name'] + '.flatpakrepo')).read_text())
    ref_parsed = configparser.ConfigParser(interpolation=None)
    ref_parsed.read_string((site / (config['app_id'] + '.flatpakref')).read_text())
    for section, expected in [(parsed['Flatpak Repo'], None), (ref_parsed['Flatpak Ref'], config['app_id'])]:
        if section['Url'] != config['repo_url'] or base64.b64decode(section['GPGKey']) != public_bytes:
            raise ValueError('Published repo/ref file URL or key differs from configuration')
        if expected and (section['Name'] != expected or section['Branch'] != config['branch']):
            raise ValueError('Published .flatpakref names the wrong ref')
    with tempfile.TemporaryDirectory(prefix='verify-repo-', dir=work) as tmp:
        env = isolated_env(tmp)
        local_url = repo.as_uri() + '/'
        local = pathlib.Path(tmp) / 'verify.flatpakrepo'
        local.write_text(repo_file(config, public_bytes, local_url))
        run('flatpak', 'remote-add', '--user', 'nuvio-verify', local, env=env)
        if any(name == 'nuvio-verify' and 'no-gpg-verify' in options for name, _, options in remote_options(env)):
            raise ValueError('Verification remote is not GPG-verified')
        remote = output('flatpak', 'remote-info', '--user', '--show-commit', 'nuvio-verify', config['ref'], env=env)
        if remote != commit:
            raise ValueError('Signed summary advertises %s, expected %s' % (remote, commit))
        # Pull with GPG verification of summary and commit; nothing is deployed or run.
        run('flatpak', 'install', '--user', '--noninteractive', '--no-deploy', '--no-related', '--no-deps',
            'nuvio-verify', config['ref'], env=env)
        local_ref = pathlib.Path(tmp) / 'verify.flatpakref'
        local_ref.write_text(ref_file(config, public_bytes, local_url))
        run('flatpak', 'install', '--user', '--noninteractive', '--no-deploy', '--no-related', '--no-deps',
            '--from', local_ref, env=env)
    return dict(remote_commit=remote, gpg_verified_pull=True, flatpakref_install_checked=True)


def build_signed_bundle(pages, config, public_path, destination):
    repo = pages / config['site_dir'] / 'repo'
    run('flatpak', 'build-bundle', '--arch=' + config['arch'], '--repo-url=' + config['repo_url'],
        '--gpg-keys=' + str(public_path), '--runtime-repo=' + config['runtime_repo'],
        repo, destination, config['app_id'], config['branch'])


def verify_signed_bundle(path, config, public_bytes, commit, work):
    header = flatpak_bundle.read_bundle(path)
    if (header['ref'] != config['ref'] or header['commit'] != commit or header['origin'] != config['repo_url']
            or header['gpg_keys'] != public_bytes or header['runtime_repo'] != config['runtime_repo']
            or header['signatures'] < 1):
        raise ValueError('Signed bundle header does not carry the expected ref/commit/repo URL/key/signature')
    with tempfile.TemporaryDirectory(prefix='verify-bundle-', dir=work) as tmp:
        env = isolated_env(tmp)
        # Flatpak refuses this install unless the embedded key verifies the commit.
        run('flatpak', 'install', '--user', '--noninteractive', '--no-deps', '--no-related', '--bundle', path, env=env)
        installed = output('flatpak', 'info', '--user', '--show-commit', config['app_id'] + '//' + config['branch'], env=env)
        origin = output('flatpak', 'info', '--user', '--show-origin', config['app_id'] + '//' + config['branch'], env=env)
        remotes = {name: (url.rstrip('/'), options) for name, url, options in remote_options(env)}
    if installed != commit or origin not in remotes or remotes[origin][0] != config['repo_url'].rstrip('/') \
            or 'no-gpg-verify' in remotes[origin][1]:
        raise ValueError('Installing the signed bundle did not create a GPG-verified origin for the repository')
    return dict(ref=header['ref'], commit=commit, origin_remote=origin, repo_url=header['origin'],
                key_fingerprint=header['gpg_fingerprint'], signatures=header['signatures'])


def publish(args):
    config = load_config(args.config, args.allow_local_url)
    public_path = pathlib.Path(args.public_key or REPO_DIR / config['public_key'])
    public_bytes, public = load_public_key(public_path)
    fingerprint = signing_key(args.gpg_key, public, args.gpg_homedir)
    bundle = pathlib.Path(args.bundle).resolve()
    pages = pathlib.Path(args.pages).resolve()
    if not pages.is_dir():
        raise ValueError('Pages checkout %s does not exist' % pages)
    repo = pages / config['site_dir'] / 'repo'
    header = flatpak_bundle.read_bundle(bundle)
    expected_info = json.loads(pathlib.Path(args.release_info).read_text()) if args.release_info else None
    if not (repo / 'config').is_file():
        if not args.init:
            raise ValueError('%s is not an OSTree repository; pass --init for the first publish' % repo)
        repo.mkdir(parents=True, exist_ok=True)
        run('ostree', '--repo=' + str(repo), 'init', '--mode=archive-z2')
    if output('ostree', '--repo=' + str(repo), 'config', 'get', 'core.mode') != 'archive-z2':
        raise ValueError('Pages repository must be an archive-z2 OSTree repository')
    work_root = pathlib.Path(args.work_dir).resolve() if args.work_dir else bundle.parent
    work = pathlib.Path(tempfile.mkdtemp(prefix='.nuvio-flatpak-publish-', dir=work_root))
    try:
        scratch = work / 'import'
        run('ostree', '--repo=' + str(scratch), 'init', '--mode=archive-z2')
        run('flatpak', 'build-import-bundle', '--no-update-summary', scratch, bundle)
        if output('ostree', '--repo=' + str(scratch), 'rev-parse', header['ref']) != header['commit']:
            raise ValueError('Imported commit differs from the bundle header')
        info = release_info(scratch, header['ref'])
        current = None
        if config['ref'] in repo_refs(repo):
            current = dict(commit=output('ostree', '--repo=' + str(repo), 'rev-parse', config['ref']),
                           info=release_info(repo, config['ref']))
        check_candidate(config, public_bytes, header, info, current, expected_info)
        # Gates passed; mutate only the Pages working tree (git restores it on failure).
        run('ostree', '--repo=' + str(repo), 'pull-local', scratch, config['ref'])
        run('flatpak', 'build-sign', '--arch=' + config['arch'], *gpg_args(fingerprint, args.gpg_homedir),
            repo, config['app_id'], config['branch'], env=signing_env())
        run('flatpak', 'build-update-repo', *gpg_args(fingerprint, args.gpg_homedir),
            '--title=' + config['title'], '--comment=' + config['comment'], '--homepage=' + config['homepage'],
            '--default-branch=' + config['branch'], '--generate-static-deltas', '--prune',
            '--prune-depth=%d' % config['prune_depth'], repo, env=signing_env())
        write_site(pages, config, public_bytes, public, info, header['commit'])
        size = check_site_size(pages, config)
        report = dict(ref=config['ref'], commit=header['commit'], version=info['version'],
                      version_code=info['version_code'], git_commit=info['git_commit'],
                      replaced_commit=current and current['commit'], key_fingerprint=public['primary'],
                      repo_url=config['repo_url'], site_bytes=size,
                      repo_check=verify_repo(pages, config, public_bytes, header['commit'], work))
        if args.signed_bundle_out:
            destination = pathlib.Path(args.signed_bundle_out).resolve()
            partial = destination.with_name('.' + destination.name + '.partial')
            build_signed_bundle(pages, config, public_path, partial)
            report['signed_bundle'] = verify_signed_bundle(partial, config, public_bytes, header['commit'], work)
            os.replace(partial, destination)
            report['signed_bundle']['path'] = str(destination)
        if args.report:
            write_atomic(pathlib.Path(args.report), json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        shutil.rmtree(work, ignore_errors=True)


def verify(args):
    config = load_config(args.config, args.allow_local_url)
    public_bytes, _ = load_public_key(args.public_key or REPO_DIR / config['public_key'])
    pages = pathlib.Path(args.pages).resolve()
    repo = pages / config['site_dir'] / 'repo'
    commit = output('ostree', '--repo=' + str(repo), 'rev-parse', config['ref'])
    work = pathlib.Path(tempfile.mkdtemp(prefix='.nuvio-flatpak-verify-', dir=args.work_dir or pages.parent))
    try:
        report = dict(commit=commit, site_bytes=check_site_size(pages, config),
                      repo_check=verify_repo(pages, config, public_bytes, commit, work))
        if args.signed_bundle:
            report['signed_bundle'] = verify_signed_bundle(pathlib.Path(args.signed_bundle).resolve(), config,
                                                           public_bytes, commit, work)
        print(json.dumps(report, indent=2))
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ['publish', 'verify', 'check-key', 'inspect']:
        p = sub.add_parser(name)
        if name in ('publish', 'verify', 'check-key'):
            p.add_argument('--config', default=str(DEFAULT_CONFIG))
            p.add_argument('--public-key', help='Defaults to the committed flatpak-repo/<public_key>')
            p.add_argument('--allow-local-url', action='store_true', help='Accept a file:// repo_url (local tests only)')
        if name in ('publish', 'verify'):
            p.add_argument('--pages', required=True, help='Local checkout of the GitHub Pages branch')
            p.add_argument('--work-dir', help='Scratch parent for import/verification (needs ~2x bundle size)')
        if name in ('publish', 'check-key'):
            p.add_argument('--gpg-key', required=name == 'publish', help='Full fingerprint of the signing key')
            p.add_argument('--gpg-homedir')
        if name == 'publish':
            p.add_argument('--bundle', required=True, help='Verified CI .flatpak (never modified)')
            p.add_argument('--release-info', help='CI release-info.json that the bundle must match')
            p.add_argument('--signed-bundle-out', help='Write the signed, repo-linked bundle here')
            p.add_argument('--report')
            p.add_argument('--init', action='store_true', help='Create the OSTree repo on first publish')
        if name == 'verify':
            p.add_argument('--signed-bundle')
        if name == 'inspect':
            p.add_argument('bundle')
    args = parser.parse_args(argv)
    try:
        if args.command == 'publish':
            publish(args)
        elif args.command == 'verify':
            verify(args)
        elif args.command == 'check-key':
            config = load_config(args.config, args.allow_local_url)
            _, public = load_public_key(args.public_key or REPO_DIR / config['public_key'])
            if args.gpg_key:
                signing_key(args.gpg_key, public, args.gpg_homedir)
            print(json.dumps(public, indent=2))
        else:
            header = flatpak_bundle.read_bundle(args.bundle)
            header['gpg_keys'] = len(header['gpg_keys']) if header['gpg_keys'] else None
            header.pop('metadata')
            print(json.dumps(header, indent=2))
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit('flatpak_repo: ' + str(error))


if __name__ == '__main__':
    main()
