package io.github.yudigaga.lightfreeze.control;

/** Configuration detachment happens only inside a confirmed thaw's still-held lease. */
final class RemovalCommit {
    interface Steps {
        void removeLocal() throws Exception;
        boolean syncRemoved() throws Exception;
    }
    private RemovalCommit() { }
    static void afterConfirmedThaw(boolean confirmed, boolean leased, boolean cancellationAckSupported, Steps steps)
            throws Exception {
        if (!confirmed) throw new IllegalStateException("解冻尚未确认，未移出管理");
        if (leased && !cancellationAckSupported)
            throw new IllegalStateException("移出管理需更新系统模块后重启，未修改管理关系");
        steps.removeLocal();
        if (leased && !steps.syncRemoved())
            throw new IllegalStateException("已确认解冻，但移出管理的系统配置未确认；恢复记录已保留，请导出恢复清单核对");
    }
}
