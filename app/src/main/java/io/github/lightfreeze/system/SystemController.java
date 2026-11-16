package io.github.yudigaga.lightfreeze.system;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import android.os.*;
import android.telephony.TelephonyManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.yudigaga.lightfreeze.automation.FrozenUrlSnapshot;
import io.github.yudigaga.lightfreeze.automation.UrlAction;
import io.github.yudigaga.lightfreeze.core.Protocol;

/** No service in the APK. This object exists only in the injected system_server process. */
final class SystemController implements AutomationEngine.Host, FrozenUrlHooks.Gate {
    static final String ACTION = "io.github.yudigaga.lightfreeze.CONTROL";
    private final Context context;
    private final Handler worker;
    private final String moduleApk;
    private final java.util.function.Consumer<String> diagnostics;
    private final AtomicBoolean eventQueued = new AtomicBoolean();
    private final AtomicBoolean followScanQueued = new AtomicBoolean();
    private final Map<AutomationEngine.Target,Runnable> timers = new HashMap<>();
    private final AutomationEngine engine;
    private final PolicyAuthority policyAuthority;
    private FrozenUrlCoordinator frozenUrls;
    private volatile FrameworkAccess framework;
    private volatile boolean frozenUrlHooksInstalled;
    private String frozenUrlProblem = "系统 URL 入口尚未初始化", frozenUrlHookProblem = "未安装";
    private record UrlGateView(FrozenUrlSnapshot authority, String owner, boolean armed) { }
    private volatile UrlGateView urlGateView;
    private final Set<String> urlGateDiagnostics = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private SystemStateStore store;
    private KeyguardManager keyguard;
    private boolean lockSupported, leaveSupported, protectionsSupported;
    private final boolean recentsHookInstalled;
    private final boolean cardHookInstalled;
    private volatile boolean hasActiveSessions;
    private String initializationProblem = "", capabilityProblem = "";
    private boolean statePersisted;

