package com.crediser.plugins.dniscanner;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conservative layout classifier. It never decodes or reads document data.
 * Prototypes contain only coarse 4x3 edge/chroma statistics from reference layouts.
 */
final class SideVariantRecognizer {
    private static final LayoutPrototype[] PROTOTYPES = {
        new LayoutPrototype("front", 1,
            values(.349,.370,.202,.202,.545,.251,.259,.277,.410,.481,.454,.365),
            values(.090,.138,.142,.059,.119,.120,.164,.081,.040,.048,.065,.047)),
        // Camera capture prototype: worn v1 card under warm light. Only coarse,
        // non-reversible 4x3 statistics are retained; no document image or text.
        new LayoutPrototype("front", 1,
            values(.235,.229,.184,.197,.374,.265,.262,.244,.303,.328,.298,.204),
            values(.276,.245,.333,.365,.281,.256,.270,.289,.328,.328,.323,.360)),
        // Lower-contrast J7 capture of a real v1 front. As with every prototype,
        // only the non-reversible 4x3 layout signature is retained.
        new LayoutPrototype("front", 1,
            values(.246,.190,.131,.095,.408,.228,.173,.143,.242,.331,.304,.149),
            values(.050,.041,.081,.085,.056,.077,.163,.163,.109,.114,.116,.147)),
        new LayoutPrototype("back", 1,
            values(.373,.359,.166,.263,.250,.175,.154,.289,.337,.334,.659,.543),
            values(.196,.130,.083,.045,.420,.276,.087,.052,.080,.157,.157,.125)),
        // Canonical v1 back reference. Keeping this signature explicit avoids
        // a newer high-contrast v2 front prototype reducing back-side confidence.
        new LayoutPrototype("back", 1,
            values(.519,.455,.245,.465,.478,.314,.244,.448,.347,.358,.649,.523),
            values(.217,.144,.093,.064,.429,.281,.102,.079,.092,.188,.228,.201)),
        // Mean of two corrected S7 captures of the v1 back under different rotation.
        new LayoutPrototype("back", 1,
            values(.305,.261,.112,.120,.270,.216,.151,.169,.320,.322,.628,.427),
            values(.238,.203,.201,.291,.196,.165,.183,.266,.288,.289,.379,.377)),
        new LayoutPrototype("front", 2,
            values(.263,.304,.150,.130,.228,.367,.339,.180,.314,.481,.486,.347),
            values(.094,.092,.088,.066,.173,.170,.116,.078,.159,.240,.105,.051)),
        // Corrected S7 capture of a real v2 front. The stored values are only
        // non-reversible 4x3 layout statistics; no pixels or document data.
        new LayoutPrototype("front", 2,
            values(.217,.304,.092,.095,.127,.308,.278,.140,.267,.403,.415,.359),
            values(.312,.278,.237,.260,.461,.358,.256,.276,.354,.296,.320,.352)),
        // Low-saturation camera capture of a real v2 front whose PDF417 is
        // localized in the lower-right area. Only the non-reversible 4x3
        // layout signature from the audit is retained.
        new LayoutPrototype("front", 2,
            values(.128,.209,.095,.080,.139,.229,.219,.095,.192,.262,.347,.324),
            values(.036,.040,.030,.025,.191,.177,.034,.031,.091,.143,.028,.035)),
        // Canonical upright signature from a v2 front that previously matched
        // an unrelated v3 prototype more strongly after a 180-degree rotation.
        new LayoutPrototype("front", 2,
            values(.404,.446,.375,.295,.337,.356,.490,.324,.415,.496,.538,.546),
            values(.060,.083,.073,.053,.112,.283,.091,.044,.107,.262,.150,.142)),
        new LayoutPrototype("back", 2,
            values(.304,.357,.289,.198,.161,.155,.281,.210,.356,.392,.362,.301),
            values(.129,.154,.132,.000,.116,.133,.095,.001,.047,.051,.043,.036)),
        // Same v2 back captured twice on the S7 under different lighting. The
        // real card is warmer and less saturated than the clean reference.
        new LayoutPrototype("back", 2,
            values(.383,.313,.138,.164,.326,.337,.192,.075,.282,.301,.245,.178),
            values(.171,.169,.206,.300,.190,.195,.183,.255,.290,.282,.271,.282)),
        new LayoutPrototype("back", 2,
            values(.360,.329,.186,.212,.315,.339,.227,.098,.246,.252,.200,.153),
            values(.313,.287,.323,.424,.279,.264,.300,.378,.339,.293,.306,.368)),
        // Real v2 back observed through the lower-contrast J7 camera pipeline.
        // This signature prevents a broad threshold reduction that could admit
        // a front as a back on better cameras.
        new LayoutPrototype("back", 2,
            values(.155,.177,.144,.073,.141,.158,.240,.064,.318,.324,.301,.259),
            values(.060,.087,.121,.141,.095,.145,.137,.144,.151,.174,.175,.180)),
        // Perspective-corrected JPEG from the J7's legacy camera pipeline. Its
        // color response differs substantially from the live preview while the
        // structural edge layout remains that of the v2 back.
        new LayoutPrototype("back", 2,
            values(.256,.259,.154,.098,.222,.237,.254,.082,.280,.300,.286,.218),
            values(.117,.174,.086,.060,.168,.243,.146,.066,.048,.061,.066,.071)),
        // Low-contrast S7 capture of a different real v2 back. The upright
        // layout remains clearly back/v2, but optical smoothing makes its
        // coarse signature compete with a pale v3 front unless calibrated.
        // Only non-reversible 4x3 aggregate statistics are retained.
        new LayoutPrototype("back", 2,
            values(.118,.106,.139,.173,.097,.130,.104,.114,.187,.202,.172,.181),
            values(.121,.150,.081,.132,.098,.159,.102,.100,.116,.119,.114,.116)),
        new LayoutPrototype("front", 3,
            values(.209,.232,.192,.174,.158,.334,.233,.144,.144,.199,.167,.121),
            values(.184,.215,.209,.223,.185,.185,.174,.247,.226,.236,.281,.163)),
        new LayoutPrototype("front", 3,
            values(.439,.538,.467,.495,.440,.571,.431,.374,.431,.471,.506,.355),
            values(.154,.217,.229,.182,.123,.172,.207,.181,.199,.202,.183,.109)),
        new LayoutPrototype("back", 3,
            values(.133,.282,.211,.223,.173,.155,.173,.520,.278,.403,.356,.281),
            values(.168,.142,.172,.166,.226,.247,.270,.149,.233,.278,.286,.265)),
        new LayoutPrototype("back", 3,
            values(.341,.473,.400,.475,.438,.379,.453,.691,.471,.502,.472,.431),
            values(.076,.066,.066,.063,.121,.104,.119,.047,.139,.141,.140,.138))
    };

