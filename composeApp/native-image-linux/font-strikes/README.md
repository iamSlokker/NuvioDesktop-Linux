# font-strikes

`java.lang.invoke.VarHandleSegmentAsFloatsAligned`.

Why: JDK 25's `sun.font.StrikeCache.getGlyphXAdvance` reads the native glyph info through a
memory-segment `VarHandle`, whose method handle the image looks up reflectively. Reached when AWT
measures Latin text with a Linux (FreeType) font strike: Swing components such as the file
chooser probe's `JFileChooser` (`FontDesignMetrics.getLatinCharWidth`). Upstream's `fonts/` topic
was traced on Windows, which takes another strike path.

Found by the first Linux QA build (2026-10-17). Only the observed handle class is listed: the
other `VarHandleSegmentAs*` classes go in when a QA build reports them.
