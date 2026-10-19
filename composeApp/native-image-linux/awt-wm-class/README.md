# awt-wm-class

`sun.awt.X11.XToolkit.awtAppClassName`, set once at startup by `LinuxWindowClass.kt`.

Why: XToolkit takes the windows' X11 WM_CLASS from the bottom frame of the thread that
constructs it. In the native image that frame is a generated
`java.lang.invoke.LambdaForm$DMH/…` class, so 1.16.0 windows were named
`java-lang-invoke-LambdaForm$DMH/sa346b79c`: no dock matched them to the desktop entry, and
Hyprland users had to write blur rules against a name that changes between builds. The field
is private, so it is written by reflection, and the JVM agent never records it because the
JVM run that traced the app predates the call. `native-image-linux.gradle.kts` passes
`--add-opens=java.desktop/sun.awt.X11` at image build time.

Reported by a Hyprland user on 1.16.0 (2026-10-19).
