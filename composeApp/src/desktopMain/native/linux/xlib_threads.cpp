#include <X11/Xlib.h>

// libX11 before 1.8 only locks what was created after XInitThreads(): a display, resource database
// or the quark table made earlier keeps a null mutex, and once XInitThreads() has run every later
// lock of it is pthread_mutex_lock(NULL). libmpv calls it when its video output opens, long after
// AWT and the HUD's GTK have used Xlib, which crashed the AppImage (it bundles Ubuntu 22.04's
// libX11 1.7.5) while a shader was switched. Calling it when the bridge loads, which the app does
// before AWT starts, puts every Xlib object under a lock. libX11 1.8 already does this itself.
__attribute__((constructor)) static void nuvioInitXlibThreads() { XInitThreads(); }
