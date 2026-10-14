"""Linux release policy for the packaging source snapshot.

build.py must stay byte-identical because trusted builder seeds pin it, so the
seeded canonical build applies this right after build.source_tree(). The
snapshot is what Gradle packages and what Nuvio-source-<commit>.tar.gz ships.
"""
import hashlib
import json
from pathlib import Path

# Not redistributed: the app fetches and verifies this pinned dataset at runtime
# (AnimeListDownload.kt), so it stays out of the snapshot, the JAR and the source tarball.
RUNTIME_DOWNLOADED = {'composeApp/src/desktopMain/resources/anime-list-mini.json'}


# Linux-only replacement artwork. Upstream originals stay unchanged in the checkout
# (and for other platforms); Linux packages and the Linux source snapshot carry only
# the replacements, which the package gate then requires.
OVERLAY = 'tools/linux/packaging/artwork/overlay/'


def apply(destination, info):
    destination = Path(destination)
    manifest = destination.parent / 'source-files.json'
    files = [tuple(row) for row in json.loads(manifest.read_text())]
    kept = []
    for name, digest in files:
        if name in RUNTIME_DOWNLOADED:
            (destination / name).unlink()
            continue
        kept.append((name, digest))
    selected = dict(kept)
    overlay = {}
    for name, digest in kept:
        if not name.startswith(OVERLAY):
            continue
        target = name[len(OVERLAY):]
        if target not in selected or target.startswith(OVERLAY):
            raise ValueError('Linux overlay target is not a tracked source file: ' + target)
        overlay[target] = (name, digest, selected[target])
    # The replaced originals must not survive anywhere else in the snapshot.
    originals = {original for _, _, original in overlay.values()}
    for name, digest in kept:
        if name not in overlay and digest in originals:
            raise ValueError('Linux overlay original bytes remain in source snapshot: ' + name)
    for target, (name, _, _) in overlay.items():
        (destination / target).write_bytes((destination / name).read_bytes())
    kept = [(name, overlay[name][1] if name in overlay else digest) for name, digest in kept]
    manifest.write_text(json.dumps(kept, indent=2) + '\n')
    # Same serialization as build.source_tree(), so the stamp identifies the shipped snapshot.
    return dict(info, source_sha256=hashlib.sha256(json.dumps(kept).encode()).hexdigest())
