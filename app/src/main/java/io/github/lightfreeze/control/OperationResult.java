package io.github.yudigaga.lightfreeze.control;

public final class OperationResult {
    public final boolean success;
    public final String message;
    public final BatchStatus finalState;
    public OperationResult(boolean success, String message) { this(success, message, null); }
    public OperationResult(boolean success, String message, BatchStatus finalState) {
        this.success = success; this.message = message; this.finalState = finalState;
    }
    public String summary(String operation) {
        if ("freeze".equals(operation) && finalState != null) return finalState.freezeSummary();
        String verb = "freeze".equals(operation) ? "冻结" : "解冻";
        return success ? verb + "完成" : verb + "未全部完成，请在轻冻中查看状态";
    }
}
