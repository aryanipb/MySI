package android.graphics;

/** JVM unit-test stand-in working on the bounding box of a path. Never packaged into the app. */
public class Region {
    private final Path path = new Path();

    public Region() {}

    public Region(Rect r) {
        path.moveTo(r.left, r.top);
        path.lineTo(r.right, r.bottom);
    }

    public boolean isEmpty() { return path.isEmpty(); }

    public boolean setPath(Path p, Region clip) {
        path.reset();
        path.addPath(p);
        if (clip != null) path.op(clip.path, Path.Op.INTERSECT);
        return !path.isEmpty();
    }

    public Path getBoundaryPath() { return new Path(path); }
}
