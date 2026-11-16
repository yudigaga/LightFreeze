package io.github.yudigaga.lightfreeze.core;

import java.util.ArrayList;
import java.util.List;

/** Any selected trigger may refreeze the source page's current session. */
public final class FreezeTriggers {
    public static final int MANUAL = 0;
    public static final int LOCK = 1;
    public static final int CARD = 4;
    public static final int ALL = LOCK | CARD;
    private static final int LEGACY_EXIT = 2;
    private FreezeTriggers() { }

    public static int checked(int mask) {
        if (mask < 0 || (mask & ~ALL) != 0) throw new IllegalArgumentException("未知回冻触发条件");
        return mask;
    }
    /** Read migration only: preserve lock/card bits and retire the old exit trigger. */
    public static int normalizeLegacy(int mask) {
        if (mask < 0 || (mask & ~(ALL | LEGACY_EXIT)) != 0)
            throw new IllegalArgumentException("未知回冻触发条件");
        return mask & ALL;
    }
    public static boolean has(int mask, int trigger) { return (checked(mask) & checked(trigger)) != 0; }
    public static String describe(int mask) {
        checked(mask);
        if (mask == MANUAL) return "手动（不自动回冻）";
        List<String> names = new ArrayList<>();
        if (has(mask, LOCK)) names.add("锁屏回冻");
        if (has(mask, CARD)) names.add("移除最近任务卡片后回冻");
        return String.join("、", names);
    }
}
