// Headless call-contract fixture. The paint body is taken verbatim from
// pinned WebKit source, before/after the reviewed patch, by test-bitmap-copy.py.
// Skia is replaced by a recording adapter: this tests the requested blend mode,
// not a second implementation of ShareableBitmap::paint or a WebKit GUI.
#include <cassert>
#include <memory>
#include <vector>

enum class CompositeOperator { Copy, SourceOver };
enum class SkBlendMode { kSrc, kSrcOver };
struct SkPaint {
    SkBlendMode mode = SkBlendMode::kSrcOver;
    void setBlendMode(SkBlendMode value) { mode = value; }
};
struct IntPoint { float x, y; };
struct IntRect {
    float x, y, w, h;
    IntPoint size() const { return {w, h}; }
};
struct FloatRect {
    float x, y, w, h;
    FloatRect(IntRect r) : x(r.x), y(r.y), w(r.w), h(r.h) { }
    FloatRect(IntPoint p, IntPoint size) : x(p.x), y(p.y), w(size.x), h(size.y) { }
    void scale(float factor) { x *= factor; y *= factor; w *= factor; h *= factor; }
};
struct Image { };
struct Options { };
struct Canvas {
    struct Call { FloatRect source, destination; SkBlendMode mode; };
    std::vector<Call> calls;
    void drawImageRect(Image* image, FloatRect source, FloatRect destination,
        Options, const SkPaint* paint, Options) {
        assert(image);
        calls.push_back({source, destination, paint ? paint->mode : SkBlendMode::kSrcOver});
    }
};
struct GraphicsContext {
    Canvas canvas;
    CompositeOperator operation;
    struct Mode { CompositeOperator operation; };
    Mode compositeMode() const { return {operation}; }
    Canvas* platformContext() { return &canvas; }
};
enum class BackingStoreCopy { DontCopyBackingStore };
struct ShareableBitmap {
    std::unique_ptr<Image> createPlatformImage(BackingStoreCopy) { return std::make_unique<Image>(); }
    void paint(GraphicsContext&, float, const IntPoint&, const IntRect&);
};

@@PAINT@@

int main() {
    ShareableBitmap bitmap;
    GraphicsContext context{{}, CompositeOperator::Copy};
    // Transparent erasure, opaque replacement and repeated translucent updates
    // all require Src. Verify every update requests replacement, at both scales.
    for (float scale : {1.f, 2.f}) {
        for (int update = 0; update < 4; ++update) {
            bitmap.paint(context, scale, {10, 20}, {3, 4, 7, 9});
            const auto& call = context.canvas.calls.back();
            if (call.mode != SkBlendMode::kSrc) return 1;
            assert(call.source.x == 3 * scale && call.source.y == 4 * scale);
            assert(call.source.w == 7 * scale && call.source.h == 9 * scale);
            assert(call.destination.x == 10 * scale && call.destination.y == 20 * scale);
            assert(call.destination.w == 7 * scale && call.destination.h == 9 * scale);
        }
    }
    context.operation = CompositeOperator::SourceOver;
    bitmap.paint(context, 1, {0, 0}, {0, 0, 1, 1});
    if (context.canvas.calls.back().mode != SkBlendMode::kSrcOver) return 2;
}
