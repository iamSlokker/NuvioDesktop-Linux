#!/usr/bin/env python3
"""What an Umbra merge brought that Linux may not have yet. Run it for every sync.

  tools/linux/umbra_sync_checklist.py <last synced commit> [--to upstream/windows-tv-adaptive]

Reads the added lines of `git diff <commit> <to>` and lists:

  external fun     a new JNI method; NO LINUX when nothing under native/linux names it
  OS branch        a new Windows or macOS branch with no Linux case near it, here or upstream
  HUD entry        a new menu action or HUD command; NO LINUX when no Linux source handles it

These are text searches over this checkout, so a listed line is a lead to read and a clean list is
no proof. NO LINUX lines also appear when the port simply has not been applied yet: run it before
the sync for the work list, and after it for what is left. --strict exits 1 while any remain.
"""
import argparse
import dataclasses
from pathlib import Path
import re
import subprocess
import sys

KOTLIN_ROOTS = ('composeApp/src/commonMain/', 'composeApp/src/desktopMain/kotlin/', 'composeApp/src/desktopMain/innertubex/')
NATIVE_ROOT = 'composeApp/src/desktopMain/native/'
LINUX_NATIVE = NATIVE_ROOT + 'linux/'
HUD_FILES = ('composeApp/src/desktopMain/resources/player-ui/controls.js',
             'composeApp/src/desktopMain/resources/player-ui/controls.html')
# Where a HUD command can be handled on Linux: the bridge and the shared desktop code it reports to.
LINUX_HANDLERS = (LINUX_NATIVE, 'composeApp/src/desktopMain/kotlin/', 'composeApp/src/commonMain/kotlin/')
# A Windows-only or macOS-only file has nothing to branch on, and vendored C already covers Linux.
OTHER_OS_PATH = re.compile(r'(^|/)(native/(windows|macos|nuvio-engine|quickjs-kt)/|[^/]*(Windows|Win32|Macos|MacOs)[^/]*$)')

EXTERNAL_FUN = re.compile(r'\bexternal\s+fun\s+(?:`([^`]+)`|(\w+))')
OS_BRANCH = re.compile(r'DesktopHostOs\.(WINDOWS|MACOS)|\bisWindows(Host)?\b|\bisMac(Os|OS)?(Host)?\b'
                       r'|os\.name|#\s*if(def)?\b.*(_WIN32|_WIN64|__APPLE__)')
LINUX_CASE = re.compile(r'LINUX|[Ll]inux|__linux__|\belse\s*->')
HUD_ENTRY = re.compile(r'action:\s*"send:(\w+)"|data-command="(\w+)"')
NEARBY_LINES = 6


@dataclasses.dataclass
class Item:
    kind: str
    path: str
    line: int
    name: str
    linux: bool

    def render(self):
        return '  %-8s %s:%d  %s' % ('ok' if self.linux else 'NO LINUX', self.path, self.line, self.name)


def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], check=True, stdout=subprocess.PIPE,
                          text=True, errors='replace').stdout


def added_lines(repo, since, to, paths):
    """(path, new line number, text) of every line `git diff since to` adds under paths."""
    output = git(repo, 'diff', '--unified=0', '--no-color', '--no-ext-diff', since, to, '--', *paths)
    path, number = None, 0
    for line in output.splitlines():
        if line.startswith('+++ '):
            path = None if line[4:] == '/dev/null' else line[4:].removeprefix('b/')
        elif line.startswith('@@'):
            number = int(re.search(r'\+(\d+)', line).group(1))
        elif line.startswith('+') and path:
            yield path, number, line[1:]
            number += 1


def tree_text(repo, roots, suffixes=('.kt', '.cpp', '.h', '.hpp', '.c', '.js', '.html')):
    """This checkout's files under roots, read once."""
    texts = {}
    for root in roots:
        base = Path(repo, root)
        if base.is_file():
            texts[root] = base.read_text(errors='replace')
        for file in base.rglob('*') if base.is_dir() else ():
            if file.is_file() and file.suffix in suffixes:
                texts[str(file.relative_to(repo))] = file.read_text(errors='replace')
    return texts


