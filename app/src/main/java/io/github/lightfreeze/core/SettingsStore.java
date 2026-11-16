package io.github.yudigaga.lightfreeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import io.github.yudigaga.lightfreeze.automation.AutomationLinks;
import io.github.yudigaga.lightfreeze.tags.Tag;
import io.github.yudigaga.lightfreeze.tags.TagCodec;
import io.github.yudigaga.lightfreeze.tags.TagRules;
import io.github.yudigaga.lightfreeze.tags.TagStore;
import io.github.yudigaga.lightfreeze.tags.HomeTabLayout;
import io.github.yudigaga.lightfreeze.tags.AppTabs;
import io.github.yudigaga.lightfreeze.tags.ManagementState;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SettingsStore {
    private final SharedPreferences prefs;
    private final Context context;
    private final TagStore tags;
    public SettingsStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        tags = new TagStore(this.context);
    }
    public static long beginSelfFreezePreparation() {
        synchronized (SettingsStore.class) { return SelfFreezePreparation.beginFreeze(); }
    }
    public static long beginMainClaim() {
        synchronized (SettingsStore.class) { return SelfFreezePreparation.beginMainClaim(); }
    }
    public static void finishPreparation(long generation) {
        synchronized (SettingsStore.class) { SelfFreezePreparation.release(generation); }
    }
    public boolean confirmMainEnabled(long generation) {
        synchronized (SettingsStore.class) {
            if (!SelfFreezePreparation.current(generation)) return false;
            save(prefs.edit().putBoolean("selfFreeze", false), true);
            return SelfFreezePreparation.release(generation);
        }
    }
    public void setPreparedSelfFreeze(long generation, boolean value) {
        synchronized (SettingsStore.class) {
            requirePreparation(generation);
            save(prefs.edit().putBoolean("selfFreeze", value), true);
        }
    }
    public static void requirePreparation(long generation) {
        synchronized (SettingsStore.class) {
            if (!SelfFreezePreparation.current(generation)) throw new IllegalStateException("本体冻结准备已被新的打开操作取代，请重试");
        }
    }
    public String getBackend() { return prefs.getString("backend", "shizuku"); }
    public void setBackend(String value) {
        if (!"root".equals(value) && !"shizuku".equals(value)) throw new IllegalArgumentException("未知权限源");
        save(prefs.edit().putString("backend", value));
    }
    public int getMode() { return FreezeTriggers.normalizeLegacy(prefs.getInt("mode", 0)); }
    public long getLockDelayMs() { return prefs.getLong("lockDelayMs", 3000L); }
    public void setLockDelayMs(long value) {
        synchronized (SettingsStore.class) {
            checkDelay(value); validateScalar("lockDelayMs", value);
            save(prefs.edit().putLong("lockDelayMs", value));
        }
    }
    public long getLeaveDelayMs() { return prefs.getLong("leaveDelayMs", 10000L); }
    public void setLeaveDelayMs(long value) {
        synchronized (SettingsStore.class) {
            checkDelay(value); validateScalar("leaveDelayMs", value);
            save(prefs.edit().putLong("leaveDelayMs", value));
        }
    }
    public boolean getProtectMedia() { return prefs.getBoolean("protectMedia", true); }
    public void setProtectMedia(boolean value) {
        synchronized (SettingsStore.class) {
            validateScalar("protectMedia", value); save(prefs.edit().putBoolean("protectMedia", value));
        }
    }
    public boolean getProtectRecents() { return prefs.getBoolean("protectRecents", false); }
    public void setProtectRecents(boolean value) {
        synchronized (SettingsStore.class) {
            validateScalar("protectRecents", value); save(prefs.edit().putBoolean("protectRecents", value));
        }
    }
    public Set<String> getHighRiskAllowed() {
        return HighRiskPolicy.checked(prefs.getStringSet("highRiskAllowed", Collections.emptySet()));
    }
    public void setHighRiskAllowed(Set<String> values) {
        synchronized (SettingsStore.class) {
            SelfFreezePreparation.requireEditable();
            Set<String> checked = HighRiskPolicy.checked(values);
            buildConfigJson(getTags(), getManagedPackages(), null, -1, checked);
            save(prefs.edit().putStringSet("highRiskAllowed", checked));
        }
    }
    public boolean getSelfFreeze() { return prefs.getBoolean("selfFreeze", false); }
    public Set<String> getManagedPackages() {
        synchronized (SettingsStore.class) { return new HashSet<>(managementState().direct); }
    }
    public void setManagedPackages(Set<String> values) {
        synchronized (SettingsStore.class) {
        Set<String> copy = new HashSet<>(values);
        boolean growthOnly = copy.containsAll(getManagedPackages());
        if (!growthOnly) SelfFreezePreparation.requireEditable();
        saveManagement(ManagementState.checked(copy, getTags()), growthOnly);
        }
    }
    private ManagementState managementState() {
        String encoded = prefs.getString("managementState", null);
        return encoded == null ? ManagementState.checked(prefs.getStringSet("managed", Collections.emptySet()), tags.getAll())
                : ManagementState.decode(encoded);
    }
    public List<Tag> getTags() {
        synchronized (SettingsStore.class) { return TagRules.concreteTags(managementState().tags, getMode()); }
    }
    public int getDefaultMode() { return getTabMode(AppTabs.ALL); }
    public void setDefaultMode(int mode) { setTabMode(AppTabs.ALL, mode); }
    public String getDefaultTab(String pkg) { return TagRules.defaultTab(pkg, getTags()); }
    public int getTabMode(String id) {
        Integer mode = pageModes(getTags(), null, -1).get(id);
        if (mode == null) throw new IllegalArgumentException("标签不存在或已删除");
        return mode;
    }
    public void setTabMode(String id, int mode) {
        synchronized (SettingsStore.class) {
            checkMode(mode);
            if (AppTabs.isBuiltin(id) && !AppTabs.ALL.equals(id))
                throw new IllegalArgumentException("动态视图不单独设置回冻规则");
            List<Tag> current = getTags();
            if (AppTabs.isBuiltin(id)) {
                buildConfigJson(current, getManagedPackages(), id, mode);
                save(prefs.edit().putInt("tabMode:" + id, mode));
            } else {
                List<Tag> changed = new ArrayList<>(); boolean found = false;
                for (Tag tag : current) {
                    if (tag.id.equals(id)) { changed.add(new Tag(tag.id, tag.name, mode, tag.packages)); found = true; }
                    else changed.add(tag);
                }
                if (!found) throw new IllegalArgumentException("标签不存在或已删除");
                setTags(changed);
            }
        }
    }
    public List<String> getHomeTabOrder() { return homeTabLayout().order; }
    public Set<String> getHiddenHomeTabs() { return homeTabLayout().hidden; }
    public void setHomeTabLayout(List<String> order, Set<String> hidden) {
        synchronized (SettingsStore.class) {
            HomeTabLayout candidate = HomeTabLayout.checked(order, hidden, customTabIds());
            // Presentation only: this key is absent from rules, membership and frozen-URL authority.
            save(prefs.edit().putString("homeTabLayout", candidate.encode()), true);
        }
    }
    private HomeTabLayout homeTabLayout() {
        synchronized (SettingsStore.class) {
            return HomeTabLayout.read(prefs.getString("homeTabLayout", null), customTabIds());
        }
    }
    private List<String> customTabIds() {
        List<String> ids = new ArrayList<>();
        for (Tag tag : getTags()) ids.add(tag.id);
        return ids;
    }
    public void validateTags(List<Tag> values) {
        synchronized (SettingsStore.class) {
            List<Tag> checked = TagRules.concreteTags(TagCodec.decode(TagCodec.encode(values)), getMode());
            ManagementState candidate = managementState().withTags(checked);
            buildConfigJson(candidate.tags, candidate.direct);
            candidate.encode();
        }
    }
    public void setTags(List<Tag> values) {
        synchronized (SettingsStore.class) {
        SelfFreezePreparation.requireEditable();
        // Validate the entire candidate before revocation or a configuration write.
        List<Tag> checked = TagRules.concreteTags(TagCodec.decode(TagCodec.encode(values)), getMode());
        saveManagement(managementState().withTags(checked), false);
        }
    }
    public void removeManagedPackages(Set<String> packages) {
        synchronized (SettingsStore.class) {
            SelfFreezePreparation.requireEditable();
            saveManagement(managementState().without(Set.copyOf(packages)), false);
        }
    }
    public void validateManagedRemoval(Set<String> packages) {
        synchronized (SettingsStore.class) {
            SelfFreezePreparation.requireEditable();
            ManagementState candidate = managementState().without(Set.copyOf(packages));
            buildConfigJson(candidate.tags, candidate.direct);
            candidate.encode();
        }
    }
    private void saveManagement(ManagementState candidate, boolean allowDuringPreparation) {
        buildConfigJson(candidate.tags, candidate.direct);
        String encoded = candidate.encode();
        ManagementState before = managementState();
        Set<String> incoming = new HashSet<>();
        for (Tag tag : candidate.tags) incoming.add(tag.id);
        AutomationLinks links = new AutomationLinks(context, this);
        for (Tag tag : before.tags) if (!incoming.contains(tag.id)) links.revokeTagTokens(tag.id);
        Set<String> removed = before.allManaged();
        removed.removeAll(candidate.allManaged());
        for (String pkg : removed) links.revokeUrlToken("package", pkg);
        // First mutation migrates legacy data; reads remain side-effect free during self-freeze preparation.
        save(prefs.edit().putString("managementState", encoded), allowDuringPreparation);
    }
    public Set<String> getAllManagedPackages() {
        synchronized (SettingsStore.class) { return managementState().allManaged(); }
    }
    public int getEffectiveMode(String pkg) {
        return getTabMode(getDefaultTab(pkg));
    }
    public synchronized String getOrCreateShortcutToken(String pkg) {
        String key = "shortcut:" + pkg;
        String token = prefs.getString(key, null);
        if (token == null) {
            token = UUID.randomUUID() + ":" + UUID.randomUUID();
            save(prefs.edit().putString(key, token));
        }
        return token;
    }
    public boolean isValidShortcutToken(String pkg, String token) {
        if (pkg == null || token == null || !getAllManagedPackages().contains(pkg)) return false;
        String expected = prefs.getString("shortcut:" + pkg, null);
        return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
    public String toConfigJson() {
        synchronized (SettingsStore.class) { return buildConfigJson(getTags(), getManagedPackages()); }
    }
    private String buildConfigJson(List<Tag> labels, Set<String> direct) {
        return buildConfigJson(labels, direct, null, -1);
    }
    private String buildConfigJson(List<Tag> labels, Set<String> direct, String changedTab, int changedMode) {
        return buildConfigJson(labels, direct, changedTab, changedMode, getHighRiskAllowed());
    }
    private String buildConfigJson(List<Tag> labels, Set<String> direct, String changedTab, int changedMode,
                                   Set<String> highRiskAllowed) {
        return RuleSnapshot.encodeTabs(getLockDelayMs(), getLeaveDelayMs(), getProtectMedia(),
                getProtectRecents(), direct, labels, pageModes(labels, changedTab, changedMode), highRiskAllowed);
    }
    private Map<String, Integer> pageModes(List<Tag> labels, String changedTab, int changedMode) {
        Map<String, Integer> builtins = new HashMap<>();
        for (String id : List.of(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE)) {
            if (!AppTabs.ALL.equals(id)) builtins.put(id, 0);
            else if (id.equals(changedTab)) builtins.put(id, changedMode);
            else if (prefs.contains("tabMode:" + id))
                builtins.put(id, FreezeTriggers.normalizeLegacy(prefs.getInt("tabMode:" + id, getMode())));
        }
        return TagRules.pageModes(getMode(), labels, builtins);
    }
    private void validateScalar(String key, Object value) {
        try {
            RuleSnapshot.checked(new JSONObject(toConfigJson()).put(key, value));
        } catch (JSONException e) { throw new IllegalStateException("配置无法序列化", e); }
    }
    private static void checkMode(int value) {
        FreezeTriggers.checked(value);
    }
    private static void checkDelay(long value) {
        if (value < 0 || value > 30000L) throw new IllegalArgumentException("延迟需在 0–30 秒之间");
    }
    private static void save(SharedPreferences.Editor editor) {
        save(editor, false);
    }
    private static void save(SharedPreferences.Editor editor, boolean allowDuringPreparation) {
        synchronized (SettingsStore.class) {
            if (!allowDuringPreparation) SelfFreezePreparation.requireEditable();
            if (!editor.commit()) throw new IllegalStateException("无法保存配置，未确认更改");
        }
    }
}
