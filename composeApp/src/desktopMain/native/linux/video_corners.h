#pragma once

#include <X11/Xlib.h>
#include <X11/extensions/shape.h>
#include <algorithm>
#include <cmath>
#include <vector>

// Rounded corners for the Now Playing video (Windows: SetWindowRgn on the player's container). mpv draws
// in a child window of the AWT Canvas, so that child is the one cut: the Canvas behind it, which the app
// paints in mpv's black, shows in the corners. Cutting the Canvas itself would expose the app's surface.

// The rows of a rounded rectangle, in YXBanded order. A radius of half the short side at most.
inline std::vector<XRectangle> linuxRoundedRectangles(int width, int height, int radius) {
    std::vector<XRectangle> rows;
    if (width <= 0 || height <= 0) return rows;
    const int r = std::clamp(radius, 0, std::min(width, height) / 2);
    const auto row = [&](int y, int inset, int rowHeight) {
        rows.push_back({(short)inset, (short)y, (unsigned short)(width - inset * 2), (unsigned short)rowHeight});
    };
    for (int y = 0; y < r; ++y) {
        const double dy = r - y - 0.5;
        row(y, (int)std::lround(r - std::sqrt((double)r * r - dy * dy)), 1);
    }
    if (height > r * 2) row(r, 0, height - r * 2);
    for (int y = r - 1; y >= 0; --y) {
        const double dy = r - y - 0.5;
        row(height - 1 - y, (int)std::lround(r - std::sqrt((double)r * r - dy * dy)), 1);
    }
    return rows;
}

// Cuts every drawable child of canvas to width x height with radius, or makes it square again with a radius
// of 0. The caller traps X errors: mpv can destroy its window at any time. False when no child was found.
inline bool linuxCutVideoCorners(Display *display, Window canvas, int width, int height, int radius) {
    Window root = None, parent = None, *children = nullptr;
    unsigned int count = 0;
    if (!XQueryTree(display, canvas, &root, &parent, &children, &count)) return false;
    const auto rows = linuxRoundedRectangles(width, height, radius);
    bool cut = false;
    for (unsigned int i = 0; i < count; ++i) {
        XWindowAttributes attributes{};
        if (!XGetWindowAttributes(display, children[i], &attributes) || attributes.c_class != InputOutput) continue;
        if (radius > 0) {
            XShapeCombineRectangles(display, children[i], ShapeBounding, 0, 0,
                const_cast<XRectangle *>(rows.data()), (int)rows.size(), ShapeSet, YXBanded);
        } else {
            XShapeCombineMask(display, children[i], ShapeBounding, 0, 0, None, ShapeSet);
        }
        cut = true;
    }
    if (children) XFree(children);
    return cut;
}
