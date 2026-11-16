package io.github.yudigaga.lightfreeze.ui;

import androidx.appcompat.app.AppCompatActivity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import io.github.yudigaga.lightfreeze.R;
import io.github.yudigaga.lightfreeze.BuildConfig;
import io.github.yudigaga.lightfreeze.control.AppEntry;
import io.github.yudigaga.lightfreeze.control.AppRepository;
import io.github.yudigaga.lightfreeze.control.FreezerController;
import io.github.yudigaga.lightfreeze.control.OperationResult;
import io.github.yudigaga.lightfreeze.core.BridgeClient;
import io.github.yudigaga.lightfreeze.core.SettingsStore;
import io.github.yudigaga.lightfreeze.core.Protocol;
import io.github.yudigaga.lightfreeze.core.SelfFreezePreparation;
import io.github.yudigaga.lightfreeze.automation.AutomationLinks;
import io.github.yudigaga.lightfreeze.automation.FrozenUrlSnapshot;
import io.github.yudigaga.lightfreeze.tags.AppTabs;
import io.github.yudigaga.lightfreeze.tags.Tag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Foreground controller. The single Compose host owns presentation, never background work. */
public final class MainActivity extends AppCompatActivity {
    public static final int HOME = 1101, APPS = 1102, SETTINGS = 1103, ABOUT = 1104;
    private static final String[] BUILTIN_TABS = {AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE};
    private int destination = HOME;
    private String homeQuery = "", appsQuery = "", query = "";
    private boolean searchVisible, busy, settingsPage, tagsPage;
    private boolean mainClaimConfirmed, needsKeepConfirmation;
    private long mainClaimGeneration;
    private boolean thirdParty = true, official = true, frozenOnly, unfrozenOnly;
    private String backendStatus = "尚未检查操作权限", selectedTabId = AppTabs.MANAGED;
    private String emptyMessage = "正在读取应用…";
    private Bundle systemStatus = new Bundle();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> entries = new ArrayList<>();
    private List<AppEntry> visibleEntries = new ArrayList<>();
    private final Set<String> selected = new HashSet<>(), externallyRestricted = new HashSet<>(), unknownStates = new HashSet<>();
    private Set<String> managedSnapshot = new HashSet<>();
    private final Map<String, String> visibleTabs = new LinkedHashMap<>();
    private final Map<String, Drawable> icons = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) { return size() > 64; }
    };
    private final Set<String> loadingIcons = new HashSet<>();
    private final Map<String, List<java.lang.ref.WeakReference<ImageView>>> iconTargets = new LinkedHashMap<>();
    private SettingsStore settings;
    private AppRepository repository;
    private FreezerController controller;
    private TagUi tagUi;
    private HighRiskUi highRiskUi;
    private MiuixBridge bridge;
    private MiuixMain presentation;
    private final OnBackInvokedCallback backCallback = this::navigateBack;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == 4711 && !isDestroyed() && "shizuku".equals(settings.getBackend())) {
            backendStatus = grantResult == PackageManager.PERMISSION_GRANTED ? "已授权，待验证连接" : "授权被拒绝，请在 Shizuku 中检查授权";
            if (!busy) refresh();
        }
    };
    @Override public void onCreate(Bundle state) {

        super.onCreate(state);
        settings = new SettingsStore(this);
        repository = new AppRepository(this);
        controller = new FreezerController(this, settings);
        tagUi = new TagUi(this, settings);
        highRiskUi = new HighRiskUi(this, settings, repository, controller);
        synchronized (SettingsStore.class) {
            needsKeepConfirmation = settings.getSelfFreeze() || SelfFreezePreparation.requiresKeep();
            mainClaimGeneration = SettingsStore.beginMainClaim();
        }
        Shizuku.addRequestPermissionResultListener(permissionListener);
        if (state != null) {
            settingsPage = state.getBoolean("settingsPage");
            destination = state.getInt("destination", settingsPage ? SETTINGS : HOME);
            homeQuery = state.getString("homeQuery", state.getString("query", ""));
            appsQuery = state.getString("appsQuery", "");
            searchVisible = state.getBoolean("searchVisible", false);
            tagsPage = state.getBoolean("tagsPage");
            query = state.getString("query", "");
            selectedTabId = state.getString("selectedTabId");
            if (selectedTabId == null) {
                selectedTabId = state.getString("tagFilterId");
                if (selectedTabId == null) {
                    int legacyFilter = state.getInt("filter");
                    selectedTabId = legacyFilter >= 0 && legacyFilter < BUILTIN_TABS.length
                            ? BUILTIN_TABS[legacyFilter] : AppTabs.ALL;
                }
            }
            thirdParty = state.getBoolean("thirdParty", true);
            official = state.getBoolean("official", true);
            frozenOnly = state.getBoolean("frozenOnly", false);
            unfrozenOnly = state.getBoolean("unfrozenOnly", false);
            tagUi.restore(state.getString("tagDetailId"));
            tagUi.restoreExport(state.getString("exportDraftId"));
        }
        bridge = new MiuixBridge(this);
        presentation = new MiuixMain(this, tagUi, highRiskUi);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(bridge.view());
        query = destination == APPS ? appsQuery : homeQuery;
        if (tagsPage) tagUi.show(); else renderDestination();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        rememberQuery();
        state.putInt("destination", destination);
        state.putString("homeQuery", homeQuery);
        state.putString("appsQuery", appsQuery);
        state.putBoolean("searchVisible", searchVisible);
        state.putBoolean("settingsPage", settingsPage);
        state.putBoolean("tagsPage", tagsPage);
        state.putString("query", query);
        state.putString("selectedTabId", selectedTabId);
        state.putBoolean("thirdParty", thirdParty);
        state.putBoolean("official", official);
        state.putBoolean("frozenOnly", frozenOnly);
        state.putBoolean("unfrozenOnly", unfrozenOnly);
        state.putString("tagDetailId", tagUi.detailId());
        state.putString("exportDraftId", tagUi.exportDraftId());
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        // Local cleanup queues after preparation or an already-authorized provider write.
        // Do not route through the UI task admission guard during destruction.
        Runnable exportCleanup = tagUi == null ? null : tagUi.exportCleanupOnDestroy();
        if (exportCleanup != null) worker.execute(exportCleanup);
        worker.shutdown(); // Finish an already-authorized write and its verification before releasing the executor.
        super.onDestroy();
    }

    private boolean catalogVisible() { return !settingsPage && !tagsPage && (destination == HOME || destination == APPS); }
    private void rememberQuery() { if (destination == APPS) appsQuery = query; else if (destination == HOME) homeQuery = query; }
    private void navigate(int next) {
        rememberQuery(); hideKeyboard(); selected.clear();
        destination = next; settingsPage = next == SETTINGS; tagsPage = false;
        query = next == APPS ? appsQuery : homeQuery;
        searchVisible = !query.isEmpty(); renderDestination();
    }
    private void renderDestination() {
        if (destination == SETTINGS) showSettings();
        else if (destination == ABOUT) showAbout();
        else showApps();
    }
    private void updateBackHandler() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (settingsPage || tagsPage || destination != HOME || !selected.isEmpty() || searchVisible)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }
    private void navigateBack() {
        if (bridge != null && bridge.handleBack()) return;
        if (busy) return;
        if (tagsPage) { if (!tagUi.back()) navigate(destination); }
        else if (!selected.isEmpty()) { selected.clear(); updateBatchBar(); }
        else if (catalogVisible() && searchVisible) { query = ""; rememberQuery(); searchVisible = false; hideKeyboard(); applyFilter(); }
        else if (destination != HOME) navigate(HOME);
    }
    private void showApps() { settingsPage = false; tagsPage = false; applyFilter(); }
    private void showSettings() { destination = SETTINGS; settingsPage = true; tagsPage = false; presentation.settings(); updateBackHandler(); }
    private void showAbout() { destination = ABOUT; settingsPage = false; tagsPage = false; presentation.about(); updateBackHandler(); }
    private void updateBatchBar() { if (catalogVisible()) presentation.catalog(); updateBackHandler(); }

    private void applyFilter() {
        if (!catalogVisible()) return;
        List<Tag> tags;
        try {
            tags = settings.getTags();
            managedSnapshot = new HashSet<>(settings.getManagedPackages());
            for (Tag tag : tags) managedSnapshot.addAll(tag.packages);
        } catch (RuntimeException e) {
            visibleEntries = List.of();
            emptyMessage = "无法安全读取标签规则：" + error(e) + "\n当前未将损坏配置当作空配置使用。";
            presentation.catalog();
            notifyMessage("标签配置读取失败，请检查配置文件。", true); return;
        }
        if (destination == HOME) rebuildPrimaryTabs(tags);
        Tag selectedTag = null;
        for (Tag tag : tags) if (tag.id.equals(selectedTabId)) selectedTag = tag;
        List<AppEntry> result = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (AppEntry app : entries) {
            if (destination == HOME && selectedTag != null && !selectedTag.packages.contains(app.packageName)) continue;
            if (destination == HOME && AppTabs.isBuiltin(selectedTabId) && !AppTabs.matchesBuiltin(selectedTabId,
                    managedSnapshot.contains(app.packageName), app.frozen, available(app))) continue;
            if (!AppTabs.matchesSecondary(app.systemApp, app.frozen, thirdParty, official, frozenOnly, unfrozenOnly)) continue;
            if (!needle.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(needle)) continue;
            result.add(app);
        }
        visibleEntries = List.copyOf(result);
        emptyMessage = busy && entries.isEmpty() ? "正在读取系统应用状态…"
                : !thirdParty && !official ? "未选择应用来源\n请在筛选中勾选「三方」或「官方」。"
                : needle.isEmpty() && destination == HOME && AppTabs.MANAGED.equals(selectedTabId)
                ? "还没有匹配的已管理应用\n前往「应用」页，选择应用加入标签或纳入管理。"
                : needle.isEmpty() ? "此标签与筛选下暂无应用\n可切换「全部」或调整筛选。"
                : "没有匹配的应用\n试试应用名称、完整包名或调整筛选。";
        presentation.catalog(); updateBackHandler();
    }
    private void rebuildPrimaryTabs(List<Tag> tags) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String id : BUILTIN_TABS) titles.put(id, AppTabs.title(id));
        for (Tag tag : tags) titles.put(tag.id, tag.name);
        visibleTabs.clear(); Set<String> hidden = settings.getHiddenHomeTabs();
        for (String id : settings.getHomeTabOrder()) if (titles.containsKey(id) && !hidden.contains(id)) visibleTabs.put(id, titles.get(id));
        if (visibleTabs.isEmpty()) throw new IllegalStateException("首页至少需要一个可见标签");
        if (!visibleTabs.containsKey(selectedTabId)) { selectedTabId = visibleTabs.keySet().iterator().next(); selected.clear(); }
    }
    private void showCatalogMenu() { presentation.catalogMenu(); }
    private void chooseSecondaryFilter() { presentation.filters(); }
    private void showTabActions(String id) {
        selected.clear(); updateBatchBar();
        if (AppTabs.isBuiltin(id)) { tagUi.showBuiltinActions(id, () -> exportBuiltinTab(id)); return; }
        try {
            for (Tag tag : settings.getTags()) if (tag.id.equals(id)) { presentation.tabActions(id, tag.name); return; }
            applyFilter();
        } catch (RuntimeException e) { notifyMessage("无法读取标签：" + error(e), true); }
    }

    private void exportBuiltinTab(String id) {
        try {
            Set<String> managed = settings.getAllManagedPackages();
            Set<String> packages = new HashSet<>();
            for (AppEntry app : entries) {
                if (AppTabs.matchesBuiltin(id, managed.contains(app.packageName), app.frozen, available(app)))
                    packages.add(app.packageName);
            }
            tagUi.exportBuiltin(id, AppTabs.title(id), packages);
        } catch (RuntimeException e) { notifyMessage("无法导出应用快照：" + error(e), true); }
    }

    public int tagEnterPage(String title) {
        if (!tagsPage) { rememberQuery(); hideKeyboard(); }
        settingsPage = false; tagsPage = true; updateBackHandler(); return destination;
    }
    public boolean tagBusy() { return writesBlocked(); }
    private boolean writesBlocked() { return busy || !mainClaimConfirmed || SelfFreezePreparation.blocked(); }
    public Bundle tagSystemStatus() { return new Bundle(systemStatus); }
    public void tagTask(String message, TagUi.Work work) { runTask(message, work::run); }
    /** Document export is not a privileged configuration mutation. Queue behind active writes. */
    public void tagDocumentTask(String message, TagUi.Work work) {
        if (isDestroyed()) return;
        notifyMessage(message, false);
        worker.execute(() -> {
            try {
                Runnable completed = work.run();
                runOnUiThread(() -> { if (!isDestroyed()) completed.run(); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) notifyMessage("导出未完成：" + error(e), true); });
            }
        });
    }

    public boolean tagLayoutTask(String message, TagUi.Work work) {
        if (busy || isDestroyed()) return false;
        runTask(message, work::run, true); return busy;
    }
    public boolean tagTaskRunning() { return busy; }
    public void tagMessage(String message, boolean failure) { notifyMessage(message, failure); }
    public OperationResult tagSyncConfig() { return controller.syncConfig(); }
    public void tagRefresh() { refresh(); }
    public void tagRefresh(String message, boolean failure) { refresh(false, message, failure); }
    public void tagAssignmentsChanged() { selected.clear(); if (catalogVisible()) applyFilter(); }
    public void homeTabLayoutChanged() { if (catalogVisible()) applyFilter(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (tagUi != null) tagUi.onActivityResult(requestCode, resultCode, data);
    }

    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean requestRootCheck) {
        refresh(requestRootCheck, null, false);
    }

    private void refresh(boolean requestRootCheck, String completionMessage, boolean completionFailure) {
        runTask("正在读取权限和系统状态…", () -> {
            synchronized (SettingsStore.class) {
                if (!mainClaimConfirmed && !SelfFreezePreparation.current(mainClaimGeneration)) {
                    needsKeepConfirmation |= settings.getSelfFreeze() || SelfFreezePreparation.requiresKeep();
                    mainClaimGeneration = SettingsStore.beginMainClaim();
                }
            }
            Bundle actual = BridgeClient.request(this, Protocol.STATUS, new Bundle(), 2500);
            String claimProblem = "";
            if (!mainClaimConfirmed) {
                boolean confirmed = false;
                if (actual.getBoolean("ok") && actual.getInt("protocolVersion", 0) >= 5) {
                    Bundle input = new Bundle(); input.putInt("userId", android.os.Process.myUid() / 100000);
                    Bundle keep = BridgeClient.request(this, Protocol.KEEP_SELF_ENABLED, input, 5000);
                    confirmed = keep.getBoolean("ok");
                    if (!confirmed) claimProblem = keep.getString("message", "本体解冻状态未确认，请重试刷新");
                } else confirmed = actual.getBoolean("ok") || !needsKeepConfirmation;
                if (confirmed) {
                    mainClaimConfirmed = settings.confirmMainEnabled(mainClaimGeneration);
                    if (!mainClaimConfirmed) claimProblem = "本体准备已更新，请重新刷新并确认保持解冻。";
                } else if (claimProblem.isEmpty()) claimProblem = "本体解冻状态未确认；请检查系统模块后重试刷新，当前仅查看状态。";
            }
            List<AppEntry> loaded = repository.load(true);
            Restrictions restrictions = readRestrictions(loaded);
            boolean root = "root".equals(settings.getBackend());
            OperationResult backend = !root || requestRootCheck ? controller.checkBackend() : null;
            final Bundle actualStatus = actual;
            final String keepProblem = claimProblem;
            return () -> {
                entries.clear(); entries.addAll(loaded);
                applyRestrictions(restrictions);
                selected.retainAll(packageNames(loaded));
                if (backend != null) backendStatus = (root ? "上次检查：" : "") + backend.message;
                systemStatus = actualStatus;
                if (tagsPage) tagUi.show();
                else if (settingsPage) showSettings();
                else if (destination == ABOUT) showAbout();
                else { applyFilter(); updateBatchBar(); }
                if (!keepProblem.isEmpty()) notifyMessage(keepProblem, true);
                else if (completionMessage != null) notifyMessage(completionMessage, completionFailure);
            };
        }, true);
    }

    private void requestBackendPermission() {
        if ("shizuku".equals(settings.getBackend())) {
            try {
                if (!Shizuku.pingBinder()) {
                    notifyMessage("Shizuku 服务未运行。请先启动 Shizuku，再返回请求授权。", true);
                    return;
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(4711);
                    notifyMessage("已向 Shizuku 请求授权，请完成授权对话框。", false);
                    return;
                }
            } catch (RuntimeException e) {
                notifyMessage("无法请求 Shizuku 授权：" + error(e), true);
                return;
            }
        }
        refresh(true);
    }

    private void showDetail(AppEntry app) { hideKeyboard(); presentation.detail(app); }
    private void freeze(AppEntry app) {
        presentation.confirm("冻结“" + app.label + "”？", "应用会停止，正在进行的播放、通话、下载等任务可能中断。", "冻结",
                () -> perform("正在冻结应用…", app, () -> controller.freeze(app)));
    }
    private void freezeFromFab() {
        if (writesBlocked()) { notifyMessage("请先完成当前操作并确认本体解冻状态。", true); return; }
        List<AppEntry> targets = CatalogTargets.forFreeze(entries, visibleEntries, selected, managedSnapshot);
        if (targets.isEmpty()) { notifyMessage("当前结果中没有已管理应用。", false); return; }
        confirmBatch(true, targets);
    }
    private void batch(boolean freeze) {
        List<AppEntry> targets = new ArrayList<>();
        for (AppEntry app : entries) if (selected.contains(app.packageName)) targets.add(app);
        confirmBatch(freeze, targets);
    }
    private void confirmBatch(boolean freeze, List<AppEntry> chosen) {
        if (writesBlocked()) { notifyMessage("请先完成当前操作并确认本体解冻状态。", true); return; }
        final List<AppEntry> targets = List.copyOf(chosen);
        if (targets.isEmpty()) { notifyMessage("请先选择应用。", false); return; }
        presentation.confirm((freeze ? "冻结 " : "解冻 ") + targets.size() + " 个应用？",
                freeze ? "会停止所选应用及其任务，已冻结项直接跳过。每项独立核查并显示实际结果。"
                        : "解冻所选应用，各自采用所属标签或未分组默认规则。不会批量打开应用。",
                "执行", () -> runTask("正在处理所选应用…", () -> {
                    StringBuilder report = new StringBuilder();
                    List<OperationResult> results = controller.changeAll(targets, freeze, true);
                    for (int i = 0; i < targets.size(); i++) {
                        AppEntry app = targets.get(i); OperationResult result = results.get(i);
                        report.append(app.label).append(result.success ? "：成功 · " : "：失败 · ").append(result.message).append('\n');
                    }
                    Set<String> touched = packageNames(targets);
                    List<AppEntry> loaded = repository.loadPackages(touched);
                    Restrictions restrictions = readRestrictions(loaded);
                    return () -> {
                        selected.clear(); applyEntryUpdates(touched, loaded, restrictions);
                        presentation.info("批量操作结果", report.toString());
                    };
                }));
    }
    private void confirmRemoveFromManagement(List<AppEntry> targets) {
        if (writesBlocked()) { notifyMessage("请先完成当前操作并确认本体解冻状态。", true); return; }
        List<AppEntry> fixed = List.copyOf(targets);
        if (fixed.isEmpty()) { notifyMessage("所选应用没有已管理项。", false); return; }
        presentation.confirm("移出管理并解冻 " + fixed.size() + " 个应用？",
                "先解冻并确认系统状态，再移除这些应用的全部标签归属和管理关系，并撤销单应用链接。解冻失败项不会移出；状态未确认时保留恢复记录，请核对恢复清单。",
                "移出并解冻", () -> runTask("正在解冻并移出管理…", () -> {
                    OperationResult result = controller.removeFromManagement(fixed);
                    return () -> { selected.clear(); tagRefresh(result.message, !result.success); };
                }));
    }

    private void pinShortcut(AppEntry app) {
        if (writesBlocked()) { notifyMessage("请先确认本体解冻状态。", true); return; }
        if (!settings.getAllManagedPackages().contains(app.packageName)) {
            notifyMessage("请先将此应用纳入管理，再创建快捷方式。", true); return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            notifyMessage("当前桌面不支持添加固定快捷方式。", true); return;
        }
        Intent intent = new Intent(this, ShortcutActivity.class).setAction("io.github.yudigaga.lightfreeze.OPEN")
                .putExtra("packageName", app.packageName)
                .putExtra("token", settings.getOrCreateShortcutToken(app.packageName));
        ShortcutInfo info = new ShortcutInfo.Builder(this, "open:" + app.packageName)
                .setShortLabel(app.label).setLongLabel("解冻并打开“" + app.label + "”")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher)).setIntent(intent).build();
        try {
            boolean accepted = manager.requestPinShortcut(info, null);
            notifyMessage(accepted ? "已向桌面请求添加快捷方式，请在桌面确认。" : "桌面未接受快捷方式请求。", !accepted);
        } catch (RuntimeException e) { notifyMessage("无法请求快捷方式：" + error(e), true); }
    }

    private void exportRecovery() {
        runTask("正在读取恢复记录…", () -> { String recovery = controller.exportRecovery(); return () -> presentation.recovery(recovery); }, true);
    }

    private void confirmSelfFreeze() {
        if (!systemStatus.getBoolean("ready") || !systemStatus.getBoolean("selfFreezeSupported")) {
            notifyMessage("系统模块尚未报告可用的本体冻结能力，请检查框架与模块状态后刷新。", true); return;
        }
        int original = getPackageManager().getApplicationEnabledSetting(getPackageName());
        String recoveryCommand = "adb shell pm " + (original == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                ? "default-state" : "enable") + " --user " + android.os.Process.myUid() / 100000 + " " + getPackageName();
        presentation.confirmSelfFreeze(recoveryCommand, () -> runTask("正在提交配置与本体冻结请求…", () -> {
                    long preparation = SettingsStore.beginSelfFreezePreparation();
                    boolean retainBarrier = false;
                    try {
                        OperationResult synced = controller.syncConfig();
                        if (!synced.success) return () -> notifyMessage(synced.message, true);
                        SettingsStore.requirePreparation(preparation);
                        AutomationLinks links = new AutomationLinks(this, settings);
                        FrozenUrlSnapshot snapshot = links.frozenSnapshot();
                        if (snapshot.enabled && (!systemStatus.getBoolean("frozenUrlSupported")
                                || systemStatus.getInt("moduleVersionCode", 0) < BuildConfig.VERSION_CODE))
                            return () -> notifyMessage("本体 URL 系统入口尚未就绪；请更新模块并重启后再冻结本体。", true);
                        Bundle authority = new Bundle(); authority.putString("authority", snapshot.encode());
                        Bundle acknowledged = BridgeClient.request(this, Protocol.SYNC_FROZEN_URLS, authority, 5000);
                        if (!acknowledged.getBoolean("ok") || !snapshot.digest().equals(acknowledged.getString("digest")))
                            return () -> notifyMessage("系统 URL 授权快照未确认，未冻结本体；请更新模块后重试。", true);
                        settings.setPreparedSelfFreeze(preparation, true);
                        Bundle input = new Bundle(); input.putInt("userId", android.os.Process.myUid() / 100000);
                        input.putBoolean("recoveryConfirmed", true); input.putBoolean("configurationConfirmed", true);
                        input.putString("urlDigest", snapshot.digest()); input.putString("urlEpoch", acknowledged.getString("epoch", ""));
                        Bundle result = BridgeClient.request(this, Protocol.SELF_FREEZE, input, 5000);
                        boolean accepted = result.getBoolean("ok") && result.getBoolean("accepted");
                        boolean unknown = result.getBoolean("unavailable") || (result.getBoolean("ok") && !accepted);
                        retainBarrier = accepted || unknown;
                        if (!accepted && !unknown && SelfFreezePreparation.current(preparation))
                            settings.setPreparedSelfFreeze(preparation, false);
                        return () -> {
                            if (accepted) {
                                android.widget.Toast.makeText(this, "已安排冻结本体，保留系统规则与已授权 URL。", android.widget.Toast.LENGTH_LONG).show();
                                finish();
                            } else {
                                showSettings();
                                notifyMessage(unknown ? "本体冻结结果未确认。可点击保持本体解冻取消待执行任务，或使用外部工具核对。"
                                        : result.getString("message", "系统已拒绝本体冻结。"), true);
                            }
                        };
                    } finally { if (!retainBarrier) SettingsStore.finishPreparation(preparation); }
                }));
    }
    private void keepSelfEnabled() {
        long claim = SettingsStore.beginMainClaim();
        runTask("正在确认保持本体解冻…", () -> {
            Bundle input = new Bundle(); input.putInt("userId", android.os.Process.myUid() / 100000);
            Bundle result = BridgeClient.request(this, Protocol.KEEP_SELF_ENABLED, input, 5000);
            if (result.getBoolean("ok")) {
                mainClaimConfirmed = settings.confirmMainEnabled(claim);
                if (!mainClaimConfirmed) {
                    result.putBoolean("ok", false); result.putString("message", "已有新的本体冻结准备，请重新确认保持解冻。");
                }
            }
            return () -> { showSettings(); notifyMessage(result.getString("message", "本体状态未确认，请检查系统模块后刷新。"), !result.getBoolean("ok")); };
        }, true);
    }

    private void syncConfig() {
        runTask("正在保存并同步配置…", () -> {
            OperationResult result = controller.syncConfig();
            return () -> { if (tagsPage) tagUi.show(); else renderDestination(); notifyMessage(result.message, !result.success); };
        });
    }

    private interface Operation { OperationResult run() throws Exception; }
    private interface Work { Runnable run() throws Exception; }

    private void perform(String working, Operation operation) {
        runTask(working, () -> {
            OperationResult result = operation.run();
            List<AppEntry> loaded = repository.load(true);
            Restrictions restrictions = readRestrictions(loaded);
            return () -> {
                entries.clear(); entries.addAll(loaded);
                applyRestrictions(restrictions);
                if (catalogVisible()) { applyFilter(); updateBatchBar(); }
                notifyMessage(result.message, !result.success);
            };
        });
    }

    private void perform(String working, AppEntry app, Operation operation) {
        runTask(working, () -> {
            OperationResult result = operation.run();
            if (!result.success) android.util.Log.w("LightFreeze", app.packageName + " 未完成：" + result.message);
            Set<String> touched = Set.of(app.packageName);
            List<AppEntry> loaded = repository.loadPackages(touched);
            Restrictions restrictions = readRestrictions(loaded);
            return () -> {
                applyEntryUpdates(touched, loaded, restrictions);
                notifyMessage(result.message, !result.success);
            };
        });
    }

    private void applyEntryUpdates(Set<String> touched, List<AppEntry> loaded, Restrictions restrictions) {
        Map<String, AppEntry> updated = new LinkedHashMap<>();
        for (AppEntry app : loaded) updated.put(app.packageName, app);
        for (int i = entries.size() - 1; i >= 0; i--) {
            AppEntry old = entries.get(i);
            if (!touched.contains(old.packageName)) continue;
            AppEntry replacement = updated.remove(old.packageName);
            if (replacement == null) entries.remove(i); else entries.set(i, replacement);
        }
        entries.addAll(updated.values());
        externallyRestricted.removeAll(touched); externallyRestricted.addAll(restrictions.restricted);
        unknownStates.removeAll(touched); unknownStates.addAll(restrictions.unknown);
        if (catalogVisible()) { applyFilter(); updateBatchBar(); }
    }

    private void runTask(String working, Work work) { runTask(working, work, false); }
    private void runTask(String working, Work work, boolean allowPreparation) {
        if (busy || isDestroyed()) return;
        if (!allowPreparation && (!mainClaimConfirmed || SelfFreezePreparation.blocked())) {
            notifyMessage("请先刷新并确认本体解冻状态，再继续操作。", true); return;
        }
        busy = true; bridge.setBusy(true); notifyMessage(working, false);
        worker.execute(() -> {
            try {
                Runnable completed = work.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; bridge.setBusy(false); bridge.setMessage(""); completed.run();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; bridge.setBusy(false); notifyMessage("操作未完成：" + error(e), true);
                    if (!tagsPage) renderDestination();
                });
            }
        });
    }
    private void notifyMessage(String message, boolean failure) { if (bridge != null) bridge.setMessage(message, failure); }

    private String ownerRuleName(String pkg) {
        for (Tag tag : settings.getTags()) if (tag.packages.contains(pkg)) return tag.name;
        return "未分组默认规则";
    }
    private boolean available(AppEntry app) {
        return !externallyRestricted.contains(app.packageName) && !unknownStates.contains(app.packageName)
                && (app.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || app.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }
    private String stateLabel(AppEntry app) {
        String state = unknownStates.contains(app.packageName) ? "状态待核对"
                : externallyRestricted.contains(app.packageName)
                ? "外部限制" + (app.frozen ? " · 已冻结" : "")
                : app.frozen ? "已冻结" : available(app) ? "可用" : "外部限制";
        return state + (managedSnapshot.contains(app.packageName) ? " · 已管理" : "")
                + (app.systemApp ? " · 系统" : "");
    }
    private static Set<String> packageNames(List<AppEntry> items) {
        Set<String> names = new HashSet<>(); for (AppEntry app : items) names.add(app.packageName); return names;
    }
    private Restrictions readRestrictions(List<AppEntry> items) {
        Restrictions result = new Restrictions();
        PackageManager pm = getPackageManager();
        for (AppEntry app : items) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(app.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
                if ((!app.frozen && !info.enabled) || (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0)
                    result.restricted.add(app.packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                result.unknown.add(app.packageName);
            }
        }
        return result;
    }
    private void applyRestrictions(Restrictions result) {
        externallyRestricted.clear(); externallyRestricted.addAll(result.restricted);
        unknownStates.clear(); unknownStates.addAll(result.unknown);
    }
    private static final class Restrictions {
        final Set<String> restricted = new HashSet<>(), unknown = new HashSet<>();
    }
    private static String error(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    public void tagLoadIcon(String pkg, ImageView target) {
        target.setTag(pkg);
        Drawable cached = icons.get(pkg);
        if (cached != null) { target.setImageDrawable(cached); return; }
        target.setImageResource(R.drawable.ic_launcher);
        iconTargets.computeIfAbsent(pkg, key -> new ArrayList<>()).add(new java.lang.ref.WeakReference<>(target));
        if (!loadingIcons.add(pkg)) return;
        worker.execute(() -> {
            Drawable drawable;
            try { drawable = getPackageManager().getApplicationIcon(pkg); }
            catch (PackageManager.NameNotFoundException | RuntimeException e) { drawable = getDrawable(R.drawable.ic_launcher); }
            Drawable loaded = drawable;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                loadingIcons.remove(pkg); icons.put(pkg, loaded);
                List<java.lang.ref.WeakReference<ImageView>> pending = iconTargets.remove(pkg);
                if (pending != null) for (java.lang.ref.WeakReference<ImageView> reference : pending) {
                    ImageView view = reference.get();
                    if (view != null && pkg.equals(view.getTag())) view.setImageDrawable(loaded);
                }
            });
        });
    }
    public MiuixBridge miuix() { return bridge; }
    public SettingsStore uiSettings() { return settings; }
    public List<AppEntry> uiEntries() { return List.copyOf(entries); }
    public List<AppEntry> uiVisibleEntries() { return visibleEntries; }
    public Set<String> uiSelected() { return Set.copyOf(selected); }
    public Map<String, String> uiTabs() { return new LinkedHashMap<>(visibleTabs); }
    public int uiDestination() { return destination; }
    public String uiQuery() { return query; }
    public String uiSelectedTab() { return selectedTabId; }
    public String uiEmptyMessage() { return emptyMessage; }
    public boolean uiSearchVisible() { return searchVisible; }
    public boolean uiBusy() { return busy; }
    public boolean uiWritesAllowed() { return !writesBlocked(); }
    public Bundle uiSystemStatus() { return new Bundle(systemStatus); }
    public String uiBackendStatus() { return backendStatus; }
    public boolean[] uiFilterFlags() { return new boolean[]{thirdParty, official, frozenOnly, unfrozenOnly}; }
    public String uiFilterLabel() {
        String source = thirdParty && official ? "三方、官方" : thirdParty ? "三方" : official ? "官方" : "未选来源";
        return source + (frozenOnly == unfrozenOnly ? "" : frozenOnly ? " · 已冻结" : " · 未冻结");
    }
    public String uiStateLabel(AppEntry app) { return stateLabel(app); }
    public String uiRuleLabel(AppEntry app) { return ownerRuleName(app.packageName) + " · " + io.github.yudigaga.lightfreeze.core.FreezeTriggers.describe(settings.getEffectiveMode(app.packageName)); }
    public boolean uiManaged(AppEntry app) { return settings.getAllManagedPackages().contains(app.packageName); }
    public boolean uiAvailable(AppEntry app) { return available(app); }
    public void uiNavigate(int next) { if (!busy) navigate(next); }
    public void uiBack() { navigateBack(); }
    public void uiToggleSearch() { searchVisible = !searchVisible; if (!searchVisible) { query = ""; rememberQuery(); hideKeyboard(); } applyFilter(); }
    public void uiSearch(String value) { query = value; rememberQuery(); applyFilter(); }
    public void uiSetFilters(boolean[] values) {
        thirdParty = values[0]; official = values[1]; frozenOnly = values[2]; unfrozenOnly = values[3]; selected.clear(); applyFilter();
    }
    public void uiSelectTab(String id) { if (busy) return; selectedTabId = id; selected.clear(); applyFilter(); }
    public void uiAppClick(AppEntry app) { if (!busy) { if (selected.isEmpty()) showDetail(app); else uiToggleSelected(app); } }
    public void uiToggleSelected(AppEntry app) { if (!busy) { if (!selected.add(app.packageName)) selected.remove(app.packageName); updateBatchBar(); } }
    public void uiSelectAll() { if (!busy) { selected.clear(); selected.addAll(packageNames(visibleEntries)); updateBatchBar(); } }
    public void uiCancelSelection() { if (!busy) { selected.clear(); updateBatchBar(); } }
    public void uiAssignSelection() { if (!busy) tagUi.addToTag(Set.copyOf(selected)); }
    public void uiRemoveSelection() {
        if (busy) return;
        List<AppEntry> targets = new ArrayList<>(); Set<String> managed = settings.getAllManagedPackages();
        for (AppEntry app : entries) if (selected.contains(app.packageName) && managed.contains(app.packageName)) targets.add(app);
        confirmRemoveFromManagement(targets);
    }
    public void uiBatch(boolean freezing) { batch(freezing); }
    public void uiFreezeVisible() { freezeFromFab(); }
    public void uiCatalogMenu() { showCatalogMenu(); }
    public void uiFilters() { chooseSecondaryFilter(); }
    public void uiTabActions(String id) { showTabActions(id); }
    public void uiTags() { if (!busy) tagUi.open(); }
    public void uiNewTag() { tagUi.createTag(); }
    public void uiLayout() { tagUi.showHomeTabLayout(); }
    public void uiImport() { tagUi.requestImport(); }
    public void uiRefresh() { if (!busy) refresh(); }
    public void uiBackendDialog() { if (!writesBlocked()) presentation.backend(); else notifyMessage("请先完成当前操作并确认本体解冻状态。", true); }
    public void uiChooseBackend(int which) {
        if (writesBlocked()) return;
        try { settings.setBackend(which == 1 ? "root" : "shizuku"); backendStatus = "尚未检查操作权限"; showSettings(); }
        catch (RuntimeException e) { notifyMessage("未保存操作权限：" + error(e), true); }
    }
    public void uiRequestPermission() { if (!busy) requestBackendPermission(); }
    public void uiChooseLockDelay(long delay) {
        if (writesBlocked()) return;
        try { settings.setLockDelayMs(delay); showSettings(); syncConfig(); }
        catch (RuntimeException e) { showSettings(); notifyMessage("未保存回冻延迟：" + error(e), true); }
    }
    public void uiSetProtectMedia(boolean enabled) {
        if (writesBlocked() || enabled == settings.getProtectMedia()) return;
        try { settings.setProtectMedia(enabled); showSettings(); syncConfig(); }
        catch (RuntimeException e) { showSettings(); notifyMessage("未保存媒体保护：" + error(e), true); }
    }
    public void uiSetProtectRecents(boolean enabled) {
        if (writesBlocked() || enabled == settings.getProtectRecents()) return;
        try { settings.setProtectRecents(enabled); showSettings(); syncConfig(); }
        catch (RuntimeException e) { showSettings(); notifyMessage("未保存最近任务保护：" + error(e), true); }
    }
    public void uiSelfFreeze(boolean enabled) { if (!writesBlocked()) { if (enabled) confirmSelfFreeze(); else keepSelfEnabled(); } }
    public void uiKeepEnabled() { if (!busy) keepSelfEnabled(); }
    public void uiRestoreAll() {
        presentation.confirm("恢复全部应用？", "保留原始 DEFAULT / ENABLED 设置，包含系统自动冻结记录。需要持续解冻时请先取消对应标签回冻条件。", "恢复",
                () -> perform("正在恢复应用…", controller::restoreAll));
    }
    public void uiExportRecovery() { exportRecovery(); }
    public void uiCopy(String label, String value) { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value)); notifyMessage("已复制“" + label + "”。", false); }
    public void uiShareRecovery(String recovery) {
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "轻冻恢复清单").putExtra(Intent.EXTRA_TEXT, recovery), "导出恢复清单")); }
        catch (RuntimeException e) { notifyMessage("无法打开分享页面：" + error(e), true); }
    }
    public void uiOpenRepository() {
        String url = "https://github.com/yudigaga/LightFreeze";
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (RuntimeException e) { presentation.repository(url); }
    }
    public void uiOpenReference() {
        String url = "https://github.com/compose-miuix-ui/miuix";
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (RuntimeException e) { presentation.reference(url); }
    }
    public void uiManage(AppEntry app) {
        runTask("正在纳入管理并同步规则…", () -> {
            Set<String> packages = new HashSet<>(settings.getManagedPackages()); packages.add(app.packageName); settings.setManagedPackages(packages);
            OperationResult result = controller.syncConfig();
            return () -> tagRefresh("已纳入管理。" + (result.success ? "" : "\n系统同步未完成：" + result.message), !result.success);
        });
    }
    public void uiUnfreeze(AppEntry app) { String tab = settings.getDefaultTab(app.packageName); perform("正在解冻应用…", app, () -> controller.unfreezeByRule(app, tab)); }
    public void uiFreeze(AppEntry app) { freeze(app); }
    public void uiOpen(AppEntry app) { String tab = settings.getDefaultTab(app.packageName); perform("正在打开应用…", app, () -> controller.open(app, tab)); }
    public void uiAssign(AppEntry app) { if (!busy) tagUi.addToTag(Set.of(app.packageName)); }
    public void uiRemove(AppEntry app) { confirmRemoveFromManagement(List.of(app)); }
    public void uiPin(AppEntry app) { pinShortcut(app); }
    private void hideKeyboard() {
        if (getWindow().getInsetsController() != null) getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        if (getCurrentFocus() != null) getCurrentFocus().clearFocus();
    }
}
