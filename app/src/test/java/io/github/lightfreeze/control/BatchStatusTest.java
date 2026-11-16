package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class BatchStatusTest {
    @Test public void allThreeDisabledSettingsCountAsFrozen() {
        BatchStatus status = BatchStatus.fromEnabledSettings(List.of(2, 3, 4));
        assertEquals(3, status.frozen);
        assertEquals(0, status.notFrozen);
        assertEquals(0, status.unconfirmed);
        assertEquals("已冻结", status.freezeSummary());
    }

    @Test public void reportsFinalStateCountsInsteadOfWriteSuccesses() {
        BatchStatus status = BatchStatus.fromEnabledSettings(List.of(3, 0, 2, 1));
        OperationResult result = new OperationResult(false, "其中一项操作失败", status);
        assertFalse(result.success);
        assertEquals("已冻结 2 个，未冻结 2 个", result.summary("freeze"));
        assertEquals("其中一项操作失败", result.message);
    }

    @Test public void pendingOperationDoesNotTurnConfirmedFrozenStateIntoNotFrozen() {
        OperationResult result = new OperationResult(false, "此前操作结果待核对",
                BatchStatus.fromEnabledSettings(List.of(3)));
        assertEquals("已冻结", result.summary("freeze"));
        assertFalse(result.success);
    }

    @Test public void unknownStateIsNotMisreportedAsUnfrozen() {
        BatchStatus status = BatchStatus.fromEnabledSettings(Arrays.asList(3, 0, null, -1, 5));
        assertEquals(1, status.frozen);
        assertEquals(1, status.notFrozen);
        assertEquals(3, status.unconfirmed);
        assertEquals("已冻结 1 个，未冻结 1 个，3 个状态未确认", status.freezeSummary());
    }

    @Test public void emptyTargetSetDoesNotClaimAlreadyFrozen() {
        assertEquals("没有可操作的应用", BatchStatus.fromEnabledSettings(List.of()).freezeSummary());
    }

    @Test public void mixedFrozenCountDoesNotDependOnOperationSuccessFlag() {
        OperationResult result = new OperationResult(true, "写入成功后状态已被其他工具改变",
                BatchStatus.fromEnabledSettings(List.of(3, 1)));
        assertEquals("已冻结 1 个，未冻结 1 个", result.summary("freeze"));
    }

    @Test public void thawSummaryPreservesRuleFailureInsteadOfCallingEnabledAppsSuccess() {
        OperationResult result = new OperationResult(false, "回冻规则未确认",
                BatchStatus.fromEnabledSettings(List.of(1)));
        assertEquals("解冻未全部完成，请在轻冻中查看状态", result.summary("unfreeze"));
    }

    @Test public void validationFailureWithoutReadbackDoesNotClaimFinalCounts() {
        OperationResult result = new OperationResult(false, "标签不存在");
        assertEquals("冻结未全部完成，请在轻冻中查看状态", result.summary("freeze"));
    }
}
