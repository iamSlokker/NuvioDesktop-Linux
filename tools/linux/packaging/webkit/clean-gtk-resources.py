#!/usr/bin/env python3
"""Remove Inkscape export destinations from SVG source, never from ELF bytes."""
import difflib
import json
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
changes = {}
patch = ''
for path in sorted(root.rglob('*.svg')):
    original = path.read_text()
    updated, count = re.subn(r'\s+inkscape:export-filename="/(?:var/)?home/[^"\n]*"', '', original)
    if count:
        relative = str(path.relative_to(root))
        changes[relative] = count
        patch += ''.join(difflib.unified_diff(original.splitlines(True), updated.splitlines(True),
                         fromfile='a/' + relative, tofile='b/' + relative))
        path.write_text(updated)
(root.parent / 'gtk-resource-paths.patch').write_text(patch)
(root.parent / 'gtk-resource-paths.json').write_text(json.dumps(changes, indent=2) + '\n')
print('Removed source SVG export-path metadata:', sum(changes.values()))
