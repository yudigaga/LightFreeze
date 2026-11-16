package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class TabTargetSelectorTest {
    private static AppEntry app(String name, int setting) {
        return new AppEntry(name, name, 0, 10001, setting, false, setting == 3);
    }

    @Test public void allAndManagedUseTheCompleteInstalledCatalog() {
        List<AppEntry> catalog = List.of(app("android", 0), app("com.example.a", 0), app("com.example.b", 3));
        Set<String> managed = Set.of("com.example.b", "com.uninstalled.app");
        assertEquals(Set.of("android", "com.example.a", "com.example.b"),
                TabTargetSelector.select("all", catalog, managed, entry -> false));
        assertEquals(Set.of("com.example.b"),
                TabTargetSelector.select("managed", catalog, managed, entry -> false));
    }

    @Test public void frozenIncludesAllThreePmDisabledStatesFromOtherTools() {
        List<AppEntry> catalog = List.of(app("com.default.app", 0), app("com.enabled.app", 1),
                app("com.disabled.app", 2), app("com.frozen.app", 3), app("com.untilused.app", 4));
        assertEquals(Set.of("com.disabled.app", "com.frozen.app", "com.untilused.app"),
                TabTargetSelector.select("frozen", catalog, Set.of(), entry -> false));
    }

    @Test public void availableRequiresDefaultOrEnabledAndConfirmedPmAvailability() {
        List<AppEntry> catalog = List.of(app("com.default.app", 0), app("com.enabled.app", 1),
                app("com.external.app", 0), app("com.unknown.app", 1), app("com.disabled.app", 2),
                app("com.frozen.app", 3), app("com.untilused.app", 4));
        assertEquals(Set.of("com.default.app", "com.enabled.app"),
                TabTargetSelector.select("available", catalog, Set.of(), entry ->
                        !Set.of("com.external.app", "com.unknown.app").contains(entry.packageName)));
    }

    @Test public void selectedMembershipCannotExpandAfterRequestStarts() {
        List<AppEntry> catalog = new ArrayList<>(List.of(app("com.first.app", 0)));
        Set<String> managed = new HashSet<>(Set.of("com.first.app"));
        Set<String> snapshot = TabTargetSelector.select("managed", catalog, managed, entry -> true);
        managed.clear();
        managed.add("com.later.app");
        catalog.clear();
        catalog.add(app("com.later.app", 0));
        assertEquals(Set.of("com.first.app"), snapshot);
    }

    @Test public void rejectsNonWireAndUnknownTabsInsteadOfSelectingEverything() {
        for (String tab : new String[]{"builtin:all", "ALL", "work", "", null}) {
            try { TabTargetSelector.select(tab, List.of(), Set.of(), entry -> true); fail("accepted invalid tab"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
