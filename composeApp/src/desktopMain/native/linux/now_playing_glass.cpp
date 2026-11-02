#include "now_playing_glass.h"
#include "pip_hud_geometry.h"
#include "video_corners.h"
#include "window_chrome.h"

#include <gtk/gtk.h>
#include <gdk/gdkx.h>

#include <algorithm>
#include <cstdio>
#include <exception>
#include <functional>
#include <utility>

namespace {
// GTK thread only. The bezel is a root-level ARGB window like the HUD (the compositor blends it over mpv), but
// it takes no input at all, so the video under it stays clickable.
struct Glass {
    GtkWidget *window = nullptr;
    cairo_surface_t *image = nullptr;
    std::vector<int32_t> pixels;
    int width = 0, height = 0, margin = 0;
    Window host = None;
    bool wanted = false;
    bool focused = true;
    bool raised = false;

    Window cornerCanvas = None;
    int cornerRadius = 0;
    int cutWidth = -1, cutHeight = -1;
    std::vector<Window> cutChildren;

    guint timer = 0;
};

Glass &glass() {
    static auto *instance = new Glass();
    return *instance;
}

void hideBezel(Glass &g) {
    if (g.window) gtk_widget_hide(g.window);
    g.raised = false;
}

void placeBezel(Glass &g) {
    if (!g.window) return;
    if (!g.wanted || g.host == None) { hideBezel(g); return; }
    GdkDisplay *display = gtk_widget_get_display(g.window);
    Display *connection = gdk_x11_display_get_xdisplay(display);
    XWindowAttributes attributes{};
    int rootX = 0, rootY = 0;
    Window child = None;
    gdk_x11_display_error_trap_push(display);
    const int found = XGetWindowAttributes(connection, g.host, &attributes);
    const int translated = found ? XTranslateCoordinates(connection, g.host, attributes.root,
        0, 0, &rootX, &rootY, &child) : 0;
    const int error = gdk_x11_display_error_trap_pop(display);
    // The Canvas goes with each track; the next one takes the bezel over.
    if (!found || !translated || error || attributes.map_state != IsViewable || !g.focused) { hideBezel(g); return; }

    const int scale = std::max(1, gtk_widget_get_scale_factor(g.window));
    const HudGeometry physical{rootX - g.margin, rootY - g.margin, g.width, g.height};
    const auto logical = logicalHudGeometry(physical, scale);
    if (!gtk_widget_get_visible(g.window)) {
        gtk_window_move(GTK_WINDOW(g.window), logical.x, logical.y);
        gtk_window_resize(GTK_WINDOW(g.window), logical.width, logical.height);
        gtk_widget_show_all(g.window);
    }
    GdkWindow *native = gtk_widget_get_window(g.window);
    gdk_x11_display_error_trap_push(display);
    gdk_window_move_resize(native, logical.x, logical.y, logical.width, logical.height);
    // X coordinates are physical pixels, as for the HUD.
    XMoveResizeWindow(connection, gdk_x11_window_get_xid(native), physical.x, physical.y, physical.width, physical.height);
    if (!g.raised) gdk_window_raise(native);
    g.raised = true;
    gdk_x11_display_error_trap_pop_ignored(display);
    gdk_display_flush(display);
}

// Re-cuts when the Canvas changed size or mpv opened a new window: mpv resizes its child after the Canvas, and
// the cut is worked out from the Canvas, so the two agree as soon as mpv catches up.
void applyCorners(Glass &g, bool force) {
    if (g.cornerCanvas == None) return;
    GdkDisplay *display = gdk_display_get_default();
    if (!display || !GDK_IS_X11_DISPLAY(display)) return;
    Display *connection = gdk_x11_display_get_xdisplay(display);
    gdk_x11_display_error_trap_push(display);
    XWindowAttributes attributes{};
    Window root = None, parent = None, *children = nullptr;
    unsigned int count = 0;
    bool changed = force;
    bool alive = XGetWindowAttributes(connection, g.cornerCanvas, &attributes) &&
        XQueryTree(connection, g.cornerCanvas, &root, &parent, &children, &count);
    std::vector<Window> current;
    if (alive) {
        current.assign(children, children + count);
        changed = changed || attributes.width != g.cutWidth || attributes.height != g.cutHeight || current != g.cutChildren;
    }
    if (children) XFree(children);
    if (alive && changed && linuxCutVideoCorners(connection, g.cornerCanvas, attributes.width, attributes.height, g.cornerRadius)) {
        g.cutWidth = attributes.width;
        g.cutHeight = attributes.height;
        g.cutChildren = std::move(current);
    }
    gdk_x11_display_error_trap_pop_ignored(display);
    gdk_display_flush(display);
    if (!alive || g.cornerRadius <= 0) {
        g.cornerCanvas = None;
        g.cornerRadius = 0;
        g.cutChildren.clear();
        g.cutWidth = g.cutHeight = -1;
    }
}

gboolean glassTick(gpointer) {
    Glass &g = glass();
    placeBezel(g);
    applyCorners(g, false);
    if (!g.wanted && g.cornerCanvas == None) {
        g.timer = 0;
        return G_SOURCE_REMOVE;
    }
    return G_SOURCE_CONTINUE;
}

void startTimer(Glass &g) {
    if (!g.timer) g.timer = g_timeout_add(250, glassTick, nullptr);
}

bool createBezelWindow(Glass &g) {
    if (g.window) return true;
    GdkScreen *screen = gdk_screen_get_default();
    GdkVisual *visual = screen ? gdk_screen_get_rgba_visual(screen) : nullptr;
    if (!visual) {
        std::fprintf(stderr, "Linux bezel: no X11 RGBA visual\n");
        return false;
    }
    g.window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    g_object_ref_sink(g.window);
    gtk_window_set_screen(GTK_WINDOW(g.window), screen);
    gtk_window_set_decorated(GTK_WINDOW(g.window), FALSE);
    gtk_window_set_skip_taskbar_hint(GTK_WINDOW(g.window), TRUE);
    gtk_window_set_skip_pager_hint(GTK_WINDOW(g.window), TRUE);
    gtk_window_set_focus_on_map(GTK_WINDOW(g.window), FALSE);
    gtk_window_set_accept_focus(GTK_WINDOW(g.window), FALSE);
    gtk_widget_set_visual(g.window, visual);
    gtk_widget_set_app_paintable(g.window, TRUE);
    g_signal_connect(g.window, "delete-event", G_CALLBACK(gtk_true), nullptr);
    g_signal_connect(g.window, "draw", G_CALLBACK(+[](GtkWidget *, cairo_t *cr, gpointer) -> gboolean {
        Glass &self = glass();
        cairo_set_operator(cr, CAIRO_OPERATOR_SOURCE);
        if (self.image) cairo_set_source_surface(cr, self.image, 0, 0);
        else cairo_set_source_rgba(cr, 0, 0, 0, 0);
        cairo_paint(cr);
        return TRUE;
    }), nullptr);
    gtk_widget_realize(g.window);
    GdkDisplay *display = gtk_widget_get_display(g.window);
    GdkWindow *native = gtk_widget_get_window(g.window);
    gdk_x11_display_error_trap_push(display);
    gdk_window_set_override_redirect(native, TRUE);
    // As for the HUD: no frame-drawn replies come for an override-redirect window, and GDK's clock would stall.
    gdk_x11_window_set_frame_sync_enabled(native, FALSE);
    gdk_x11_display_error_trap_pop_ignored(display);
    // No input region: clicks and the wheel go through to the video.
    cairo_region_t *none = cairo_region_create();
    gtk_widget_input_shape_combine_region(g.window, none);
    cairo_region_destroy(none);
    return true;
}

// The bezel is shown from the Now Playing page, which may be an audio-started player with no HUD yet, so this
// one starts the GTK thread. Nothing is thrown at the AWT event thread.
void postStarting(std::function<void()> action) {
    try {
        postOnLinuxGtkThreadStarting(std::move(action));
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Linux bezel: %s\n", error.what());
    }
}
}

