package io.github.yudigaga.lightfreeze.control;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.github.yudigaga.lightfreeze.core.SettingsStore;
import io.github.yudigaga.lightfreeze.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.ArrayList;

/** UI, pinned tag shortcuts and authenticated URLs all use the same real controller. */
public final class TargetActions {
    private final Context context;
    private final SettingsStore settings;
    public TargetActions(Context context, SettingsStore settings) {
        this.context = context.getApplicationContext(); this.settings = settings;
    }
    public OperationResult execute(String operation, String targetType, String targetId) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("标签与 URL 操作必须在工作线程执行");
        try {
            long started = SystemClock.elapsedRealtime();
            if (!"freeze".equals(operation) && !"unfreeze".equals(operation))
                throw new IllegalArgumentException("未知冻结操作");
            AppRepository repository = new AppRepository(context);
            List<AppEntry> installed;
            Set<String> targets = new TreeSet<>();
            if ("tab".equals(targetType)) {
                installed = repository.load(true);
                targets.addAll(TabTargetSelector.select(targetId, installed,
                        settings.getAllManagedPackages(), this::available));
            } else if ("tag".equals(targetType)) {
                if (targetId == null || !UUID.fromString(targetId).toString().equals(targetId))
                    throw new IllegalArgumentException("无效的标签 ID");
                Tag selected = null;
                for (Tag tag : settings.getTags()) if (tag.id.equals(targetId)) { selected = tag; break; }
                if (selected == null) throw new IllegalArgumentException("标签不存在或已删除");
                targets.addAll(selected.packages);
                installed = repository.loadPackages(targets);
            } else if ("package".equals(targetType)) {
                ControlRules.validate(targetId, android.os.Process.myUid() / 100000);
                if (!settings.getAllManagedPackages().contains(targetId))
                    throw new IllegalArgumentException("应用尚未纳入管理");
                targets.add(targetId);
                installed = repository.loadPackages(targets);
            } else throw new IllegalArgumentException("未知操作目标");
            boolean freeze = "freeze".equals(operation);
            if (targets.isEmpty()) return new OperationResult(false, "标签没有应用，未执行操作",
                    freeze ? BatchStatus.fromEnabledSettings(List.of()) : null);
            Map<String, AppEntry> catalog = new HashMap<>();
            for (AppEntry app : installed) catalog.put(app.packageName, app);
            FreezerController controller = new FreezerController(context, settings);
            StringBuilder report = new StringBuilder();
            int successes = 0;
            List<AppEntry> work = new ArrayList<>();
            for (String pkg : targets) if (catalog.containsKey(pkg)) work.add(catalog.get(pkg));
            List<OperationResult> results = controller.changeAll(work, freeze, true);
            Map<String, OperationResult> outcomes = new HashMap<>();
            for (int i = 0; i < work.size(); i++) outcomes.put(work.get(i).packageName, results.get(i));
            // Freeze the member set before writes; state changes and tag edits cannot expand this request.
            for (String pkg : targets) {
                AppEntry app = catalog.get(pkg);
                OperationResult result = app == null
                        ? new OperationResult(false, "当前用户未安装此应用")
                        : outcomes.get(pkg);
                if (result.success) successes++;
                else Log.w("LightFreeze", operation + " " + pkg + ": " + result.message);
                report.append(app == null ? pkg : app.label).append(result.success ? "：成功 · " : "：失败 · ")
                        .append(result.message).append('\n');
            }
            BatchStatus finalState = null;
            if (freeze) {
                List<Integer> finalSettings = new ArrayList<>(targets.size());
                for (String pkg : targets) finalSettings.add(readFinalSetting(catalog.get(pkg)));
                finalState = BatchStatus.fromEnabledSettings(finalSettings);
            }
            Log.i("LightFreeze", "batch " + operation + " count=" + targets.size() + " success=" + successes
                    + (finalState == null ? "" : " frozen=" + finalState.frozen + " notFrozen="
                            + finalState.notFrozen + " unconfirmed=" + finalState.unconfirmed)
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            return new OperationResult(successes == targets.size()
                    && (finalState == null || finalState.frozen == targets.size()),
                    (finalState == null ? "" : "最终状态：" + finalState.freezeSummary() + "\n")
                            + "处理 " + targets.size() + " 项，成功 " + successes + " 项，失败 "
                            + (targets.size() - successes) + " 项\n" + report, finalState);
        } catch (Exception e) {
            return new OperationResult(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Integer readFinalSetting(AppEntry app) {
        if (app == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(app.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
            if (info.uid != app.uid || info.uid / 100000 != app.userId) return null;
            return pm.getApplicationEnabledSetting(app.packageName);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private boolean available(AppEntry app) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(app.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
            return info.uid == app.uid && info.enabled && (info.flags & ApplicationInfo.FLAG_SUSPENDED) == 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}
