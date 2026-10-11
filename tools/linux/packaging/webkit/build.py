#!/usr/bin/env python3
"""AppImage-only pinned engine stage; never rebuilds media or the application.

Outputs are candidates until the separate relocation/sandbox/payload audit passes.
An output directory or successful compilation alone is not a validation marker.
"""
import argparse
import fcntl
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

MANIFEST = json.loads((HERE / 'manifest.json').read_text())
BUILD_INPUTS = ['manifest.json', 'Containerfile', 'build.py', 'build-engine.sh', 'build-gtk.sh',
                'prepare-source.py', 'prepare-gtk.py', 'clean-gtk-resources.py', 'gtk-private-resources.patch']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def recipe_identity(base):
    paths = [HERE / name for name in BUILD_INPUTS] + sorted((HERE / 'patches').glob('*.patch'))
    inputs = [(str(p.relative_to(HERE)), digest(p)) for p in paths]
    inputs.append(('../apt-prefetch.py', digest(HERE.parent / 'apt-prefetch.py')))
    return hashlib.sha256(json.dumps([base, inputs], sort_keys=True).encode()).hexdigest()


def inventory(root, source=False):
    result = {}
    for p in sorted(root.rglob('*')):
        relative = str(p.relative_to(root))
        if p.is_symlink():
            if os.path.isabs(os.readlink(p)):
                raise ValueError('Absolute engine symlink: ' + relative)
            # The pinned source archive contains an unused dangling Skia/Bazel
            # link. Record it verbatim; runtime/cache payload links must resolve.
            if (not source and not p.exists()) or not p.resolve().is_relative_to(root.resolve()):
                raise ValueError('Escaping/broken engine symlink: ' + relative)
            result[relative] = {'link': os.readlink(p)}
        elif p.is_file():
            result[relative] = {'sha256': digest(p), 'mode': p.stat().st_mode & 0o7777}
        elif not p.is_dir():
            raise ValueError('Special file in engine cache: ' + relative)
    return result


def valid_cache(generation, identity):
    record = generation / 'build-complete.json'
    if not record.is_file():
        return False
    data = json.loads(record.read_text())
    if data['recipe_identity'] != identity or data['source_sha256'] != MANIFEST['source']['sha256']:
        raise ValueError('Engine cache provenance mismatch')
    if data['files'] != inventory(generation / 'install/opt/nuvio-webkit'):
        raise ValueError('Engine cache inventory mismatch; refusing reuse')
    from webkit.contract import require_files
    require_files(generation / 'install/opt/nuvio-webkit')
    if data['build_policy'] != build_policy(generation):
        raise ValueError('Engine build policy mismatch')
    if data['corresponding_source'] != inventory(generation / 'corresponding-source'):
        raise ValueError('Engine corresponding-source inventory mismatch')
    return True


def build_policy(generation):
    values = {}
    for line in (generation / 'objects/CMakeCache.txt').read_text().splitlines():
        if ':' in line and '=' in line and not line.startswith(('//', '#')):
            key, value = line.split('=', 1)
            values[key.split(':', 1)[0]] = value
    expected = dict(DEVELOPER_MODE='OFF', ENABLE_BUBBLEWRAP_SANDBOX='ON',
                    CMAKE_BUILD_TYPE='Release', USE_GTK4='OFF', USE_SOUP2='OFF')
    if any(values.get(key) != value for key, value in expected.items()):
        raise ValueError('Engine was not configured with the production/sandbox/ABI policy')
    for key in ['CMAKE_C_FLAGS_RELEASE', 'CMAKE_CXX_FLAGS_RELEASE']:
        for flag in ['-fstack-protector-strong', '-D_FORTIFY_SOURCE=2']:
            if flag not in values.get(key, '').split():
                raise ValueError('Missing compiler hardening: ' + key + ': ' + flag)
        expected[key] = values[key]
    for key in ['CMAKE_SHARED_LINKER_FLAGS', 'CMAKE_MODULE_LINKER_FLAGS', 'CMAKE_EXE_LINKER_FLAGS']:
        if '-z,relro,-z,now' not in values.get(key, ''):
            raise ValueError('Missing linker hardening: ' + key)
        expected[key] = values[key]
    return expected


