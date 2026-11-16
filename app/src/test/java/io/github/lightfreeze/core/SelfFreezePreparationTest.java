package io.github.yudigaga.lightfreeze.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfFreezePreparationTest {
    @Test public void preparedSnapshotBlocksCredentialChangesUntilConfirmedRelease() {
        long initial = SelfFreezePreparation.beginMainClaim(); SelfFreezePreparation.release(initial);
        SelfFreezePreparation.requireEditable();
        long generation = SelfFreezePreparation.beginFreeze();
        assertThrows(IllegalStateException.class, SelfFreezePreparation::requireEditable);
        assertThrows(IllegalStateException.class, SelfFreezePreparation::beginFreeze);
        SelfFreezePreparation.release(generation); SelfFreezePreparation.requireEditable();
    }
    @Test public void delayedKeepReplyCannotReleaseANewerFreezePreparation() {
        long main = SelfFreezePreparation.beginMainClaim(); SelfFreezePreparation.release(main);
        long freeze = SelfFreezePreparation.beginFreeze();
        assertFalse(SelfFreezePreparation.release(main));
        assertTrue(SelfFreezePreparation.current(freeze));
        assertThrows(IllegalStateException.class, SelfFreezePreparation::requireEditable);
        assertTrue(SelfFreezePreparation.release(freeze)); SelfFreezePreparation.requireEditable();
    }
    @Test public void newMainClaimSupersedesOldPreparationAndOnlyLatestAckReleasesIt() {
        long initial = SelfFreezePreparation.beginMainClaim(); SelfFreezePreparation.release(initial);
        long freeze = SelfFreezePreparation.beginFreeze();
        long main = SelfFreezePreparation.beginMainClaim();
        SelfFreezePreparation.release(freeze);
        assertThrows(IllegalStateException.class, SelfFreezePreparation::requireEditable);
        SelfFreezePreparation.release(main); SelfFreezePreparation.requireEditable();
    }
    @Test public void repeatedMainRecreationDoesNotInventAFrozenBody() {
        long initial = SelfFreezePreparation.beginMainClaim(); SelfFreezePreparation.release(initial);
        long first = SelfFreezePreparation.beginMainClaim();
        assertTrue(SelfFreezePreparation.blocked()); assertFalse(SelfFreezePreparation.requiresKeep());
        long recreated = SelfFreezePreparation.beginMainClaim();
        assertFalse(SelfFreezePreparation.requiresKeep());
        assertFalse(SelfFreezePreparation.release(first));
        assertTrue(SelfFreezePreparation.release(recreated));
        SelfFreezePreparation.requireEditable();
    }
    @Test public void actualFreezeRequirementSurvivesMainRecreationUntilItsOwnAcknowledgement() {
        long initial = SelfFreezePreparation.beginMainClaim(); SelfFreezePreparation.release(initial);
        long freeze = SelfFreezePreparation.beginFreeze();
        long main = SelfFreezePreparation.beginMainClaim();
        long recreated = SelfFreezePreparation.beginMainClaim();
        assertTrue(SelfFreezePreparation.requiresKeep());
        assertFalse(SelfFreezePreparation.release(freeze));
        assertFalse(SelfFreezePreparation.release(main));
        assertTrue(SelfFreezePreparation.requiresKeep());
        assertTrue(SelfFreezePreparation.release(recreated));
        assertFalse(SelfFreezePreparation.requiresKeep());
    }
}
