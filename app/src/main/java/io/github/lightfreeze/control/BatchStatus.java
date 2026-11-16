package io.github.yudigaga.lightfreeze.control;

import java.util.Collection;

/** Counts a fixed target set's final PM state, independently of individual write receipts. */
public final class BatchStatus {
    public final int frozen, notFrozen, unconfirmed;

    private BatchStatus(int frozen, int notFrozen, int unconfirmed) {
        this.frozen = frozen; this.notFrozen = notFrozen; this.unconfirmed = unconfirmed;
    }

    public static BatchStatus fromEnabledSettings(Collection<Integer> settings) {
        int frozen = 0, notFrozen = 0, unconfirmed = 0;
        for (Integer setting : settings) {
            if (setting != null && AppEntry.isDisabledSetting(setting)) frozen++;
            else if (setting != null && (setting == 0 || setting == 1)) notFrozen++;
            else unconfirmed++;
        }
        return new BatchStatus(frozen, notFrozen, unconfirmed);
    }

    public String freezeSummary() {
        if (frozen + notFrozen + unconfirmed == 0) return "没有可操作的应用";
        if (notFrozen == 0 && unconfirmed == 0) return "已冻结";
        return "已冻结 " + frozen + " 个，未冻结 " + notFrozen + " 个"
                + (unconfirmed == 0 ? "" : "，" + unconfirmed + " 个状态未确认");
    }
}