def word_in(texts, word, ignore=()):
    pattern = re.compile(r'\b%s\b' % re.escape(word))
    return any(pattern.search(text) for path, text in texts.items() if path not in ignore)


def external_funs(repo, since, to, native_texts):
    for path, number, text in added_lines(repo, since, to, KOTLIN_ROOTS):
        if text.lstrip().startswith(('//', '*')):
            continue
        match = EXTERNAL_FUN.search(text)
        if match:
            name = match.group(1) or match.group(2)
            yield Item('external fun', path, number, name, word_in(native_texts, name))


def has_linux_case(lines, index):
    start, end = max(0, index - NEARBY_LINES), index + NEARBY_LINES + 1
    return any(LINUX_CASE.search(line) for line in lines[start:end])


def file_lines(repo, ref, path):
    try:
        return git(repo, 'show', '%s:%s' % (ref, path)).splitlines()
    except subprocess.CalledProcessError:
        return []


def os_branches(repo, since, to):
    """A new branch counts as covered if Linux is near it upstream or near the same line in this checkout."""
    for path, number, text in added_lines(repo, since, to, (*KOTLIN_ROOTS, NATIVE_ROOT)):
        stripped = text.strip()
        if OTHER_OS_PATH.search(path) or path.startswith(LINUX_NATIVE) or stripped.startswith(('//', '*')):
            continue
        if not OS_BRANCH.search(text):
            continue
        linux = has_linux_case(file_lines(repo, to, path), number - 1)
        if not linux:
            here = Path(repo, path)
            lines = here.read_text(errors='replace').splitlines() if here.is_file() else []
            linux = any(has_linux_case(lines, index) for index, line in enumerate(lines) if line.strip() == stripped)
        yield Item('OS branch', path, number, stripped[:100], linux)


def hud_entries(repo, since, to, handler_texts):
    ignore = set(HUD_FILES)
    for path, number, text in added_lines(repo, since, to, HUD_FILES):
        for match in HUD_ENTRY.finditer(text):
            name = match.group(1) or match.group(2)
            yield Item('HUD entry', path, number, name, word_in(handler_texts, name, ignore))


def checklist(repo, since, to):
    native = tree_text(repo, (LINUX_NATIVE,))
    handlers = tree_text(repo, LINUX_HANDLERS)
    return {
        'external fun': list(external_funs(repo, since, to, native)),
        'OS branch': list(os_branches(repo, since, to)),
        'HUD entry': _unique(hud_entries(repo, since, to, handlers)),
    }


def _unique(items):
    """One line per name: the same command is often added to the page and to the menu."""
    seen, unique = set(), []
    for item in items:
        if item.name not in seen:
            seen.add(item.name)
            unique.append(item)
    return unique


def render(sections, since, to):
    out = ['Umbra sync checklist: %s..%s' % (since, to)]
    for kind, items in sections.items():
        missing = [item for item in items if not item.linux]
        out.append('')
        out.append('%s: %d new, %d with no Linux side' % (kind, len(items), len(missing)))
        out.extend(item.render() for item in sorted(items, key=lambda item: (item.linux, item.path, item.line)))
    return '\n'.join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('since', help='the last Umbra commit integrated (the newest pending-list entry names it)')
    parser.add_argument('--to', default='upstream/windows-tv-adaptive')
    parser.add_argument('--repo', default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument('--strict', action='store_true', help='exit 1 while anything has no Linux side')
    args = parser.parse_args(argv)
    sections = checklist(args.repo, args.since, args.to)
    print(render(sections, args.since, args.to))
    missing = any(not item.linux for items in sections.values() for item in items)
    return 1 if args.strict and missing else 0


if __name__ == '__main__':
    sys.exit(main())
