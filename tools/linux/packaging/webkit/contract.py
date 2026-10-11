"""AppImage engine inventory; compilation alone is not sandbox acceptance."""
import hashlib
import json
import os
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
MANIFEST = json.loads((HERE / 'manifest.json').read_text())
ENGINE_FILES = [
    'lib/native/libwebkit2gtk-4.1.so.0', 'lib/native/libjavascriptcoregtk-4.1.so.0',
    'lib/native/libgtk-3.so.0', 'lib/native/libgdk-3.so.0',
    'lib/native/webkit2gtk-4.1/injected-bundle/libwebkit2gtkinjectedbundle.so',
]
PROCESSES = ['lib/webkit2gtk-4.1/' + name for name in
             ['WebKitWebProcess', 'WebKitNetworkProcess', 'WebKitGPUProcess']]
HELPERS = ['bin/nuvio-bwrap', 'bin/nuvio-dbus-proxy']
RESOURCES = ['share/glib-2.0/schemas/gschemas.compiled', 'share/mime/mime.cache',
             'lib/native/gio/modules/libgiognutls.so',
             'lib/native/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so',
             'bin/nuvio-pixbuf-query', 'bin/nuvio-gtk-query', 'bin/nuvio-gst-plugin-scanner']


def require_files(root, packaged=False):
    for relative in ENGINE_FILES + PROCESSES + (HELPERS + RESOURCES if packaged else []):
        path = root / relative
        if not path.is_file() or not path.stat().st_size or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('Missing/escaping private WebKit input: ' + relative)
        if relative in ENGINE_FILES + PROCESSES + HELPERS or relative.startswith('bin/'):
            with path.open('rb') as stream:
                if stream.read(4) != b'\x7fELF':
                    raise ValueError('Private WebKit binary is not ELF: ' + relative)
        if (relative in PROCESSES or relative.startswith('bin/')) and (not os.access(path, os.X_OK) or path.stat().st_mode & 0o6000):
            raise ValueError('Invalid WebKit helper permissions: ' + relative)


def check_runtime(root):
    record = root / 'share/nuvio-webkit/runtime.json'
    if not record.is_file():
        raise ValueError('AppImage blocked: missing private WebKit provenance/inventory')
    data = json.loads(record.read_text())
    if (data['source_sha256'] != MANIFEST['source']['sha256'] or data['version'] != MANIFEST['version']
            or data['developer_mode'] is not False or data['bubblewrap_sandbox'] is not True):
        raise ValueError('AppImage blocked: wrong WebKit source/build policy')
    require_files(root, packaged=True)
    for relative in ENGINE_FILES + PROCESSES + HELPERS + RESOURCES:
        if relative not in data['files']:
            raise ValueError('AppImage blocked: incomplete WebKit inventory: ' + relative)
    for relative, expected in data['files'].items():
        path = root / relative
        if pathlib.PurePosixPath(relative).is_absolute() or '..' in pathlib.PurePosixPath(relative).parts:
            raise ValueError('Unsafe WebKit inventory path')
        if not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('Missing/escaping packaged WebKit input: ' + relative)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected['sha256'] or path.stat().st_mode & 0o7777 != expected['mode']:
            raise ValueError('AppImage blocked: WebKit inventory mismatch: ' + relative)
    return data
