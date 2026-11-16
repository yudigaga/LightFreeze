package io.github.yudigaga.lightfreeze.system;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Modern libxposed API 102 entry; resources scope this module to android only. */
public final class FreezerModule extends XposedModule {
    private volatile SystemController controller;
    private boolean windowHookInstalled;
    private boolean recentsHookInstalled;
    private boolean cardHookInstalled;
    private final java.util.concurrent.atomic.AtomicInteger unscopedCardDiagnostics = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger cardRequestDiagnostics = new java.util.concurrent.atomic.AtomicInteger();
    private FrozenUrlHooks.Installed frozenUrlHooks = new FrozenUrlHooks.Installed(false, "未安装");

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        log(Log.INFO, "LightFreeze", "module_start version=" + io.github.yudigaga.lightfreeze.BuildConfig.VERSION_NAME
                + " code=" + io.github.yudigaga.lightfreeze.BuildConfig.VERSION_CODE);
        if (getApiVersion() < 102 || (getFrameworkProperties() & XposedInterface.PROP_CAP_SYSTEM) == 0) {
            log(Log.ERROR, "LightFreeze", "API 102 system-server capability is unavailable"); return;
        }
        ClassLoader loader = param.getClassLoader();
        frozenUrlHooks = FrozenUrlHooks.install(this, loader, () -> controller);
        if (!frozenUrlHooks.supported()) log(Log.ERROR, "LightFreeze", "Frozen URL hooks unavailable: " + frozenUrlHooks.reason());
        try {
            // This overload is present in AOSP 13–16; no guessing among OEM overloads.
            Class<?> placer = Class.forName("com.android.server.wm.WindowSurfacePlacer", false, loader);
            Method placement = FrameworkAccess.exact(placer, "performSurfacePlacement", boolean.class);
            hook(placement).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                SystemController current = controller; if (current != null) current.signalEvent();
                return result;
            });
            windowHookInstalled = true;
        } catch (Exception e) { log(Log.ERROR, "LightFreeze", "Window visibility hook unavailable: " + FrameworkAccess.describe(e)); }
        try {
            Class<?> recentTasks = Class.forName("com.android.server.wm.RecentTasks", false, loader);
            Class<?> task = Class.forName("com.android.server.wm.Task", false, loader);
            Method add = FrameworkAccess.exact(recentTasks, "add", task);
            Method remove = FrameworkAccess.exact(recentTasks, "remove", task);
            if (add.getReturnType() != void.class || remove.getReturnType() != void.class)
                throw new NoSuchMethodException("RecentTasks.add/remove must return void");
            hook(add).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                SystemController current = controller; if (current != null) current.signalEvent();
                return result;
            });
            hook(remove).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                SystemController current = controller; if (current != null) current.signalEvent();
                return result;
            });
            recentsHookInstalled = true;
        } catch (Exception e) { log(Log.ERROR, "LightFreeze", "RecentTasks event hooks unavailable: " + FrameworkAccess.describe(e)); }
        installCardHooks(loader);
        try {
            Class<?> service = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            Method ready = FrameworkAccess.exact(service, "systemReady");
            hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (controller == null) {
                    try {
                        Object wm = chain.getThisObject(); Context context = (Context) FrameworkAccess.field(wm, "mContext");
                        controller = new SystemController(context, wm, loader, getModuleApplicationInfo().sourceDir,
                                windowHookInstalled, recentsHookInstalled, cardHookInstalled,
                                message -> log(Log.INFO, "LightFreeze", message));
                        controller.setFrozenUrlHooks(frozenUrlHooks.supported(), frozenUrlHooks.reason());
                    } catch (Exception e) { log(Log.ERROR, "LightFreeze", "System initialization failed: " + FrameworkAccess.describe(e)); }
                }
                return result;
            });
        } catch (Exception e) { log(Log.ERROR, "LightFreeze", "WMS readiness hook unavailable: " + FrameworkAccess.describe(e)); }
    }
    private void installCardHooks(ClassLoader loader) {
        installMembershipCardHook(loader);
        try {
            Class<?> atms = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader);
            Class<?> recentTasks = Class.forName("com.android.server.wm.RecentTasks", false, loader);
            Class<?> task = Class.forName("com.android.server.wm.Task", false, loader);
            Method removeOne = FrameworkAccess.exact(atms, "removeTask", int.class);
            Method removeAll = FrameworkAccess.exact(atms, "removeAllVisibleRecentTasks");
            Method isRecents = FrameworkAccess.exact(atms, "isCallerRecents", int.class);
            Method notifyRemoved = FrameworkAccess.exact(recentTasks, "notifyTaskRemoved", task, boolean.class, boolean.class);
            Method baseIntent = FrameworkAccess.exact(task, "getBaseIntent");
            Field taskUser = task.getDeclaredField("mUserId"); taskUser.setAccessible(true);
            if (removeOne.getReturnType() != boolean.class || removeAll.getReturnType() != void.class
                    || isRecents.getReturnType() != boolean.class || notifyRemoved.getReturnType() != void.class
                    || baseIntent.getReturnType() != Intent.class || taskUser.getType() != int.class)
                throw new NoSuchMethodException("card removal method or task identity signature mismatch");
            hook(notifyRemoved).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (!RecentsRemovalScope.active() && unscopedCardDiagnostics.getAndIncrement() < 6) {
                    // OEM removal paths may bypass ATMS.removeTask. Capture class/method names only.
                    StringBuilder frames = new StringBuilder();
                    StackTraceElement[] stack = new Throwable().getStackTrace();
                    for (int i = 0; i < Math.min(24, stack.length); i++) {
                        if (i > 0) frames.append(" > ");
                        frames.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
                    }
                    log(Log.INFO, "LightFreeze", "card_callback outside_recents_scope trimmed=" + chain.getArg(1)
                            + " kill=" + chain.getArg(2) + " callerUid=" + Binder.getCallingUid() + " frames=" + frames);
                }
                if (RecentsRemovalScope.active()) try {
                    Object removed = chain.getArg(0);
                    Intent base = (Intent) baseIntent.invoke(removed);
                    ComponentName component = base == null ? null : base.getComponent();
                    String pkg = component == null ? base == null ? null : base.getPackage() : component.getPackageName();
                    int user = taskUser.getInt(removed);
                    if (pkg != null && user >= 0) {
                        SystemStateStore.validatePackage(pkg);
                        RecentsRemovalScope.record(new AutomationEngine.Target(pkg, user));
                    }
                } catch (Exception e) { log(Log.ERROR, "LightFreeze", "Removed card identity unavailable: " + FrameworkAccess.describe(e)); }
                return result;
            });
            hook(removeOne).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                boolean recents = isRecentsRemoval(isRecents, chain.getThisObject());
                if (cardRequestDiagnostics.getAndIncrement() < 16)
                    log(Log.INFO, "LightFreeze", "card_remove_request recents=" + recents + " callerUid=" + Binder.getCallingUid());
                RecentsRemovalScope.Scope scope = RecentsRemovalScope.begin(recents);
                boolean success = false;
                try {
                    Object result = chain.proceed(); success = Boolean.TRUE.equals(result); return result;
                } finally {
                    java.util.Set<AutomationEngine.Target> targets = scope.finish(success);
                    SystemController current = controller; if (current != null) current.signalTaskRemoved(targets);
                }
            });
            hook(removeAll).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                RecentsRemovalScope.Scope scope = RecentsRemovalScope.begin(isRecentsRemoval(isRecents, chain.getThisObject()));
                boolean success = false;
                try { Object result = chain.proceed(); success = true; return result; }
                finally {
                    java.util.Set<AutomationEngine.Target> targets = scope.finish(success);
                    SystemController current = controller; if (current != null) current.signalTaskRemoved(targets);
                }
            });
            cardHookInstalled = true;
        } catch (Exception e) { log(Log.ERROR, "LightFreeze", "Card removal hooks unavailable: " + FrameworkAccess.describe(e)); }
    }
    private void installMembershipCardHook(ClassLoader loader) {
        try {
            Class<?> recentTasks = Class.forName("com.android.server.wm.RecentTasks", false, loader);
            Class<?> task = Class.forName("com.android.server.wm.Task", false, loader);
            Method remove = FrameworkAccess.exact(recentTasks, "remove", task);
            Method baseIntent = FrameworkAccess.exact(task, "getBaseIntent");
            Field members = recentTasks.getDeclaredField("mTasks"); members.setAccessible(true);
            Field taskUser = task.getDeclaredField("mUserId"); taskUser.setAccessible(true);
            if (remove.getReturnType() != void.class || baseIntent.getReturnType() != Intent.class
                    || !java.util.Collection.class.isAssignableFrom(members.getType()) || taskUser.getType() != int.class)
                throw new NoSuchMethodException("recent-task membership signature mismatch");
            hook(remove).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                RecentTaskMembership before = null;
                AutomationEngine.Target target = null;
                try {
                    Object removed = chain.getArg(0);
                    before = new RecentTaskMembership(removed,
                            (java.util.Collection<?>) members.get(chain.getThisObject()));
                    Intent base = (Intent) baseIntent.invoke(removed);
                    ComponentName component = base == null ? null : base.getComponent();
                    String pkg = component == null ? base == null ? null : base.getPackage() : component.getPackageName();
                    int user = taskUser.getInt(removed);
                    if (pkg != null && user >= 0) {
                        SystemStateStore.validatePackage(pkg);
                        target = new AutomationEngine.Target(pkg, user);
                    }
                } catch (Exception e) {
                    if (unscopedCardDiagnostics.getAndIncrement() < 6)
                        log(Log.WARN, "LightFreeze", "card_membership unavailable: " + e.getClass().getSimpleName());
                }
                Object result = chain.proceed();
                try {
                    if (before != null && target != null
                            && before.removedFrom((java.util.Collection<?>) members.get(chain.getThisObject()))) {
                        if (RecentsRemovalScope.active()) RecentsRemovalScope.record(target);
                        else {
                            SystemController current = controller;
                            if (current != null) current.signalTaskRemoved(java.util.Set.of(target));
                        }
                    }
                } catch (Exception e) {
                    if (unscopedCardDiagnostics.getAndIncrement() < 6)
                        log(Log.WARN, "LightFreeze", "card_membership result unavailable: " + e.getClass().getSimpleName());
                }
                return result;
            });
            cardHookInstalled = true;
        } catch (Exception e) {
            log(Log.ERROR, "LightFreeze", "Recent-task membership hook unavailable: " + FrameworkAccess.describe(e));
        }
    }
    private boolean isRecentsRemoval(Method isRecents, Object service) {
        if (controller == null || Binder.getCallingPid() == android.os.Process.myPid()) return false;
        try { return (boolean) isRecents.invoke(service, Binder.getCallingUid()); }
        catch (Exception e) { log(Log.ERROR, "LightFreeze", "Recents caller identity unavailable: " + FrameworkAccess.describe(e)); return false; }
    }
}
