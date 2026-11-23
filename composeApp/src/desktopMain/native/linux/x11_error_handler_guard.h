#pragma once

#include <X11/Xlib.h>

// The handler that takes the place of Xlib's fatal default whenever a library asks for that default.
void linuxReplaceFatalX11Default(XErrorHandler handler);

// How many of libmpv's XSetErrorHandler import slots point at the guard (bound as the bridge loads).
int linuxX11ErrorGuardBoundImports();
