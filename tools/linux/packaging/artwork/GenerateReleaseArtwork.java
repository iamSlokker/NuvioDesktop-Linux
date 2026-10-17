/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 NuvioDesktop-Linux contributors.
 * Asset authoring only. No application/runtime dependency.
 */
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
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

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("repository-root output-directory");
        Path repo = Path.of(args[0]), out = Path.of(args[1]); Files.createDirectories(out);
        Path fonts = repo.resolve("composeApp/src/commonMain/composeResources/font");
        regular = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("inter_regular.ttf").toFile());
        bold = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("inter_bold.ttf").toFile());
        for (String mode : new String[]{"basic", "adaptive", "adaptive_ambient", "tv"}) preview(out, mode);
    }
}
