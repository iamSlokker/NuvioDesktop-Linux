import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

// Packaging only: offscreen scaling; no Toolkit, window or display access.
class ResizeIcon {
    public static void main(String[] args) throws Exception {
        var source = ImageIO.read(new File(args[0]));
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, 512, 512, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", new File(args[1]))) throw new IllegalStateException("PNG writer unavailable");
    }
}