    private final ScannerConfig config;

    SideVariantRecognizer(ScannerConfig config) {
        this.config = config;
    }

    RecognitionResult recognize(Bitmap bitmap) {
        FeatureVector selectedFeatures = features(bitmap);
        List<Candidate> all = candidates(selectedFeatures);
        Candidate best = bestCandidate(selectedFeatures);
        Collections.sort(all, (first, second) -> Float.compare(second.score, first.score));
        float secondDifferentSide = 0f;
        float secondDifferentVariant = 0f;
        for (Candidate candidate : all) {
            if (!candidate.side.equals(best.side)) {
                secondDifferentSide = Math.max(secondDifferentSide, candidate.score);
            }
            if (candidate.variant != best.variant) {
                secondDifferentVariant = Math.max(secondDifferentVariant, candidate.score);
            }
        }

        float sideConfidence = confidence(best.score, best.score - secondDifferentSide);
        float variantConfidence = confidence(best.score, best.score - secondDifferentVariant);
        String side = sideConfidence >= config.minimumSideConfidence ? best.side : "unknown";
        Integer variant = variantConfidence >= config.minimumVariantConfidence ? best.variant : null;
        if ("unknown".equals(side)) {
            variant = null;
        }
        List<String> signals = new ArrayList<>();
        if (best.templateScore > 0.55f) {
            signals.add("layout_signature");
        }
        if (selectedFeatures.mrzScore > 0.50f) {
            signals.add("machine_readable_rows");
        }
        if (selectedFeatures.squareRegionScore > 0.50f) {
            signals.add("square_dense_region");
        }
        if (selectedFeatures.wideRegionScore > 0.50f) {
            signals.add("wide_dense_region");
        }
        if (selectedFeatures.localizedLowerRightScore > 0.50f) {
            signals.add("localized_lower_right_dense_region");
        }
        if (selectedFeatures.contentScore > 0.20f) {
            signals.add("printed_content");
        }
        return new RecognitionResult(
            side,
            variant,
            sideConfidence,
            variantConfidence,
            0,
            Collections.unmodifiableList(signals),
            selectedFeatures.contentScore
        );
    }

