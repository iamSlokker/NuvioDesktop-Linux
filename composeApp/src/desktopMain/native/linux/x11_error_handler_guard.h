#pragma once

#include <X11/Xlib.h>

// The handler that takes the place of Xlib's fatal default whenever a library asks for that default.
void linuxReplaceFatalX11Default(XErrorHandler handler);
