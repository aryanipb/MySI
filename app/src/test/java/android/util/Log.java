package android.util;

/**
 * JVM unit-test stand-in for android.util.Log, which the mockable android.jar cannot run.
 * Test sources come first on the unit-test classpath, so PdfBox-Android's warnings land here.
 * Never packaged into the app.
 */
public final class Log {
    private Log() {}

    public static int d(String tag, String msg) { return 0; }

    public static int d(String tag, String msg, Throwable tr) { return 0; }

    public static int i(String tag, String msg) { return 0; }

    public static int w(String tag, String msg) { return 0; }

    public static int w(String tag, String msg, Throwable tr) { return 0; }

    public static int e(String tag, String msg) { return 0; }

    public static int e(String tag, String msg, Throwable tr) { return 0; }
}
