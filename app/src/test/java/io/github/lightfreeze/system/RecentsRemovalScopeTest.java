package io.github.yudigaga.lightfreeze.system;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecentsRemovalScopeTest {
    private static final AutomationEngine.Target TARGET = new AutomationEngine.Target("example.app", 0);
    @Test public void onlySuccessfulRecentsRequestsProduceDeduplicatedTargets() {
        RecentsRemovalScope.Scope scope = RecentsRemovalScope.begin(true);
        RecentsRemovalScope.record(TARGET); RecentsRemovalScope.record(TARGET);
        assertEquals(Set.of(TARGET), scope.finish(true));
        RecentsRemovalScope.record(TARGET);
        assertTrue(RecentsRemovalScope.begin(false).finish(true).isEmpty());
        scope = RecentsRemovalScope.begin(true); RecentsRemovalScope.record(TARGET);
        assertTrue(scope.finish(false).isEmpty());
    }
    @Test public void unrelatedCleanupAndNestedUntrustedCallsCannotLeakIntoTheGesture() {
        RecentsRemovalScope.record(TARGET);
        RecentsRemovalScope.Scope outer = RecentsRemovalScope.begin(true);
        RecentsRemovalScope.Scope nested = RecentsRemovalScope.begin(false);
        RecentsRemovalScope.record(TARGET); assertTrue(nested.finish(true).isEmpty());
        assertTrue(outer.finish(true).isEmpty());
        outer = RecentsRemovalScope.begin(true); nested = RecentsRemovalScope.begin(false); nested.finish(false);
        RecentsRemovalScope.record(TARGET); assertEquals(Set.of(TARGET), outer.finish(true));
    }
}
