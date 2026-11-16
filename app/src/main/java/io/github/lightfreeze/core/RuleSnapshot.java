package io.github.yudigaga.lightfreeze.core;

import io.github.yudigaga.lightfreeze.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import io.github.yudigaga.lightfreeze.tags.TagRules;
import io.github.yudigaga.lightfreeze.tags.AppTabs;

/** Pure configuration snapshot builder shared by candidate validation and system synchronization. */
public final class RuleSnapshot {
    private RuleSnapshot() {}
    public static String encodeTabs(long lockDelay, long leaveDelay, boolean protectMedia,
                                    boolean protectRecents, Set<String> direct, List<Tag> tags,
                                    Map<String, Integer> tabModes) {
        return encodeTabs(lockDelay, leaveDelay, protectMedia, protectRecents, direct, tags, tabModes, Set.of());
    }
    public static String encodeTabs(long lockDelay, long leaveDelay, boolean protectMedia,
                                    boolean protectRecents, Set<String> direct, List<Tag> tags,
                                    Map<String, Integer> tabModes, Set<String> highRiskAllowed) {
        Set<String> allowed = HighRiskPolicy.checked(highRiskAllowed);
        try {
            Set<String> known = new HashSet<>(List.of(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE));
            for (Tag tag : tags) known.add(tag.id);
            if (!known.equals(tabModes.keySet())) throw new IllegalArgumentException("标签回冻规则不完整");
            for (Integer mode : tabModes.values()) {
                if (mode == null) throw new IllegalArgumentException("未知标签回冻方式");
                FreezeTriggers.checked(mode);
            }
            tabModes = new java.util.HashMap<>(tabModes);
            // Dynamic views only select targets; ALL's old value remains the unassigned default.
            tabModes.put(AppTabs.MANAGED, 0);
            tabModes.put(AppTabs.FROZEN, 0);
            tabModes.put(AppTabs.AVAILABLE, 0);
            int mode = tabModes.get(AppTabs.ALL);
            Set<String> all = new HashSet<>(direct);
            for (Tag tag : tags) all.addAll(tag.packages);
            if (all.size() > 500) throw new IllegalArgumentException("已管理应用与标签成员合计不能超过 500 个");
            ArrayList<String> packages = new ArrayList<>(all); Collections.sort(packages);
            JSONObject legacyModes = new JSONObject();
            for (String pkg : packages) {
                int resolved = tabModes.get(TagRules.defaultTab(pkg, tags));
                if (resolved != mode) legacyModes.put(pkg, resolved);
            }
            return checked(new JSONObject().put("version", 1).put("mode", mode)
                    .put("lockDelayMs", lockDelay).put("leaveDelayMs", leaveDelay)
                    .put("protectMedia", protectMedia).put("protectRecents", protectRecents)
                    .put("managed", new JSONArray(packages)).put("appModes", legacyModes)
                    .put("tabModes", new JSONObject(tabModes))
                    .put("highRiskAllowed", new JSONArray(new java.util.TreeSet<>(allowed))));
        } catch (JSONException e) { throw new IllegalStateException("配置无法序列化", e); }
    }
    public static String encode(int mode, long lockDelay, long leaveDelay, boolean protectMedia,
                                boolean protectRecents, Set<String> direct, List<Tag> tags,
                                Map<String, Integer> appModes) {
        mode = FreezeTriggers.normalizeLegacy(mode);
        try {
            Set<String> all = new HashSet<>(direct);
            for (Tag tag : tags) all.addAll(tag.packages);
            if (all.size() > 500) throw new IllegalArgumentException("已管理应用与标签成员合计不能超过 500 个");
            ArrayList<String> packages = new ArrayList<>(all);
            Collections.sort(packages);
            JSONObject modes = new JSONObject();
            for (String pkg : packages) {
                int resolved = TagRules.resolveMode(pkg, appModes.getOrDefault(pkg, -1), tags, mode);
                if (resolved != mode) modes.put(pkg, resolved);
            }
            return checked(new JSONObject().put("version", 1).put("mode", mode)
                    .put("lockDelayMs", lockDelay).put("leaveDelayMs", leaveDelay)
                    .put("protectMedia", protectMedia).put("protectRecents", protectRecents)
                    .put("managed", new JSONArray(packages)).put("appModes", modes));
        } catch (JSONException e) { throw new IllegalStateException("配置无法序列化", e); }
    }
    static String checked(JSONObject value) {
        String result = value.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
            throw new IllegalArgumentException("规则快照过大，请减少标签成员或包名长度");
        return result;
    }
}
