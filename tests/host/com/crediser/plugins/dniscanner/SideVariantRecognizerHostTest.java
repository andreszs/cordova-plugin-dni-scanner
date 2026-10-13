package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** Validates conservative layout recognition on upright local references. */
public final class SideVariantRecognizerHostTest {
    private SideVariantRecognizerHostTest() {
    }

    public static void main(String[] args) throws Exception {
        assertRequestedSideGate();
        SideVariantRecognizer recognizer = new SideVariantRecognizer(ScannerConfig.DEFAULT);
        assertAuditedV2FrontSignature(recognizer);
        assertAuditedUprightV2FrontSignature(recognizer);
        assertAuditedLowContrastV2BackSignature(recognizer);
        if (args.length == 0) {
            throw new IllegalArgumentException("Provide one or more reference image paths.");
        }
        int failures = 0;
        for (String argument : args) {
            ExpectedArgument input = expectedArgument(argument);
            File file = new File(input.path);
            BufferedImage source = flattenTransparency(ImageIO.read(file));
            Expected expected = input.expected == null ? expected(file.getName()) : input.expected;
            failures += validate(recognizer, file.getName(), source, expected);
            for (int reducedWidth : new int[] { 192, 128, 96 }) {
                BufferedImage degraded = downsampleAndRestore(source, reducedWidth);
                String degradedName = file.getName() + "@blur" + reducedWidth;
                if (expected.variant == 2 && "back".equals(expected.side)) {
                    failures += validate(recognizer, degradedName, degraded, expected);
                } else {
                    failures += validateSideSafety(recognizer, degradedName, degraded, expected.side);
                }
            }
        }
        if (failures > 0) {
            throw new AssertionError(failures + " recognition scenario(s) failed");
        }
    }

    private static void assertRequestedSideGate() {
        RecognitionResult front = recognition("front");
        RecognitionResult back = recognition("back");
        RecognitionResult unknown = recognition("unknown");
        if (!front.matchesRequestedSide("front") || front.matchesRequestedSide("back")
            || !back.matchesRequestedSide("back") || back.matchesRequestedSide("front")
            || unknown.matchesRequestedSide("front") || unknown.matchesRequestedSide("back")) {
            throw new AssertionError("Requested-side gate must accept only an exact side match");
        }
    }

    private static RecognitionResult recognition(String side) {
        return new RecognitionResult(
            side, null, 0.9f, 0f, 0, java.util.Collections.emptyList()
        );
    }

    private static void assertAuditedV2FrontSignature(SideVariantRecognizer recognizer)
        throws Exception {
        assertAuditedSignature(
            recognizer,
            "audited-v2-front",
            new float[] {
                .128f,.209f,.095f,.080f,.139f,.229f,.219f,.095f,.192f,.262f,.347f,.324f
            },
            new float[] {
                .036f,.040f,.030f,.025f,.191f,.177f,.034f,.031f,.091f,.143f,.028f,.035f
            },
            "front",
            2
        );
    }

    private static void assertAuditedUprightV2FrontSignature(
        SideVariantRecognizer recognizer
    ) throws Exception {
        assertAuditedSignature(
            recognizer,
            "audited-upright-v2-front",
            new float[] {
                .404f,.446f,.375f,.295f,.337f,.356f,.490f,.324f,.415f,.496f,.538f,.546f
            },
            new float[] {
                .060f,.083f,.073f,.053f,.112f,.283f,.091f,.044f,.107f,.262f,.150f,.142f
            },
            "front",
            2
        );
    }

    private static void assertAuditedLowContrastV2BackSignature(
        SideVariantRecognizer recognizer
    ) throws Exception {
        assertAuditedSignature(
            recognizer,
            "audited-low-contrast-v2-back",
            new float[] {
                .118f,.106f,.139f,.173f,.097f,.130f,.104f,.114f,.187f,.202f,.172f,.181f
            },
            new float[] {
                .121f,.150f,.081f,.132f,.098f,.159f,.102f,.100f,.116f,.119f,.114f,.116f
            },
            "back",
            2
        );
    }

