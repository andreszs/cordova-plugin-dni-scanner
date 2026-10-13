package com.crediser.plugins.dniscanner;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** Runs the detector against a complete camera/screenshot frame. */
public final class DocumentFrameInspectorHostTest {
    private DocumentFrameInspectorHostTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Provide one complete frame image path.");
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        int longest = Math.max(source.getWidth(), source.getHeight());
        float scale = Math.min(1f, ScannerConfig.DEFAULT.analysisMaxDimension / (float) longest);
        int width = Math.max(2, Math.round(source.getWidth() * scale));
        int height = Math.max(2, Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();

        byte[] luma = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = scaled.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                luma[y * width + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            }
        }
        DocumentDetection detection = new DocumentDetector(ScannerConfig.DEFAULT)
            .detect(new LumaFrame(width, height, luma));
        if (detection == null) {
            System.out.printf("frame=%dx%d detection=null%n", width, height);
        } else {
            System.out.printf(
                "frame=%dx%d score=%.3f area=%.3f center=%.3f aspect=%.3f quad=%s%n",
                width, height, detection.score, detection.areaFraction,
                detection.centerOffset, detection.observedAspect, detection.quad.points()
            );
        }
    }
}
