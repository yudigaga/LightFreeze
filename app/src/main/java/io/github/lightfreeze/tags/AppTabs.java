package io.github.yudigaga.lightfreeze.tags;

/** Built-in live views are separate from user-owned tag membership. */
public final class AppTabs {
    public static final String ALL = "builtin:all";
    public static final String MANAGED = "builtin:managed";
    public static final String FROZEN = "builtin:frozen";
    public static final String AVAILABLE = "builtin:available";
    private AppTabs() {}
    public static boolean isBuiltin(String id) {
        return ALL.equals(id) || MANAGED.equals(id) || FROZEN.equals(id) || AVAILABLE.equals(id);
    }
    public static String title(String id) {
        if (ALL.equals(id)) return "全部";
        if (MANAGED.equals(id)) return "已管理";
        if (FROZEN.equals(id)) return "已冻结";
        if (AVAILABLE.equals(id)) return "可用";
        throw new IllegalArgumentException("未知内置标签");
    }
    public static boolean matchesBuiltin(String id, boolean managed, boolean frozen, boolean available) {
        if (ALL.equals(id)) return true;
        if (MANAGED.equals(id)) return managed;
        if (FROZEN.equals(id)) return frozen;
        if (AVAILABLE.equals(id)) return available;
        throw new IllegalArgumentException("未知内置标签");
    }
    public static boolean matchesSecondary(boolean systemApp, boolean frozen, boolean thirdParty,
                                            boolean official, boolean frozenOnly) {
        return matchesSecondary(systemApp, frozen, thirdParty, official, frozenOnly, false);
    }
    public static boolean matchesSecondary(boolean systemApp, boolean frozen, boolean thirdParty,
                                            boolean official, boolean frozenOnly, boolean unfrozenOnly) {
        boolean statusMatches = frozenOnly == unfrozenOnly || (frozen ? frozenOnly : unfrozenOnly);
        return (systemApp ? official : thirdParty) && statusMatches;
    }
}
