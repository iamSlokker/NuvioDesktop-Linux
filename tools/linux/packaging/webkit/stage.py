#!/usr/bin/env python3
"""AppImage-only runtime closure, from a verified engine in its pinned builder."""
import hashlib
import difflib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

from .contract import ENGINE_FILES, PROCESSES, HELPERS, MANIFEST, require_files

HERE = pathlib.Path(__file__).resolve().parent
SYSTEM_LIB = pathlib.Path('/usr/lib/x86_64-linux-gnu')
ASSEMBLY_INPUTS = ['build.py', 'formats.py', 'verify.py', 'fetch.py', 'config.json',
                   'webkit/stage.py', 'webkit/contract.py', 'webkit/package.py', 'webkit/nuvio-appimage']


TORRSERVER = 'lib/resources/torrserver/linux-amd64/TorrServer'


def assembly_identity():
    files = {name: hashlib.sha256((HERE.parent / name).read_bytes()).hexdigest() for name in ASSEMBLY_INPUTS}
    identity = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return identity, files


def packaged_build_policy(policy):
    # Full compiler flags (including their container prefix maps) belong in the
    # corresponding-source engine-build.json, not in the relocatable payload.
    switches = ['DEVELOPER_MODE', 'ENABLE_BUBBLEWRAP_SANDBOX', 'CMAKE_BUILD_TYPE', 'USE_GTK4', 'USE_SOUP2']
    return dict(configuration={key: policy[key] for key in switches},
                full_policy_sha256=hashlib.sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest())


def linked_paths(output):
    # ldd prints absolute paths with spaces verbatim. Stop at the address, not
    # the first space (AppImage extraction paths are not restricted to tokens).
    return re.findall(r'^\s*(\S+) => (/[^\n]+?)\s+\(0x[0-9a-fA-F]+\)\s*$', output, re.M)


def is_elf(path):
    if not path.is_file():
        return False
    with path.open('rb') as stream:
        return stream.read(4) == b'\x7fELF'


def dynamic_elves(image):
    """The ELF files whose libraries the AppImage must carry. TorrServer is static (Go, CGO off):
    it needs none, and ldd refuses it. verify.py checks that it really is static."""
    return [p for p in image.rglob('*') if not p.is_symlink() and is_elf(p)
            and p.relative_to(image).as_posix() != TORRSERVER]


def copy_common_licenses(source, destination):
    """Make Ubuntu copyright references self-contained, without host-path assumptions."""
    files = []
    for src in sorted(source.rglob('*')):
        if not src.is_file() and not src.is_symlink():
            continue
        if not src.resolve().is_relative_to(source.resolve()) or not src.is_file() or is_elf(src):
            raise ValueError('Unsafe common-license input: ' + src.name)
        relative = src.relative_to(source)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, target)
        files.append(relative.as_posix())
    if not files:
        raise ValueError('Missing Ubuntu common license texts')
    return files


def clean_icon_sources(originals, destination, source_output):
    """Clean installed SVG editor metadata and retain exact corresponding source.

    Unlike GTK's generated-resource source, the installed icon theme contains
    both XML quote styles. Keep every other byte, including drawing attributes.
    """
    changes, patch = {}, ''
    for original in sorted(originals.rglob('*.svg')):
        before = original.read_text()
        after, count = re.subn(r'''\s+inkscape:export-filename=(["'])/(?:var/)?home/[^"'\n]*\1''', '', before)
        if not count:
            continue
        name = str(original.relative_to(originals))
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(after)
        retained = source_output / 'Adwaita/original' / name
        retained.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, retained)
        changes[name] = count
        patch += ''.join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                         fromfile='a/' + name, tofile='b/' + name))
    (source_output / 'adwaita-resource-paths.patch').write_text(patch)
    (source_output / 'adwaita-resource-paths.json').write_text(json.dumps(changes, indent=2) + '\n')
    return changes


def archive_source(work, generation):
    from .build import inventory
    record = json.loads((generation / 'build-complete.json').read_text())
    source = generation / 'corresponding-source'
    if inventory(source) != record['corresponding_source']:
        raise ValueError('Corresponding-source changed during assembly')
    resources = work / 'webkit-resource-sources'
    runtime = json.loads((work / 'Nuvio.AppDir/usr/lib/nuvio-desktop/share/nuvio-webkit/runtime.json').read_text())
    if runtime['recipe_identity'] != record['recipe_identity'] or runtime['build_policy'] != packaged_build_policy(record['build_policy']):
        raise ValueError('Runtime policy does not match its corresponding-source build record')
    if inventory(resources) != runtime['resource_source_files']:
        raise ValueError('Modified resource source inventory mismatch')
    identity, files = assembly_identity()
    name = 'Nuvio-WebKit-source-' + record['recipe_identity'][:16] + '-' + identity[:12]
    with tempfile.TemporaryDirectory(prefix='webkit-source-', dir=work) as tmp:
        root = pathlib.Path(tmp)
        shutil.copytree(source, root / 'engine')
        shutil.copytree(resources, root / 'resource-sources')
        for relative in files:
            target = root / 'assembly' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(HERE.parent / relative, target)
        (root / 'assembly-files.json').write_text(json.dumps(files, indent=2) + '\n')
        shutil.copy2(generation / 'build-complete.json', root / 'engine-build.json')
        shutil.make_archive(str(work / 'artifacts' / name), 'gztar', root)


