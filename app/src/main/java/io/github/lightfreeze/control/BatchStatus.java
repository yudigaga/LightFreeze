package io.github.yudigaga.lightfreeze.control;

import java.util.Collection;

/** Counts a fixed target set's final PM state, independently of individual write receipts. */
public final class BatchStatus {
    public final int frozen, notFrozen, unconfirmed;
    private final int notInstalled;

    private BatchStatus(int frozen, int notFrozen, int unconfirmed, int notInstalled) {
        this.frozen = frozen; this.notFrozen = notFrozen; this.unconfirmed = unconfirmed;
        this.notInstalled = notInstalled;
    }

    public static BatchStatus fromEnabledSettings(Collection<Integer> settings) {
        return fromEnabledSettings(settings, 0);
    }

    static BatchStatus fromEnabledSettings(Collection<Integer> settings, int notInstalled) {
        int frozen = 0, notFrozen = 0, unconfirmed = 0;
        for (Integer setting : settings) {
            if (setting != null && AppEntry.isDisabledSetting(setting)) frozen++;
            else if (setting != null && (setting == 0 || setting == 1)) notFrozen++;
            else unconfirmed++;
        }
        return new BatchStatus(frozen, notFrozen, unconfirmed, notInstalled);
    }

    public String freezeSummary() {
        String summary;
        if (frozen + notFrozen + unconfirmed == 0) summary = "没有可操作的应用";
        else if (notFrozen == 0 && unconfirmed == 0) summary = "已冻结";
        else summary = "已冻结 " + frozen + " 个，未冻结 " + notFrozen + " 个"
                    + (unconfirmed == 0 ? "" : "，" + unconfirmed + " 个状态未确认");
        return summary + (notInstalled == 0 ? "" : "，已跳过 " + notInstalled + " 个未安装应用");
    }
}
