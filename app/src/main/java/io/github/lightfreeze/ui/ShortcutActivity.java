package io.github.yudigaga.lightfreeze.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import io.github.yudigaga.lightfreeze.automation.AutomationLinks;
import io.github.yudigaga.lightfreeze.control.AppEntry;
import io.github.yudigaga.lightfreeze.control.AppRepository;
import io.github.yudigaga.lightfreeze.control.FreezerController;
import io.github.yudigaga.lightfreeze.control.OperationResult;
import io.github.yudigaga.lightfreeze.control.TargetActions;
import io.github.yudigaga.lightfreeze.core.SettingsStore;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit shortcuts have independent local capabilities and do not require the URL switch. */
public final class ShortcutActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DispatcherUi.prepare(this);
        if (state != null) { DispatcherUi.finish(this); return; }
        SettingsStore settings = new SettingsStore(this);
        if (AutomationLinks.LABEL_ACTION.equals(getIntent().getAction())
                || AutomationLinks.BUILTIN_ACTION.equals(getIntent().getAction())) {
            runGroupShortcut(settings, AutomationLinks.BUILTIN_ACTION.equals(getIntent().getAction()));
            return;
        }
        final String packageName;
        final String token;
        try {
            packageName = getIntent().getStringExtra("packageName");
            token = getIntent().getStringExtra("token");
            if (!"io.github.yudigaga.lightfreeze.OPEN".equals(getIntent().getAction())
                    || packageName == null || !settings.isValidShortcutToken(packageName, token)
                    || !settings.getAllManagedPackages().contains(packageName)
                    || getPackageName().equals(packageName)) {
                fail("快捷方式无效或应用已移出管理，请重新创建");
                return;
            }
        } catch (RuntimeException e) { fail("快捷方式数据无效，请重新创建。"); return; }
        worker.execute(() -> {
            try {
                if (!settings.isValidShortcutToken(packageName, token))
                    throw new IllegalStateException("快捷方式已撤销或应用已移出管理。");
                List<AppEntry> targets = new AppRepository(this).loadPackages(List.of(packageName));
                if (targets.isEmpty()) {
                    fail("当前用户未找到此应用，请重新创建快捷方式");
                    return;
                }
                OperationResult result = new FreezerController(this, settings).open(targets.get(0));
                if (!result.success) Log.w("LightFreeze", "shortcut open " + packageName + " 未完成：" + result.message);
                DispatcherUi.finishWithToast(this, result.success ? "已提交打开请求"
                        : "打开或回冻未全部确认，请在轻冻中查看状态");
            } catch (Exception e) {
                Log.w("LightFreeze", "shortcut open 未完成：" + e.getClass().getSimpleName());
                fail("打开或回冻未全部确认，请在轻冻中查看状态");
            }
        });
    }

    private void runGroupShortcut(SettingsStore settings, boolean builtin) {
        final String tagId;
        final String operation;
        final String token;
        final AutomationLinks links = new AutomationLinks(this, settings);
        try {
            tagId = getIntent().getStringExtra(builtin ? "tabId" : "tagId");
            operation = getIntent().getStringExtra("operation");
            token = getIntent().getStringExtra("token");
            boolean authorized = builtin ? links.authorizeTabShortcut(tagId, operation, token)
                    : links.authorizeTagShortcut(tagId, operation, token);
            if (getIntent().getData() != null || !authorized) {
                fail("标签快捷方式无效或标签已删除，请重新创建");
                return;
            }
        } catch (RuntimeException e) { fail("标签快捷方式数据无效，请重新创建"); return; }
        worker.execute(() -> {
            try {
                boolean authorized = builtin ? links.authorizeTabShortcut(tagId, operation, token)
                        : links.authorizeTagShortcut(tagId, operation, token);
                if (!authorized)
                    throw new IllegalStateException("标签快捷方式已撤销或标签已删除。");
                OperationResult result = new TargetActions(this, settings).execute(operation, builtin ? "tab" : "tag", tagId);
                if (!result.success) Log.w("LightFreeze", "shortcut " + operation + " 未完成：" + result.message);
                DispatcherUi.finishWithToast(this, result.summary(operation));
            } catch (Exception e) {
                Log.w("LightFreeze", "shortcut 操作未完成：" + e.getClass().getSimpleName());
                fail("操作未完成，请在轻冻中查看状态");
            }
        });
    }

    private void fail(String message) {
        DispatcherUi.finishWithToast(this, message);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
