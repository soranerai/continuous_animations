package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    private XposedBridge() { }
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) { return callback.new Unhook(); }
}
