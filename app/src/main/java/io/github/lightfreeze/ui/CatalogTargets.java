package io.github.yudigaga.lightfreeze.ui;

import io.github.yudigaga.lightfreeze.control.AppEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A general freeze action never turns an unselected application directory into management. */
final class CatalogTargets {
    private CatalogTargets() { }
    static List<AppEntry> forFreeze(List<AppEntry> catalog, List<AppEntry> visible,
                                   Set<String> selected, Set<String> managed) {
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : selected.isEmpty() ? visible : catalog) {
            if (selected.isEmpty() ? managed.contains(app.packageName) : selected.contains(app.packageName))
                result.add(app);
        }
        return List.copyOf(result);
    }
}
