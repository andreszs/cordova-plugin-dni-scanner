package com.crediser.plugins.dniscanner;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** Runs the actual detector against private reference files without copying them into the repo. */
public final class DocumentDetectorHostTest {
    private static final int FRAME_WIDTH = 640;
    private static final int FRAME_HEIGHT = 960;

    private DocumentDetectorHostTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Provide one or more reference image paths.");
        }
        DocumentDetector detector = new DocumentDetector(ScannerConfig.DEFAULT);
        int failures = 0;
        for (String argument : args) {
            File file = new File(argument);
            BufferedImage source = ImageIO.read(file);
            if (source == null) {
                System.err.println("UNREADABLE " + file.getName());
                failures++;
                continue;
            }
            float[][] scenarios = {
                { 560f, 0f },
                { 520f, 11f },
                { 480f, -11f }
            };
            for (float[] scenario : scenarios) {
                LumaFrame frame = createPortraitCameraFrame(source, Math.round(scenario[0]), scenario[1]);
                DocumentDetection detection = detector.detect(frame);
                String scenarioName = file.getName() + "@" + Math.round(scenario[1]) + "deg";
                if (detection == null) {
                    System.err.println("NOT_DETECTED " + scenarioName);
                    failures++;
                } else {
                    System.out.printf(
                        "DETECTED %s score=%.3f area=%.3f aspect=%.3f center=%.3f%n",
                        scenarioName, detection.score, detection.areaFraction,
                        detection.observedAspect, detection.centerOffset
                    );
                }
            }
        }
        if (failures > 0) {
            throw new AssertionError(failures + " reference image(s) were not detected");
        }
    }

    private static LumaFrame createPortraitCameraFrame(BufferedImage source, int cardWidth,
                                                        float rotationDegrees) {
        BufferedImage frame = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        for (int y = 0; y < FRAME_HEIGHT; y++) {
            int shade = 78 + y * 34 / FRAME_HEIGHT;
            graphics.setColor(new Color(shade, shade + 3, shade + 7));
            graphics.drawLine(0, y, FRAME_WIDTH, y);
        }
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int cardHeight = Math.round(cardWidth / ScannerConfig.DEFAULT.expectedDocumentAspect);
        int left = (FRAME_WIDTH - cardWidth) / 2;
        int top = (FRAME_HEIGHT - cardHeight) / 2;
        graphics.rotate(
            Math.toRadians(rotationDegrees),
            FRAME_WIDTH * 0.5,
            FRAME_HEIGHT * 0.5
        );
        graphics.drawImage(source, left, top, cardWidth, cardHeight, null);
        graphics.dispose();

        byte[] luma = new byte[FRAME_WIDTH * FRAME_HEIGHT];
        for (int y = 0; y < FRAME_HEIGHT; y++) {
            for (int x = 0; x < FRAME_WIDTH; x++) {
                int color = frame.getRGB(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                luma[y * FRAME_WIDTH + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            }
        }
        return new LumaFrame(FRAME_WIDTH, FRAME_HEIGHT, luma);
    }
}
