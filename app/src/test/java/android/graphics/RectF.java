package android.graphics;

/** JVM unit-test stand-in with the framework's fields and behaviour. Never packaged into the app. */
public class RectF {
    public float left;
    public float top;
    public float right;
    public float bottom;

    public RectF() {}

    public RectF(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public final float width() { return right - left; }

    public final float height() { return bottom - top; }

    public boolean contains(float x, float y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public void union(float x, float y) {
        if (x < left) left = x; else if (x > right) right = x;
        if (y < top) top = y; else if (y > bottom) bottom = y;
    }

    public void round(Rect dst) {
        dst.left = Math.round(left);
        dst.top = Math.round(top);
        dst.right = Math.round(right);
        dst.bottom = Math.round(bottom);
    }
}
