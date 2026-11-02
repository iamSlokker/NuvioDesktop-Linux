#pragma once

#include <cstdint>
#include <vector>

// The Now Playing video's rounded corners and glass bezel. Process-wide: each track opens a new player, and
// the bezel stays up across them. Every call only queues work for the GTK thread and never throws.
void linuxSetVideoCornerRadius(uint64_t canvas, int radiusPx);
// argb: premultiplied ARGB, width x height, with the Canvas' rectangle marginPx in from each edge.
void linuxBezelShow(uint64_t canvas, std::vector<int32_t> argb, int width, int height, int marginPx);
void linuxBezelMove(uint64_t canvas);
void linuxBezelHide();
// AWT's focus for the app window: an override-redirect window stays above every managed one, so the bezel
// only shows while Nuvio is in front, as the HUD does.
void linuxBezelSetWindowFocused(bool focused);
