#!/usr/bin/env python3
"""Compile the patched production resolver; no GTK initialization or display.

Run inside the WebKit builder with the patched source at /work/source.
This proves the path contract, not whole-engine sandbox acceptance.
"""
import os
import pathlib
import resource
import shlex
import shutil
import subprocess
import tempfile

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
SOURCE = pathlib.Path('/work/source/webkitgtk-2.50.4/Source/WTF/wtf/glib/NuvioRelocation.h')
GLIB = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'glib-2.0'], text=True))
HELPERS = ['lib/webkit2gtk-4.1/WebKitWebProcess', 'lib/webkit2gtk-4.1/WebKitNetworkProcess',
           'lib/webkit2gtk-4.1/WebKitGPUProcess', 'bin/nuvio-bwrap', 'bin/nuvio-dbus-proxy']
DATA = ['lib/native/webkit2gtk-4.1/injected-bundle/libwebkit2gtkinjectedbundle.so',
        'share/locale', 'share/glib-2.0/schemas']

with tempfile.TemporaryDirectory(prefix='nuvio relocation ') as tmp:
    directory = pathlib.Path(tmp)
    shutil.copy2(SOURCE, directory / SOURCE.name)
    (directory / 'library.cpp').write_text('#include "NuvioRelocation.h"\n'
        'extern "C" const char* query(const char* path, bool executable) { return nuvioPrivatePath(path, executable); }\n')
    (directory / 'main.cpp').write_text('#include <dlfcn.h>\n#include <stdio.h>\n'
        'int main(int argc, char** argv) { if (argc != 4) return 2; '
        'void* lib = dlopen(argv[1], RTLD_NOW); if (!lib) return 3; '
        'auto query = reinterpret_cast<const char*(*)(const char*,bool)>(dlsym(lib,"query")); '
        'if (!query) return 4; puts(query(argv[2], argv[3][0] == \'1\')); }\n')
    subprocess.run(['g++-12', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-shared', '-fPIC',
                    str(directory / 'library.cpp'), '-o', str(directory / 'resolver.so'), '-ldl', *GLIB], check=True)
    subprocess.run(['g++-12', str(directory / 'main.cpp'), '-o', str(directory / 'probe'), '-ldl'], check=True)
    (directory / 'helper.cpp').write_text('#include "NuvioRelocation.h"\n#include <stdio.h>\n'
        'int main() { puts(nuvioPrivatePath("share/locale")); }\n')
    subprocess.run(['g++-12', '-std=c++20', '-Wall', '-Wextra', '-Werror', str(directory / 'helper.cpp'),
                    '-o', str(directory / 'helper-probe'), '-ldl', *GLIB], check=True)
    cases = 0
    for place in ['first image', 'unrelated/relocated image']:
        root = directory / place
        (root / 'lib/native').mkdir(parents=True)
        library = root / 'lib/native/resolver.so'
        shutil.copy2(directory / 'resolver.so', library)
        for relative in HELPERS + DATA:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if relative.startswith('share/'):
                path.mkdir(parents=True, exist_ok=True)
            else:
                path.write_bytes(b'fixture')
                path.chmod(0o755 if relative in HELPERS else 0o644)

        def query(relative, success):
            global cases
            result = subprocess.run([str(directory / 'probe'), str(library), relative,
                                     '1' if relative in HELPERS else '0'], text=True, capture_output=True,
                                    env=dict(os.environ, WEBKIT_EXEC_PATH='/untrusted/override'))
            assert (result.returncode == 0) == success, (relative, result.stderr)
            if success:
                assert result.stdout.strip() == str((root / relative).resolve())
            cases += 1

        for relative in HELPERS + DATA:
            query(relative, True)
        helper_probe = root / 'lib/webkit2gtk-4.1/ResolverProbe'
        shutil.copy2(directory / 'helper-probe', helper_probe)
        self_path = subprocess.check_output([str(helper_probe)], text=True).strip()
        assert self_path == str(root / 'share/locale')
        cases += 1
        helper = root / HELPERS[0]
        helper.unlink()
        query(HELPERS[0], False)
        helper.symlink_to(directory / 'probe')
        query(HELPERS[0], False)
        helper.unlink()
        helper.write_bytes(b'fixture')
        helper.chmod(0o644)
        query(HELPERS[0], False)
        helper.chmod(0o4755)
        query(HELPERS[0], False)
    wrong_layout = subprocess.run([str(directory / 'helper-probe')], capture_output=True)
    assert wrong_layout.returncode != 0
    cases += 1
    print(f'Production relocation resolver: {cases} cases PASS; no display or WebKit process launched')