    private static void assertAuditedSignature(SideVariantRecognizer recognizer,
                                               String name, float[] edge,
                                               float[] saturation,
                                               String expectedSide,
                                               int expectedVariant) throws Exception {
        Class<?> featureClass = Class.forName(
            "com.crediser.plugins.dniscanner.SideVariantRecognizer$FeatureVector"
        );
        java.lang.reflect.Constructor<?> constructor = featureClass.getDeclaredConstructor(
            float[].class, float[].class,
            float.class, float.class, float.class, float.class,
            float.class, float.class, float.class, float.class
        );
        constructor.setAccessible(true);
        Object normal = featureVector(constructor, edge, saturation);
        java.lang.reflect.Method candidatesMethod = SideVariantRecognizer.class
            .getDeclaredMethod("candidates", featureClass);
        candidatesMethod.setAccessible(true);
        java.util.List<?> normalCandidates = (java.util.List<?>) candidatesMethod.invoke(
            recognizer, normal
        );
        java.util.List<Object> candidates = new java.util.ArrayList<>();
        candidates.addAll(normalCandidates);

        Object bestNormal = bestCandidate(normalCandidates);
        float normalScore = candidateFloat(bestNormal, "score");
        Object best = bestNormal;
        float bestScore = normalScore;
        String bestSide = candidateString(best, "side");
        int bestVariant = candidateInt(best, "variant");
        float secondDifferentSide = 0f;
        for (Object candidate : candidates) {
            if (!bestSide.equals(candidateString(candidate, "side"))) {
                secondDifferentSide = Math.max(
                    secondDifferentSide,
                    candidateFloat(candidate, "score")
                );
            }
        }
        java.lang.reflect.Method confidenceMethod = SideVariantRecognizer.class
            .getDeclaredMethod("confidence", float.class, float.class);
        confidenceMethod.setAccessible(true);
        float confidence = (float) confidenceMethod.invoke(
            recognizer,
            bestScore,
            bestScore - secondDifferentSide
        );
        System.out.printf(
            "%s side=%s variant=%d score=%.3f confidence=%.3f%n",
            name, bestSide, bestVariant, bestScore, confidence
        );
        if (!expectedSide.equals(bestSide) || bestVariant != expectedVariant
            || confidence < ScannerConfig.DEFAULT.minimumSideConfidence) {
            throw new AssertionError(
                name + " must be confirmed as " + expectedSide + "/" + expectedVariant
            );
        }
    }

