package io.github.yudigaga.lightfreeze.tags;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.yudigaga.lightfreeze.core.FreezeTriggers;

public final class TagRules {
    private TagRules() { }
    public static String defaultTab(String pkg, List<Tag> tags) {
        for (Tag tag : tags) if (tag.packages.contains(pkg)) return tag.id;
        return AppTabs.ALL;
    }
    public static List<Tag> concreteTags(List<Tag> tags, int legacyDefault) {
        legacyDefault = FreezeTriggers.normalizeLegacy(legacyDefault);
        List<Tag> result = new ArrayList<>();
        for (Tag tag : tags) result.add(tag.mode < 0
                ? new Tag(tag.id, tag.name, legacyDefault, tag.packages) : tag);
        return result;
    }
    public static Map<String, Integer> pageModes(int legacyDefault, List<Tag> tags, Map<String, Integer> builtins) {
        legacyDefault = FreezeTriggers.normalizeLegacy(legacyDefault);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : List.of(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE))
            result.put(id, legacyDefault);
        for (Map.Entry<String, Integer> entry : builtins.entrySet()) {
            if (!AppTabs.isBuiltin(entry.getKey())) throw new IllegalArgumentException("未知内置标签");
            if (entry.getValue() == null) throw new IllegalArgumentException("未知回冻方式");
            result.put(entry.getKey(), FreezeTriggers.normalizeLegacy(entry.getValue()));
        }
        for (Tag tag : concreteTags(tags, legacyDefault)) result.put(tag.id, tag.mode);
        return result;
    }
    public static int resolveMode(String pkg, int appMode, List<Tag> tags, int globalMode) {
        if (appMode != -1) appMode = FreezeTriggers.normalizeLegacy(appMode);
        globalMode = FreezeTriggers.normalizeLegacy(globalMode);
        if (appMode >= 0) return appMode;
        for (Tag tag : tags) {
            if (tag.packages.contains(pkg)) return tag.mode < 0 ? globalMode : tag.mode;
        }
        return globalMode;
    }
}
