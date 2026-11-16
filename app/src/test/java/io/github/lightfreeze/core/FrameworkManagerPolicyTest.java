package io.github.yudigaga.lightfreeze.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameworkManagerPolicyTest {
    @Test public void standaloneLsposedManagersAreNotBlockedByTheFixedList() {
        assertFalse(FrameworkManagerPolicy.fixedProtectionApplies("org.lsposed.manager", true));
        assertFalse(FrameworkManagerPolicy.fixedProtectionApplies("com.lsposed.manager", true));
    }

    @Test public void managerExceptionDoesNotCoverShellRootOrShizukuComponents() {
        for (String pkg : new String[]{"android", "com.android.shell", "com.android.systemui",
                "com.topjohnwu.magisk", "me.weishu.kernelsu", "moe.shizuku.privileged.api",
                "rikka.sui", "io.github.yudigaga.lightfreeze"})
            assertTrue(pkg, FrameworkManagerPolicy.fixedProtectionApplies(pkg, true));
    }

    @Test public void exceptionIsExactAndDoesNotExpandUnrelatedProtection() {
        for (String pkg : new String[]{"org.lsposed.manager.extra", "Org.Lsposed.Manager",
                "org.lsposed.lspatch", "org.meowcat.edxposed.manager"})
            assertTrue(pkg, FrameworkManagerPolicy.fixedProtectionApplies(pkg, true));
        assertFalse(FrameworkManagerPolicy.fixedProtectionApplies("com.example.app", false));
    }
}
