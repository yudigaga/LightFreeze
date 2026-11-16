package io.github.yudigaga.lightfreeze.tags;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeTabLayoutTest {
    private static final String CUSTOM = "11111111-1111-4111-8111-111111111111";
    private static final String NEW = "22222222-2222-4222-8222-222222222222";
    private static final List<String> BUILTINS = List.of(AppTabs.ALL, AppTabs.MANAGED,
            AppTabs.FROZEN, AppTabs.AVAILABLE);

    @Test public void absentLayoutDefaultsToAllFourThenCustomTags() {
        HomeTabLayout layout = HomeTabLayout.read(null, List.of(CUSTOM));
        assertEquals(List.of(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN,
                AppTabs.AVAILABLE, CUSTOM), layout.order);
        assertTrue(layout.hidden.isEmpty());
    }

    @Test public void builtinsAndCustomTagsSharePersistedOrderAndVisibility() {
        List<String> order = List.of(CUSTOM, AppTabs.FROZEN, AppTabs.ALL,
                AppTabs.AVAILABLE, AppTabs.MANAGED);
        HomeTabLayout saved = HomeTabLayout.checked(order, Set.of(AppTabs.ALL, CUSTOM), List.of(CUSTOM));
        HomeTabLayout read = HomeTabLayout.read(saved.encode(), List.of(CUSTOM));
        assertEquals(order, read.order);
        assertEquals(Set.of(AppTabs.ALL, CUSTOM), read.hidden);
    }

    @Test public void addedTagsAppendAndDeletedTagsArePruned() {
        HomeTabLayout saved = HomeTabLayout.checked(List.of(CUSTOM, AppTabs.AVAILABLE,
                AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN), Set.of(CUSTOM, AppTabs.ALL), List.of(CUSTOM));
        HomeTabLayout read = HomeTabLayout.read(saved.encode(), List.of(NEW));
        assertEquals(List.of(AppTabs.AVAILABLE, AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, NEW), read.order);
        assertEquals(Set.of(AppTabs.ALL), read.hidden);
    }

    @Test public void deletingOnlyVisibleCustomTagRestoresFirstKnownTab() {
        HomeTabLayout saved = HomeTabLayout.checked(List.of(CUSTOM, AppTabs.FROZEN,
                AppTabs.ALL, AppTabs.MANAGED, AppTabs.AVAILABLE), Set.copyOf(BUILTINS), List.of(CUSTOM));
        HomeTabLayout read = HomeTabLayout.read(saved.encode(), List.of());
        assertFalse(read.hidden.contains(AppTabs.FROZEN));
        assertTrue(read.hidden.contains(AppTabs.ALL));
    }

    @Test public void staleDialogStillAppendsNewlyCreatedTag() {
        HomeTabLayout saved = HomeTabLayout.checked(BUILTINS, Set.of(AppTabs.ALL), List.of(NEW));
        assertEquals(NEW, saved.order.get(saved.order.size() - 1));
        assertFalse(saved.hidden.contains(NEW));
    }

    @Test public void cannotExplicitlyHideEveryTab() {
        assertThrows(IllegalArgumentException.class,
                () -> HomeTabLayout.checked(BUILTINS, Set.copyOf(BUILTINS), List.of()));
    }

    @Test public void invalidSelectionCannotBeSaved() {
        assertThrows(IllegalArgumentException.class,
                () -> HomeTabLayout.checked(List.of(AppTabs.ALL, AppTabs.ALL), Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> HomeTabLayout.checked(List.of("missing"), Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> HomeTabLayout.checked(BUILTINS, Set.of("missing"), List.of()));
    }

    @Test public void malformedSavedDataRecoversUsableDefaults() {
        for (String raw : List.of("{", "{\"version\":2}",
                "{\"version\":1,\"order\":[\"builtin:all\",\"builtin:all\"],\"hidden\":[]}")) {
            HomeTabLayout layout = HomeTabLayout.read(raw, List.of(CUSTOM));
            assertEquals(AppTabs.ALL, layout.order.get(0));
            assertEquals(5, layout.order.size());
            assertTrue(layout.hidden.isEmpty());
        }
    }
}