def retain_source(generation, archive, gtk_inputs):
    destination = generation / 'corresponding-source'
    destination.mkdir(exist_ok=True)
    shutil.copy2(archive, destination / archive.name)
    shutil.copytree(gtk_inputs, destination / 'gtk-inputs', dirs_exist_ok=True)
    shutil.copytree(HERE, destination / 'recipe', dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copy2(HERE.parent / 'apt-prefetch.py', destination / 'apt-prefetch.py')
    for name in ['gtk-resource-paths.patch', 'gtk-resource-paths.json',
                 'webkit-builder-packages.tsv', 'build-resources.txt', 'gtk-build-resources.txt',
                 'container-memory-peak.bytes']:
        shutil.copy2(generation / name, destination / name)
    for source_root, name in [(generation / 'source' / ('webkitgtk-' + MANIFEST['version']), 'WebKit'),
                               (generation / 'gtk-source', 'GTK')]:
        for source in source_root.rglob('*'):
            if source.is_file() and source.name.startswith(('LICENSE', 'COPYING')):
                target = destination / 'notices' / name / source.relative_to(source_root)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    return inventory(destination)


def main():
    from fetch import fetch
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packaging-work-dir', type=pathlib.Path, required=True)
    parser.add_argument('--work-dir', type=pathlib.Path)
    parser.add_argument('--source-archive', type=pathlib.Path, help='Optional already downloaded pinned archive')
    parser.add_argument('--result-file', type=pathlib.Path, help='Write the selected verified generation for the packaging driver')
    args = parser.parse_args()
    if any(os.environ.get(k) for k in ['DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS']):
        parser.error('Unset display/live-test variables')
    packaging = args.packaging_work_dir.resolve()
    work = (args.work_dir or packaging.parent / 'linux-webkit').resolve()
    if work == HERE or HERE.is_relative_to(work) or work.is_relative_to(HERE) or work == packaging:
        parser.error('Unsafe engine output directory')
    base = json.loads((packaging / 'canonical/release-info.json').read_text())['builder_image_id']
    work.mkdir(parents=True, exist_ok=True)
    with (work / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        identity = recipe_identity(base)
        generation = work / 'generations' / identity
        if valid_cache(generation, identity):
            if args.result_file:
                args.result_file.write_text(json.dumps(dict(generation=str(generation), recipe_identity=identity)) + '\n')
            print(generation / 'install/opt/nuvio-webkit')
            return
        if shutil.disk_usage(work).free < MANIFEST['minimum_free_disk_gib'] * 1024**3:
            raise ValueError('At least 40 GiB free disk required for a cold engine build')
        archive = work / 'downloads' / ('webkitgtk-' + MANIFEST['version'] + '.tar.xz')
        if args.source_archive:
            if digest(args.source_archive) != MANIFEST['source']['sha256']:
                raise ValueError('Pinned WebKit source digest mismatch')
            archive.parent.mkdir(parents=True, exist_ok=True)
            if args.source_archive.resolve() != archive:
                shutil.copy2(args.source_archive, archive)
        fetch(MANIFEST['source'], archive)
        gtk_inputs = work / 'downloads/gtk-source'
        for name, specification in MANIFEST['gtk_sources'].items():
            fetch(specification, gtk_inputs / name)
        generation.mkdir(parents=True, exist_ok=True)
        # A recipe key names its source/objects. Interrupted builds can resume;
        # new source, patch, compiler image or build flags select a new directory.
        builder_key = hashlib.sha256((HERE / 'Containerfile').read_bytes()
                                     + (HERE.parent / 'apt-prefetch.py').read_bytes() + base.encode()).hexdigest()
        image = 'localhost/nuvio-webkit-builder:' + builder_key
        with tempfile.TemporaryDirectory(prefix='nuvio-webkit-builder-') as tmp:
            shutil.copy2(HERE.parent / 'apt-prefetch.py', tmp)
            subprocess.run(['podman', 'build', '--pull=never', '--network=host', '--file', str(HERE / 'Containerfile'),
                            '--build-arg', 'PACKAGING_BUILDER_IMAGE=' + base, '--tag', image, tmp], check=True)
        image_id = subprocess.check_output(['podman', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip()
        ccache = work / 'ccache'
        ccache.mkdir(exist_ok=True)
        memory = str(MANIFEST['memory_gib']) + 'g'
        command = ['podman', 'run', '--rm', '--network=none', '--userns=keep-id', '--cap-drop=all',
                   '--security-opt', 'label=disable', '--security-opt', 'no-new-privileges',
                   '--memory=' + memory, '--memory-swap=' + memory, '--cpus=' + str(MANIFEST['jobs']),
                   '--env', 'NUVIO_WEBKIT_RECIPE_ID=' + identity, '--env', 'PYTHONDONTWRITEBYTECODE=1',
                   '--pids-limit=512', '--mount', f'type=bind,src={generation},dst=/work',
                   '--mount', f'type=bind,src={ccache},dst=/work/ccache',
                   '--mount', f'type=bind,src={archive},dst=/source-archive.tar.xz,ro',
                   '--mount', f'type=bind,src={gtk_inputs},dst=/gtk-inputs,ro',
                   '--mount', f'type=bind,src={HERE},dst=/recipe,ro', image_id, 'bash', '-ec',
                   'python3 /recipe/prepare-source.py; '
                   'python3 /recipe/test-source-policy.py /source-archive.tar.xz; '
                   'bash /recipe/build-gtk.sh; python3 /recipe/test-relocation.py; bash /recipe/build-engine.sh']
        with (generation / 'build.log').open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        record = dict(recipe_identity=identity, source_sha256=MANIFEST['source']['sha256'],
                      builder_image_id=image_id, files=inventory(generation / 'install/opt/nuvio-webkit'),
                      build_policy=build_policy(generation),
                      corresponding_source=retain_source(generation, archive, gtk_inputs),
                      acceptance='BUILT_ONLY')
        temporary = generation / 'build-complete.json.tmp'
        temporary.write_text(json.dumps(record, indent=2) + '\n')
        temporary.replace(generation / 'build-complete.json')
        if args.result_file:
            args.result_file.write_text(json.dumps(dict(generation=str(generation), recipe_identity=identity)) + '\n')
        print(generation / 'install/opt/nuvio-webkit')


if __name__ == '__main__':
    main()
