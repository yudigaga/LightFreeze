package io.github.yudigaga.lightfreeze.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreezeTriggersTest {
    @Test public void everyCombinationPreservesItsIndependentTriggers() {
        for (int mask : new int[]{0, 1, 4, 5}) {
            assertEquals(mask, FreezeTriggers.checked(mask));
            assertEquals((mask & 1) != 0, FreezeTriggers.has(mask, FreezeTriggers.LOCK));
            assertEquals((mask & 4) != 0, FreezeTriggers.has(mask, FreezeTriggers.CARD));
        }
        assertFalse(FreezeTriggers.has(0, FreezeTriggers.ALL));
        assertTrue(FreezeTriggers.has(5, FreezeTriggers.LOCK | FreezeTriggers.CARD));
    }
    @Test public void unknownBitsAndNegativeValuesCannotBecomeRules() {
        for (int mask : new int[]{-1, 2, 3, 6, 7, 8, 15, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> FreezeTriggers.checked(mask));
        assertThrows(IllegalArgumentException.class, () -> FreezeTriggers.has(1, 8));
    }
    @Test public void summariesNameAllEnabledTriggersAndManualMeansNone() {
        assertEquals("手动（不自动回冻）", FreezeTriggers.describe(0));
        assertEquals("锁屏回冻", FreezeTriggers.describe(1));
        assertEquals("移除最近任务卡片后回冻", FreezeTriggers.describe(4));
        assertEquals("锁屏回冻、移除最近任务卡片后回冻", FreezeTriggers.describe(5));
    }
    @Test public void oldMasksDropOnlyTheRetiredExitBitAndUnknownBitsStillFail() {
        for (int mask = 0; mask <= 7; mask++) assertEquals(mask & 5, FreezeTriggers.normalizeLegacy(mask));
        for (int mask : new int[]{-1, 8, 15, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> FreezeTriggers.normalizeLegacy(mask));
    }
}
