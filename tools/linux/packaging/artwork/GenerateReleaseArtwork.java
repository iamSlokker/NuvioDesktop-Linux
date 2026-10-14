/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 NuvioDesktop-Linux contributors.
 * Asset authoring only. No application/runtime dependency.
 */
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class GenerateReleaseArtwork {
    private static final int W = 1920, H = 1082;
    private static Font regular, bold;
    private static final String[] TITLES = {
        "Lumen 47", "Paper Orbit", "Aster Station Nine", "Velvet Circuit",
        "The Copper Interval", "Signal Meadow", "Lantern Geometry", "Indigo Archive"
    };
    private static final Color[] COLORS = {
        new Color(0x4AD8C5), new Color(0xF7B65A), new Color(0xA68BF5), new Color(0xEE8DAA),
        new Color(0x71AFE9), new Color(0x97CB77), new Color(0xE4A56E), new Color(0x90A8D5)
    };

    private static String f(double n) { return String.format(Locale.ROOT, "%.4f", n); }
    private static String outline(String label, double width, double height) {
        Shape glyph = bold.deriveFont(100f).createGlyphVector(new FontRenderContext(null, true, true), label).getOutline();
        Rectangle2D bounds = glyph.getBounds2D();
        double scale = Math.min((width - 4) / bounds.getWidth(), (height - 4) / bounds.getHeight());
        AffineTransform t = new AffineTransform();
        t.translate((width - bounds.getWidth() * scale) / 2, (height - bounds.getHeight() * scale) / 2);
        t.scale(scale, scale); t.translate(-bounds.getX(), -bounds.getY());
        PathIterator path = glyph.getPathIterator(t); StringBuilder result = new StringBuilder(); double[] c = new double[6];
        while (!path.isDone()) {
            switch (path.currentSegment(c)) {
                case PathIterator.SEG_MOVETO: result.append("M").append(f(c[0])).append(' ').append(f(c[1])); break;
                case PathIterator.SEG_LINETO: result.append("L").append(f(c[0])).append(' ').append(f(c[1])); break;
                case PathIterator.SEG_QUADTO: result.append("Q").append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(' ').append(f(c[3])); break;
                case PathIterator.SEG_CUBICTO: result.append("C").append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(' ').append(f(c[3])).append(' ').append(f(c[4])).append(' ').append(f(c[5])); break;
                case PathIterator.SEG_CLOSE: result.append('Z'); break;
            }
            path.next();
        }
        return result.toString();
    }

    private static void quality(Path out, String filename, String label, String width, String fill) throws IOException {
        double w = Double.parseDouble(width), h = 13.221;
        String svg = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!-- SPDX-License-Identifier: MIT; Copyright (c) 2026 NuvioDesktop-Linux contributors.\n" +
            "     Original plain typographic badge, not an official logo. Inter 4.1 outlines; see bundled OFL. -->\n" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"13.2210\" viewBox=\"0.0000 0.0000 " + width + " 13.2210\">\n" +
            "  <title>" + label + "</title>\n" +
            "  <rect width=\"" + width + "\" height=\"13.2210\" rx=\"1.4\" fill=\"" + fill + "\"/>\n" +
            "  <path fill=\"#111820\" d=\"" + outline(label, w, h) + "\"/>\n</svg>\n";
        Files.writeString(out.resolve(filename), svg);
    }

    private static void text(Graphics2D g, String s, int x, int y, int size, Color color, boolean strong) {
        g.setFont((strong ? bold : regular).deriveFont((float)size)); g.setColor(color); g.drawString(s, x, y);
    }
    private static void round(Graphics2D g, int x, int y, int w, int h, int radius, Color color) {
        g.setColor(color); g.fillRoundRect(x, y, w, h, radius, radius);
    }
    private static void abstractArt(Graphics2D original, int x, int y, int w, int h, int seed) {
        Graphics2D g = (Graphics2D) original.create();
        g.translate(x, y); g.clip(new RoundRectangle2D.Double(0, 0, w, h, 12, 12));
        Color accent = COLORS[Math.floorMod(seed, COLORS.length)];
        g.setPaint(new GradientPaint(0, h, new Color(0x102031), w, 0, accent.darker().darker()));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
        int diameter = Math.max(w, h) / 2;
        g.fillOval(w / 4 + seed % 3 * w / 12, h / 8, diameter, diameter);
        Path2D ridge = new Path2D.Double(); ridge.moveTo(0, h);
        ridge.lineTo(0, h * .67); ridge.lineTo(w * .23, h * .42);
        ridge.lineTo(w * .48, h * .72); ridge.lineTo(w * .74, h * .34);
        ridge.lineTo(w, h * .64); ridge.lineTo(w, h); ridge.closePath();
        g.setColor(new Color(15, 27, 45, 220)); g.fill(ridge);
        g.setStroke(new BasicStroke(Math.max(1f, w / 180f)));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
        for (int i = 0; i < 5; i++) g.draw(new Line2D.Double(w * .1, h * (.8 + i * .025), w * .9, h * (.6 + i * .025)));
        g.dispose();
    }
    private static void card(Graphics2D g, int x, int y, int w, int h, int seed, boolean focus) {
        abstractArt(g, x, y, w, h, seed);
        String title = TITLES[seed % TITLES.length];
        int titleSize = Math.max(14, w / 9);
        while (titleSize > 14 && bold.deriveFont((float)titleSize).getStringBounds(title, g.getFontRenderContext()).getWidth() > w - 32) titleSize--;
        text(g, title, x + 16, y + h - 45, titleSize, Color.WHITE, true);
        text(g, "FICTIONAL FILM", x + 16, y + h - 18, 15, new Color(0xBDCAD7), false);
        if (focus) { g.setColor(Color.WHITE); g.setStroke(new BasicStroke(5)); g.drawRoundRect(x - 5, y - 5, w + 10, h + 10, 16, 16); }
    }
    private static void nav(Graphics2D g, boolean tv) {
        text(g, "NUVIO", 64, 68, 40, Color.WHITE, true);
        text(g, "Home", tv ? 1130 : 350, 64, 25, Color.WHITE, true);
        text(g, "Movies", tv ? 1280 : 475, 64, 25, new Color(0xACB9C8), false);
        text(g, "Series", tv ? 1440 : 625, 64, 25, new Color(0xACB9C8), false);
        text(g, "Search", tv ? 1590 : 775, 64, 25, new Color(0xACB9C8), false);
    }
    private static void rail(Graphics2D g, int y, String title, int height, boolean focus) {
        text(g, title, 64, y, 32, Color.WHITE, true);
        for (int i = 0; i < 8; i++) card(g, 64 + i * 230, y + 24, 208, height, i, focus && i == 0);
    }
    private static void hero(Graphics2D g, boolean tv, boolean ambient) {
        abstractArt(g, tv ? 630 : 820, 103, tv ? 1225 : 1035, tv ? 655 : 590, 0);
        g.setPaint(new GradientPaint(tv ? 600 : 790, 0, new Color(0x0C111B), tv ? 1060 : 1210, 0, new Color(12,17,27,0)));
        g.fillRect(tv ? 600 : 790, 103, tv ? 470 : 420, tv ? 655 : 590);
        if (ambient) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(1450, 610), 1200, new float[]{0, 1}, new Color[]{new Color(42, 100, 104, 130), new Color(12,17,27,0)}));
            g.fillRect(0, 660, W, 420);
        }
        int y = tv ? 345 : 315;
        text(g, "SYNTHETIC FEATURE", 64, y - 80, 20, new Color(0x5BD9C9), true);
        text(g, "Lumen 47", 64, y, tv ? 96 : 82, Color.WHITE, true);
        text(g, "2031   •   1h 42m   •   Adventure", 68, y + 55, 25, new Color(0xC6D0DD), false);
        text(g, "Across an imaginary world of light and paper,", 68, y + 111, 26, new Color(0xD2DBE5), false);
        text(g, "a quiet signal opens a new horizon.", 68, y + 151, 26, new Color(0xD2DBE5), false);
        round(g, 68, y + 188, 190, 58, 16, Color.WHITE);
        text(g, "▶  Watch", 91, y + 226, 25, new Color(0x182230), true);
        round(g, 278, y + 188, 190, 58, 16, new Color(0x283344));
        text(g, "+  Library", 298, y + 226, 25, Color.WHITE, true);
    }
    private static void preview(Path out, String mode) throws IOException {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(new Color(0x0C111B)); g.fillRect(0, 0, W, H);
        boolean tv = mode.equals("tv"); nav(g, tv);
        if (mode.equals("basic")) {
            rail(g, 150, "Continue watching", 270, false);
            rail(g, 515, "Discover your next story", 360, false);
        } else {
            hero(g, tv, mode.equals("adaptive_ambient"));
            rail(g, tv ? 800 : 760, tv ? "Your next story — remote focus" : "Discover your next story", tv ? 190 : 220, tv);
        }
        g.setColor(new Color(0x3A4655)); g.setStroke(new BasicStroke(2)); g.drawRoundRect(1, 1, W - 3, H - 3, 22, 22);
        round(g, 1375, 1028, 490, 34, 10, new Color(0x101923));
        text(g, "ILLUSTRATED PREVIEW · FICTIONAL TITLES", 1395, 1051, 17, new Color(0xCAD6E2), false);
        g.dispose(); ImageIO.write(image, "png", out.resolve("setup_mode_" + mode + ".png").toFile());
    }

    // ---------------------------------------------------------------------------------------
    // Linux package overlay. Original replacements for the 47 provider/rating marks and
    // award/hero badges whose imported rights are unresolved. build.py substitutes these bytes
    // only in the Linux source snapshot; composeApp originals stay unchanged for upstream and
    // other platforms. Names, formats, SVG sizes/viewBoxes and PNG pixel sizes are preserved.
    // ---------------------------------------------------------------------------------------
    private static final String DRAWABLE = "composeApp/src/commonMain/composeResources/drawable/";
    private static final Color GOLD = new Color(0xE9B949), GOLD_DARK = new Color(0xB98F2E),
        SILVER = new Color(0xC4CCD6), SILVER_DARK = new Color(0x8E98A5), WHITE = new Color(0xF3F5F8),
        TEAL = new Color(0x5BD9C9), BLUE = new Color(0x71AFE9), CORAL = new Color(0xEF8A73),
        VIOLET = new Color(0xA68BF5), GREEN = new Color(0x7CCB86), PLATE = new Color(0xD5DBE3),
        INK = new Color(0x1B2430), FRESH = new Color(0x3E9B67), STALE = new Color(0x6E6A8A),
        AMBER = new Color(0xE0A436), MUTED = new Color(0x7D848D);

    private static final class Item {
        final Shape shape; final Color color; final double[] rect;
        Item(Shape shape, Color color, double[] rect) { this.shape = shape; this.color = color; this.rect = rect; }
    }
    private static final class Scene {
        final double w, h; final java.util.List<Item> items = new ArrayList<>();
        Scene(double w, double h) { this.w = w; this.h = h; }
        Scene rect(double x, double y, double rw, double rh, double r, Color c) {
            items.add(new Item(rr(x, y, rw, rh, r), c, new double[]{x, y, rw, rh, r})); return this;
        }
        Scene fill(Shape s, Color c) { items.add(new Item(s, c, null)); return this; }
    }

    private static Shape rr(double x, double y, double w, double h, double r) { return new RoundRectangle2D.Double(x, y, w, h, 2 * r, 2 * r); }
    private static Shape circle(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r); }
    private static Area minus(Shape a, Shape... cuts) { Area r = new Area(a); for (Shape c : cuts) r.subtract(new Area(c)); return r; }
    private static Area union(Shape... shapes) { Area r = new Area(); for (Shape s : shapes) r.add(new Area(s)); return r; }
    private static Shape ring(double cx, double cy, double r, double t) { return minus(circle(cx, cy, r), circle(cx, cy, r - t)); }
    private static Shape stroke(Shape s, double w) {
        return new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(s);
    }
    private static Shape grow(Shape s, double gap) { return union(s, stroke(s, 2 * gap)); }
    private static Shape poly(double... xy) {
        Path2D p = new Path2D.Double(); p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath(); return p;
    }
    private static Shape line(double... xy) {
        Path2D p = new Path2D.Double(); p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }
    private static Shape star(double cx, double cy, double ro, double ri, int n) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 2 * n; i++) {
            double a = -Math.PI / 2 + i * Math.PI / n, r = i % 2 == 0 ? ro : ri;
            if (i == 0) p.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a)); else p.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
        p.closePath(); return p;
    }
    /** Inter Bold outlines fitted (uniform scale) and centered in a box. */
    private static Shape glyphs(String label, double cx, double cy, double maxW, double maxH) {
        Shape g = bold.deriveFont(100f).createGlyphVector(new FontRenderContext(null, true, true), label).getOutline();
        Rectangle2D b = g.getBounds2D();
        double k = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
        AffineTransform t = new AffineTransform();
        t.translate(cx - b.getWidth() * k / 2, cy - b.getHeight() * k / 2); t.scale(k, k); t.translate(-b.getX(), -b.getY());
        return t.createTransformedShape(g);
    }
    /** Generic cup trophy (no figure), total height 0.9 * s from top y0. */
    private static Shape trophy(double cx, double y0, double s) {
        Path2D cup = new Path2D.Double();
        cup.moveTo(cx - .30 * s, y0); cup.lineTo(cx + .30 * s, y0); cup.lineTo(cx + .30 * s, y0 + .18 * s);
        cup.curveTo(cx + .30 * s, y0 + .42 * s, cx + .14 * s, y0 + .52 * s, cx, y0 + .52 * s);
        cup.curveTo(cx - .14 * s, y0 + .52 * s, cx - .30 * s, y0 + .42 * s, cx - .30 * s, y0 + .18 * s); cup.closePath();
        return union(cup,
            stroke(new Ellipse2D.Double(cx - .44 * s, y0 + .05 * s, .24 * s, .24 * s), .06 * s),
            stroke(new Ellipse2D.Double(cx + .20 * s, y0 + .05 * s, .24 * s, .24 * s), .06 * s),
            new Rectangle2D.Double(cx - .05 * s, y0 + .48 * s, .10 * s, .20 * s),
            rr(cx - .20 * s, y0 + .66 * s, .40 * s, .10 * s, .03 * s),
            rr(cx - .28 * s, y0 + .78 * s, .56 * s, .12 * s, .03 * s));
    }
    /** Generic two-branch laurel along a circle, open at the top. */
    private static Shape laurel(double cx, double cy, double r) {
        Area side = new Area();
        Path2D stem = new Path2D.Double();
        for (int deg = 100; deg <= 245; deg += 5) {
            double a = Math.toRadians(deg), x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            if (deg == 100) stem.moveTo(x, y); else stem.lineTo(x, y);
        }
        side.add(new Area(stroke(stem, r * .06)));
        double len = r * .34, wid = r * .14;
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(112 + i * 24), x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            double tangent = Math.atan2(Math.cos(a), -Math.sin(a));
            for (int side2 : new int[]{-1, 1}) {
                double tilt = tangent + side2 * Math.toRadians(32), off = r * .09 * side2;
                AffineTransform t = new AffineTransform();
                t.translate(x + off * Math.cos(a) + Math.cos(tilt) * len * .45, y + off * Math.sin(a) + Math.sin(tilt) * len * .45);
                t.rotate(tilt);
                side.add(new Area(t.createTransformedShape(new Ellipse2D.Double(-len / 2, -wid / 2, len, wid))));
            }
        }
        Area both = new Area(side);
        both.add(side.createTransformedArea(new AffineTransform(-1, 0, 0, 1, 2 * cx, 0)));
        return both;
    }
    private static Shape tv(double x, double y, double w, double h) {
        double t = w * .09;
        return union(minus(rr(x, y, w, h * .74, w * .1), rr(x + t, y + t, w - 2 * t, h * .74 - 2 * t, w * .05)),
            new Rectangle2D.Double(x + w * .45, y + h * .72, w * .1, h * .14), rr(x + w * .25, y + h * .86, w * .5, h * .12, h * .04));
    }
    /** Silver label for nominations: rounded box with knocked-out NOM. */
    private static Shape nomBox(double y) { return minus(rr(14, y, 72, 25, 6), glyphs("NOM", 50, y + 12.5, 54, 15)); }

    private static String hex(Color c) { return String.format(Locale.ROOT, "#%06X", c.getRGB() & 0xFFFFFF); }
    private static String num(double n, int decimals) {
        String s = String.format(Locale.ROOT, "%." + decimals + "f", n);
        if (s.startsWith("-") && Double.parseDouble(s) == 0) s = s.substring(1);
        return s;
    }
    private static String pathData(Shape shape, int d) {
        StringBuilder r = new StringBuilder(); double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO: r.append('M').append(num(c[0], d)).append(' ').append(num(c[1], d)); break;
                case PathIterator.SEG_LINETO: r.append('L').append(num(c[0], d)).append(' ').append(num(c[1], d)); break;
                case PathIterator.SEG_QUADTO: r.append('Q').append(num(c[0], d)).append(' ').append(num(c[1], d)).append(' ')
                    .append(num(c[2], d)).append(' ').append(num(c[3], d)); break;
                case PathIterator.SEG_CUBICTO: r.append('C').append(num(c[0], d)).append(' ').append(num(c[1], d)).append(' ')
                    .append(num(c[2], d)).append(' ').append(num(c[3], d)).append(' ').append(num(c[4], d)).append(' ').append(num(c[5], d)); break;
                case PathIterator.SEG_CLOSE: r.append('Z'); break;
            }
        }
        return r.toString();
    }
    private static Path target(Path out, String name) throws IOException {
        Path file = out.resolve(DRAWABLE + name); Files.createDirectories(file.getParent()); return file;
    }
    private static void svg(Path out, String name, String rootAttributes, String title, Scene s) throws IOException {
        int d = Math.max(s.w, s.h) <= 50 ? 3 : 2;
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!-- SPDX-License-Identifier: MIT; Copyright (c) 2026 NuvioDesktop-Linux contributors.\n" +
            "     Original Linux package replacement; not an official logo, rating or award mark. Inter 4.1 outlines; see bundled OFL. -->\n");
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ").append(rootAttributes).append(">\n");
        b.append("  <title>").append(title).append("</title>\n");
        for (Item item : s.items) {
            if (item.rect != null) {
                double[] q = item.rect;
                b.append("  <rect x=\"").append(num(q[0], d)).append("\" y=\"").append(num(q[1], d)).append("\" width=\"")
                    .append(num(q[2], d)).append("\" height=\"").append(num(q[3], d)).append("\" rx=\"").append(num(q[4], d))
                    .append("\" fill=\"").append(hex(item.color)).append("\"/>\n");
            } else {
                String rule = item.shape.getPathIterator(null).getWindingRule() == PathIterator.WIND_EVEN_ODD ? "\" fill-rule=\"evenodd" : "";
                b.append("  <path fill=\"").append(hex(item.color)).append(rule).append("\" d=\"").append(pathData(item.shape, d)).append("\"/>\n");
            }
        }
        Files.writeString(target(out, name), b.append("</svg>\n").toString());
    }
    private static void png(Path out, String name, Scene s) throws IOException {
        BufferedImage image = new BufferedImage((int) s.w, (int) s.h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Item item : s.items) { g.setColor(item.color); g.fill(item.shape); }
        g.dispose(); ImageIO.write(image, "png", target(out, name).toFile());
    }

    /** Neutral text-label plate; plain Inter lettering, no provider logo geometry or colors. */
    private static Scene plate(double w, double h, String label, double radius, double textW, double textH) {
        return new Scene(w, h).rect(0, 0, w, h, radius, PLATE).fill(glyphs(label, w / 2, h / 2, w * textW, h * textH), INK);
    }
    /** Rating-status mark: label plus an up/down/check glyph, on a circle, square or rosette. */
    private static Scene status(double size, String label, String shape, String glyph, Color fill, Color ink) {
        Scene s = new Scene(size, size); double c = size / 2;
        Shape base;
        if (shape.equals("circle")) base = circle(c, c, c - size * .02);
        else if (shape.equals("square")) base = rr(size * .02, size * .02, size * .96, size * .96, size * .2);
        else base = star(c, c, c - size * .01, c * .84, 14);
        s.fill(base, fill);
        s.fill(glyphs(label, c, size * .40, size * .56, size * .28), ink);
        double gy = size * .70, gs = size * .12;
        Shape mark = glyph.equals("up") ? poly(c - gs, gy + gs * .6, c + gs, gy + gs * .6, c, gy - gs * .8)
            : glyph.equals("down") ? poly(c - gs, gy - gs * .6, c + gs, gy - gs * .6, c, gy + gs * .8)
            : stroke(line(c - gs, gy, c - gs * .25, gy + gs * .7, c + gs * 1.1, gy - gs * .7), size * .07);
        return s.fill(mark, ink);
    }
    private static final String HERO = "viewBox=\"0 0 100 100\" width=\"100\" height=\"100\"";
    private static Scene hero() { return new Scene(100, 100); }

    private static void overlay(Path out) throws IOException {
        // Provider and rating marks: neutral plates with plain lettering.
        svg(out, "discord_logo.svg", "viewBox=\"0 0 24 24\"", "DC text mark (Linux replacement)", plate(24, 24, "DC", 5, .66, .38));
        png(out, "kitsu_logo.png", plate(48, 48, "KIT", 10, .74, .32));
        svg(out, "mdblist_logo.svg", "viewBox=\"0 0 200 200\"", "MDB text mark (Linux replacement)", plate(200, 200, "MDB", 40, .74, .30));
        png(out, "introdb_favicon.png", plate(256, 256, "IDB", 52, .70, .32));
        png(out, "simkl_logo.png", plate(192, 192, "SIMKL", 38, .80, .24));
        png(out, "tvdb_logo.png", plate(400, 216, "TVDB", 40, .78, .40));
        svg(out, "trakt_tv_favicon.svg", "viewBox=\"0 0 48 48\"", "TR text mark (Linux replacement)", plate(48, 48, "TR", 24, .56, .34));
        svg(out, "trakt_logo_wordmark.svg", "viewBox=\"0 0 104.55 45\"", "Trakt text label (Linux replacement)", plate(104.55, 45, "Trakt", 9, .80, .50));
        png(out, "rating_imdb.png", plate(95, 48, "IMDb", 9, .80, .46));
        png(out, "rating_tmdb.png", plate(67, 48, "TMDB", 9, .80, .34));
        png(out, "rating_letterboxd.png", plate(48, 48, "LB", 10, .66, .40));
        png(out, "rating_metacritic.png", plate(48, 48, "MC", 10, .70, .38));
        png(out, "rating_trakt.png", plate(48, 48, "TR", 10, .62, .40));
        svg(out, "rating_myanimelist.svg", "viewBox=\"0 0 1024 1024\"", "MAL text mark (Linux replacement)", plate(1024, 1024, "MAL", 200, .76, .34));
        png(out, "rating_rotten_tomatoes.png", status(48, "RT", "circle", "up", FRESH, Color.WHITE));
        png(out, "rating_rotten_tomatoes_rotten.png", status(48, "RT", "circle", "down", STALE, Color.WHITE));
        png(out, "rating_rotten_tomatoes_certified.png", status(72, "RT", "rosette", "check", FRESH, Color.WHITE));
        png(out, "rating_audience_score.png", status(48, "AS", "square", "up", AMBER, INK));
        png(out, "rating_audience_stale.png", status(48, "AS", "square", "down", MUTED, Color.WHITE));
        png(out, "rating_audience_verified_hot.png", status(72, "AS", "rosette", "check", AMBER, INK));

        // Award/hero badges: generic trophy, laurel, medal, star and media geometry; no statuettes.
        svg(out, "hero_badge_best_picture.svg", HERO, "Top picture award (generic trophy)",
            hero().fill(minus(trophy(50, 6, 98), star(50, 6 + 98 * .22, 13, 5.5, 5)), GOLD));
        svg(out, "hero_badge_oscar_nom.svg", HERO, "Top picture nominee (generic trophy, NOM)",
            hero().fill(minus(trophy(50, 3, 70), star(50, 3 + 70 * .22, 9, 4, 5)), SILVER).fill(nomBox(72), SILVER));
        svg(out, "hero_badge_globe_win.svg", HERO, "Major award winner (laurel and star)",
            hero().fill(laurel(50, 52, 42), GOLD).fill(star(50, 50, 22, 9.5, 5), GOLD));
        svg(out, "hero_badge_globe_nom.svg", HERO, "Major award nominee (laurel and star, NOM)",
            hero().fill(laurel(50, 36, 30), SILVER).fill(star(50, 34, 15, 6.5, 5), SILVER).fill(nomBox(72), SILVER));
        svg(out, "hero_badge_emmy_win.svg", HERO, "Television award winner (screen and star)",
            hero().fill(tv(10, 10, 80, 82), GOLD).fill(star(50, 40, 15, 6.5, 5), GOLD));
        svg(out, "hero_badge_emmy_nom.svg", HERO, "Television award nominee (screen and star, NOM)",
            hero().fill(tv(22, 2, 56, 64), SILVER).fill(star(50, 25, 10, 4.5, 5), SILVER).fill(nomBox(72), SILVER));
        svg(out, "hero_badge_palme.svg", HERO, "Festival top prize (laurel, 1ST)",
            hero().fill(laurel(50, 52, 42), GOLD).fill(glyphs("1ST", 50, 50, 40, 20), GOLD));
        svg(out, "hero_badge_golden_lion.svg", HERO, "Festival top prize (shield and star)",
            hero().fill(minus(new Area(new Path2D.Double() {{
                moveTo(50, 5); lineTo(87, 17); lineTo(85, 52); curveTo(83, 74, 66, 88, 50, 96);
                curveTo(34, 88, 17, 74, 15, 52); lineTo(13, 17); closePath(); }}), star(50, 47, 23, 9.5, 5)), GOLD));
        svg(out, "hero_badge_golden_bear.svg", HERO, "Festival top prize (hexagon and star)",
            hero().fill(minus(poly(50, 4, 90, 27, 90, 73, 50, 96, 10, 73, 10, 27), star(50, 52, 25, 10.5, 5)), GOLD));
        Shape frame = rr(33, 36, 34, 28, 3);
        Area holes = new Area();
        for (int i = 0; i < 4; i++) { holes.add(new Area(rr(36 + i * 7.5, 38, 4, 4, 1))); holes.add(new Area(rr(36 + i * 7.5, 58, 4, 4, 1))); }
        svg(out, "hero_badge_festival.svg", HERO, "Festival selection (laurel and film frame)",
            hero().fill(laurel(50, 52, 42), GOLD).fill(minus(frame, holes, rr(37, 45, 26, 10, 1.5)), WHITE));
        Path2D gaugeArc = new Path2D.Double();
        for (int deg = 180; deg <= 360; deg += 5) {
            double a = Math.toRadians(deg), x = 50 + 36 * Math.cos(a), y = 64 + 36 * Math.sin(a);
            if (deg == 180) gaugeArc.moveTo(x, y); else gaugeArc.lineTo(x, y);
        }
        svg(out, "hero_badge_metacritic.svg", HERO, "High critic score (gauge)",
            hero().fill(stroke(gaugeArc, 12), GREEN).fill(union(poly(46, 61, 54, 67, 76, 36), circle(50, 64, 8)), WHITE)
                .fill(rr(14, 82, 72, 8, 4), GREEN));
        svg(out, "hero_badge_cult.svg", HERO, "Cult favourite (ring, CULT)",
            hero().fill(ring(50, 50, 47, 6), VIOLET).fill(glyphs("CULT", 50, 50, 68, 24), VIOLET));
        Area columns = new Area();
        for (int i = 0; i < 4; i++) columns.add(new Area(new Rectangle2D.Double(19 + i * 18, 42, 8, 34)));
        svg(out, "hero_badge_director.svg", HERO, "Prestige studio (columned building)",
            hero().fill(union(poly(8, 34, 50, 10, 92, 34), new Rectangle2D.Double(12, 34, 76, 6), columns,
                rr(10, 78, 80, 6, 2), rr(6, 86, 88, 7, 2)), WHITE));
        Area stripes = new Area();
        for (int i = 0; i < 4; i++) stripes.add(new Area(poly(22 + i * 16, 28, 31 + i * 16, 28, 25 + i * 16, 40, 16 + i * 16, 40)));
        AffineTransform tilt = AffineTransform.getRotateInstance(Math.toRadians(-14), 14, 40);
        svg(out, "hero_badge_studio.svg", HERO, "Director spotlight (clapperboard)",
            hero().fill(union(rr(14, 44, 72, 44, 6), tilt.createTransformedShape(minus(rr(14, 27, 72, 14, 3), stripes))), WHITE));
        Shape page = minus(rr(20, 6, 60, 88, 7), poly(62, 6, 81, 6, 81, 25), stroke(line(34, 52, 46, 64, 67, 38), 9));
        svg(out, "hero_badge_true_story.svg", HERO, "Based on a true story (document and check)",
            hero().fill(union(page, poly(62, 9, 62, 24, 77, 24)), WHITE));
        svg(out, "hero_badge_stinger.svg", HERO, "Scene after the credits (credit lines and play)",
            hero().fill(union(rr(10, 16, 64, 10, 5), rr(10, 36, 50, 10, 5), rr(10, 56, 36, 10, 5)), WHITE)
                .fill(poly(58, 52, 58, 90, 90, 71), TEAL));
        svg(out, "hero_badge_new_release.svg", HERO, "New release (starburst, NEW)",
            hero().fill(minus(star(50, 50, 48, 40, 14), glyphs("NEW", 50, 50, 58, 20)), TEAL));
        svg(out, "hero_badge_release_status.svg", HERO, "Not yet streaming (clock)",
            hero().fill(union(ring(50, 50, 45, 9), stroke(line(50, 24, 50, 50, 68, 60), 8)), CORAL));
        svg(out, "hero_badge_trending.svg", HERO, "Trending now (rising arrow)",
            hero().fill(union(stroke(line(10, 78, 36, 52, 54, 66, 78, 38), 10), poly(64, 26, 93, 18, 87, 48)), CORAL));
        Area sprockets = new Area();
        for (int i = 0; i < 6; i++) { sprockets.add(new Area(rr(13 + i * 13.5, 29, 6, 6, 1.5))); sprockets.add(new Area(rr(13 + i * 13.5, 65, 6, 6, 1.5))); }
        svg(out, "hero_badge_short_film.svg", HERO, "Short film (two-frame strip)",
            hero().fill(minus(rr(6, 24, 88, 52, 6), sprockets, rr(14, 38, 33, 24, 3), rr(53, 38, 33, 24, 3)), BLUE));
        Shape front = rr(8, 46, 66, 44, 6), middle = rr(18, 29, 64, 40, 6), back = rr(28, 12, 62, 36, 6);
        svg(out, "hero_badge_mini_series.svg", HERO, "Limited series (stacked episodes)",
            hero().fill(minus(back, grow(middle, 4)), VIOLET).fill(minus(middle, grow(front, 4)), VIOLET).fill(front, BLUE));
        svg(out, "hero_badge_binge_ready.svg", HERO, "Binge ready (play and chevrons)",
            hero().fill(union(poly(8, 22, 8, 78, 46, 50), stroke(line(56, 28, 72, 50, 56, 72), 9), stroke(line(74, 28, 90, 50, 74, 72), 9)), TEAL));

        // PNG hero badges keep their pixel sizes; Kotlin loads them by literal filename.
        png(out, "hero_badge_win.png", medal(315, 801, true));
        png(out, "hero_badge_nom.png", medal(316, 801, false));
        Scene people = new Scene(423, 306);
        Shape center = union(circle(211.5, 92, 54), new Ellipse2D.Double(211.5 - 100, 160, 200, 230));
        Shape sides = union(circle(82, 122, 42), new Ellipse2D.Double(82 - 74, 178, 148, 180),
            circle(341, 122, 42), new Ellipse2D.Double(341 - 74, 178, 148, 180));
        people.fill(minus(sides, grow(center, 12)), SILVER).fill(minus(center, star(211.5, 238, 40, 16.5, 5)), GOLD);
        png(out, "hero_badge_people_choice.png", people);
        Scene foreign = new Scene(423, 306);
        Shape left = union(rr(8, 14, 250, 170, 42), poly(62, 176, 54, 240, 118, 180));
        Shape right = union(rr(166, 100, 249, 160, 42), poly(352, 252, 368, 300, 300, 256));
        foreign.fill(minus(left, grow(right, 10), glyphs("A", 98, 84, 80, 86)), BLUE)
            .fill(minus(right, glyphs("Ω", 300, 180, 100, 96)), WHITE);
        png(out, "hero_badge_foreign.png", foreign);
        Scene available = new Scene(258, 323);
        available.fill(minus(rr(10, 12, 238, 299, 44), poly(92, 104, 92, 220, 184, 162)), GREEN);
        png(out, "hero_badge_release_status_available.png", available);
    }
    /** Tall ribbon medal: win = gold disc with solid star cut-out; nomination = silver with outlined star. */
    private static Scene medal(int w, int h, boolean win) {
        Scene s = new Scene(w, h); double c = w / 2.0, r = c - 10;
        Shape tails = union(poly(c - 118, 250, c - 8, 250, c - 8, h - 6, c - 63, h - 70, c - 118, h - 6),
            poly(c + 8, 250, c + 118, 250, c + 118, h - 6, c + 63, h - 70, c + 8, h - 6));
        Shape disc = circle(c, c + 6, r);
        Shape cut = win ? star(c, c + 12, r * .66, r * .28, 5) : minus(star(c, c + 12, r * .66, r * .28, 5), star(c, c + 15, r * .40, r * .17, 5));
        return s.fill(minus(tails, grow(disc, 10)), win ? GOLD_DARK : SILVER_DARK).fill(minus(disc, cut), win ? GOLD : SILVER);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("repository-root output-directory");
        Path repo = Path.of(args[0]), out = Path.of(args[1]); Files.createDirectories(out);
        Path fonts = repo.resolve("composeApp/src/commonMain/composeResources/font");
        regular = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("inter_regular.ttf").toFile());
        bold = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("inter_bold.ttf").toFile());
        quality(out, "quality_badge_4k_bluray.svg", "4K", "21.3674", "#FED700");
        quality(out, "quality_badge_4k_web.svg", "4K", "21.3674", "#FFFFFF");
        quality(out, "quality_badge_atmos.svg", "ATMOS", "47.5500", "#FFFFFF");
        quality(out, "quality_badge_dts.svg", "DTS:X", "47.5500", "#FFFFFF");
        quality(out, "quality_badge_dv.svg", "DV", "47.5500", "#FFFFFF");
        quality(out, "quality_badge_hdr.svg", "HDR", "47.5500", "#FFFFFF");
        for (String mode : new String[]{"basic", "adaptive", "adaptive_ambient", "tv"}) preview(out, mode);
        overlay(out.resolve("linux-overlay"));
    }
}
