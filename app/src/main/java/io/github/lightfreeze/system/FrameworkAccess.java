package io.github.yudigaga.lightfreeze.system;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.*;
import android.provider.Settings;
import android.telecom.TelecomManager;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.github.yudigaga.lightfreeze.core.FrameworkManagerPolicy;
import io.github.yudigaga.lightfreeze.core.HighRiskPolicy;

/** Exact Android 13–16 signatures; unsupported OEM structures stop automatic writes. */
final class FrameworkAccess {
    static final String APP = "io.github.yudigaga.lightfreeze";
    static final String PERMISSION = APP + ".permission.CONTROL";
    private final Context context;
    private final Object pm, wm, globalLock, root;
    private final Method getPackage, getEnabled, setEnabled, getHidden, getSuspended;
    private final Method allWindows, isVisible, owningUid, owningPackage, visibleRequested;
    private final Method currentUser, userHandleOf, clientUid, playerState;
    private final Field taskUserId;
    private final AudioManager audio;
    private final TelecomManager telecom;
    private final UserManager users;
    private final Set<String> fixedProtected = Set.of(APP, "android", "com.android.shell",
            "rikka.shizuku", "rikka.sui", "com.topjohnwu.magisk", "me.weishu.kernelsu",
            "me.weishu.kernelsu_next", "me.bmax.apatch", "org.lsposed.manager", "org.lsposed.lspatch",
            "com.android.systemui", "com.android.phone", "com.android.providers.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "moe.shizuku.privileged.api", "moe.shizuku.redirectstorage", "io.github.huskydg.magisk",
            "com.rifsxd.ksunext", "com.sukisu.ultra", "com.superuser", "eu.chainfire.supersu",
            "com.noshufou.android.su", "com.lsposed.manager", "org.meowcat.edxposed.manager");
    private final String moduleDigest;
    private Supplier<Set<String>> highRiskAllowedSource = Set::of;
    final boolean visibilitySupported;
    private final String visibilityProblem;
    private String recentsProblem = "";