    private static Object bestCandidate(java.util.List<?> candidates) throws Exception {
        Object best = null;
        float bestScore = -1f;
        for (Object candidate : candidates) {
            float score = candidateFloat(candidate, "score");
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static Object featureVector(java.lang.reflect.Constructor<?> constructor,
                                        float[] edge, float[] saturation) throws Exception {
        float bottom = average(edge, 8, 12);
        float middle = average(edge, 4, 8);
        float mrz = clamp01((bottom - .18f) / .30f + .25f * (bottom - middle));
        float rightMiddle = edge[7];
        float surroundingRight = (edge[3] + edge[6] + edge[11]) / 3f;
        float square = clamp01((rightMiddle - .30f) / .35f
            + Math.max(0f, rightMiddle - surroundingRight));
        float lowerRight = (edge[10] + edge[11]) * .5f;
        float lowerLeft = (edge[8] + edge[9]) * .5f;
        float wide = clamp01((lowerRight - .30f) / .32f);
        float localized = clamp01((lowerRight - lowerLeft - .03f) / .18f
            + Math.max(0f, lowerRight - .28f) / .25f);
        float leftSaturation = (saturation[0] + saturation[4] + saturation[8]) / 3f;
        float rightSaturation = (saturation[3] + saturation[7] + saturation[11]) / 3f;
        float photoLeft = clamp01(.45f + leftSaturation * 1.8f
            + (edge[4] - edge[7]) * .6f);
        float photoRight = clamp01(.45f + rightSaturation * 1.8f
            + (edge[7] - edge[4]) * .6f);
        float monochromeRight = clamp01(.55f + rightMiddle - rightSaturation * 2f);
        float rightDense = clamp01((rightMiddle + edge[11]) * .9f);
        return constructor.newInstance(
            edge, saturation, mrz, square, wide, localized, photoLeft,
            photoRight, monochromeRight, rightDense
        );
    }

    private static float average(float[] values, int start, int end) {
        float sum = 0f;
        for (int i = start; i < end; i++) {
            sum += values[i];
        }
        return sum / (end - start);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String candidateString(Object candidate, String name) throws Exception {
        java.lang.reflect.Field field = candidate.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(candidate);
    }

    private static int candidateInt(Object candidate, String name) throws Exception {
        java.lang.reflect.Field field = candidate.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(candidate);
    }

    private static float candidateFloat(Object candidate, String name) throws Exception {
        java.lang.reflect.Field field = candidate.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(candidate);
    }

    private static int validate(SideVariantRecognizer recognizer, String name,
                                BufferedImage image, Expected expected) {
        RecognitionResult result = recognizer.recognize(new Bitmap(image));
        boolean valid = expected.side.equals(result.side)
            && result.variant != null
            && expected.variant == result.variant
            && result.rotationDegrees == 0;
        System.out.printf(
            "%s %s side=%s(%.3f) variant=%s(%.3f) rotation=%d signals=%s%n",
            valid ? "RECOGNIZED" : "NOT_RECOGNIZED",
            name,
            result.side,
            result.sideConfidence,
            result.variant,
            result.variantConfidence,
            result.rotationDegrees,
            result.signals
        );
        if (!valid) {
            printSignature(recognizer, new Bitmap(image));
        }
        return valid ? 0 : 1;
    }

    private static int validateSideSafety(SideVariantRecognizer recognizer, String name,
                                          BufferedImage image, String expectedSide) {
        RecognitionResult result = recognizer.recognize(new Bitmap(image));
        boolean valid = "unknown".equals(result.side) || expectedSide.equals(result.side);
        System.out.printf(
            "%s %s side=%s(%.3f) expected-side=%s%n",
            valid ? "SAFE" : "WRONG_SIDE",
            name,
            result.side,
            result.sideConfidence,
            expectedSide
        );
        return valid ? 0 : 1;
    }

    private static void printSignature(SideVariantRecognizer recognizer, Bitmap bitmap) {
        try {
            java.lang.reflect.Method method = SideVariantRecognizer.class.getDeclaredMethod(
                "features", Bitmap.class
            );
            method.setAccessible(true);
            Object features = method.invoke(recognizer, bitmap);
            java.lang.reflect.Field edgeField = features.getClass().getDeclaredField("edgeGrid");
            java.lang.reflect.Field saturationField = features.getClass().getDeclaredField("saturationGrid");
            edgeField.setAccessible(true);
            saturationField.setAccessible(true);
            System.out.println("  edge=" + java.util.Arrays.toString((float[]) edgeField.get(features)));
            System.out.println("  saturation="
                + java.util.Arrays.toString((float[]) saturationField.get(features)));
            java.lang.reflect.Method candidatesMethod = SideVariantRecognizer.class.getDeclaredMethod(
                "candidates", features.getClass()
            );
            candidatesMethod.setAccessible(true);
            java.util.List<?> candidates = (java.util.List<?>) candidatesMethod.invoke(
                recognizer, features
            );
            for (Object candidate : candidates) {
                java.lang.reflect.Field sideField = candidate.getClass().getDeclaredField("side");
                java.lang.reflect.Field variantField = candidate.getClass().getDeclaredField("variant");
                java.lang.reflect.Field scoreField = candidate.getClass().getDeclaredField("score");
                java.lang.reflect.Field templateField = candidate.getClass().getDeclaredField("templateScore");
                sideField.setAccessible(true);
                variantField.setAccessible(true);
                scoreField.setAccessible(true);
                templateField.setAccessible(true);
                System.out.printf(
                    "  candidate=%s/v%s score=%.3f template=%.3f%n",
                    sideField.get(candidate), variantField.get(candidate),
                    scoreField.getFloat(candidate), templateField.getFloat(candidate)
                );
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static Expected expected(String name) {
        int variant = name.contains("v1-") ? 1 : name.contains("v2-") ? 2 : 3;
        String side = name.contains("frente") ? "front" : "back";
        return new Expected(side, variant);
    }

    private static ExpectedArgument expectedArgument(String argument) {
        int separator = argument.indexOf('|');
        if (separator < 0) {
            return new ExpectedArgument(argument, null);
        }
        String[] parts = argument.substring(0, separator).split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Expected side:variant|path, got: " + argument
            );
        }
        return new ExpectedArgument(
            argument.substring(separator + 1),
            new Expected(parts[0], Integer.parseInt(parts[1]))
        );
    }

    private static BufferedImage downsampleAndRestore(BufferedImage source, int reducedWidth) {
        int reducedHeight = Math.max(1,
            Math.round(source.getHeight() * reducedWidth / (float) source.getWidth()));
        BufferedImage reduced = new BufferedImage(
            reducedWidth, reducedHeight, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D down = reduced.createGraphics();
        down.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );
        down.drawImage(source, 0, 0, reducedWidth, reducedHeight, null);
        down.dispose();

        BufferedImage restored = new BufferedImage(
            source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB
        );
        Graphics2D up = restored.createGraphics();
        up.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );
        up.drawImage(reduced, 0, 0, restored.getWidth(), restored.getHeight(), null);
        up.dispose();
        return restored;
    }

    private static BufferedImage flattenTransparency(BufferedImage source) {
        if (!source.getColorModel().hasAlpha()) {
            return source;
        }
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if (((source.getRGB(x, y) >>> 24) & 0xff) > 10) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return source;
        }
        BufferedImage output = new BufferedImage(maxX - minX + 1, maxY - minY + 1,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
        graphics.drawImage(source, -minX, -minY, null);
        graphics.dispose();
        return output;
    }

    private static final class Expected {
        final String side;
        final int variant;

        Expected(String side, int variant) {
            this.side = side;
            this.variant = variant;
        }
    }

    private static final class ExpectedArgument {
        final String path;
        final Expected expected;

        ExpectedArgument(String path, Expected expected) {
            this.path = path;
            this.expected = expected;
        }
    }
}
