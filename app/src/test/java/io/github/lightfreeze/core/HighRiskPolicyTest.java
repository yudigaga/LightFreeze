package io.github.yudigaga.lightfreeze.core;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class HighRiskPolicyTest {
    @Test public void allowanceIsPerPackageAndDoesNotLeakAcrossSharedUidPackages() {
        Set<String> allowed = HighRiskPolicy.checked(Set.of("com.android.thememanager"));
        assertTrue(HighRiskPolicy.isAllowed(allowed, "com.android.thememanager"));
        assertFalse(HighRiskPolicy.isAllowed(allowed, "com.android.systemui"));
        assertFalse(HighRiskPolicy.isAllowed(Set.of(), "com.android.thememanager"));
    }
    @Test public void savedPermissionIsAnImmutableCopy() {
        Set<String> draft = new HashSet<>(Set.of("com.android.thememanager"));
        Set<String> saved = HighRiskPolicy.checked(draft);
        draft.clear();
        assertTrue(saved.contains("com.android.thememanager"));
        assertThrows(UnsupportedOperationException.class, () -> saved.clear());
    }
    @Test public void invalidTargetsAndGenericSelfFreezeAreRejected() {
        for (String target : new String[]{"android", "io.github.yudigaga.lightfreeze", "bad", "", "a.b;pm", "a." + "b".repeat(254)})
            assertThrows(IllegalArgumentException.class, () -> HighRiskPolicy.checked(Set.of(target)));
        assertThrows(IllegalArgumentException.class, () -> HighRiskPolicy.checked(null));
        Set<String> withNull = new HashSet<>(); withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> HighRiskPolicy.checked(withNull));
    }
    @Test public void oversizedPermissionListsAreRejected() {
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < 501; i++) targets.add("example.app" + i);
        assertThrows(IllegalArgumentException.class, () -> HighRiskPolicy.checked(targets));
        targets.remove("example.app500");
        assertEquals(500, HighRiskPolicy.checked(targets).size());
    }
}
