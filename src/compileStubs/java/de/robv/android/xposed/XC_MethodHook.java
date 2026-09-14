package de.robv.android.xposed;
public class XC_MethodHook {
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }
    public static class MethodHookParam { public Object thisObject; public Object[] args; }
    public class Unhook { public void unhook() { } }
}
