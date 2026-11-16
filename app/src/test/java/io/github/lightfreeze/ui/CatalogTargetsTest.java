package io.github.yudigaga.lightfreeze.ui;

import io.github.yudigaga.lightfreeze.control.AppEntry;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogTargetsTest {
    private final AppEntry managed = app("example.managed");
    private final AppEntry outside = app("example.outside");
    private final AppEntry unselected = app("example.unselected");
    private static AppEntry app(String pkg) { return new AppEntry(pkg, pkg, 0, 10001, 0, false, false); }
    @Test public void emptySelectionFreezesOnlyVisibleManagedApplications() {
        assertEquals(List.of(managed), CatalogTargets.forFreeze(List.of(managed, outside),
                List.of(managed, outside), Set.of(), Set.of(managed.packageName)));
    }
    @Test public void hiddenManagedApplicationsAreNotAddedToTheDefaultAction() {
        assertTrue(CatalogTargets.forFreeze(List.of(managed, outside), List.of(outside),
                Set.of(), Set.of(managed.packageName)).isEmpty());
    }
    @Test public void explicitSelectionCanManageAnUnmanagedApplicationWithoutExpandingToOthers() {
        assertEquals(List.of(outside), CatalogTargets.forFreeze(List.of(managed, outside, unselected),
                List.of(managed), Set.of(outside.packageName), Set.of(managed.packageName)));
    }
    @Test public void selectionCannotInventAnUninstalledTarget() {
        assertTrue(CatalogTargets.forFreeze(List.of(managed), List.of(managed),
                Set.of("example.removed"), Set.of(managed.packageName)).isEmpty());
    }
}