    SystemController(Context context, Object wm, ClassLoader loader, String moduleApk, boolean windowHookInstalled,
                     boolean recentsHookInstalled) {
        this(context, wm, loader, moduleApk, windowHookInstalled, recentsHookInstalled, false);
    }
    SystemController(Context context, Object wm, ClassLoader loader, String moduleApk, boolean windowHookInstalled,
                     boolean recentsHookInstalled, boolean cardHookInstalled) {
        this(context, wm, loader, moduleApk, windowHookInstalled, recentsHookInstalled, cardHookInstalled,
                message -> Log.i("LightFreeze", message));
    }
    SystemController(Context context, Object wm, ClassLoader loader, String moduleApk, boolean windowHookInstalled,
                     boolean recentsHookInstalled, boolean cardHookInstalled, java.util.function.Consumer<String> diagnostics) {
        this.context = context; this.moduleApk = moduleApk; this.recentsHookInstalled = recentsHookInstalled;
        this.diagnostics = diagnostics;
        this.cardHookInstalled = cardHookInstalled;
        HandlerThread thread = new HandlerThread("LightFreeze", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper()); engine = new AutomationEngine(this);
        policyAuthority = new PolicyAuthority(engine);
        worker.post(() -> initialize(wm, loader, windowHookInstalled));
    }
    private void initialize(Object wm, ClassLoader loader, boolean windowHookInstalled) {
        try {
            framework = new FrameworkAccess(context, wm, loader, moduleApk);
            framework.setHighRiskAllowedSource(() -> engine.policy.highRiskAllowed());
            store = new SystemStateStore(); store.load(engine, now());
            persist(engine);
            hasActiveSessions = engine.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.ACTIVE);
            leaveSupported = windowHookInstalled && framework.visibilitySupported;
            if (!leaveSupported) capabilityProblem = "窗口聚合不可用: " + framework.visibilityProblem();
            initializeEvents();
        } catch (Exception e) {
            initializationProblem = FrameworkAccess.describe(e); engine.healthy = false; hasActiveSessions = false;
            diagnostic("system_init_error " + initializationProblem);
        }
        if (engine.healthy) initializeFrozenUrls();
        try {
            IntentFilter filter = new IntentFilter(ACTION);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { receive(intent); }
            }, filter, FrameworkAccess.PERMISSION, worker, Context.RECEIVER_EXPORTED);
        } catch (Exception e) {
            initializationProblem = "签名控制桥注册失败: " + FrameworkAccess.describe(e);
            engine.healthy = false; diagnostic("bridge_init_error " + initializationProblem); return;
        }
        diagnostic("system_ready healthy=" + engine.healthy + " visibility=" + leaveSupported
                + " lock=" + lockSupported + " cardHooks=" + cardHookInstalled + " urlHooks=" + frozenUrlHooksInstalled
                + " sessions=" + engine.sessions.size() + " capability=" + capabilityProblem);
        if (engine.healthy) { seedFollowed("boot"); engine.onEvent(); }
    }
    void setFrozenUrlHooks(boolean installed, String problem) {
        frozenUrlHooksInstalled = installed; frozenUrlHookProblem = problem;
    }
    private void initializeFrozenUrls() {
        try {
            FrozenUrlStateStore urls = new FrozenUrlStateStore();
            frozenUrls = new FrozenUrlCoordinator(new FrozenUrlCoordinator.Host() {
                public int moduleSetting(int user) throws Exception { return framework.moduleSetting(user); }
                public void enable(int user, int original) throws Exception {
                    assertFrozenOwner(user); framework.enableModuleForUrl(user, original); assertFrozenOwner(user);
                }
                public void disable(int user) throws Exception { assertFrozenOwner(user); framework.freezeModule(user); }
                public void prepareFreeze() throws Exception {
                    assertFrozenOwner(frozenUrls.userId()); engine.prepareSelfFreeze();
                }
                public void persist(String json) throws Exception { urls.save(json); }
                public boolean post(Runnable job, long delay) {
                    return worker.postDelayed(() -> { try { job.run(); } finally { publishFrozenView(); } }, delay);
                }
                public void launch(String ticket, String uri, int user) throws Exception { framework.launchUrlActivity(ticket, uri, user); }
                public void log(String problem) { diagnostic("frozen_url_error " + problem); }
            });
            frozenUrls.restore(urls.load(), framework.moduleBinding(framework.currentUser()));
            IntentFilter lifecycle = new IntentFilter();
            lifecycle.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED); lifecycle.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            lifecycle.addAction(Intent.ACTION_PACKAGE_REPLACED); lifecycle.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (intent.getData() == null || !FrameworkAccess.APP.equals(intent.getData().getSchemeSpecificPart()) || frozenUrls == null) return;
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid >= 0 && frozenUrls.userId() >= 0 && uid / 100000 != frozenUrls.userId()) return;
                    try {
                        frozenUrls.keepEnabled();
                        int user = uid >= 0 ? uid / 100000 : framework.currentUser();
                        if (user == framework.currentUser())
                            frozenUrls.sync(FrozenUrlSnapshot.create(user, false, Map.of()), framework.moduleBinding(user));
                    } catch (Exception e) {
                        try { urls.invalidate(); } catch (Exception failure) { Log.e("LightFreeze", "旧本体 URL 文件清理失败: " + failure.getClass().getSimpleName()); }
                        frozenUrls = null; frozenUrlProblem = "本体应用数据或安装已改变，旧系统 URL 授权已停止，请重新打开同步";
                        Log.e("LightFreeze", frozenUrlProblem);
                    }
                    finally { publishFrozenView(); }
                }
            }, lifecycle, null, worker, Context.RECEIVER_EXPORTED);
            frozenUrlProblem = ""; publishFrozenView();
        } catch (Exception e) {
            frozenUrls = null; urlGateView = null; frozenUrlProblem = FrameworkAccess.describe(e);
            Log.e("LightFreeze", "本体 URL 状态初始化失败: " + frozenUrlProblem);
        }
    }
    private void assertFrozenOwner(int user) throws Exception {
        if (frozenUrls == null || !framework.currentUserUnlocked(user)
                || !frozenUrls.ownerBinding().equals(framework.moduleBinding(user)))
            throw new SecurityException("frozen URL module identity or user changed");
    }
    private void publishFrozenView() {
        FrozenUrlCoordinator urls = frozenUrls;
        urlGateView = urls == null ? null : new UrlGateView(urls.authority(), urls.ownerBinding(), urls.armed());
    }
    private boolean validFrozenGrant(Intent intent, int user, int uid) {
        UrlGateView view = urlGateView; FrameworkAccess access = framework;
        if (!frozenUrlHooksInstalled) return rejectUrlGate("hooks_unavailable");
        if (view == null || view.authority == null || access == null) return rejectUrlGate("state_unavailable");
        if (!view.armed) return rejectUrlGate("not_armed");
        if (user < 0 || uid < 0 || uid / 100000 != user) return rejectUrlGate("caller_user_mismatch");
        try {
            if (!view.authority.accepts(UrlAction.parse(intent.getDataString()), user)) return rejectUrlGate("grant_rejected");
            long identity = Binder.clearCallingIdentity();
            try {
                if (!access.currentUserUnlocked(user)) return rejectUrlGate("user_not_unlocked");
                if (!view.owner.equals(access.moduleBinding(user))) return rejectUrlGate("installation_changed");
                return true;
            } finally { Binder.restoreCallingIdentity(identity); }
        } catch (Exception e) { return rejectUrlGate("validation_error"); }
    }
    private boolean rejectUrlGate(String reason) {
        if (urlGateDiagnostics.add(reason)) diagnostic("frozen_url_gate preserved_original reason=" + reason);
        return false;
    }
    @Override public boolean dispatch(Intent intent, int user, int uid) {
        if (!validFrozenGrant(intent, user, uid)) return false;
        final String uri = intent.getDataString();
        return worker.post(() -> {
            long identity = Binder.clearCallingIdentity();
            try {
                if (frozenUrls == null || !framework.currentUserUnlocked(user)
                        || !frozenUrls.ownerBinding().equals(framework.moduleBinding(user))) return;
                boolean routed = frozenUrls.route(uri, user);
                diagnostic("frozen_url_route accepted=" + routed + " state=" + frozenUrls.phase());
                if (!routed) framework.launchUrlActivity(null, uri, user);
            } catch (Exception e) { diagnostic("frozen_url_launch_error " + e.getClass().getSimpleName()); }
            finally { publishFrozenView(); Binder.restoreCallingIdentity(identity); }
        });
    }
    @Override public ResolveInfo resolve(Intent intent, int user, int uid) {
        if (!validFrozenGrant(intent, user, uid)) return null;
        long identity = Binder.clearCallingIdentity();
        try { return framework.urlActivityInfo(user); }
        catch (Exception e) { return null; }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @SuppressLint("MissingPermission") // Injected system UID only; registration failures leave the capability unavailable.
    private void initializeEvents() {
        keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard != null) {
            try {
                keyguard.addKeyguardLockedStateListener(worker::post, locked -> signalFollowScan("keyguard"));
                keyguard.isKeyguardLocked(); lockSupported = leaveSupported;
            } catch (Exception e) { capabilityProblem += "; Keyguard监听不可用: " + FrameworkAccess.describe(e); }
        }
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) throw new IllegalStateException("AudioManager unavailable");
            audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> configurations) { signalEvent(); }
            }, worker);
            audio.addOnModeChangedListener(worker::post, mode -> signalEvent());
            IntentFilter systemEvents = new IntentFilter();
            systemEvents.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            systemEvents.addAction(Intent.ACTION_USER_UNLOCKED);
            systemEvents.addAction(Intent.ACTION_USER_BACKGROUND);
            systemEvents.addAction(Intent.ACTION_USER_FOREGROUND);
            systemEvents.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
            systemEvents.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) signalEvent();
                    else signalFollowScan("user_event");
                }
            }, systemEvents, null, worker, Context.RECEIVER_EXPORTED);
            protectionsSupported = true;
        } catch (Exception e) { capabilityProblem += "; 媒体/通话事件不可用: " + FrameworkAccess.describe(e); }
        try {
            IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            packages.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (intent.getData() == null) return;
                    String pkg = intent.getData().getSchemeSpecificPart();
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid < 0) return;
                    try {
                        SystemStateStore.validatePackage(pkg);
                        AutomationEngine.Target target = new AutomationEngine.Target(pkg, uid / 100000);
                        reconcileOwnedTarget(target);
                        followTarget(target, uid);
                        engine.onEvent();
                    } catch (Exception e) { diagnostic("tag_follow_package_rejected reason=" + e.getClass().getSimpleName()); }
                }
            }, packages, null, worker, Context.RECEIVER_EXPORTED);
        } catch (Exception e) { capabilityProblem += "; 标签应用状态监听不可用: " + FrameworkAccess.describe(e); }
    }
    private void reconcileOwnedTarget(AutomationEngine.Target target) throws Exception {
        if (!engine.healthy || framework == null || !engine.ownedFreezes.containsKey(target)
                || target.userId() != framework.currentUser()) return;
        try {
            PackageInfo info = framework.packageInfo(target);
            engine.reconcileOwnedFreeze(target, info.applicationInfo.uid, FrameworkAccess.installationIdentity(info),
                    framework.setting(target));
        } catch (PackageManager.NameNotFoundException e) {
            AutomationEngine.OwnedFreeze owned = engine.ownedFreezes.get(target);
            if (owned != null && owned.confirmed()) engine.reconcileOwnedFreeze(target, -1, "", -1);
        }
    }
    private boolean followTarget(AutomationEngine.Target target, int expectedUid) throws Exception {
        AutomationEngine.FollowCandidate candidate = followCandidate(target, expectedUid, true);
        return candidate != null && engine.follow(candidate.target(), candidate.uid(), candidate.digest(), candidate.original());
    }
    private AutomationEngine.FollowCandidate followCandidate(AutomationEngine.Target target, int expectedUid,
                                                           boolean reconcileOwnership) throws Exception {
        if (!engine.healthy || framework == null || !engine.policy.managed().contains(target.packageName())
                || engine.policy.modeFor(target.packageName()) == 0 || engine.sessions.containsKey(target)) return null;
        if (!framework.currentUserUnlocked(target.userId())) return null;
        int mode = engine.policy.modeFor(target.packageName());
        if (((mode & 1) != 0 && !lockSupported) || ((mode & 4) != 0 && (!cardHookInstalled || !leaveSupported))) return null;
        PackageInfo info = framework.packageInfo(target);
        if (info.applicationInfo.uid / 100000 != target.userId()
                || (expectedUid >= 0 && info.applicationInfo.uid != expectedUid)) return null;
        int setting = framework.setting(target);
        String digest = FrameworkAccess.installationIdentity(info);
        if (reconcileOwnership) engine.reconcileOwnedFreeze(target, info.applicationInfo.uid, digest, setting);
        if ((setting != 0 && setting != 1) || !info.applicationInfo.enabled
                || (info.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) return null;
        framework.assertTargetAllowed(target, info);
        return new AutomationEngine.FollowCandidate(target, info.applicationInfo.uid, digest, setting);
    }
    private void seedFollowed(String reason) {
        if (!engine.healthy || framework == null) return;
        int adopted = 0, rejected = 0;
        try {
            int user = framework.currentUser();
            if (!framework.currentUserUnlocked(user)) return;
            List<AutomationEngine.FollowCandidate> candidates = new ArrayList<>();
            for (String pkg : new ArrayList<>(engine.policy.managed())) {
                try {
                    AutomationEngine.FollowCandidate candidate = followCandidate(new AutomationEngine.Target(pkg, user), -1, false);
                    if (candidate != null) candidates.add(candidate);
                }
                catch (Exception e) { rejected++; }
            }
            adopted = engine.followAll(candidates);
        } catch (Exception e) { rejected++; }
        diagnostic("tag_follow_scan reason=" + reason + " adopted=" + adopted + " rejected=" + rejected);
    }
    private void signalFollowScan(String reason) {
        if (!followScanQueued.compareAndSet(false, true)) return;
        worker.post(() -> {
            followScanQueued.set(false);
            seedFollowed(reason); engine.onEvent();
        });
    }
    void signalEvent() {
        // Window placement can be frequent. Queue at most one snapshot; idle sessions cost no scans.
        if (!hasActiveSessions) return;
        if (!eventQueued.compareAndSet(false, true)) return;
        worker.post(() -> {
            eventQueued.set(false); if (hasActiveSessions) engine.onEvent();
            hasActiveSessions = engine.healthy && engine.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.ACTIVE);
        });
    }
    void signalTaskRemoved(Set<AutomationEngine.Target> targets) {
        if (!cardHookInstalled || targets.isEmpty()) return;
        worker.post(() -> {
            for (AutomationEngine.Target target : targets) {
                try { followTarget(target, -1); }
                catch (Exception e) { diagnostic("tag_follow_card_rejected reason=" + e.getClass().getSimpleName()); }
                engine.onTaskRemoved(target);
            }
            hasActiveSessions = engine.healthy && engine.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.ACTIVE);
        });
    }
    private void receive(Intent intent) {
        ResultReceiver receiver = null;
        boolean authorized = false;
        Bundle result = new Bundle(); result.putBoolean("ok", false);
        try {
            if (!ACTION.equals(intent.getAction())) return;
            receiver = intent.getParcelableExtra("receiver", ResultReceiver.class);
            if (receiver == null) return;
            authorize();
            authorized = true;
            String operation = intent.getStringExtra("operation"); Bundle input = intent.getBundleExtra("input");
            if (input == null) input = new Bundle();
            String id = input.getString("requestId", ""); result.putString("requestId", id);
            if ("STATUS".equals(operation)) { status(result); result.putBoolean("ok", true); return; }
            if (!engine.healthy || framework == null || store == null || !statePersisted)
                throw new IllegalStateException(initializationProblem + "; " + engine.problem);
            long identity = Binder.clearCallingIdentity();
            try { dispatch(operation, input, result); }
            finally { Binder.restoreCallingIdentity(identity); }
            result.putBoolean("ok", true);
            if (!result.containsKey("message")) result.putString("message", "系统已确认");
        } catch (Exception e) {
            String failure = FrameworkAccess.describe(e); result.putString("message", failure);
            // Never log control input, URL, token, ticket, digest or config acknowledgements.
            String operation = safeOperation(intent.getStringExtra("operation"));
            if (authorized && Protocol.SYNC_CONFIG.equals(operation) && statePersisted) {
                try {
                    Bundle input = intent.getBundleExtra("input");
                    String text = input == null ? null : input.getString("config");
                    if (text != null && text.length() <= 128 * 1024) {
                        String token = policyAuthority.persistedToken(SystemStateStore.parsePolicy(new JSONObject(text)));
                        if (token != null) {
                            result.putBoolean("configPersisted", true);
                            result.putString("configToken", token);
                            result.putLong("revision", engine.revision);
                        }
                    }
                } catch (Exception ignored) { /* Failed or unmatched snapshots have no acknowledgement. */ }
            }
            diagnostic("control_rejected operation=" + operation + " reason="
                    + (operation.contains("URL") ? e.getClass().getSimpleName() : failure));
        }
        finally {
            publishFrozenView();
            if (receiver != null) { try { receiver.send(result.getBoolean("ok") ? 0 : 1, result); } catch (RuntimeException ignored) { } }
        }
    }
    private void authorize() throws Exception {
        // The receiver enforces sender permission; the module certificate was pinned at initialization.
        if (framework == null) throw new SecurityException("system controller is not initialized");
        framework.authorizeController();
    }
    private void status(Bundle result) {
        boolean ready = engine.healthy && store != null && framework != null && statePersisted;
        result.putString("moduleVersionName", io.github.yudigaga.lightfreeze.BuildConfig.VERSION_NAME);
        result.putInt("moduleVersionCode", io.github.yudigaga.lightfreeze.BuildConfig.VERSION_CODE);
        result.putInt("protocolVersion", 8);
        result.putBoolean("ownerRulesSupported", ready);
        result.putBoolean("configPersistedAckSupported", ready);
        result.putBoolean("highRiskAllowlistSupported", ready);
        result.putBoolean("tagFollowSupported", ready);
        result.putBoolean("autoRecoverySupported", ready);
        result.putBoolean("multiTriggerSupported", ready);
        boolean taskQuerySupported = ready && framework.recentsQuerySupported();
        result.putBoolean("cardSupported", taskQuerySupported && cardHookInstalled && leaveSupported);
        result.putString("cardReason", !cardHookInstalled ? "划卡回冻不可用：最近任务移除入口 Hook 未完整安装"
                : !leaveSupported ? "划卡回冻不可用：可见窗口事件能力未就绪"
                : taskQuerySupported ? "" : "划卡回冻不可用：" + (framework == null ? initializationProblem : framework.recentsProblem()));
        result.putBoolean("tabSessionsSupported", ready);
        result.putBoolean("directThawSupported", ready);
        result.putBoolean("configTokenSupported", ready);
        result.putBoolean("groupSessionsSupported", ready);
        result.putBoolean("recentsProtectionSupported", taskQuerySupported && recentsHookInstalled);
        result.putString("recentsProtectionReason", recentsHookInstalled ? (framework == null ? initializationProblem : framework.recentsProblem())
                : "最近任务保护不可用：RecentTasks.add/remove 事件 Hook 未完整安装");
        result.putBoolean("ready", ready); result.putBoolean("lockSupported", ready && lockSupported);
        result.putBoolean("leaveSupported", false);
        String selfFreezeReason;
        try {
            if (!ready || frozenUrls == null) selfFreezeReason = frozenUrlProblem;
            else if (!frozenUrls.healthy()) selfFreezeReason = frozenUrls.problem();
            else { framework.assertModuleFreezable(framework.currentUser()); selfFreezeReason = ""; }
        }
        catch (Exception e) { selfFreezeReason = FrameworkAccess.describe(e); }
        result.putBoolean("selfFreezeSupported", selfFreezeReason.isEmpty());
        result.putString("selfFreezeReason", selfFreezeReason.isEmpty()
                ? "将仅冻结本体，目标自动会话保留；跨重启重新注入未实测，需外部工具恢复本体" : selfFreezeReason);
        String selfProblem = frozenUrls == null ? frozenUrlProblem : frozenUrls.problem();
        result.putString("selfFreezeState", frozenUrls == null ? "unavailable" : frozenUrls.phase());
        result.putString("selfFreezeError", selfProblem);
        result.putBoolean("frozenUrlSupported", ready && frozenUrls != null && frozenUrls.healthy() && frozenUrlHooksInstalled);
        result.putString("frozenUrlReason", frozenUrls == null ? frozenUrlProblem
                : frozenUrlHooksInstalled ? selfProblem : frozenUrlHookProblem);
        result.putString("frozenUrlEpoch", frozenUrls == null ? "" : frozenUrls.epoch());
        result.putString("frozenUrlDigest", frozenUrls == null ? "" : frozenUrls.digest());
        result.putInt("frozenUrlActive", frozenUrls == null ? 0 : frozenUrls.activeCount());
        result.putString("automaticTargetPolicy", "高危系统组件默认保护；可在设置中按包名放行，风险由用户承担，手动解冻仍可恢复");
        result.putString("message", (ready ? "系统控制桥已就绪" + (capabilityProblem.isEmpty() ? "" : "; " + capabilityProblem)
                + (engine.problem.isEmpty() ? "" : "; " + engine.problem) : initializationProblem + "; " + engine.problem)
                + (selfProblem.isEmpty() ? "" : "; 本体冻结/URL状态: " + selfProblem));
        result.putLong("revision", engine.revision); result.putInt("sessions", engine.sessions.size());
        int unresolved = 0;
        for (AutomationEngine.Session s : engine.sessions.values()) if (s.phase != AutomationEngine.Phase.ACTIVE) unresolved++;
        result.putInt("unresolved", unresolved);
    }
    private void dispatch(String operation, Bundle input, Bundle result) throws Exception {
        if ("GET_AUTO_RECOVERY_LIST".equals(operation)) {
            int user = input.getInt("userId", -1);
            if (user != framework.currentUser()) throw new SecurityException("current user required");
            JSONArray records = new JSONArray();
            for (AutomationEngine.Target target : new ArrayList<>(engine.ownedFreezes.keySet())) {
                if (target.userId() != user) continue;
                try {
                    PackageInfo info = framework.packageInfo(target);
                    int setting = framework.setting(target);
                    String digest = FrameworkAccess.installationIdentity(info);
                    engine.reconcileOwnedFreeze(target, info.applicationInfo.uid, digest, setting);
                    if (setting != 3 || info.applicationInfo.uid / 100000 != user) continue;
                    Integer original = engine.ownedBaseline(target, info.applicationInfo.uid, digest);
                    if (original != null) records.put(new JSONObject().put("packageName", target.packageName())
                            .put("userId", user).put("uid", info.applicationInfo.uid)
                            .put("signature", FrameworkAccess.digest(info)).put("installedAt", info.firstInstallTime)
                            .put("originalSetting", original));
                } catch (PackageManager.NameNotFoundException ignored) {
                    AutomationEngine.OwnedFreeze owned = engine.ownedFreezes.get(target);
                    if (owned != null && owned.confirmed()) engine.reconcileOwnedFreeze(target, -1, "", -1);
                }
            }
            result.putString("records", records.toString()); return;
        }
        if ("GET_AUTO_RECOVERY".equals(operation)) {
            String pkg = input.getString("packageName"); SystemStateStore.validatePackage(pkg);
            int user = input.getInt("userId", -1);
            if (user != framework.currentUser()) throw new SecurityException("current user required");
            AutomationEngine.Target target = new AutomationEngine.Target(pkg, user);
            PackageInfo info = framework.packageInfo(target);
            if (info.applicationInfo.uid != input.getInt("uid", -1) || info.applicationInfo.uid / 100000 != user)
                throw new SecurityException("target UID changed");
            int setting = framework.setting(target);
            String digest = FrameworkAccess.installationIdentity(info);
            engine.reconcileOwnedFreeze(target, info.applicationInfo.uid, digest, setting);
            Integer original = setting == 3 ? engine.ownedBaseline(target, info.applicationInfo.uid, digest) : null;
            result.putBoolean("available", original != null);
            if (original != null) result.putInt("originalSetting", original);
            return;
        }
        if (Protocol.KEEP_SELF_ENABLED.equals(operation)) {
            int user = input.getInt("userId", -1);
            if (user != framework.currentUser()) throw new SecurityException("current user required");
            try { if (frozenUrls != null) frozenUrls.keepEnabled(); }
            finally { publishFrozenView(); }
            result.putString("message", "已取消本体自动回冻，目标应用规则保留"); return;
        }
        if (Protocol.SYNC_FROZEN_URLS.equals(operation)) {
            if (frozenUrls == null) throw new IllegalStateException(frozenUrlProblem);
            FrozenUrlSnapshot authority = FrozenUrlSnapshot.decode(input.getString("authority"));
            if (!framework.currentUserUnlocked(authority.userId)) throw new SecurityException("current unlocked user required");
            try { frozenUrls.sync(authority, framework.moduleBinding(authority.userId)); }
            finally { publishFrozenView(); }
            result.putString("digest", frozenUrls.digest()); result.putString("epoch", frozenUrls.epoch()); return;
        }
        if (Protocol.BEGIN_FROZEN_URL.equals(operation)) {
            String ticket = input.getString("ticket", "");
            if (ticket.isEmpty() && (frozenUrls == null || !frozenUrls.armed())) {
                result.putBoolean("proceed", true); result.putBoolean("managed", false); return;
            }
            if (frozenUrls == null || ticket.length() > 64 || !frozenUrls.begin(ticket, input.getString("actionDigest", "")))
                throw new SecurityException("invalid or duplicate frozen URL delivery");
            publishFrozenView(); result.putBoolean("proceed", true); result.putBoolean("managed", true); return;
        }
        if (Protocol.END_FROZEN_URL.equals(operation)) {
            if (frozenUrls == null) throw new IllegalStateException(frozenUrlProblem);
            String ticket = input.getString("ticket", "");
            if (ticket.length() > 64) throw new SecurityException("invalid frozen URL delivery");
            try { frozenUrls.end(ticket); } finally { publishFrozenView(); }
            result.putString("message", "已确认 URL 请求结束；全部请求完成后安排本体回冻"); return;
        }
        if ("SYNC_CONFIG".equals(operation)) {
            String text = input.getString("config");
            if (text == null || text.length() > 128 * 1024) throw new IllegalArgumentException("invalid config payload");
            result.putString("configToken", policyAuthority.sync(SystemStateStore.parsePolicy(new JSONObject(text))));
            result.putBoolean("configPersisted", true);
            diagnostic("config_synced managed=" + engine.policy.managed().size() + " tabs=" + engine.policy.tabModes().size()
                    + " sessions=" + engine.sessions.size());
            signalFollowScan("config");
            result.putLong("revision", engine.revision); return;
        }
        if ("SELF_FREEZE".equals(operation)) { freezeSelf(input, result); return; }
        if (("ACQUIRE".equals(operation) || "BEGIN_SESSION".equals(operation))
                && !policyAuthority.matches(input.getString("configToken"))) {
            result.putBoolean("configMismatch", true);
            throw new IllegalStateException("system configuration changed; sync the current configuration before acquiring a lease");
        }
        String pkg = input.getString("packageName"); SystemStateStore.validatePackage(pkg);
        int user = input.getInt("userId", -1); if (user < 0) throw new IllegalArgumentException("missing userId");
        AutomationEngine.Target target = new AutomationEngine.Target(pkg, user);
        String id = input.getString("requestId", "");
        if (id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("missing/invalid requestId");
        PackageInfo actual = framework.packageInfo(target);
        if ("FINISH".equals(operation) || ("ACQUIRE".equals(operation) && !input.getBoolean("freezeRequested", false)))
            framework.assertManualRestoreAllowed(target, actual);
        else framework.assertTargetAllowed(target, actual);
        if (actual.applicationInfo.uid != input.getInt("uid", -1)) throw new SecurityException("target UID changed");
        switch (operation == null ? "" : operation) {
            case "ACQUIRE", "BEGIN_SESSION" -> {
                boolean temporary = "BEGIN_SESSION".equals(operation);
                String sourceTab = input.getString("sourceTab", "");
                if (sourceTab == null) throw new IllegalArgumentException("invalid source tab");
                if (!sourceTab.isEmpty()) SystemStateStore.validateSourceTab(sourceTab);
                if (temporary) {
                    int mode = engine.policy.modeFor(pkg, sourceTab);
                    if ((mode & 1) != 0 && !lockSupported) throw new IllegalStateException("Keyguard event listener unavailable");
                    if ((mode & 4) != 0 && (!cardHookInstalled || !leaveSupported || !framework.recentsQuerySupported()))
                        throw new IllegalStateException("card removal hook, window events or complete recent-task query unavailable");
                    if (engine.policy.protectMedia() && !protectionsSupported) throw new IllegalStateException("media/call event protection unavailable");
                    if (engine.policy.protectRecents() && (!recentsHookInstalled || !framework.recentsQuerySupported()))
                        throw new IllegalStateException("最近任务保护不可用：" + framework.recentsProblem());
                    TargetPolicy.checkTemporaryState(framework.setting(target), input.getInt("enabledSetting", -1),
                            actual.applicationInfo.enabled);
                }
                if (!temporary && input.getBoolean("freezeRequested", false)) checkRecentFreeze(target);
                long lease = engine.acquire(target, id, actual.applicationInfo.uid, FrameworkAccess.installationIdentity(actual),
                        input.getInt("enabledSetting", -1), temporary, input.getBoolean("launchExpected", true), sourceTab);
                result.putLong("lease", lease);
            }
            case "CHECK_FREEZE" -> {
                engine.checkFreezeLease(target, id, input.getLong("lease", -1), actual.applicationInfo.uid,
                        FrameworkAccess.installationIdentity(actual));
                checkRecentFreeze(target);
            }
            case "FINISH" -> engine.finish(target, id, input.getLong("lease", -1), input.getBoolean("success"),
                    input.getBoolean("unknown"), input.getInt("enabledSetting", -1));
            case "ABORT_LAUNCH" -> engine.abortLaunch(target, id, input.getLong("lease", -1));
            default -> throw new IllegalArgumentException("unsupported operation");
        }
        result.putInt("enabledSetting", framework.setting(target));
    }
    private void checkRecentFreeze(AutomationEngine.Target target) throws Exception {
        if (!engine.policy.protectRecents()) return;
        if (!recentsHookInstalled) throw new IllegalStateException("最近任务保护不可用：事件 Hook 未完整安装，已拒绝冻结");
        try {
            if (framework.hasRecentTask(target)) throw new IllegalStateException("最近任务保护：请先从最近任务移除该应用，再冻结");
        } catch (Exception e) {
            throw new IllegalStateException("最近任务保护已拒绝冻结：" + FrameworkAccess.describe(e), e);
        }
    }
    private void freezeSelf(Bundle input, Bundle result) throws Exception {
        int user = input.getInt("userId", -1);
        if (frozenUrls == null) throw new IllegalStateException(frozenUrlProblem);
        framework.assertModuleFreezable(user); assertFrozenOwner(user);
        if (frozenUrls.authority() != null && frozenUrls.authority().enabled && !frozenUrlHooksInstalled)
            throw new IllegalStateException("URL入口未完整安装，启用URL时不能冻结本体；请更新并重启系统模块");
        try {
            frozenUrls.arm(user, input.getString("urlDigest", ""), input.getString("urlEpoch", ""),
                    input.getBoolean("configurationConfirmed"), input.getBoolean("recoveryConfirmed"));
        } finally { publishFrozenView(); }
        result.putBoolean("accepted", true); result.putString("selfFreezeState", frozenUrls.phase());
        result.putString("message", "已安排冻结本体；目标应用自动会话保留，请使用外部授权工具恢复本包");
    }
    private static String safeOperation(String operation) {
        return operation != null && Set.of("STATUS", "SYNC_CONFIG", "SYNC_FROZEN_URLS", "SELF_FREEZE", "KEEP_ENABLED",
                "BEGIN_FROZEN_URL", "END_FROZEN_URL", "ACQUIRE", "BEGIN_SESSION", "FINISH", "CHECK_FREEZE", "ABORT_LAUNCH",
                "GET_AUTO_RECOVERY", "GET_AUTO_RECOVERY_LIST").contains(operation)
                ? operation : "UNKNOWN";
    }
    @Override public void diagnostic(String message) { diagnostics.accept(message); }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public AutomationEngine.Observation observe(AutomationEngine.Session s) throws Exception {
        boolean known = lockSupported, locked = false;
        try { if (known) locked = keyguard.isKeyguardLocked(); } catch (RuntimeException e) { known = false; }
        AutomationEngine.Observation o = framework.observe(s, known, locked, engine.policy.protectMedia(), engine.policy.protectRecents());
        if ((engine.policy.protectMedia() && !protectionsSupported) || (engine.policy.protectRecents() && !recentsHookInstalled))
            return new AutomationEngine.Observation(o.identityMatches(), o.setting(), o.visibilityKnown(), o.visible(),
                    o.lockKnown(), o.locked(), false, false);
        return o;
    }
    @Override public void freeze(AutomationEngine.Session s) throws Exception { framework.freeze(s); }
    @Override public boolean hasRecentTask(AutomationEngine.Session s) throws Exception { return framework.hasRecentTask(s.target); }
    @Override public void persist(AutomationEngine engine) throws Exception {
        try {
            store.save(engine);
            statePersisted = true;
            hasActiveSessions = engine.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.ACTIVE);
        } catch (Exception e) { statePersisted = false; hasActiveSessions = false; throw e; }
    }
    @Override public void schedule(AutomationEngine.Target target, long delay, Runnable runnable) {
        cancel(target); timers.put(target, runnable); worker.postDelayed(runnable, delay);
    }
    @Override public void cancel(AutomationEngine.Target target) {
        Runnable pending = timers.remove(target); if (pending != null) worker.removeCallbacks(pending);
    }
}
