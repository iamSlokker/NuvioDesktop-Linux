"""Linux release policy for the packaging source snapshot.

Both canonical builds apply this exactly once, right after build.source_tree():
build.native() (the bootstrap path that exports a new builder seed) and the
seeded canonical_from_seed.py; source_tree() never calls it. The snapshot is what Gradle packages and what Nuvio-source-<commit>.tar.gz ships.

Linux packages carry the same source as upstream: the artwork overlay (reversed
2026-10-17) and the anime dataset exclusion (reversed 2026-10-18) are gone by owner
decision, so nothing is removed or replaced. The hook stays because build.py, whose
bytes the WebKit builder seed pins, calls it.
"""


def apply(destination, info):
    return info
