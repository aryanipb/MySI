package android.graphics;

/**
 * JVM unit-test stand-in for android.graphics.Path, enough for PdfBox's text engine, which only
 * keeps clip paths and never draws. It tracks the bounding box of every point, so bounds are
 * exact; intersect and union work on those boxes. Never packaged into the app.
 */
public class Path {
    public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }

    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }

    private boolean empty = true;
    private float minX, minY, maxX, maxY;
    private FillType fillType = FillType.WINDING;

    public Path() {}

    public Path(Path src) {
        if (src != null) copy(src);
    }

    private void copy(Path src) {
        empty = src.empty;
        minX = src.minX;
        minY = src.minY;
        maxX = src.maxX;
        maxY = src.maxY;
        fillType = src.fillType;
    }

    private void add(float x, float y) {
        if (empty) {
            minX = maxX = x;
            minY = maxY = y;
            empty = false;
        } else {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
    }

    public void reset() { empty = true; }

    public boolean isEmpty() { return empty; }

    public void setFillType(FillType ft) { fillType = ft; }

    public void moveTo(float x, float y) { add(x, y); }

    public void lineTo(float x, float y) { add(x, y); }

    public void quadTo(float x1, float y1, float x2, float y2) {
        add(x1, y1);
        add(x2, y2);
    }

    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        add(x1, y1);
        add(x2, y2);
        add(x3, y3);
    }

    public void close() {}

    public void addPath(Path src) {
        if (!src.empty) {
            add(src.minX, src.minY);
            add(src.maxX, src.maxY);
        }
    }

    public boolean isRect(RectF rect) { return false; }

    public void computeBounds(RectF bounds, boolean exact) {
        if (empty) {
            bounds.left = bounds.top = bounds.right = bounds.bottom = 0f;
        } else {
            bounds.left = minX;
            bounds.top = minY;
            bounds.right = maxX;
            bounds.bottom = maxY;
        }
    }

    public boolean op(Path other, Op op) {
        if (op == Op.UNION) {
            addPath(other);
        } else if (op == Op.INTERSECT) {
            if (empty || other.empty) {
                empty = true;
            } else {
                minX = Math.max(minX, other.minX);
                minY = Math.max(minY, other.minY);
                maxX = Math.min(maxX, other.maxX);
                maxY = Math.min(maxY, other.maxY);
                if (minX > maxX || minY > maxY) empty = true;
            }
        }
        return true;
    }
}