    private Candidate bestCandidate(FeatureVector features) {
        List<Candidate> candidates = candidates(features);
        Candidate best = candidates.get(0);
        for (Candidate candidate : candidates) {
            if (candidate.score > best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private List<Candidate> candidates(FeatureVector features) {
        List<Candidate> candidates = new ArrayList<>();
        for (int variant = 1; variant <= 3; variant++) {
            candidates.add(score("front", variant, features));
            candidates.add(score("back", variant, features));
        }
        return candidates;
    }

    private Candidate score(String side, int variant, FeatureVector features) {
        float templateScore = 0f;
        for (LayoutPrototype prototype : PROTOTYPES) {
            if (prototype.variant == variant && prototype.side.equals(side)) {
                templateScore = Math.max(templateScore, similarity(features, prototype));
            }
        }

        float ruleScore = 0.45f;
        if (variant == 3 && "back".equals(side)) {
            ruleScore = 0.50f * features.squareRegionScore + 0.35f * features.mrzScore
                + 0.15f * features.rightDenseScore;
        } else if (variant == 3) {
            ruleScore = 0.55f * features.photoLeftScore
                + 0.25f * (1f - features.mrzScore)
                + 0.20f * (1f - features.squareRegionScore);
        } else if (variant == 2 && "front".equals(side)) {
            ruleScore = 0.36f * features.wideRegionScore
                + 0.30f * features.photoLeftScore
                + 0.24f * features.localizedLowerRightScore
                + 0.10f * (1f - features.mrzScore);
        } else if (variant == 2) {
            ruleScore = 0.50f * features.mrzScore
                + 0.27f * features.monochromeRightScore
                + 0.13f * (1f - features.wideRegionScore)
                + 0.10f * (1f - features.localizedLowerRightScore);
        } else if ("front".equals(side)) {
            ruleScore = 0.52f * features.mrzScore + 0.33f * features.photoRightScore
                + 0.15f * (1f - features.wideRegionScore);
        } else {
            ruleScore = 0.52f * features.wideRegionScore + 0.33f * features.monochromeRightScore
                + 0.15f * (1f - features.mrzScore);
        }
        // Coarse reference signatures are the primary signal. Rules only break ties and
        // provide explainable evidence; they must not turn a PDF-like texture into a side ID.
        float score = clamp01(0.88f * templateScore + 0.12f * ruleScore);
        return new Candidate(side, variant, score, templateScore);
    }

    private FeatureVector features(Bitmap source) {
        Bitmap scaled = Bitmap.createScaledBitmap(
            source,
            config.recognitionWidth,
            config.recognitionHeight,
            true
        );
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        scaled.recycle();
        float[] luma = new float[pixels.length];
        float[] saturation = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = (color >> 16) & 0xff;
            int green = (color >> 8) & 0xff;
            int blue = color & 0xff;
            luma[i] = (77 * red + 150 * green + 29 * blue) / 256f;
            int maximum = Math.max(red, Math.max(green, blue));
            int minimum = Math.min(red, Math.min(green, blue));
            saturation[i] = maximum == 0 ? 0f : (maximum - minimum) / (float) maximum;
        }

        float[] edgeGrid = new float[12];
        float[] saturationGrid = new float[12];
        int[] counts = new int[12];
        for (int y = 1; y < height; y++) {
            for (int x = 1; x < width; x++) {
                int index = y * width + x;
                float gradient = Math.min(1f,
                    (Math.abs(luma[index] - luma[index - 1])
                        + Math.abs(luma[index] - luma[index - width])) / 80f);
                int cellX = Math.min(3, x * 4 / width);
                int cellY = Math.min(2, y * 3 / height);
                int cell = cellY * 4 + cellX;
                edgeGrid[cell] += gradient;
                saturationGrid[cell] += saturation[index];
                counts[cell]++;
            }
        }
        for (int i = 0; i < 12; i++) {
            if (counts[i] > 0) {
                edgeGrid[i] /= counts[i];
                saturationGrid[i] /= counts[i];
            }
        }

        float bottomEdge = average(edgeGrid, 8, 12);
        float middleEdge = average(edgeGrid, 4, 8);
        float topEdge = average(edgeGrid, 0, 4);
        float mrzScore = clamp01((bottomEdge - 0.18f) / 0.30f + 0.25f * (bottomEdge - middleEdge));
        float rightMiddle = edgeGrid[7];
        float surroundingRight = (edgeGrid[3] + edgeGrid[6] + edgeGrid[11]) / 3f;
        float squareRegionScore = clamp01((rightMiddle - 0.30f) / 0.35f
            + Math.max(0f, rightMiddle - surroundingRight));
        float lowerRight = (edgeGrid[10] + edgeGrid[11]) * 0.5f;
        float wideRegionScore = clamp01((lowerRight - 0.30f) / 0.32f);
        float lowerLeft = (edgeGrid[8] + edgeGrid[9]) * 0.5f;
        float localizedLowerRightScore = clamp01(
            (lowerRight - lowerLeft - 0.03f) / 0.18f
                + Math.max(0f, lowerRight - 0.28f) / 0.25f
        );
        float leftSaturation = (saturationGrid[0] + saturationGrid[4] + saturationGrid[8]) / 3f;
        float rightSaturation = (saturationGrid[3] + saturationGrid[7] + saturationGrid[11]) / 3f;
        float photoLeftScore = clamp01(0.45f + leftSaturation * 1.8f
            + (edgeGrid[4] - edgeGrid[7]) * 0.6f);
        float photoRightScore = clamp01(0.45f + rightSaturation * 1.8f
            + (edgeGrid[7] - edgeGrid[4]) * 0.6f);
        float monochromeRightScore = clamp01(0.55f + rightMiddle
            - rightSaturation * 2.0f);
        float rightDenseScore = clamp01((rightMiddle + edgeGrid[11]) * 0.9f);
        float edgeMean = average(edgeGrid, 0, edgeGrid.length);
        int activeCells = 0;
        for (float value : edgeGrid) {
            if (value >= 0.055f) {
                activeCells++;
            }
        }
        float activeCoverage = activeCells / (float) edgeGrid.length;
        // This intentionally ignores where content appears. It only describes
        // whether the corrected card contains a useful amount of printed texture.
        // It is diagnostic metadata and never blocks automatic capture.
        float contentScore = clamp01(
            0.68f * clamp01((edgeMean - 0.015f) / 0.16f)
                + 0.32f * activeCoverage
        );

        return new FeatureVector(
            edgeGrid,
            saturationGrid,
            mrzScore,
            squareRegionScore,
            wideRegionScore,
            localizedLowerRightScore,
            photoLeftScore,
            photoRightScore,
            monochromeRightScore,
            rightDenseScore,
            contentScore
        );
    }

    private float similarity(FeatureVector features, LayoutPrototype prototype) {
        float edgeDistance = 0f;
        float normalizedEdgeDistance = 0f;
        float saturationDistance = 0f;
        float featureEdgeMean = average(features.edgeGrid, 0, features.edgeGrid.length);
        float prototypeEdgeMean = average(prototype.edgeGrid, 0, prototype.edgeGrid.length);
        float featureScale = Math.max(0.035f, featureEdgeMean);
        float prototypeScale = Math.max(0.035f, prototypeEdgeMean);
        for (int i = 0; i < 12; i++) {
            edgeDistance += Math.abs(features.edgeGrid[i] - prototype.edgeGrid[i]);
            normalizedEdgeDistance += Math.abs(
                features.edgeGrid[i] / featureScale - prototype.edgeGrid[i] / prototypeScale
            );
            saturationDistance += Math.abs(features.saturationGrid[i] - prototype.saturationGrid[i]);
        }
        edgeDistance /= 12f;
        normalizedEdgeDistance /= 12f;
        saturationDistance /= 12f;
        float absoluteScore = clamp01(
            1f - (0.72f * edgeDistance + 0.28f * saturationDistance) / 0.34f
        );
        float layoutScore = clamp01(
            1f - (0.82f * normalizedEdgeDistance + 0.18f * saturationDistance) / 0.70f
        );
        // Absolute contrast works best on sharp captures. Relative edge layout
        // remains stable after the optical blur/downsampling common on older
        // cameras, while sharpness is already gated independently.
        return 0.38f * absoluteScore + 0.62f * layoutScore;
    }

    private float confidence(float score, float margin) {
        return clamp01(0.34f + score * 0.48f + Math.max(0f, margin) * 1.15f);
    }

    private static float average(float[] values, int start, int end) {
        float sum = 0f;
        for (int index = start; index < end; index++) {
            sum += values[index];
        }
        return sum / Math.max(1, end - start);
    }

    private static float[] values(double... values) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (float) values[i];
        }
        return result;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class LayoutPrototype {
        final String side;
        final int variant;
        final float[] edgeGrid;
        final float[] saturationGrid;

        LayoutPrototype(String side, int variant, float[] edgeGrid, float[] saturationGrid) {
            this.side = side;
            this.variant = variant;
            this.edgeGrid = edgeGrid;
            this.saturationGrid = saturationGrid;
        }
    }

    private static final class FeatureVector {
        final float[] edgeGrid;
        final float[] saturationGrid;
        final float mrzScore;
        final float squareRegionScore;
        final float wideRegionScore;
        final float localizedLowerRightScore;
        final float photoLeftScore;
        final float photoRightScore;
        final float monochromeRightScore;
        final float rightDenseScore;
        final float contentScore;

        FeatureVector(float[] edgeGrid, float[] saturationGrid, float mrzScore,
                      float squareRegionScore, float wideRegionScore,
                      float localizedLowerRightScore, float photoLeftScore,
                      float photoRightScore, float monochromeRightScore,
                      float rightDenseScore, float contentScore) {
            this.edgeGrid = edgeGrid;
            this.saturationGrid = saturationGrid;
            this.mrzScore = mrzScore;
            this.squareRegionScore = squareRegionScore;
            this.wideRegionScore = wideRegionScore;
            this.localizedLowerRightScore = localizedLowerRightScore;
            this.photoLeftScore = photoLeftScore;
            this.photoRightScore = photoRightScore;
            this.monochromeRightScore = monochromeRightScore;
            this.rightDenseScore = rightDenseScore;
            this.contentScore = contentScore;
        }
    }

    private static final class Candidate {
        final String side;
        final int variant;
        final float score;
        final float templateScore;

        Candidate(String side, int variant, float score, float templateScore) {
            this.side = side;
            this.variant = variant;
            this.score = score;
            this.templateScore = templateScore;
        }
    }
}
