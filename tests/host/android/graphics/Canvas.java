package android.graphics;

import java.awt.image.BufferedImage;

/** Software host-JVM stand-in; Android uses its native Canvas implementation. */
public final class Canvas {
    private final Bitmap target;

    public Canvas(Bitmap target) {
        this.target = target;
    }

    public void drawBitmap(Bitmap source, Matrix matrix, Paint paint) {
        if (matrix.source == null || matrix.destination == null) {
            throw new IllegalArgumentException("Missing four-point mapping");
        }
        double[] inverse = solve(matrix.destination, matrix.source);
        BufferedImage sourceImage = source.image();
        BufferedImage targetImage = target.image();
        for (int y = 0; y < targetImage.getHeight(); y++) {
            for (int x = 0; x < targetImage.getWidth(); x++) {
                double denominator = inverse[6] * x + inverse[7] * y + 1.0;
                double sourceX = (inverse[0] * x + inverse[1] * y + inverse[2]) / denominator;
                double sourceY = (inverse[3] * x + inverse[4] * y + inverse[5]) / denominator;
                int sampleX = Math.max(0, Math.min(sourceImage.getWidth() - 1,
                    (int) Math.round(sourceX)));
                int sampleY = Math.max(0, Math.min(sourceImage.getHeight() - 1,
                    (int) Math.round(sourceY)));
                targetImage.setRGB(x, y, sourceImage.getRGB(sampleX, sampleY));
            }
        }
    }

    private static double[] solve(float[] destination, float[] source) {
        double[][] equations = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = destination[i * 2];
            double y = destination[i * 2 + 1];
            double sx = source[i * 2];
            double sy = source[i * 2 + 1];
            int row = i * 2;
            equations[row][0] = x;
            equations[row][1] = y;
            equations[row][2] = 1.0;
            equations[row][6] = -sx * x;
            equations[row][7] = -sx * y;
            equations[row][8] = sx;
            equations[row + 1][3] = x;
            equations[row + 1][4] = y;
            equations[row + 1][5] = 1.0;
            equations[row + 1][6] = -sy * x;
            equations[row + 1][7] = -sy * y;
            equations[row + 1][8] = sy;
        }
        for (int column = 0; column < 8; column++) {
            int pivot = column;
            for (int row = column + 1; row < 8; row++) {
                if (Math.abs(equations[row][column]) > Math.abs(equations[pivot][column])) {
                    pivot = row;
                }
            }
            double[] temporary = equations[column];
            equations[column] = equations[pivot];
            equations[pivot] = temporary;
            double divisor = equations[column][column];
            for (int value = column; value <= 8; value++) {
                equations[column][value] /= divisor;
            }
            for (int row = 0; row < 8; row++) {
                if (row == column) {
                    continue;
                }
                double factor = equations[row][column];
                for (int value = column; value <= 8; value++) {
                    equations[row][value] -= factor * equations[column][value];
                }
            }
        }
        double[] result = new double[8];
        for (int row = 0; row < 8; row++) {
            result[row] = equations[row][8];
        }
        return result;
    }
}
