// video_corners.h on a private Xvfb (native-build.sh): a Canvas with a child as mpv makes it, cut and made square again.
#include "video_corners.h"
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

bool contains(Display *display, Window window, int x, int y) {
    int count = 0, ordering = 0;
    XRectangle *rectangles = XShapeGetRectangles(display, window, ShapeBounding, &count, &ordering);
    bool inside = false;
    for (int i = 0; i < count; ++i) {
        const XRectangle &r = rectangles[i];
        inside = inside || (x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height);
    }
    if (rectangles) XFree(rectangles);
    return inside;
}
}

int main() {
    try {
        require(linuxRoundedRectangles(0, 10, 2).empty(), "an empty window has no rows");
        const auto square = linuxRoundedRectangles(100, 50, 0);
        require(square.size() == 1 && square[0].width == 100 && square[0].height == 50, "radius 0 is one rectangle");
        // Radius above half the short side: clamped, no middle band, and the rows still cover the height.
        const auto pill = linuxRoundedRectangles(100, 20, 500);
        require(pill.size() == 20, "a clamped radius has a row per line, got " + std::to_string(pill.size()));
        for (size_t i = 0; i < pill.size(); ++i) {
            require(pill[i].y == (short)i, "rows are in order");
            require(pill[i].x + pill[i].width == 100 - pill[i].x, "rows are symmetrical");
        }
        require(pill[0].x > pill[9].x, "the first row is the most inset");

        Display *display = XOpenDisplay(nullptr);
        require(display != nullptr, "no X display");
        int event = 0, error = 0;
        require(XShapeQueryExtension(display, &event, &error), "no SHAPE extension");
        const Window root = DefaultRootWindow(display);
        const Window canvas = XCreateSimpleWindow(display, root, 0, 0, 320, 180, 0, 0, 0);
        require(!linuxCutVideoCorners(display, canvas, 320, 180, 16), "no child yet");
        const Window video = XCreateSimpleWindow(display, canvas, 0, 0, 320, 180, 0, 0, 0);
        XSync(display, False);

        require(linuxCutVideoCorners(display, canvas, 320, 180, 16), "the child is cut");
        XSync(display, False);
        require(!contains(display, video, 0, 0), "the corner is cut");
        require(!contains(display, video, 319, 179), "the far corner is cut");
        require(contains(display, video, 160, 0), "the top edge stays");
        require(contains(display, video, 0, 90), "the left edge stays");
        require(contains(display, video, 16, 16), "the corner's centre stays");

        // The cut follows the Canvas' size, whatever the child's is at that moment.
        require(linuxCutVideoCorners(display, canvas, 640, 360, 16), "the child is cut for a new size");
        XSync(display, False);
        require(contains(display, video, 600, 300), "the new size is what counts");

        require(linuxCutVideoCorners(display, canvas, 320, 180, 0), "the child is made square");
        XSync(display, False);
        require(contains(display, video, 0, 0), "the corner is back");

        XDestroyWindow(display, canvas);
        XCloseDisplay(display);
    } catch (const std::exception &failure) {
        std::cerr << "FAIL: " << failure.what() << '\n';
        return 1;
    }
    std::cout << "VIDEO_CORNERS_PASS\n";
    return 0;
}
