package android.os;
/** Compile-only Android API declaration. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final Thread MAIN_THREAD = Thread.currentThread();
    public static Looper myLooper() { return Thread.currentThread() == MAIN_THREAD ? MAIN : null; }
    public static Looper getMainLooper() { return MAIN; }
}
