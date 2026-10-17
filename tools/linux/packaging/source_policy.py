"""Linux release policy for the packaging source snapshot.

Both canonical builds apply this exactly once, right after build.source_tree():
build.native() (the bootstrap path that exports a new builder seed) and the
seeded canonical_from_seed.py; source_tree() never calls it. The snapshot is what Gradle packages and what Nuvio-source-<commit>.tar.gz ships.
"""
import hashlib
import json
from pathlib import Path

# Not redistributed: the app fetches and verifies this pinned dataset at runtime
# (AnimeListDownload.kt), so it stays out of the snapshot, the JAR and the source tarball.
RUNTIME_DOWNLOADED = {'composeApp/src/desktopMain/resources/anime-list-mini.json'}


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
    manifest.write_text(json.dumps(kept, indent=2) + '\n')
    # Same serialization as build.source_tree(), so the stamp identifies the shipped snapshot.
    return dict(info, source_sha256=hashlib.sha256(json.dumps(kept).encode()).hexdigest())
