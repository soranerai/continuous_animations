import app.soranerai.continuousanimations.Main;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import org.telegram.messenger.ImageReceiver;
import java.lang.reflect.*;

/** JVM checks against compile-only stubs, not an Android integration test. */
public class HookSafetyTest {
    public static class Receiver extends ImageReceiver {
        int calls;
        boolean fail;
        Runnable reenter;
        public int animatedFileDrawableRepeatMaxCount = 3;
        public void setAutoRepeatCount(int count) {
            calls++;
            if (fail) throw new LinkageError("test linkage failure");
            if (reenter != null) reenter.run();
        }
        public void startAnimation(boolean force) { throw new AssertionError("must not start playback"); }
    }
    static Object callback() throws Exception {
        Constructor<?> c = Class.forName("app.soranerai.continuousanimations.Main$AvatarHook").getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }
    static void invoke(Object hook, MethodHookParam param) {
        try {
            Method method = hook.getClass().getDeclaredMethod("afterHookedMethod", MethodHookParam.class);
            method.setAccessible(true);
            method.invoke(hook, param);
        } catch (Exception e) { throw new AssertionError("callback escaped", e); }
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Main.initAndStart();
        String initial = Main.getStatus();
        Main.initAndStart();
        check(initial.equals(Main.getStatus()));
        Object hook = callback();
        Receiver receiver = new Receiver();
        MethodHookParam param = new MethodHookParam();
        param.thisObject = receiver;
        receiver.reenter = () -> invoke(hook, param);
        invoke(hook, param);
        check(receiver.calls == 1 && receiver.animatedFileDrawableRepeatMaxCount == 0);
        Thread worker = new Thread(() -> invoke(hook, param));
        worker.start(); worker.join();
        check(receiver.calls == 1);
        MethodHookParam failedOriginal = new MethodHookParam() {
            public boolean hasThrowable() { return true; }
        };
        failedOriginal.thisObject = receiver;
        invoke(hook, failedOriginal);
        check(receiver.calls == 1);
        receiver.fail = true;
        invoke(hook, param);
        invoke(hook, param);
        check(receiver.calls == 2 && Main.getStatus().contains("test linkage failure"));
        Object fresh = callback();
        Main.unload();
        Main.initAndStart();
        invoke(fresh, param);
        check(receiver.calls == 2);
        System.out.println("Hook safety checks passed");
    }
}
