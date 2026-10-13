package android.graphics;

/** Stores the four-point mapping used by the host Canvas stand-in. */
public final class Matrix {
    float[] source;
    float[] destination;

    public boolean setPolyToPoly(float[] src, int srcIndex, float[] dst, int dstIndex,
                                 int pointCount) {
        if (pointCount != 4) {
            return false;
        }
        source = new float[8];
        destination = new float[8];
        System.arraycopy(src, srcIndex, source, 0, 8);
        System.arraycopy(dst, dstIndex, destination, 0, 8);
        return true;
    }
}
