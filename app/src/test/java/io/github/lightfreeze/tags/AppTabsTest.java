package io.github.yudigaga.lightfreeze.tags;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppTabsTest {
    @Test public void allContainsEveryCatalogState() {
        assertTrue(AppTabs.matchesBuiltin(AppTabs.ALL, false, false, false));
        assertTrue(AppTabs.matchesBuiltin(AppTabs.ALL, true, true, true));
    }
    @Test public void originalViewsRetainTheirLiveMembership() {
        assertTrue(AppTabs.matchesBuiltin(AppTabs.MANAGED, true, false, false));
        assertFalse(AppTabs.matchesBuiltin(AppTabs.MANAGED, false, true, true));
        assertTrue(AppTabs.matchesBuiltin(AppTabs.FROZEN, false, true, false));
        assertTrue(AppTabs.matchesBuiltin(AppTabs.AVAILABLE, false, false, true));
        assertFalse(AppTabs.matchesBuiltin(AppTabs.AVAILABLE, true, false, false));
    }
    @Test public void sourceChoicesAreAnOrWithBothSelectedShowingBothSources() {
        assertTrue(AppTabs.matchesSecondary(false, false, true, true, false));
        assertTrue(AppTabs.matchesSecondary(true, false, true, true, false));
        assertTrue(AppTabs.matchesSecondary(false, false, true, false, false));
        assertFalse(AppTabs.matchesSecondary(true, false, true, false, false));
        assertTrue(AppTabs.matchesSecondary(true, false, false, true, false));
    }
    @Test public void frozenIsAnAdditionalConstraintAndNoSourcesMeansEmpty() {
        assertFalse(AppTabs.matchesSecondary(false, false, true, true, true));
        assertTrue(AppTabs.matchesSecondary(true, true, true, true, true));
        assertFalse(AppTabs.matchesSecondary(false, true, false, false, false));
        assertFalse(AppTabs.matchesSecondary(true, true, false, false, true));
    }
    @Test public void unfrozenCanBeSelectedIndependentlyWithoutChangingSourceSelection() {
        assertTrue(AppTabs.matchesSecondary(false, false, true, false, false, true));
        assertFalse(AppTabs.matchesSecondary(false, true, true, false, false, true));
        assertFalse(AppTabs.matchesSecondary(true, false, true, false, false, true));
        assertTrue(AppTabs.matchesSecondary(true, true, false, true, true, false));
        assertFalse(AppTabs.matchesSecondary(true, false, false, true, true, false));
    }
    @Test public void bothStatusChoicesOrNeitherShowBothStatusesWithinSelectedSource() {
        for (boolean frozen : new boolean[]{false, true}) {
            assertTrue(AppTabs.matchesSecondary(false, frozen, true, true, false, false));
            assertTrue(AppTabs.matchesSecondary(true, frozen, true, true, true, true));
            assertFalse(AppTabs.matchesSecondary(true, frozen, true, false, true, true));
            assertFalse(AppTabs.matchesSecondary(false, frozen, false, false, false, false));
        }
    }
}
