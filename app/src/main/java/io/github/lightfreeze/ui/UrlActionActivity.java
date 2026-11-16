package io.github.yudigaga.lightfreeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import io.github.yudigaga.lightfreeze.automation.AutomationLinks;
import io.github.yudigaga.lightfreeze.automation.UrlAction;
import io.github.yudigaga.lightfreeze.automation.FrozenUrlClient;
import io.github.yudigaga.lightfreeze.control.OperationResult;
import io.github.yudigaga.lightfreeze.control.TargetActions;
import io.github.yudigaga.lightfreeze.core.SettingsStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A custom-scheme dispatcher. The intent filter is routing, never authorization. */
public final class UrlActionActivity extends Activity {
    private static final String LOG_TAG = "LightFreeze";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DispatcherUi.prepare(this);
        if (state != null) { DispatcherUi.finish(this); return; }
        SettingsStore settings = new SettingsStore(this);
        AutomationLinks links = new AutomationLinks(this, settings);
        final UrlAction action;
        try {
            if (!Intent.ACTION_VIEW.equals(getIntent().getAction())) throw new IllegalArgumentException();
            action = UrlAction.parse(getIntent().getDataString());
        } catch (RuntimeException e) { reject("操作链接无效"); return; }
        worker.execute(() -> {
            String ticket = null;
            String summary = "URL 操作未完成，请在轻冻中查看状态";
            try {
                ticket = FrozenUrlClient.begin(this, getIntent(), action, settings);
                if (!links.authorizeUrl(action)) {
                    summary = "URL 授权已失效，请在轻冻中重新复制链接";
                } else {
                    OperationResult result = new TargetActions(this, settings).execute(action.operation, action.targetType, action.targetId);
                    if (!result.success) Log.w(LOG_TAG, "URL " + action.operation + " / "
                            + action.targetType + " 未完成：" + result.message);
                    summary = result.summary(action.operation);
                }
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "URL 操作未完成：" + e.getClass().getSimpleName());
            } finally {
                if (!FrozenUrlClient.end(this, ticket)) {
                    Log.w(LOG_TAG, "URL 结束回执未确认，本体回冻状态需核对");
                    summary += "；本体回冻未确认";
                }
                DispatcherUi.finishWithToast(this, summary);
            }
        });
    }

    private void reject(String message) {
        Log.w(LOG_TAG, message);
        DispatcherUi.finishWithToast(this, message);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