void linuxSetVideoCornerRadius(uint64_t canvas, int radiusPx) {
    auto action = [canvas, radiusPx] {
        Glass &g = glass();
        const Window window = (Window)canvas;
        if (radiusPx <= 0 && g.cornerCanvas != window) {
            // Only a window that was cut needs anything done; a square one stays as it is.
            return;
        }
        g.cornerCanvas = window;
        g.cornerRadius = std::max(0, radiusPx);
        applyCorners(g, true);
        if (g.cornerCanvas != None) startTimer(g);
    };
    if (radiusPx <= 0) postOnLinuxGtkThread(std::move(action));
    else postStarting(std::move(action));
}

void linuxBezelShow(uint64_t canvas, std::vector<int32_t> argb, int width, int height, int marginPx) {
    postStarting([canvas, argb = std::move(argb), width, height, marginPx]() mutable {
        Glass &g = glass();
        if (!createBezelWindow(g)) return;
        g.host = (Window)canvas;
        g.margin = marginPx;
        g.width = width;
        g.height = height;
        if (g.image) cairo_surface_destroy(g.image);
        g.pixels = std::move(argb);
        g.image = cairo_image_surface_create_for_data(reinterpret_cast<unsigned char *>(g.pixels.data()),
            CAIRO_FORMAT_ARGB32, width, height, width * 4);
        const int scale = std::max(1, gtk_widget_get_scale_factor(g.window));
        cairo_surface_set_device_scale(g.image, scale, scale);
        g.wanted = true;
        placeBezel(g);
        gtk_widget_queue_draw(g.window);
        startTimer(g);
    });
}

void linuxBezelMove(uint64_t canvas) {
    postOnLinuxGtkThread([canvas] {
        Glass &g = glass();
        if (!g.wanted) return;
        g.host = (Window)canvas;
        placeBezel(g);
    });
}

void linuxBezelHide() {
    postOnLinuxGtkThread([] {
        Glass &g = glass();
        g.wanted = false;
        g.host = None;
        hideBezel(g);
    });
}

void linuxBezelSetWindowFocused(bool focused) {
    postOnLinuxGtkThread([focused] {
        Glass &g = glass();
        g.focused = focused;
        placeBezel(g);
    });
}
