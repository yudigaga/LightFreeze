package io.github.yudigaga.lightfreeze.system;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Only the authenticated private URL gateway is projected; global package state stays real. */
final class FrozenUrlHooks {
    interface Gate {
        boolean dispatch(Intent intent, int userId, int callingUid);
        ResolveInfo resolve(Intent intent, int userId, int callingUid);
    }
    record Installed(boolean supported, String reason) { }
    private FrozenUrlHooks() { }

    static Installed install(XposedInterface module, ClassLoader loader, Supplier<Gate> gate) {
        if (Build.VERSION.SDK_INT < 33 || Build.VERSION.SDK_INT > 36)
            return new Installed(false, "Frozen URL platform contract supports Android 13–16");
        XposedInterface.HookHandle startHandle = null, queryHandle = null;
        AtomicBoolean enabled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger rejectedLaunchDiagnostics = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Class<?> atms = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader);
            Method start = unique(atms, true);
            Field globalLock = atms.getDeclaredField("mGlobalLock"); globalLock.setAccessible(true);
            Class<?> computer = Class.forName("com.android.server.pm.ComputerEngine", false, loader);
            Method query = unique(computer, false);
            int queryUser = queryUserIndex(query);
            if (queryUser != (Build.VERSION.SDK_INT <= 34 ? 5 : 6))
                throw new NoSuchMethodException("unexpected query signature for this Android version");
            startHandle = module.hook(start).setId("lightfreeze-yudigaga:frozen-url-start")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!enabled.get()) return chain.proceed();
                        try {
                            Object value = chain.getArg(3);
                            if (value instanceof Intent intent && eligible(intent, chain.getArg(4))) {
                                if (!FrozenUrlHookPolicy.simpleLaunch((Integer) chain.getArg(7),
                                        chain.getArg(9) != null, chain.getArg(10))) {
                                    if (rejectedLaunchDiagnostics.getAndIncrement() < 6)
                                        module.log(Log.INFO, "LightFreeze", "frozen_url_launch preserved_original reason=complex_launch"
                                                + " resultExpected=" + ((Integer) chain.getArg(7) >= 0)
                                                + " profiling=" + (chain.getArg(9) != null));
                                    return chain.proceed();
                                }
                                int pid = Binder.getCallingPid();
                                Object lock = globalLock.get(chain.getThisObject());
                                if (pid > 0 && pid != Process.myPid() && lock != null && !Thread.holdsLock(lock)) {
                                    Gate current = gate.get();
                                    if (current != null && current.dispatch(command(intent), (Integer) chain.getArg(11), Binder.getCallingUid()))
                                        return 0; // Accepted asynchronous command, not its execution result.
                                }
                            }
                        } catch (Exception | LinkageError e) { logFailure(module, "launch", e); }
                        return chain.proceed();
                    });
            queryHandle = module.hook(query).setId("lightfreeze-yudigaga:frozen-url-query")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object original = chain.proceed();
                        if (!enabled.get()) return original;
                        try {
                            if (!(original instanceof List<?> list) || hasOwnUrl(list)) return original;
                            Object value = chain.getArg(0);
                            // Start resolution is left to the normal chain after the worker enables the app.
                            if (!(value instanceof Intent intent) || !eligible(intent, chain.getArg(1))
                                    || (Boolean) chain.getArg(queryUser + 1)) return original;
                            int userId = (Integer) chain.getArg(queryUser);
                            Gate current = gate.get();
                            ResolveInfo projected = current == null ? null
                                    : current.resolve(command(intent), userId, (Integer) chain.getArg(4));
                            if (!ownUrl(projected) || projected.activityInfo.applicationInfo == null
                                    || !projected.activityInfo.exported || userId < 0
                                    || projected.activityInfo.applicationInfo.uid / 100000 != userId
                                    || !FrozenUrlHookPolicy.APP.equals(projected.activityInfo.applicationInfo.packageName)) return original;
                            ResolveInfo copy = new ResolveInfo(projected);
                            copy.activityInfo = new ActivityInfo(projected.activityInfo);
                            copy.activityInfo.applicationInfo = new ApplicationInfo(projected.activityInfo.applicationInfo);
                            copy.activityInfo.enabled = true; copy.activityInfo.applicationInfo.enabled = true;
                            copy.isDefault = true;
                            return List.of(copy);
                        } catch (Exception | LinkageError e) { logFailure(module, "query", e); }
                        return original;
                    });
            enabled.set(true);
            return new Installed(true, "Frozen URL launch and resolver hooks installed");
        } catch (Exception | LinkageError e) {
            if (queryHandle != null) try { queryHandle.unhook(); } catch (RuntimeException ignored) { }
            if (startHandle != null) try { startHandle.unhook(); } catch (RuntimeException ignored) { }
            return new Installed(false, "Frozen URL hooks unavailable: " + e.getClass().getSimpleName());
        }
    }

    private static Method unique(Class<?> owner, boolean launch) throws NoSuchMethodException {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            boolean matches = launch ? FrozenUrlHookPolicy.launchSignature(method.getName(), method.getModifiers(),
                    method.getReturnType().getName(), parameterNames(method)) : queryUserIndex(method) >= 0;
            if (!matches) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous frozen URL platform method");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("frozen URL platform method missing");
        found.setAccessible(true); return found;
    }
    private static int queryUserIndex(Method method) {
        return FrozenUrlHookPolicy.queryUserIndex(method.getName(), method.getModifiers(),
                method.getReturnType().getName(), parameterNames(method));
    }
    private static String[] parameterNames(Method method) {
        Class<?>[] parameters = method.getParameterTypes(); String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) names[i] = parameters[i].getName();
        return names;
    }
    private static boolean eligible(Intent intent, Object resolvedType) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null
                || !"lightfreeze-yudigaga".equals(intent.getData().getScheme()) || !"v1".equals(intent.getData().getHost())) return false;
        if (resolvedType != null) return false;
        ComponentName component = intent.getComponent();
        Set<String> categories = intent.getCategories();
        boolean complex = intent.getSelector() != null || intent.getClipData() != null
                || intent.getType() != null || intent.hasFileDescriptors();
        if (categories != null) for (String category : categories)
            if (!Intent.CATEGORY_DEFAULT.equals(category) && !Intent.CATEGORY_BROWSABLE.equals(category)) complex = true;
        return FrozenUrlHookPolicy.route(intent.getAction(), intent.getDataString(), intent.getPackage(),
                component == null ? null : component.getPackageName(), component == null ? null : component.getClassName(),
                complex, intent.getFlags());
    }
    private static Intent command(Intent original) {
        return new Intent(Intent.ACTION_VIEW, original.getData())
                .setComponent(new ComponentName(FrozenUrlHookPolicy.APP, FrozenUrlHookPolicy.ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
    private static boolean hasOwnUrl(List<?> list) {
        for (Object value : list) if (value instanceof ResolveInfo info && ownUrl(info)) return true;
        return false;
    }
    private static boolean ownUrl(ResolveInfo value) {
        return value != null && value.activityInfo != null
                && FrozenUrlHookPolicy.APP.equals(value.activityInfo.packageName)
                && FrozenUrlHookPolicy.ACTIVITY.equals(value.activityInfo.name);
    }
    private static void logFailure(XposedInterface module, String path, Throwable error) {
        module.log(Log.WARN, "LightFreeze", "Frozen URL " + path + " preserved original: " + error.getClass().getSimpleName());
    }
}
