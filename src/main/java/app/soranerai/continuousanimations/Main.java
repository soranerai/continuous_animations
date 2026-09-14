package app.soranerai.continuousanimations;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

/** Entry point loaded by the ExteraGram plugin loader. */
public final class Main {
    private static final int CACHE_TYPE_EMOJI_STATUS = 7;
    private static final int CACHE_TYPE_ALERT_EMOJI_STATUS = 9;
    private static final int MAX_INSTALL_ATTEMPTS = 6;
    private static final long RETRY_BASE_DELAY_MS = 300L;

    private static final HookRegistry HOOKS = new HookRegistry();
    private static boolean started;
    private static int installAttempts;
    private static Runnable scheduledRetry;
    private static String status = "not started";

    private Main() {
    }

    public static synchronized void initAndStart() {
        if (started || scheduledRetry != null) {
            return;
        }

        installHooks();
    }

    private static void installHooks() {
        int installed = 0;
        installed += installEmojiStatusHook();
        installed += installAvatarHooks();
        started = installed > 0;
        if (started) {
            log("installed " + installed + " hooks");
        } else {
            scheduleInstallRetry();
        }
    }

    private static void scheduleInstallRetry() {
        if (installAttempts >= MAX_INSTALL_ATTEMPTS) {
            log("no compatible hooks found after " + installAttempts + " attempts");
            return;
        }

        installAttempts++;
        final long delay = RETRY_BASE_DELAY_MS * installAttempts;
        scheduledRetry = new Runnable() {
            @Override
            public void run() {
                synchronized (Main.class) {
                    scheduledRetry = null;
                    if (!started) {
                        installHooks();
                    }
                }
            }
        };
        log("hook installation retry " + installAttempts + "/" + MAX_INSTALL_ATTEMPTS
                + " in " + delay + " ms");
        try {
            AndroidUtilities.runOnUIThread(scheduledRetry, delay);
        } catch (Throwable error) {
            scheduledRetry = null;
            log("retry scheduler unavailable: " + error.getClass().getSimpleName());
        }
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
            log("emoji-status hook unavailable: " + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static int installAvatarHooks() {
        int installed = 0;
        try {
            for (Method method : ImageReceiver.class.getDeclaredMethods()) {
                if ("setForUserOrChat".equals(method.getName())
                        && HOOKS.install(method, new AvatarHook())) {
                    installed++;
                }
            }
        } catch (Throwable error) {
            log("avatar hooks unavailable: " + error.getClass().getSimpleName());
        }
        log("installed " + installed + " avatar hooks");
        return installed;
    }

    public static synchronized void unload() {
        if (scheduledRetry != null) {
            try {
                AndroidUtilities.cancelRunOnUIThread(scheduledRetry);
            } catch (Throwable ignored) {
                // The retry is also guarded by the cleared reference below.
            }
            scheduledRetry = null;
        }
        HOOKS.uninstallAll();
        started = false;
        installAttempts = 0;
        log("unloaded");
    }

    public static synchronized String getStatus() {
        return status;
    }

    private static void log(String message) {
        status += "\n" + message;
    }

    private static final class EmojiStatusHook extends XC_MethodHook {
        private final Field cacheType;

        EmojiStatusHook(Field cacheType) {
            this.cacheType = cacheType;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param == null
                        || !(param.thisObject instanceof AnimatedEmojiDrawable)
                        || param.args == null
                        || param.args.length == 0
                        || !(param.args[0] instanceof ImageReceiver)) {
                    return;
                }
                int type = cacheType.getInt(param.thisObject);
                if (type == CACHE_TYPE_EMOJI_STATUS || type == CACHE_TYPE_ALERT_EMOJI_STATUS) {
                    AnimationLoop.apply(param.args[0]);
                }
            } catch (Throwable ignored) {
                // A rendering hook must never interrupt Telegram's UI thread.
            }
        }
    }

    private static final class AvatarHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param != null && param.thisObject instanceof ImageReceiver) {
                    AnimationLoop.apply(param.thisObject);
                }
            } catch (Throwable ignored) {
                // The receiver can be incomplete while it is being recycled.
            }
        }
    }

    private static final class HookRegistry {
        private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

        boolean install(Method method, XC_MethodHook callback) {
            try {
                method.setAccessible(true);
                hooks.add(XposedBridge.hookMethod(method, callback));
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        void uninstallAll() {
            for (XC_MethodHook.Unhook hook : hooks) {
                try {
                    hook.unhook();
                } catch (Throwable ignored) {
                    // Continue unhooking even if Telegram discarded one hook.
                }
            }
            hooks.clear();
        }
    }

    /** Reflective boundary around version-dependent Telegram internals. */
    private static final class AnimationLoop {
        private AnimationLoop() {
        }

        static void apply(Object receiver) {
            try {
                Reflection.invoke(receiver, "setAutoRepeatCount", new Class<?>[] {int.class}, -1);
                Reflection.setIntField(receiver, "animatedFileDrawableRepeatMaxCount", 0);

                Object animation = Reflection.invoke(receiver, "getAnimation", new Class<?>[0]);
                if (animation != null) {
                    Reflection.setIntField(animation, "repeatCount", 0);
                }
                Reflection.invoke(receiver, "startAnimation", new Class<?>[] {boolean.class}, true);
            } catch (Throwable ignored) {
                // Linkage and initialization errors are deliberately contained.
            }
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
                    return null;
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
                    return null;
                }
            }
            return null;
        }

        static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
                throws Exception {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            return method == null ? null : method.invoke(target, args);
        }

        static void setIntField(Object target, String name, int value) throws IllegalAccessException {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setInt(target, value);
            }
        }
    }
}
