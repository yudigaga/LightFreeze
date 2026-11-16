package io.github.yudigaga.lightfreeze.automation;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import io.github.yudigaga.lightfreeze.core.SettingsStore;
import io.github.yudigaga.lightfreeze.core.SelfFreezePreparation;
import io.github.yudigaga.lightfreeze.tags.Tag;
import io.github.yudigaga.lightfreeze.ui.ShortcutActivity;
import java.util.Map;
import java.util.TreeMap;

/** Tokens and opt-in stay on this device and never enter the exported tag configuration. */
public final class AutomationLinks {
    public static final String LABEL_ACTION = "io.github.yudigaga.lightfreeze.LABEL_ACTION";
    public static final String BUILTIN_ACTION = "io.github.yudigaga.lightfreeze.BUILTIN_ACTION";
    private static final Object TOKENS = new Object();
    private final Context context;
    private final SettingsStore settings;
    private final SharedPreferences prefs;

    public AutomationLinks(Context context, SettingsStore settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        prefs = this.context.getSharedPreferences("automation", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return prefs.getBoolean("urlEnabled", false); }

    public FrozenUrlSnapshot frozenSnapshot() {
        synchronized (SettingsStore.class) {
            Map<String, String> tokens = new TreeMap<>();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("url:") || !(entry.getValue() instanceof String)) continue;
                String scope = key.substring(4);
                int split = scope.indexOf(':');
                if (split < 1) continue;
                String type = scope.substring(0, split), id = scope.substring(split + 1);
                String token = (String) entry.getValue();
                if (targetExists(type, id) && AutomationAuth.isToken(token)) tokens.put(scope, token);
            }
            return FrozenUrlSnapshot.create(android.os.Process.myUid() / 100000, isEnabled(), tokens);
        }
    }

    public void setEnabled(boolean enabled) {
        save(prefs.edit().putBoolean("urlEnabled", enabled));
    }

    public String url(String operation, String targetType, String targetId) {
        // Deleting a tag and minting a token must not straddle each other's commits.
        synchronized (SettingsStore.class) {
        requireTarget(operation, targetType, targetId);
        return UrlAction.build(operation, targetType, targetId, token(AutomationAuth.key("url", targetType, targetId)));
        }
    }

    public void revokeUrlToken(String targetType, String targetId) {
        if (!UrlAction.isTarget(targetType, targetId)) throw new IllegalArgumentException("无效的操作目标");
        synchronized (SettingsStore.class) {
            synchronized (TOKENS) { save(prefs.edit().remove(AutomationAuth.key("url", targetType, targetId))); }
        }
    }

    public void revokeTagTokens(String tagId) {
        if (!UrlAction.isTarget("tag", tagId)) throw new IllegalArgumentException("无效的标签");
        synchronized (SettingsStore.class) { synchronized (TOKENS) {
            save(prefs.edit().remove(AutomationAuth.key("url", "tag", tagId))
                    .remove(AutomationAuth.key("shortcut", "tag", tagId)));
        } }
    }

    public Intent tagShortcutIntent(String operation, String tagId) {
        synchronized (SettingsStore.class) {
        requireTarget(operation, "tag", tagId);
        return new Intent(context, ShortcutActivity.class).setAction(LABEL_ACTION)
                .putExtra("operation", operation).putExtra("tagId", tagId)
                .putExtra("token", token(AutomationAuth.key("shortcut", "tag", tagId)));
        }
    }

    public Intent tabShortcutIntent(String operation, String tabId) {
        synchronized (SettingsStore.class) {
        requireTarget(operation, "tab", tabId);
        return new Intent(context, ShortcutActivity.class).setAction(BUILTIN_ACTION)
                .putExtra("operation", operation).putExtra("tabId", tabId)
                .putExtra("token", token(AutomationAuth.key("shortcut", "tab", tabId)));
        }
    }

    public boolean authorizeUrl(UrlAction action) {
        synchronized (SettingsStore.class) {
        if (action == null || !targetExists(action.targetType, action.targetId)) return false;
        return AutomationAuth.authorize(isEnabled(), true,
                prefs.getString(AutomationAuth.key("url", action.targetType, action.targetId), null), action.token);
        }
    }

    public boolean authorizeTagShortcut(String tagId, String operation, String token) {
        synchronized (SettingsStore.class) {
        return UrlAction.isOperation(operation) && targetExists("tag", tagId)
                && AutomationAuth.authorize(true, true,
                prefs.getString(AutomationAuth.key("shortcut", "tag", tagId), null), token);
        }
    }

    public boolean authorizeTabShortcut(String tabId, String operation, String token) {
        synchronized (SettingsStore.class) {
        return UrlAction.isOperation(operation) && targetExists("tab", tabId)
                && AutomationAuth.authorize(true, true,
                prefs.getString(AutomationAuth.key("shortcut", "tab", tabId), null), token);
        }
    }

    private String token(String key) {
        synchronized (TOKENS) {
            String value = prefs.getString(key, null);
            if (!AutomationAuth.isToken(value)) {
                value = AutomationAuth.newToken();
                save(prefs.edit().putString(key, value));
            }
            return value;
        }
    }

    private void requireTarget(String operation, String type, String id) {
        if (!UrlAction.isOperation(operation) || !targetExists(type, id))
            throw new IllegalArgumentException("操作目标不存在或尚未加入管理");
    }

    private boolean targetExists(String type, String id) {
        if (!UrlAction.isTarget(type, id)) return false;
        if (type.equals("tab")) return true;
        if (type.equals("package")) return settings.getAllManagedPackages().contains(id);
        for (Tag tag : settings.getTags()) if (id.equals(tag.id)) return true;
        return false;
    }

    private static void save(SharedPreferences.Editor editor) {
        synchronized (SettingsStore.class) {
            SelfFreezePreparation.requireEditable();
            if (!editor.commit()) throw new IllegalStateException("无法保存自动化设置，未确认更改");
        }
    }
}
