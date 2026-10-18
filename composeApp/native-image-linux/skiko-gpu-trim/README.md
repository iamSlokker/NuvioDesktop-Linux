# skiko-gpu-trim

The fields of skiko's Linux redrawers (`LinuxOpenGLRedrawer`, `AWTRedrawer`) and context handlers
(`OpenGLContextHandler`, `JvmContextHandler`, `ContextHandler`).

Why: `SkiaGpuResourceTrim.kt` walks `redrawer.contextHandler.context` by reflection to free
Skia's unused GPU buffers when playback starts or the window hides. Upstream registers the
Windows (Direct3D) path; on Linux the OpenGL redrawer's fields were missing, so the trim found
no context and did nothing.

Found by the owner's signed-in Linux QA build (2026-10-18).
