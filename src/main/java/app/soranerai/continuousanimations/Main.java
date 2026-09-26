package app.soranerai.continuousanimations;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import android.os.Looper;
import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

/** Entry point loaded by the ExteraGram plugin loader. */
public final class Main {
    private static final int CACHE_TYPE_EMOJI_STATUS = 7;
    private static final int CACHE_TYPE_ALERT_EMOJI_STATUS = 9;

    private static final HookRegistry HOOKS = new HookRegistry();
    private static boolean started;
    private static volatile boolean active;
    private static String status = "not started";

    private Main() {
    }

    public static synchronized void initAndStart() {
        if (started) {
            return;
        }

        started = true;
        active = true;
        try {
            installHooks();
        } catch (Throwable error) {
            active = false;
            logError("initialization failed", error);
        }
    }

    private static void installHooks() {
        int installed = 0;
        installed += installEmojiStatusHook();
        if (active) {
            installed += installAvatarHooks();
        }
        log("installed " + installed + " hooks; automatic retries disabled");
    }

    private static int installEmojiStatusHook() {
        try {
            Field cacheType = Reflection.findField(AnimatedEmojiDrawable.class, "cacheType");
            Method updateAutoRepeat = Reflection.findMethod(
                    AnimatedEmojiDrawable.class, "updateAutoRepeat", ImageReceiver.class);
            if (cacheType == null || updateAutoRepeat == null) {
                log("emoji-status hook unavailable: Telegram API changed");
                return 0;
            }
            return HOOKS.install(updateAutoRepeat, new EmojiStatusHook(cacheType)) ? 1 : 0;
        } catch (Throwable error) {
            logError("emoji-status hook unavailable", error);
            return 0;
        }
    }

    private static int installAvatarHooks() {
        int installed = 0;
        try {
            Method target = null;
            boolean ambiguous = false;
            for (Method method : ImageReceiver.class.getDeclaredMethods()) {
                if ("setForUserOrChat".equals(method.getName())
                        && method.getReturnType() == void.class
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    if (target == null || method.getParameterTypes().length
                            > target.getParameterTypes().length) {
                        target = method;
                        ambiguous = false;
                    } else if (method.getParameterTypes().length == target.getParameterTypes().length) {
                        ambiguous = true;
                    }
                }
            }
            if (ambiguous) {
                log("avatar hook unavailable: ambiguous overloads");
                return 0;
            }
            if (target != null && HOOKS.install(target, new AvatarHook())) {
                installed = 1;
            }
        } catch (Throwable error) {
            logError("avatar hooks unavailable", error);
        }
        log("installed " + installed + " avatar hooks");
        return installed;
    }

    public static synchronized void unload() {
        active = false;
        HOOKS.uninstallAll();
        // This classloader is single-use; stale callbacks can never become active again.
        log("unloaded");
    }

    public static synchronized String getStatus() {
        return status;
    }

    private static void log(String message) {
        try {
            status += "\n" + message;
            if (status.length() > 16384) {
                status = status.substring(status.length() - 16384);
            }
        } catch (Throwable ignored) {
            // Diagnostics must not escape into the hook dispatcher.
        }
    }

    private static void logError(String context, Throwable error) {
        try {
            java.io.StringWriter buffer = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(buffer));
            log(context + ": " + buffer);
        } catch (Throwable ignored) {
        }
    }

    private static final class EmojiStatusHook extends XC_MethodHook {
        private final Field cacheType;
        private boolean failed;
        private boolean running;

        EmojiStatusHook(Field cacheType) {
            this.cacheType = cacheType;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
          synchronized (Main.class) {
            try {
                if (!active || failed || running || Looper.myLooper() != Looper.getMainLooper()
                        || param == null || param.hasThrowable()
                        || !(param.thisObject instanceof AnimatedEmojiDrawable)
                        || param.args == null
                        || param.args.length == 0
                        || !(param.args[0] instanceof ImageReceiver)) {
                    return;
                }
                int type = cacheType.getInt(param.thisObject);
                if (type == CACHE_TYPE_EMOJI_STATUS || type == CACHE_TYPE_ALERT_EMOJI_STATUS) {
                    running = true;
                    try {
                        AnimationLoop.apply(param.args[0]);
                    } finally {
                        running = false;
                    }
                }
            } catch (Throwable error) {
                failed = true;
                logError("emoji callback disabled", error);
            }
          }
        }
    }

    private static final class AvatarHook extends XC_MethodHook {
        private boolean failed;
        private boolean running;
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
          synchronized (Main.class) {
            try {
                if (active && !failed && !running && Looper.myLooper() == Looper.getMainLooper()
                        && param != null && !param.hasThrowable()
                        && param.thisObject instanceof ImageReceiver) {
                    running = true;
                    try {
                        AnimationLoop.apply(param.thisObject);
                    } finally {
                        running = false;
                    }
                }
            } catch (Throwable error) {
                failed = true;
                logError("avatar callback disabled", error);
            }
          }
        }
    }

    private static final class HookRegistry {
        private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

        boolean install(Method method, XC_MethodHook callback) {
            try {
                method.setAccessible(true);
                XC_MethodHook.Unhook hook = XposedBridge.hookMethod(method, callback);
                if (hook == null) {
                    active = false;
                    log("registration returned no handle; callbacks disabled");
                    return false;
                }
                hooks.add(hook);
                return true;
            } catch (Throwable error) {
                active = false;
                logError("hook registration failed: " + method.getName(), error);
                return false;
            }
        }

        void uninstallAll() {
            for (XC_MethodHook.Unhook hook : hooks) {
                try {
                    hook.unhook();
                } catch (Throwable error) {
                    logError("unhook failed; callback remains inactive", error);
                }
            }
            hooks.clear();
        }
    }

    /** Reflective boundary around version-dependent Telegram internals. */
    private static final class AnimationLoop {
        private AnimationLoop() {
        }

        static void apply(Object receiver) throws Exception {
            Reflection.invoke(receiver, "setAutoRepeatCount", new Class<?>[] {int.class}, -1);
            Reflection.setIntField(receiver, "animatedFileDrawableRepeatMaxCount", 0);
            // Playback scheduling and drawable lifetime remain owned by Telegram.
        }
    }

    private static final class Reflection {
        private Reflection() {
        }

        static Field findField(Class<?> type, String name) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Look in the parent class.
                } catch (Throwable error) {
                    throw new IllegalStateException("Cannot access field " + name, error);
                }
            }
            return null;
        }

        static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    // Look in the parent class.
                } catch (Throwable error) {
                    throw new IllegalStateException("Cannot access method " + name, error);
                }
            }
            return null;
        }

        static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
                throws Exception {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            if (method == null) {
                throw new NoSuchMethodException(name);
            }
            return method.invoke(target, args);
        }

        static void setIntField(Object target, String name, int value) throws IllegalAccessException {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setInt(target, value);
            }
        }
    }
}
