package io.github.yudigaga.lightfreeze.control;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import io.github.yudigaga.lightfreeze.core.BridgeClient;
import io.github.yudigaga.lightfreeze.core.SettingsStore;
import io.github.yudigaga.lightfreeze.core.HighRiskPolicy;
import io.github.yudigaga.lightfreeze.tags.AppTabs;
import io.github.yudigaga.lightfreeze.tags.Tag;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FreezerController {
    private static final Object OPERATIONS = new Object();
    private final Context context;
    private final SettingsStore settings;
    private final PackageManager pm;
    private final RecoveryStore recovery;
    private final ProtectedPackages protectedPackages;
    private final SharedPreferences coordination;
    private static final long BRIDGE_TIMEOUT = 5000;
    private Batch batch;
    static final class Batch {
        Bundle status;
        Bundle syncedReply;
        String syncedConfig, configToken;
        FreezeBackend backend;
        boolean needsSync(String config, boolean tokenSupported) {
            return !tokenSupported || configToken == null || configToken.isEmpty() || !config.equals(syncedConfig);
        }
        void confirmSync(String config, boolean acknowledged, String token) {
            syncedConfig = acknowledged ? config : null; configToken = acknowledged ? token : null;
        }
        void invalidate() { status = null; syncedConfig = null; configToken = null; syncedReply = null; }
    }

    public FreezerController(Context context, SettingsStore settings) {
        this.context = context.getApplicationContext(); this.settings = settings;
        pm = context.getPackageManager(); recovery = new RecoveryStore(this.context);
        protectedPackages = new ProtectedPackages(this.context, settings);
        coordination = this.context.getSharedPreferences("coordination", Context.MODE_PRIVATE);
    }

    public OperationResult checkBackend() {
        worker();
        try { FreezeBackend backend = backend(); backend.check(); return ok(backend.name() + " 已授权"); }
        catch (Exception e) { return failed(message(e)); }
    }
    /** Raw protection reason for the settings picker, independent of the current grant. */
    public String highRiskReason(AppEntry app) {
        worker();
        if (app == null) throw new IllegalArgumentException("请选择应用");
        if (app.packageName.equals(context.getPackageName())) return "本工具只能通过专用的本体冻结设置操作";
        if (app.userId != currentUser() || app.uid < 0 || app.uid / 100000 != app.userId)
            return "应用用户或 UID 无法确认";
        if (app.uid % 100000 < 10000) return "系统核心或共享 UID（" + app.uid + "），冻结可能影响同 UID 的其他系统功能";
        return protectedPackages.riskReason(app.packageName);
    }

    public OperationResult freeze(AppEntry app) {
        return freeze(app, null);
    }
    public OperationResult freeze(AppEntry app, String sourceTab) {
        synchronized (OPERATIONS) {
            return change(app, true, false, UUID.randomUUID().toString(), null, true, sourceTab);
        }
    }

    public OperationResult unfreeze(AppEntry app, boolean temporary) {
        synchronized (OPERATIONS) {
            return change(app, false, temporary, UUID.randomUUID().toString(), null, true, null);
        }
    }
    /** Cached bridge capabilities and backend live only for this explicit batch request. */
    public List<OperationResult> changeAll(List<AppEntry> apps, boolean freeze, boolean byRule) {
        return changeAll(apps, freeze, byRule, null);
    }
    public List<OperationResult> changeAll(List<AppEntry> apps, boolean freeze, boolean byRule, String sourceTab) {
        worker();
        synchronized (OPERATIONS) {
            batch = new Batch();
            try {
                prepareManaged(apps, freeze, byRule, sourceTab);
                List<OperationResult> results = new ArrayList<>();
                for (AppEntry app : apps) results.add(freeze ? freeze(app, sourceTab)
                        : byRule ? unfreezeByRule(app, sourceTab) : unfreeze(app, false));
                return results;
            } finally { batch = null; }
        }
    }
    public OperationResult removeFromManagement(List<AppEntry> apps) {
        worker();
        synchronized (OPERATIONS) {
            batch = new Batch();
            try {
                if (apps == null || apps.isEmpty()) return failed("请选择已管理应用");
                List<AppEntry> targets = new ArrayList<>(apps);
                Bundle status = bridgeStatus();
                if (status.getBoolean("ok") && status.getBoolean("ready")
                        && !status.getBoolean("configPersistedAckSupported"))
                    return failed("移出管理需更新系统模块后重启，未执行解冻或修改分组");
                Set<String> seen = new HashSet<>(); int removed = 0, failed = 0;
                StringBuilder detail = new StringBuilder();
                for (AppEntry app : targets) {
                    if (app == null) { failed++; detail.append("无效应用：未处理\n"); continue; }
                    if (!seen.add(app.userId + ":" + app.packageName)) continue;
                    OperationResult result;
                    if (!settings.getAllManagedPackages().contains(app.packageName))
                        result = failed("应用已不在管理集合中，未修改系统状态");
                    else result = change(app, false, false, UUID.randomUUID().toString(), null,
                            false, null, true);
                    if (result.success) removed++; else failed++;
                    detail.append(app.label).append(result.success ? "：已移出 · " : "：未完成 · ")
                            .append(result.message).append('\n');
                }
                return new OperationResult(failed == 0, "已移出并解冻 " + removed + " 个，未完成 " + failed + " 个\n" + detail);
            } catch (RuntimeException e) { return failed(message(e)); }
            finally { batch = null; }
        }
    }
    private void prepareManaged(List<AppEntry> apps, boolean freeze, boolean byRule, String sourceTab) {
        Set<String> managed = settings.getManagedPackages();
        boolean changed = false;
        for (AppEntry app : apps) try {
            int state = pm.getApplicationEnabledSetting(app.packageName);
            if (freeze && AppEntry.isDisabledSetting(state)) continue;
            boolean temporary = !freeze && byRule && modeFor(app, sourceTab) != 0;
            if (!freeze && !temporary) continue;
            Identity current = identity(app);
            RecoveryStore.Record record = recovery.get(app.packageName, app.userId);
            if (record != null && (record.pending || !matches(record, current))) continue;
            if (isSuspended(current.info) || restrictionReason(app.packageName, current.info.uid) != null) continue;
            if (freeze && (!ControlRules.canTakeOwnership(state) || !current.info.enabled)) continue;
            if (temporary && !AppEntry.isDisabledSetting(state)
                    && !(ControlRules.canTakeOwnership(state) && current.info.enabled)) continue;
            changed |= managed.add(app.packageName);
        } catch (Exception ignored) { /* Individual operations report their validation failures. */ }
        if (changed) try { settings.setManagedPackages(managed); }
        catch (RuntimeException ignored) { /* Preserve per-item limits and partial-result reporting. */ }
    }
    public OperationResult unfreezeByRule(AppEntry app) {
        return unfreezeByRule(app, null);
    }
    public OperationResult unfreezeByRule(AppEntry app, String sourceTab) {
        worker();
        synchronized (OPERATIONS) {
            try {
                if (app == null) return failed("请选择应用");
                String source = sourceFor(app, sourceTab);
                int mode = modeFor(app, source);
                return change(app, false, mode != 0,
                        UUID.randomUUID().toString(), null, false, source);
            } catch (Exception e) { return failed(message(e)); }
        }
    }

    public OperationResult open(AppEntry app) {
        return open(app, null);
    }
    public OperationResult open(AppEntry app, String sourceTab) {
        worker();
        synchronized (OPERATIONS) {
            String requestId = UUID.randomUUID().toString();
            Bundle launchReceipt = new Bundle();
            boolean temporary = false;
            try {
                Identity identity = identity(app);
                int state = pm.getApplicationEnabledSetting(app.packageName);
                boolean disabled = AppEntry.isDisabledSetting(state);
                if (!disabled && (!ControlRules.canTakeOwnership(state) || !identity.info.enabled || isSuspended(identity.info))) {
                    return failed("应用受到外部限制，未改变系统状态");
                }
                String source = sourceFor(app, sourceTab);
                int mode = modeFor(app, source);
                if (restrictionReason(app.packageName, identity.info.uid) != null) mode = 0;
                Intent launch = null;
                if (!disabled) {
                    RecoveryStore.Record record = recovery.get(app.packageName, app.userId);
                    if (record != null && (record.pending || !matches(record, identity)))
                        return failed("已有恢复操作待核对或安装身份变化，未提交启动");
                    launch = pm.getLaunchIntentForPackage(app.packageName);
                    if (launch == null) return failed("此应用没有可用的启动入口");
                    if (mode != 0) {
                        // Do not arm a new timer until an already-available app's launch is accepted.
                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        OperationResult linked = change(app, false, true, requestId, launchReceipt, false, source);
                        return new OperationResult(linked.success, linked.success
                                ? launchReceipt.containsKey("lease") ? "已提交应用启动请求，并采用本页回冻规则" : "已提交应用启动请求；未建立自动回冻会话"
                                : "已提交应用启动请求，但回冻规则未确认：" + linked.message);
                    }
                }
                OperationResult result = change(app, false, mode != 0, requestId, launchReceipt, true, source);
                if (!result.success) return result;
                temporary = launchReceipt.containsKey("lease");
                if (launch == null) launch = pm.getLaunchIntentForPackage(app.packageName);
                if (launch == null) throw new IllegalStateException("此应用没有可用的启动入口");
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ok(temporary ? "已临时解冻并提交启动请求，系统会确认窗口可见性" : "已提交应用启动请求");
            } catch (Exception e) {
                if (temporary) {
                    Bundle abort = target(app, requestId);
                    abort.putLong("lease", launchReceipt.getLong("lease", -1));
                    Bundle reply = BridgeClient.request(context, "ABORT_LAUNCH", abort, BRIDGE_TIMEOUT);
                    return failed(message(e) + "；启动失败收尾：" + reply.getString("message", reply.getBoolean("ok") ? "系统已接收" : "结果待核对"));
                }
                return failed(message(e));
            }
        }
    }

    public OperationResult restoreAll() {
        worker();
        synchronized (OPERATIONS) {
            List<String> failures = new ArrayList<>();
            int restored = 0;
            try {
                failures.addAll(importSystemRecovery());
                for (RecoveryStore.Record record : recovery.all()) {
                    try {
                        if (!record.hasRestoreBaseline()) { failures.add(record.packageName + "：外部直接启用请求待核对，不是本工具原始恢复记录"); continue; }
                        if (record.userId != currentUser()) { failures.add(record.packageName + "：非当前用户，已跳过"); continue; }
                        Identity current = identity(record.packageName, record.userId);
                        if (!matches(record, current)) { failures.add(record.packageName + "：安装身份变化，已跳过"); continue; }
                        AppEntry entry = new AppEntry(record.packageName, record.packageName, record.userId, current.info.uid,
                                pm.getApplicationEnabledSetting(record.packageName), false, true);
                        OperationResult result = change(entry, false, false, UUID.randomUUID().toString(), null);
                        if (result.success) restored++; else failures.add(record.packageName + "：" + result.message);
                    } catch (Exception e) { failures.add(record.packageName + "：" + message(e)); }
                }
            } catch (Exception e) { return failed(message(e)); }
            return new OperationResult(failures.isEmpty(), "已恢复 " + restored + " 个应用" + (failures.isEmpty() ? "" : "\n" + String.join("\n", failures)));
        }
    }

    public OperationResult syncConfig() {
        worker();
        synchronized (OPERATIONS) {
            try {
                Bundle status = bridgeStatus();
                if (!status.getBoolean("ok") || !status.getBoolean("ready"))
                    return failed("系统模块未就绪，规则未同步：" + status.getString("message", "未响应"));
                boolean paused = recentsUnsupported(status);
                boolean pagesPaused = !status.getBoolean("tabSessionsSupported") && wantsTabAutomation();
                Bundle reply = syncFor(status);
                if (reply.getBoolean("ok")) rememberBridge();
                if (reply.getBoolean("ok") && paused)
                    return failed("系统模块需更新并重启；已暂停旧自动规则，避免绕过最近任务保护");
                if (reply.getBoolean("ok") && !settings.getHighRiskAllowed().isEmpty() && !status.getBoolean("highRiskAllowlistSupported"))
                    return failed("系统模块需更新并重启才能同步高危放行；已暂停旧自动规则");
                if (reply.getBoolean("ok") && wantsTabAutomation() && !status.getBoolean("tagFollowSupported"))
                    return failed("系统模块需更新并重启；已暂停旧自动规则，重启后标签成员将自动跟随规则");
                if (reply.getBoolean("ok") && wantsTabAutomation() && !status.getBoolean("ownerRulesSupported"))
                    return failed("系统模块需更新并重启；已暂停旧自动规则，重启后将统一按所属标签回冻");
                if (reply.getBoolean("ok") && wantsMultiTrigger() && !status.getBoolean("multiTriggerSupported"))
                    return failed("系统模块需更新并重启；已暂停不兼容的组合与划卡回冻规则");
                if (reply.getBoolean("ok") && pagesPaused)
                    return failed("系统模块需更新并重启才能按标签页回冻；已暂停旧自动规则，手动冻结与解冻仍可用");
                return new OperationResult(reply.getBoolean("ok"), reply.getString("message", reply.getBoolean("ok") ? "系统规则已提交" : "系统规则未确认"));
            } catch (RuntimeException e) { return failed(message(e)); }
        }
    }

    public String exportRecovery() {
        synchronized (OPERATIONS) {
            StringBuilder result = new StringBuilder("轻冻恢复清单\n仅针对清单中的原安装与用户。卸载模块不会自动解冻。\n");
            try {
                for (String skipped : importSystemRecovery()) result.append("\n系统恢复项未导入：").append(skipped).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("无法读取完整系统恢复清单：" + message(e), e);
            }
            for (RecoveryStore.Record record : recovery.all()) {
                result.append("\n包名：").append(record.packageName).append("\nuserId：").append(record.userId)
                        .append("\nuid：").append(record.uid)
                        .append(record.hasRestoreBaseline() ? "\n原始 enabled setting：" : "\n待确认的直接启用目标（不是外部原始状态）：").append(record.originalSetting)
                        .append("\n签名 SHA-256：").append(record.signature).append("\n首次安装时间：").append(record.installedAt)
                        .append("\n操作待核对：").append(record.pending).append("\nADB（授权 shell）：adb shell ")
                        .append(ControlRules.command(record.packageName, record.userId, record.originalSetting)).append('\n');
            }
            return result.toString();
        }
    }

    private OperationResult change(AppEntry app, boolean freeze, boolean temporary, String requestId, Bundle launchReceipt) {
        return change(app, freeze, temporary, requestId, launchReceipt, true, "");
    }
    private OperationResult change(AppEntry app, boolean freeze, boolean temporary, String requestId,
                                   Bundle launchReceipt, boolean launchExpected, String sourceTab) {
        return change(app, freeze, temporary, requestId, launchReceipt, launchExpected, sourceTab, false);
    }
    private OperationResult change(AppEntry app, boolean freeze, boolean temporary, String requestId,
                                   Bundle launchReceipt, boolean launchExpected, String sourceTab, boolean removeAfterThaw) {
        worker();
        Bundle lease = null;
        RecoveryStore.Record previous = null;
        RecoveryStore.Record active = null;
        boolean directEnable = false;
        boolean writeStarted = false;
        boolean bridgeFinished = false;
        try {
            if (app == null) throw new IllegalArgumentException("请选择应用");
            if (removeAfterThaw && (freeze || temporary)) throw new IllegalArgumentException("移出管理只能采用持续解冻流程");
            ControlRules.validate(app.packageName, app.userId);
            if (app.userId != currentUser()) throw new IllegalArgumentException("首版仅允许操作当前用户");
            if (removeAfterThaw) settings.validateManagedRemoval(Set.of(app.packageName));
            previous = recovery.get(app.packageName, app.userId);
            if (freeze) {
                int live = pm.getApplicationEnabledSetting(app.packageName);
                if (ControlRules.freezeAlreadySatisfied(live, previous != null && previous.pending)) {
                    ApplicationInfo installed = pm.getApplicationInfo(app.packageName,
                            PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
                    if (installed.uid != app.uid || installed.uid / 100000 != app.userId)
                        return failed("应用身份变化，请刷新列表");
                    if (previous != null && !matches(previous, identity(app)))
                        return failed("应用已重新安装或身份变化，旧恢复记录不适用于当前安装");
                    int confirmed = pm.getApplicationEnabledSetting(app.packageName);
                    if (AppEntry.isDisabledSetting(confirmed))
                        return ok("已冻结，已跳过重复操作（PM " + confirmed + "）");
                }
            }
            Identity initial = identity(app);
            boolean recoveryOnly = !freeze && temporary && restrictionReason(app.packageName, initial.info.uid) != null;
            if (recoveryOnly) temporary = false;
            if (freeze || temporary) {
                String reason = restrictionReason(app.packageName, initial.info.uid);
                if (reason != null) return failed(reason);
            }
            if (previous != null && !matches(previous, initial)) return failed("应用已重新安装或身份变化，旧恢复记录不适用于当前安装");
            if (previous != null && previous.pending) return failed("此前操作结果待核对，已阻止冲突写入；请导出恢复清单，通过外部工具核对");
            int before = pm.getApplicationEnabledSetting(app.packageName);
            if (!freeze && before == 3) {
                RecoveryStore.Record fromSystem = importSystemRecovery(app, initial, before);
                if (fromSystem != null) previous = fromSystem;
            }
            if (isSuspended(initial.info)) return failed("应用已被外部挂起，未接管或解除外部限制");
            if (freeze) {
                if (AppEntry.isDisabledSetting(before)) return ok("应用已冻结，保留当前禁用状态");
                if (!ControlRules.canTakeOwnership(before) || !initial.info.enabled) return failed("应用不可用且启用状态未知，未冻结");
            }
            directEnable = !freeze && AppEntry.isDisabledSetting(before) && (previous == null || before != 3);
            String source = sourceFor(app, sourceTab);
            int mode = modeFor(app, source);
            if (temporary && mode == 0) return failed("请先为此标签页选择锁屏或划卡回冻方式");
            if (freeze || temporary) {
                Set<String> managed = new HashSet<>(settings.getManagedPackages());
                if (managed.add(app.packageName)) settings.setManagedPackages(managed);
            }
            int original = freeze ? (previous == null ? before : previous.originalSetting)
                    : ControlRules.thawSetting(before, previous == null ? null : previous.originalSetting);
            lease = acquire(app, requestId, temporary, original, mode, launchExpected, freeze, source);
            Identity latest = identity(app);
            if (!same(initial, latest) || pm.getApplicationEnabledSetting(app.packageName) != before)
                throw new IllegalStateException("准备操作期间应用身份或状态发生变化，已停止写入");
            if (freeze) {
                String reason = restrictionReason(app.packageName, latest.info.uid);
                if (reason != null) throw new IllegalStateException(reason);
            }
            // Keep the required system check even if a grant is revoked while the journal fsyncs.
            final boolean highRiskFreeze = freeze && (initial.info.uid % 100000 < 10000
                    || HighRiskPolicy.isAllowed(settings.getHighRiskAllowed(), app.packageName)
                    || protectedPackages.riskReason(app.packageName) != null);
            active = directEnable ? RecoveryStore.Record.directEnable(app.packageName, app.userId, initial.info.uid,
                    initial.signature, initial.installedAt, temporary)
                    : previous == null || previous.originalSetting != original ? new RecoveryStore.Record(app.packageName, app.userId, initial.info.uid,
                    initial.signature, initial.installedAt, original, true, temporary) : previous.state(true, temporary);
            recovery.put(active);
            int wanted = freeze ? 3 : original;
            final Bundle ownedLease = lease;
            FreezeBackend.WriteGate gate = () -> {
                String reason = restrictionReason(app.packageName, initial.info.uid);
                if (reason != null) throw new FreezeBackend.BackendException(reason, false);
                if (!settings.getProtectRecents() && !(highRiskFreeze && ownedLease != null)) return;
                if (ownedLease == null) throw new FreezeBackend.BackendException("无法确认最近任务状态，未冻结", false);
                Bundle check = target(app, requestId); check.putLong("lease", ownedLease.getLong("lease"));
                Bundle result = BridgeClient.request(context, "CHECK_FREEZE", check, BRIDGE_TIMEOUT);
                if (!result.getBoolean("ok")) throw new FreezeBackend.BackendException(
                        result.getString("message", "冻结条件未确认，未冻结"), false);
            };
            writeStarted = true;
            if (before != wanted) {
                FreezeBackend selected = backend();
                if (freeze) selected.write(app.packageName, app.userId, wanted, gate);
                else selected.write(app.packageName, app.userId, wanted);
            }
            Identity afterIdentity = identity(app);
            if (!same(initial, afterIdentity)) throw new FreezeBackend.BackendException("写入期间应用身份变化，结果待核对", true);
            int after = pm.getApplicationEnabledSetting(app.packageName);
            if (freeze ? !ControlRules.confirmed(0, after, wanted)
                    : !ControlRules.confirmedThaw(after, wanted, afterIdentity.info.enabled, isSuspended(afterIdentity.info)))
                throw new FreezeBackend.BackendException("系统回读或实际可用状态与目标不符（" + after + "），未确认成功", false);
            if (removeAfterThaw) {
                final boolean leased = lease != null;
                final Bundle capability = bridgeStatus();
                final String[] removedConfig = {null};
                RemovalCommit.afterConfirmedThaw(ControlRules.confirmedThaw(after, wanted,
                        afterIdentity.info.enabled, isSuspended(afterIdentity.info)), leased,
                        capability.getBoolean("configPersistedAckSupported"), new RemovalCommit.Steps() {
                            public void removeLocal() {
                                synchronized (SettingsStore.class) {
                                    settings.removeManagedPackages(Set.of(app.packageName));
                                    removedConfig[0] = settings.toConfigJson();
                                }
                                if (batch != null) batch.invalidate();
                            }
                            public boolean syncRemoved() {
                                Bundle input = new Bundle(); input.putString("config", removedConfig[0]);
                                Bundle sync = BridgeClient.request(context, "SYNC_CONFIG", input, BRIDGE_TIMEOUT);
                                boolean acknowledged = sync.getBoolean("ok") || sync.getBoolean("configPersisted");
                                String token = sync.getString("configToken", "");
                                return acknowledged && !token.isEmpty()
                                        && !settings.getAllManagedPackages().contains(app.packageName);
                            }
                        });
            }
            if (lease != null) {
                Bundle finish = finishInput(app, requestId, lease, true, false, after);
                Bundle reply = BridgeClient.request(context, "FINISH", finish, BRIDGE_TIMEOUT);
                if (!reply.getBoolean("ok")) return failed("系统状态已改变，但系统协调未确认：" + reply.getString("message", "结果待核对"));
                bridgeFinished = true;
            }
            if (freeze || temporary) recovery.put(active.confirmed(temporary));
            else recovery.remove(app.packageName, app.userId);
            if (temporary && launchReceipt != null && lease != null) launchReceipt.putLong("lease", lease.getLong("lease"));
            if (freeze) return ok("已冻结，系统已确认");
            if (removeAfterThaw) return ok("已解冻并移出管理，已清除所有标签归属和单应用URL授权");
            if (recoveryOnly) return ok("已解冻；当前应用未获高危放行，未建立自动回冻会话");
            if (temporary) return ok(before == wanted ? "应用已可用，已采用本页回冻规则" : "已解冻，已采用本页回冻规则");
            return ok(directEnable ? "已直接启用，系统已确认" : "已解冻，系统已确认（" + original + "）");
        } catch (Exception e) {
            boolean unknown = e instanceof FreezeBackend.BackendException && ((FreezeBackend.BackendException) e).unknown;
            if (writeStarted && !(e instanceof FreezeBackend.BackendException)) unknown = true;
            int actual = -1;
            try { actual = pm.getApplicationEnabledSetting(app.packageName); } catch (RuntimeException ignored) { }
            if (lease != null && !bridgeFinished) {
                Bundle reply = BridgeClient.request(context, "FINISH", finishInput(app, requestId, lease, false, unknown, actual), BRIDGE_TIMEOUT);
                bridgeFinished = reply.getBoolean("ok");
                if (!bridgeFinished) unknown = true;
            }
            if (active != null && !unknown) {
                try {
                    if (directEnable) recovery.remove(app.packageName, app.userId);
                    else if (actual == 3) recovery.put(active.state(false, false));
                    else if (previous != null && actual == previous.originalSetting && !temporary) recovery.remove(app.packageName, app.userId);
                    else if (previous == null && ControlRules.canTakeOwnership(actual)) recovery.remove(app.packageName, app.userId);
                    else recovery.put(active.state(true, temporary));
                } catch (RuntimeException ignored) { /* The already-committed pending record remains authoritative. */ }
            }
            return failed(message(e) + (unknown ? "；结果待核对，已阻止此应用后续写入" : ""));
        }
    }

    private Bundle acquire(AppEntry app, String requestId, boolean temporary, int original, int mode,
                           boolean launchExpected, boolean freezeRequested, String sourceTab) {
        return acquire(app, requestId, temporary, original, mode, launchExpected, freezeRequested, sourceTab, true);
    }
    private Bundle acquire(AppEntry app, String requestId, boolean temporary, int original, int mode,
                           boolean launchExpected, boolean freezeRequested, String sourceTab, boolean retryConfig) {
        Bundle status = bridgeStatus();
        if (!status.getBoolean("ok") || !status.getBoolean("ready")) {
            if (temporary || coordination.getBoolean("bridgeSeen", false) || (freezeRequested && settings.getProtectRecents()))
                throw new IllegalStateException("系统模块未就绪，无法安全协调操作；" + status.getString("message", "请检查框架状态"));
            return null;
        }
        rememberBridge();
        boolean paused = recentsUnsupported(status);
        Bundle synced = syncFor(status);
        if (!synced.getBoolean("ok")) throw new IllegalStateException("规则同步未确认：" + synced.getString("message", "系统未响应"));
        if (paused && (freezeRequested || temporary))
            throw new IllegalStateException("最近任务保护需更新并重启系统模块；旧自动规则已暂停，未执行冻结或临时解冻");
        if ((freezeRequested || temporary) && HighRiskPolicy.isAllowed(settings.getHighRiskAllowed(), app.packageName)
                && !status.getBoolean("highRiskAllowlistSupported"))
            throw new IllegalStateException("高危放行需更新系统模块后重启，未绕过旧模块执行冻结或自动回冻");
        if (temporary && !status.getBoolean("tagFollowSupported"))
            throw new IllegalStateException("标签自动跟随需更新系统模块后重启；旧自动规则已暂停");
        if (temporary && !status.getBoolean("ownerRulesSupported"))
            throw new IllegalStateException("所属标签规则需更新系统模块后重启；未采用入口页的旧规则");
        ControlRules.requireTabSessionSupport(temporary, sourceTab, status.getBoolean("tabSessionsSupported"));
        if (temporary && !status.getBoolean("directThawSupported")
                && pm.getApplicationEnabledSetting(app.packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            throw new IllegalStateException("解冻 DISABLED / DISABLED_UNTIL_USED 并自动回冻需更新系统模块后重启；也可先持续解冻");
        if (temporary) ControlRules.requireTriggerSupport(mode, status.getBoolean("lockSupported"),
                status.getBoolean("leaveSupported"), status.getBoolean("cardSupported"), status.getBoolean("multiTriggerSupported"));
        if (temporary && !launchExpected && !status.getBoolean("groupSessionsSupported"))
            throw new IllegalStateException("系统侧模块版本尚未更新，请重启后使用标签或 URL 自动回冻解冻");
        Bundle input = target(app, requestId); input.putInt("enabledSetting", original); input.putInt("mode", mode);
        input.putString("sourceTab", sourceTab);
        input.putBoolean("launchExpected", launchExpected);
        input.putBoolean("freezeRequested", freezeRequested);
        if (status.getBoolean("configTokenSupported")) {
            String token = synced.getString("configToken");
            if (token == null || token.isEmpty()) throw new IllegalStateException("系统未返回配置确认令牌，未取得租约");
            input.putString("configToken", token);
        }
        Bundle reply = BridgeClient.request(context, temporary ? "BEGIN_SESSION" : "ACQUIRE", input, BRIDGE_TIMEOUT);
        if (retryConfig && !reply.getBoolean("ok") && reply.getBoolean("configMismatch") && !reply.containsKey("lease")) {
            if (batch != null) batch.invalidate();
            return acquire(app, requestId, temporary, original, mode, launchExpected, freezeRequested, sourceTab, false);
        }
        if (!reply.getBoolean("ok") || !reply.containsKey("lease")) throw new IllegalStateException("未取得操作租约：" + reply.getString("message", "系统未响应"));
        return reply;
    }
    private boolean recentsUnsupported(Bundle status) {
        return settings.getProtectRecents() && !status.getBoolean("recentsProtectionSupported");
    }
    private Bundle bridgeStatus() {
        if (batch != null && batch.status != null) return batch.status;
        Bundle status = BridgeClient.request(context, "STATUS", new Bundle(), 1500);
        if (batch != null) batch.status = status;
        return status;
    }
    private Bundle syncFor(Bundle status) {
        String value = configFor(status);
        boolean tokenSupported = status.getBoolean("configTokenSupported");
        if (batch != null && !batch.needsSync(value, tokenSupported) && batch.syncedReply != null)
            return new Bundle(batch.syncedReply);
        Bundle input = new Bundle(); input.putString("config", value);
        Bundle result = BridgeClient.request(context, "SYNC_CONFIG", input, BRIDGE_TIMEOUT);
        if (batch != null) {
            batch.confirmSync(value, result.getBoolean("ok"), tokenSupported ? result.getString("configToken") : null);
            batch.syncedReply = result.getBoolean("ok") ? new Bundle(result) : null;
        }
        return result;
    }
    private String configFor(Bundle status) {
        String config = settings.toConfigJson();
        if (!recentsUnsupported(status) && status.getBoolean("tabSessionsSupported")
                && (settings.getHighRiskAllowed().isEmpty() || status.getBoolean("highRiskAllowlistSupported"))
                && (!wantsTabAutomation() || status.getBoolean("tagFollowSupported"))
                && (!wantsTabAutomation() || status.getBoolean("ownerRulesSupported"))
                && (!wantsMultiTrigger() || status.getBoolean("multiTriggerSupported"))) return config;
        // An already-loaded old module must not silently ignore the newly requested protection.
        return ControlRules.pausedConfig(config, recentsUnsupported(status));
    }
    private boolean wantsTabAutomation() {
        if (settings.getTabMode(AppTabs.ALL) != 0) return true;
        for (Tag tag : settings.getTags()) if (tag.mode != 0) return true;
        return false;
    }
    private boolean wantsMultiTrigger() {
        if (settings.getTabMode(AppTabs.ALL) > 2) return true;
        for (Tag tag : settings.getTags()) if (tag.mode > 2) return true;
        return false;
    }
    private RecoveryStore.Record importSystemRecovery(AppEntry app, Identity current, int actual) throws Exception {
        Bundle status = bridgeStatus();
        if (!status.getBoolean("ok") || !status.getBoolean("ready") || !status.getBoolean("autoRecoverySupported")) return null;
        Bundle reply = BridgeClient.request(context, "GET_AUTO_RECOVERY",
                target(app, UUID.randomUUID().toString()), BRIDGE_TIMEOUT);
        if (!reply.getBoolean("ok")) throw new IllegalStateException("系统自动冻结恢复记录未确认：" + reply.getString("message", "未响应"));
        if (!reply.getBoolean("available")) return null;
        JSONObject item = new JSONObject().put("packageName", app.packageName).put("userId", app.userId)
                .put("uid", current.info.uid).put("signature", current.signature).put("installedAt", current.installedAt)
                .put("originalSetting", reply.getInt("originalSetting", -1));
        return SystemRecovery.verified(item, current.info.uid, current.signature, current.installedAt, actual);
    }
    private List<String> importSystemRecovery() throws Exception {
        Bundle status = bridgeStatus();
        if (!status.getBoolean("ok") || !status.getBoolean("ready") || !status.getBoolean("autoRecoverySupported")) return List.of();
        Bundle input = new Bundle(); input.putInt("userId", currentUser());
        Bundle reply = BridgeClient.request(context, "GET_AUTO_RECOVERY_LIST", input, BRIDGE_TIMEOUT);
        if (!reply.getBoolean("ok")) throw new IllegalStateException(reply.getString("message", "系统恢复记录未响应"));
        String json = reply.getString("records");
        if (json == null || json.length() > 512 * 1024) throw new IllegalStateException("系统恢复清单缺失或过大");
        JSONArray items = new JSONArray(json);
        if (items.length() > 500) throw new IllegalStateException("系统恢复清单超过上限");
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String pkg = item.getString("packageName"); int user = item.getInt("userId");
            try {
                Identity current = identity(pkg, user);
                RecoveryStore.Record existing = recovery.get(pkg, user);
                if (existing != null && (existing.pending || !matches(existing, current))) {
                    skipped.add(pkg + "：本地记录待核对或安装身份变化，未覆盖");
                    continue;
                }
                RecoveryStore.Record record = SystemRecovery.verified(item, current.info.uid, current.signature,
                        current.installedAt, pm.getApplicationEnabledSetting(pkg));
                recovery.put(record);
            } catch (Exception e) { skipped.add(pkg + "：" + message(e)); }
        }
        return skipped;
    }
    private static Bundle finishInput(AppEntry app, String requestId, Bundle lease, boolean success, boolean unknown, int setting) {
        Bundle result = target(app, requestId); result.putLong("lease", lease.getLong("lease"));
        result.putBoolean("success", success); result.putBoolean("unknown", unknown); result.putInt("enabledSetting", setting);
        return result;
    }
    private static Bundle target(AppEntry app, String requestId) {
        Bundle result = new Bundle(); result.putString("packageName", app.packageName); result.putInt("userId", app.userId);
        result.putInt("uid", app.uid); result.putString("requestId", requestId); return result;
    }
    private void rememberBridge() {
        if (coordination.getBoolean("bridgeSeen", false)) return;
        if (!coordination.edit().putBoolean("bridgeSeen", true).commit()) throw new IllegalStateException("无法保存系统协调状态，已停止操作");
    }
    private String sourceFor(AppEntry app, String source) {
        return settings.getDefaultTab(app.packageName);
    }
    private int modeFor(AppEntry app, String source) {
        return settings.getEffectiveMode(app.packageName);
    }
    private String restrictionReason(String pkg, int uid) {
        if (pkg.equals(context.getPackageName())) return "本工具只能通过专用的本体冻结设置操作";
        if (HighRiskPolicy.isAllowed(settings.getHighRiskAllowed(), pkg)) return null;
        if (uid % 100000 < 10000) return "系统核心 UID，需在设置的高危放行列表中单独允许";
        return protectedPackages.reason(pkg);
    }
    private FreezeBackend backend() {
        if (batch != null && batch.backend != null) return batch.backend;
        FreezeBackend value = switch (settings.getBackend()) {
            case "root" -> new RootBackend();
            case "shizuku" -> new ShizukuBackend();
            default -> throw new IllegalStateException("尚未选择有效的权限源");
        };
        if (batch != null) batch.backend = value;
        return value;
    }
    private Identity identity(AppEntry entry) throws Exception {
        if (entry == null) throw new IllegalArgumentException("请选择应用");
        Identity value = identity(entry.packageName, entry.userId);
        if (entry.uid != value.info.uid) throw new IllegalStateException("应用身份变化，请刷新列表");
        return value;
    }
    private Identity identity(String pkg, int user) throws Exception {
        ControlRules.validate(pkg, user);
        if (user != currentUser()) throw new IllegalArgumentException("首版仅允许操作当前用户");
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.MATCH_DISABLED_COMPONENTS));
        if (info.applicationInfo == null || info.applicationInfo.uid / 100000 != user || info.signingInfo == null)
            throw new IllegalStateException("无法确认应用安装身份");
        List<String> signatures = new ArrayList<>();
        for (Signature signature : info.signingInfo.getApkContentsSigners()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) { hex[i * 2] = alphabet[(digest[i] & 255) >>> 4]; hex[i * 2 + 1] = alphabet[digest[i] & 15]; }
            signatures.add(new String(hex));
        }
        Collections.sort(signatures);
        if (signatures.isEmpty()) throw new IllegalStateException("无法确认应用签名");
        return new Identity(info.applicationInfo, String.join(":", signatures), info.firstInstallTime);
    }
    private static boolean matches(RecoveryStore.Record record, Identity current) {
        return record.installedAt == current.installedAt && ControlRules.sameIdentity(record.userId, record.uid, record.signature,
                current.info.uid / 100000, current.info.uid, current.signature);
    }
    private static boolean same(Identity left, Identity right) {
        return left.info.uid == right.info.uid && left.installedAt == right.installedAt && left.signature.equals(right.signature);
    }
    private static boolean isSuspended(ApplicationInfo info) { return (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0; }
    private static int currentUser() { return Process.myUid() / 100000; }
    private static void worker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("应用操作必须在工作线程执行");
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static OperationResult ok(String message) { return new OperationResult(true, message); }
    private static OperationResult failed(String message) { return new OperationResult(false, message); }
    private static final class Identity {
        final ApplicationInfo info; final String signature; final long installedAt;
        Identity(ApplicationInfo info, String signature, long installedAt) { this.info = info; this.signature = signature; this.installedAt = installedAt; }
    }
}
