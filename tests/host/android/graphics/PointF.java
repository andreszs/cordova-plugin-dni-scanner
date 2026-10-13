package android.graphics;

/** Minimal host-JVM stand-in used only by the detector tests. */
public final class PointF {
    public float x;
    public float y;

    public PointF() {
        this(0f, 0f);
    }

    public PointF(float x, float y) {
        this.x = x;
        this.y = y;
    }
}
