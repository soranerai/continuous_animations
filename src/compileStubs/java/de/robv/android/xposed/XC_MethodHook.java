package de.robv.android.xposed;
public class XC_MethodHook {
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }
    public static class MethodHookParam { public Object thisObject; public Object[] args; public boolean hasThrowable() { return false; } }
    public class Unhook { public void unhook() { } }
}
