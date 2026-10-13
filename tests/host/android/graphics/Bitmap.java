package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Minimal host-JVM stand-in used only by the layout-recognizer tests. */
public final class Bitmap {
    public enum Config {
        ARGB_8888
    }

    private BufferedImage image;

    public Bitmap(BufferedImage image) {
        this.image = image;
    }

    public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            filter ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );
        graphics.drawImage(source.image, 0, 0, width, height, null);
        graphics.dispose();
        return new Bitmap(scaled);
    }

    public static Bitmap createBitmap(int[] pixels, int width, int height, Config config) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return new Bitmap(image);
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public void getPixels(int[] destination, int offset, int stride,
                          int x, int y, int width, int height) {
        image.getRGB(x, y, width, height, destination, offset, stride);
    }

    public void recycle() {
        image = null;
    }

    public int getPixel(int x, int y) {
        return image.getRGB(x, y);
    }

    BufferedImage image() {
        return image;
    }
}
