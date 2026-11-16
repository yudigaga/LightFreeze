package io.github.yudigaga.lightfreeze.core;

/** Generations prevent a delayed Main acknowledgement from unlocking a newer freeze snapshot. */
public final class SelfFreezePreparation {
    private static long generation;
    private static boolean blocked;
    private static boolean requiresKeep;
    private SelfFreezePreparation() { }
    public static synchronized long beginFreeze() {
        requireEditable(); blocked = true; requiresKeep = true; return ++generation;
    }
    public static synchronized long beginMainClaim() { blocked = true; return ++generation; }
    public static synchronized boolean release(long acknowledgedGeneration) {
        if (!current(acknowledgedGeneration)) return false;
        blocked = false; requiresKeep = false; return true;
    }
    public static synchronized boolean current(long expectedGeneration) { return blocked && generation == expectedGeneration; }
    public static synchronized void requireEditable() {
        if (blocked) throw new IllegalStateException("正在确认本体冻结状态，请先保持本体解冻后再修改配置");
    }
    public static synchronized boolean blocked() { return blocked; }
    public static synchronized boolean requiresKeep() { return requiresKeep; }
}