    FrameworkAccess(Context context, Object wm, ClassLoader loader, String moduleApk) throws Exception {
        this.context = context; this.wm = wm;
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager", false, loader);
        pm = Class.forName("android.app.AppGlobals", false, loader).getDeclaredMethod("getPackageManager").invoke(null);
        getPackage = exact(ipm, "getPackageInfo", String.class, long.class, int.class);
        getEnabled = exact(ipm, "getApplicationEnabledSetting", String.class, int.class);
        setEnabled = exact(ipm, "setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
        getHidden = exact(ipm, "getApplicationHiddenSettingAsUser", String.class, int.class);
        getSuspended = exact(ipm, "isPackageSuspendedForUser", String.class, int.class);
        currentUser = exact(ActivityManager.class, "getCurrentUser");
        userHandleOf = exact(UserHandle.class, "of", int.class);
        Field recentUser = null;
        try { recentUser = recentTaskUserId(loader); }
        catch (Exception e) { recentsProblem = describe(e); }
        taskUserId = recentUser;
        audio = context.getSystemService(AudioManager.class); telecom = context.getSystemService(TelecomManager.class);
        users = context.getSystemService(UserManager.class);
        clientUid = exact(AudioPlaybackConfiguration.class, "getClientUid");
        playerState = exact(AudioPlaybackConfiguration.class, "isActive");
        Object lock = null, tree = null; Method all = null, visible = null, ownerUid = null, ownerPkg = null, requested = null;
        String failure = "";
        try {
            lock = field(wm, "mGlobalLock"); tree = field(wm, "mRoot");
            all = exact(Class.forName("com.android.server.wm.WindowContainer", false, loader),
                    "forAllWindows", Consumer.class, boolean.class);
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
            visible = exact(window, "isVisible"); requested = exact(window, "isVisibleRequested");
            ownerUid = exact(window, "getOwningUid"); ownerPkg = exact(window, "getOwningPackage");
        } catch (Exception e) { failure = describe(e); }
        globalLock = lock; root = tree; allWindows = all; isVisible = visible; visibleRequested = requested;
        owningUid = ownerUid; owningPackage = ownerPkg;
        visibilitySupported = failure.isEmpty(); visibilityProblem = failure;
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(moduleApk, PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new SecurityException("cannot read loaded module APK certificate");
        moduleDigest = digest(archive);
    }
    @SuppressLint("SoonBlockedPrivateApi")
    private static Field recentTaskUserId(ClassLoader loader) throws ReflectiveOperationException {
        // Optional system_server-only probe; the caller disables recents protection if access fails.
        if (android.os.Process.myUid() != 1000) throw new SecurityException("recent task probe requires system UID");
        Field field = Class.forName("android.app.TaskInfo", false, loader).getDeclaredField("userId");
        if (field.getType() != int.class) throw new NoSuchFieldException("TaskInfo.userId is not int");
        field.setAccessible(true);
        return field;
    }
    void authorizeController() throws Exception {
        PermissionInfo permission = context.getPackageManager().getPermissionInfo(PERMISSION, 0);
        PackageInfo controller = packageInfo(new AutomationEngine.Target(APP, currentUser()));
        if (!BridgeAuthority.accepts(permission.packageName, permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE,
                moduleDigest, digest(controller))) throw new SecurityException("control permission or loaded module certificate mismatch");
    }
    int currentUser() throws Exception { return (int) currentUser.invoke(null); }
    PackageInfo packageInfo(AutomationEngine.Target t) throws Exception {
        long flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        PackageInfo info = (PackageInfo) getPackage.invoke(pm, t.packageName(), flags, t.userId());
        if (info == null || info.applicationInfo == null) throw new PackageManager.NameNotFoundException(t.packageName());
        return info;
    }
    int setting(AutomationEngine.Target t) throws Exception { return (int) getEnabled.invoke(pm, t.packageName(), t.userId()); }
    void setHighRiskAllowedSource(Supplier<Set<String>> source) {
        highRiskAllowedSource = Objects.requireNonNull(source);
    }
    void assertManualRestoreAllowed(AutomationEngine.Target t, PackageInfo p) throws Exception {
        TargetPolicy.check(t.userId(), currentUser(), p.applicationInfo.uid, false, true);
        assertNotHiddenOrSuspended(t);
    }
    void assertTargetAllowed(AutomationEngine.Target t, PackageInfo p) throws Exception {
        boolean highRiskAllowed = HighRiskPolicy.isAllowed(highRiskAllowedSource.get(), t.packageName());
        TargetPolicy.checkFreeze(t.packageName(), t.userId(), currentUser(), p.applicationInfo.uid,
                FrameworkManagerPolicy.fixedProtectionApplies(t.packageName(), fixedProtected.contains(t.packageName())), highRiskAllowed);
        if (highRiskAllowed) { assertNotHiddenOrSuspended(t); return; }
        Context targetContext = userContext(t.userId());
        PackageManager manager = targetContext.getPackageManager();
        String permissionController = (String) exact(PackageManager.class, "getPermissionControllerPackageName").invoke(manager);
        if (permissionController == null) throw new SecurityException("permission controller identity unavailable");
        if (t.packageName().equals(permissionController)) throw new SecurityException("permission controller is protected");
        TelecomManager targetTelecom = targetContext.getSystemService(TelecomManager.class);
        if (targetTelecom == null) throw new SecurityException("default dialer service unavailable");
        if (t.packageName().equals(targetTelecom.getDefaultDialerPackage())) throw new SecurityException("current dialer is protected");
        ResolveInfo home = manager.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null && t.packageName().equals(home.activityInfo.packageName))
            throw new SecurityException("current launcher is protected");
        String keyboard = Settings.Secure.getString(targetContext.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName input = keyboard == null ? null : ComponentName.unflattenFromString(keyboard);
        if (input != null && t.packageName().equals(input.getPackageName())) throw new SecurityException("current input method is protected");
        assertNotHiddenOrSuspended(t);
    }
    private void assertNotHiddenOrSuspended(AutomationEngine.Target t) throws Exception {
        if ((boolean) getHidden.invoke(pm, t.packageName(), t.userId()) || (boolean) getSuspended.invoke(pm, t.packageName(), t.userId()))
            throw new SecurityException("externally hidden or suspended package");
    }
    @SuppressLint("MissingPermission") // Runs only as system UID; denied telephony access is caught and protection fails closed.
    AutomationEngine.Observation observe(AutomationEngine.Session s, boolean lockKnown, boolean locked,
                                         boolean protectMedia, boolean protectRecents) throws Exception {
        PackageInfo info = packageInfo(s.target); int actual = setting(s.target);
        boolean identity = info.applicationInfo.uid == s.uid && s.digest.equals(installationIdentity(info));
        if (!identity) return new AutomationEngine.Observation(false, actual, false, false, lockKnown, locked, false, false);
        if (!s.temporary || s.cancelled) {
            assertManualRestoreAllowed(s.target, info);
            return new AutomationEngine.Observation(true, actual, true, false, lockKnown, locked, true, false);
        }
        assertTargetAllowed(s.target, info);
        UserHandle user = (UserHandle) userHandleOf.invoke(null, s.target.userId());
        if (!users.isUserUnlocked(user) || users.isQuietModeEnabled(user))
            return new AutomationEngine.Observation(true, actual, false, false, lockKnown, locked, false, false);
        boolean[] visible = {false}; boolean visibilityKnown = visibilitySupported;
        if (visibilityKnown) {
            try {
                synchronized (globalLock) {
                    allWindows.invoke(root, (Consumer<Object>) window -> {
                        try {
                            if ((int) owningUid.invoke(window) == s.uid && s.target.packageName().equals(owningPackage.invoke(window))
                                    && ((boolean) isVisible.invoke(window) || (boolean) visibleRequested.invoke(window))) visible[0] = true;
                        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }, false);
                }
            } catch (Exception e) { visibilityKnown = false; }
        }
        boolean protectionKnown = true, protectedNow = false;
        if (protectMedia) {
            try {
                if (audio == null || telecom == null) throw new IllegalStateException("audio/telecom service unavailable");
                protectedNow = telecom.isInCall() || audio.getMode() != AudioManager.MODE_NORMAL;
                for (AudioPlaybackConfiguration playback : audio.getActivePlaybackConfigurations()) {
                    int uid = (int) clientUid.invoke(playback);
                    if (uid < 0) throw new SecurityException("audio playback UID is anonymized");
                    if (uid == s.uid && (boolean) playerState.invoke(playback)) protectedNow = true;
                }
            } catch (Exception e) { protectionKnown = false; }
        }
        if (protectRecents) {
            try { protectedNow |= hasRecentTask(s.target); }
            catch (Exception e) { protectionKnown = false; }
        }
        return new AutomationEngine.Observation(true, actual, visibilityKnown, visible[0], lockKnown, locked,
                protectionKnown, protectedNow);
    }
    void freeze(AutomationEngine.Session s) throws Exception {
        PackageInfo info = packageInfo(s.target); assertTargetAllowed(s.target, info);
        if (info.applicationInfo.uid != s.uid || !s.digest.equals(installationIdentity(info))) throw new SecurityException("installation identity changed");
        int actual = setting(s.target);
        if (actual != s.original) throw new IllegalStateException("enabled state changed outside the lease");
        setEnabled.invoke(pm, s.target.packageName(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, s.target.userId(), "android");
    }
    void assertModuleFreezable(int userId) throws Exception {
        if (android.os.Process.myUid() != 1000 || context.checkPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE",
                android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("system UID / CHANGE_COMPONENT_ENABLED_STATE unavailable");
        authorizeController();
        AutomationEngine.Target self = new AutomationEngine.Target(APP, userId);
        if (userId != currentUser()) throw new SecurityException("self-freeze user mismatch");
        if (!moduleDigest.equals(digest(packageInfo(self)))) throw new SecurityException("module identity changed");
        int before = setting(self);
        if (before != 0 && before != 1) throw new IllegalStateException("module was already restricted");
    }
    void freezeModule(int userId) throws Exception {
        assertModuleFreezable(userId);
        AutomationEngine.Target self = new AutomationEngine.Target(APP, userId);
        setEnabled.invoke(pm, APP, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId, "android");
        if (setting(self) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            throw new IllegalStateException("PackageManager did not confirm module DISABLED_USER");
    }
    int moduleSetting(int userId) throws Exception { return setting(new AutomationEngine.Target(APP, userId)); }
    String moduleBinding(int userId) throws Exception {
        authorizeController();
        PackageInfo info = packageInfo(new AutomationEngine.Target(APP, userId));
        return info.applicationInfo.uid + ":" + installationIdentity(info) + ":" + info.getLongVersionCode() + ":" + info.lastUpdateTime;
    }
    boolean currentUserUnlocked(int userId) throws Exception {
        if (userId != currentUser() || users == null) return false;
        UserHandle user = (UserHandle) userHandleOf.invoke(null, userId);
        return users.isUserUnlocked(user) && !users.isQuietModeEnabled(user);
    }
    void enableModuleForUrl(int userId, int original) throws Exception {
        if ((original != 0 && original != 1) || !currentUserUnlocked(userId))
            throw new SecurityException("invalid module wake state or locked user");
        authorizeController();
        int before = moduleSetting(userId);
        if (before != 3 && before != original) throw new IllegalStateException("module state changed outside URL wake");
        setEnabled.invoke(pm, APP, original, 0, userId, "android");
        if (moduleSetting(userId) != original) throw new IllegalStateException("module enable was not confirmed");
    }
    ResolveInfo urlActivityInfo(int userId) throws Exception {
        ActivityInfo info = userContext(userId).getPackageManager().getActivityInfo(
                new ComponentName(APP, APP + ".ui.UrlActionActivity"),
                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE));
        if (!info.exported || !APP.equals(info.packageName) || !info.name.equals(APP + ".ui.UrlActionActivity"))
            throw new SecurityException("unexpected URL activity identity");
        ResolveInfo result = new ResolveInfo(); result.activityInfo = info; result.isDefault = true;
        result.resolvePackageName = APP; return result;
    }
    void launchUrlActivity(String ticket, String uri, int userId) throws Exception {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .setComponent(new ComponentName(APP, APP + ".ui.UrlActionActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (ticket != null) {
            intent.setIdentifier(ticket).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.putExtra(io.github.yudigaga.lightfreeze.core.Protocol.FROZEN_URL_TICKET, ticket);
        }
        userContext(userId).startActivity(intent);
    }
    String visibilityProblem() { return visibilityProblem; }
    boolean recentsQuerySupported() {
        try { recentTasks(currentUser()); recentsProblem = ""; return true; }
        catch (Exception e) { recentsProblem = describe(e); return false; }
    }
    String recentsProblem() { return recentsProblem; }
    boolean hasRecentTask(AutomationEngine.Target target) throws Exception {
        for (ActivityManager.RecentTaskInfo task : recentTasks(target.userId())) {
            if (taskUserId.getInt(task) != target.userId()) continue;
            Intent base = task.baseIntent;
            if ((base != null && (target.packageName().equals(base.getPackage())
                    || matches(target.packageName(), base.getComponent())))
                    || matches(target.packageName(), task.baseActivity) || matches(target.packageName(), task.topActivity)) return true;
        }
        return false;
    }
    @SuppressWarnings("deprecation")
    private List<ActivityManager.RecentTaskInfo> recentTasks(int userId) throws Exception {
        // AOSP filters this deprecated API for unprivileged callers; an empty filtered list is unsafe.
        if (android.os.Process.myUid() != 1000 || context.checkPermission("android.permission.REAL_GET_TASKS",
                android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("最近任务保护无法读取完整列表：system UID / REAL_GET_TASKS 不可用");
        if (taskUserId == null) throw new IllegalStateException("最近任务保护无法读取 TaskInfo.userId: " + recentsProblem);
        if (userId != currentUser()) throw new SecurityException("最近任务保护只支持当前用户");
        UserHandle handle = (UserHandle) userHandleOf.invoke(null, userId);
        if (users == null || !users.isUserUnlocked(handle) || users.isQuietModeEnabled(handle))
            throw new IllegalStateException("最近任务保护无法确认：用户未解锁或处于安静模式");
        ActivityManager manager = userContext(userId).getSystemService(ActivityManager.class);
        if (manager == null) throw new IllegalStateException("最近任务保护的 ActivityManager 不可用");
        List<ActivityManager.RecentTaskInfo> tasks = manager.getRecentTasks(1000, 0);
        if (tasks == null || tasks.size() >= 1000) throw new IllegalStateException("最近任务保护列表缺失或可能被截断");
        for (ActivityManager.RecentTaskInfo task : tasks) {
            if (task == null || taskUserId.getInt(task) < 0) throw new IllegalStateException("最近任务保护列表包含未知任务身份");
            Intent base = task.baseIntent;
            if (taskUserId.getInt(task) == userId && (base == null || (base.getPackage() == null && base.getComponent() == null))
                    && task.baseActivity == null && task.topActivity == null)
                throw new IllegalStateException("最近任务保护无法识别任务所属应用");
        }
        return tasks;
    }
    private static boolean matches(String packageName, ComponentName component) {
        return component != null && packageName.equals(component.getPackageName());
    }
    private Context userContext(int userId) throws Exception {
        UserHandle handle = (UserHandle) userHandleOf.invoke(null, userId);
        return (Context) exact(Context.class, "createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
    }
    static String installationIdentity(PackageInfo info) throws Exception { return digest(info) + ":" + info.firstInstallTime; }
    static String digest(PackageInfo info) throws Exception {
        if (info.signingInfo == null) throw new SecurityException("signing identity unavailable");
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) throw new SecurityException("empty package signatures");
        List<String> parts = new ArrayList<>();
        for (Signature signature : signatures) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            parts.add(hex.toString());
        }
        Collections.sort(parts); return String.join(":", parts);
    }
    static Method exact(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, params); method.setAccessible(true); return method;
    }
    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner.getClass();
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }
    static String describe(Throwable e) {
        if (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
