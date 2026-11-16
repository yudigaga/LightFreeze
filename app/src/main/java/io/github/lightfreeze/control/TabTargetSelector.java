package io.github.yudigaga.lightfreeze.control;

import io.github.yudigaga.lightfreeze.automation.UrlAction;
import io.github.yudigaga.lightfreeze.tags.AppTabs;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Membership is independent of home search, source filters, ordering and hidden tabs. */
final class TabTargetSelector {
    private TabTargetSelector() { }

    static Set<String> select(String tabId, List<AppEntry> catalog, Set<String> managed,
                              Predicate<AppEntry> pmAvailable) {
        if (!UrlAction.isTarget("tab", tabId)) throw new IllegalArgumentException("未知内置标签");
        Set<String> targets = new TreeSet<>();
        for (AppEntry app : catalog) {
            boolean available = "available".equals(tabId) && (app.enabledSetting == 0 || app.enabledSetting == 1)
                    && pmAvailable.test(app);
            if (AppTabs.matchesBuiltin("builtin:" + tabId, managed.contains(app.packageName),
                    AppEntry.isDisabledSetting(app.enabledSetting), available)) targets.add(app.packageName);
        }
        return targets;
    }
}
