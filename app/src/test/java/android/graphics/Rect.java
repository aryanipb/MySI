package android.graphics;

/** JVM unit-test stand-in with the framework's fields and behaviour. Never packaged into the app. */
public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() {}

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public final int width() { return right - left; }

    public final int height() { return bottom - top; }
}