def stage(image, generation, resource_sources):
    from formats import PLATFORM
    from .build import inventory
    prefix = generation / 'install/opt/nuvio-webkit'
    record = json.loads((generation / 'build-complete.json').read_text())
    if record['source_sha256'] != MANIFEST['source']['sha256'] or record['files'] != inventory(prefix):
        raise ValueError('Unverified/mutated engine candidate')
    if pathlib.Path('/webkit-builder-packages.tsv').read_bytes() != (generation / 'corresponding-source/webkit-builder-packages.tsv').read_bytes():
        raise ValueError('AppImage assembly must use the recorded engine builder')
    require_files(prefix)
    if resource_sources is None:
        raise ValueError('A corresponding-source directory is required for modified icon resources')
    resource_sources.mkdir(parents=True, exist_ok=True)
    native = image / 'lib/native'
    packages = set()
    copied = set()

    def owner(path):
        for candidate in [path, pathlib.Path('/' + str(path).removeprefix('/usr/'))]:
            result = subprocess.run(['dpkg-query', '-S', str(candidate)], text=True, capture_output=True)
            if result.returncode == 0:
                packages.update(line.split(': ', 1)[0].split(':')[0] for line in result.stdout.splitlines())
                return
        raise ValueError('No redistribution inventory owner: ' + str(path))

    def copy(src, relative, system=False):
        target = image / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_bytes() != src.read_bytes():
            raise ValueError('Conflicting private payload: ' + relative)
        shutil.copy2(src, target, follow_symlinks=True)
        copied.add(relative)
        if system:
            owner(src)

    # Keep installed SONAME aliases at their exact relative paths. Development
    # headers, static archives, .la/pkg-config files and build caches stay out.
    for src in sorted(prefix.rglob('*')):
        relative = str(src.relative_to(prefix))
        if is_elf(src) and (relative.startswith('lib/') or src.name == 'gtk-query-immodules-3.0'):
            copy(src, 'bin/nuvio-gtk-query' if src.name == 'gtk-query-immodules-3.0' else relative)
        elif src.is_file() and relative.startswith(('share/locale/', 'share/glib-2.0/schemas/')):
            copy(src, relative)
    for source, target in [('/usr/bin/bwrap', 'bin/nuvio-bwrap'),
                           ('/usr/bin/xdg-dbus-proxy', 'bin/nuvio-dbus-proxy'),
                           (str(SYSTEM_LIB / 'gdk-pixbuf-2.0/gdk-pixbuf-query-loaders'), 'bin/nuvio-pixbuf-query'),
                           (str(SYSTEM_LIB / 'gstreamer1.0/gstreamer-1.0/gst-plugin-scanner'), 'bin/nuvio-gst-plugin-scanner')]:
        copy(pathlib.Path(source), target, system=True)
    for directory in ['gio/modules', 'gdk-pixbuf-2.0/2.10.0/loaders', 'gstreamer-1.0']:
        source = SYSTEM_LIB / directory
        if not source.is_dir():
            raise ValueError('Missing runtime module directory: ' + directory)
        for src in sorted(source.iterdir()):
            if is_elf(src):
                copy(src, 'lib/native/' + directory + '/' + src.name, system=True)
    # Installed SVGs are source text. Retain changed originals and the exact
    # metadata-only patch outside the runtime image.
    with tempfile.TemporaryDirectory(prefix='nuvio-icons-') as tmp:
        temporary = pathlib.Path(tmp)
        icons = pathlib.Path('/usr/share/icons/Adwaita')
        cleaned = temporary / 'Adwaita'
        changes = clean_icon_sources(icons, cleaned, resource_sources)
        for directory in ['glib-2.0/schemas', 'mime/packages', 'icons/Adwaita', 'icons/hicolor']:
            source = pathlib.Path('/usr/share') / directory
            for src in sorted(source.rglob('*')):
                if src.is_file() and src.name not in ['gschemas.compiled', 'icon-theme.cache']:
                    name = str(src.relative_to(source))
                    relative = 'share/' + directory + '/' + name
                    # Prefer the schema installed by the same GTK source build.
                    if not (image / relative).exists():
                        replacement = cleaned / name if directory == 'icons/Adwaita' and name in changes else src
                        copy(replacement, relative)
                        owner(src)
    subprocess.run(['glib-compile-schemas', str(image / 'share/glib-2.0/schemas')], check=True)
    copied.add('share/glib-2.0/schemas/gschemas.compiled')
    # These are dpkg-trigger outputs, not package-owned source files. Rebuild
    # them from the copied XML/icons instead of reusing the builder's caches.
    subprocess.run(['update-mime-database', str(image / 'share/mime')], check=True)
    copied.update(str(p.relative_to(image)) for p in (image / 'share/mime').rglob('*') if p.is_file())
    for theme in ['Adwaita', 'hicolor']:
        subprocess.run(['gtk-update-icon-cache', '--force', str(image / 'share/icons' / theme)], check=True)
        copied.add('share/icons/' + theme + '/icon-theme.cache')
    for tool in ['glib-compile-schemas', 'update-mime-database', 'gtk-update-icon-cache']:
        owner(pathlib.Path('/usr/bin') / tool)
    subprocess.run(['gio-querymodules', str(native / 'gio/modules')], check=True)
    copied.add('lib/native/gio/modules/giomodule.cache')
    pending = dynamic_elves(image)
    seen = set()
    # lib/ last: libjawt.so resolves libawt.so to the copy the native executable already loaded.
    env = dict(os.environ, LD_LIBRARY_PATH=f'{native}:{image}/lib/runtime/lib:{image}/lib')
    while pending:
        elf = pending.pop()
        if str(elf) in seen:
            continue
        seen.add(str(elf))
        output = subprocess.check_output(['ldd', str(elf)], text=True, env=env)
        if 'not found' in output:
            raise ValueError('Unresolved AppImage dependency: ' + str(elf) + '\n' + output)
        for soname, path in linked_paths(output):
            source = pathlib.Path(path).resolve()
            if PLATFORM.match(soname) or source.is_relative_to(image.resolve()):
                continue
            copy(source, 'lib/native/' + soname, system=True)
            pending.append(native / soname)
    # Patch ELF loader metadata only; never rewrite embedded binary path strings.
    for relative in sorted(copied):
        path = image / relative
        if is_elf(path):
            rpath = '$ORIGIN/' + os.path.relpath(native, path.parent)
            subprocess.run(['patchelf', '--set-rpath', rpath, str(path)], check=True)
    notices = image / 'share/licenses/Ubuntu-AppImage'
    notices.mkdir(parents=True)
    for package in sorted(packages | {'libgtk-3-0', 'libwebkit2gtk-4.1-0'}):
        source = pathlib.Path('/usr/share/doc') / package / 'copyright'
        if not source.is_file():
            raise ValueError('Missing runtime notice: ' + package)
        shutil.copy2(source, notices / (package + '.copyright'))
    (notices / 'packages.json').write_text(json.dumps(sorted(packages), indent=2) + '\n')
    # Many copyright files reference /usr/share/common-licenses, which need not
    # exist on an AppImage host. Keep full texts from the same immutable builder.
    for name in copy_common_licenses(pathlib.Path('/usr/share/common-licenses'), notices / 'common-licenses'):
        copied.add('share/licenses/Ubuntu-AppImage/common-licenses/' + name)
    explanation = notices / 'COMMON-LICENSES-README.txt'
    explanation.write_text('References to /usr/share/common-licenses in the adjacent Ubuntu copyright files\n'
                           'refer to the full texts preserved here in common-licenses/.\n'
                           'These notices do not replace corresponding-source obligations.\n')
    copied.add(str(explanation.relative_to(image)))
    if record['corresponding_source'] != inventory(generation / 'corresponding-source'):
        raise ValueError('Engine source/notices inventory mismatch')
    shutil.copytree(generation / 'corresponding-source/notices', image / 'share/licenses', dirs_exist_ok=True)
    shutil.copy2(HERE / 'nuvio-appimage', image / 'bin/nuvio-desktop')
    (image / 'bin/nuvio-desktop').chmod(0o755)
    copied.add('bin/nuvio-desktop')
    metadata = image / 'share/nuvio-webkit'
    metadata.mkdir(parents=True)
    require_files(image, packaged=True)
    files = {rel: dict(sha256=hashlib.sha256((image / rel).read_bytes()).hexdigest(),
                      mode=(image / rel).stat().st_mode & 0o7777) for rel in sorted(copied)}
    data = dict(version=MANIFEST['version'], source_sha256=MANIFEST['source']['sha256'],
                recipe_identity=record['recipe_identity'], builder_image_id=record['builder_image_id'],
                build_policy=packaged_build_policy(record['build_policy']), assembly_identity=assembly_identity()[0],
                resource_source_files=inventory(resource_sources),
                developer_mode=False, bubblewrap_sandbox=True, files=files,
                acceptance='BUILT_ONLY; full engine sandbox/overlay acceptance is separate')
    (metadata / 'runtime.json').write_text(json.dumps(data, indent=2) + '\n')
