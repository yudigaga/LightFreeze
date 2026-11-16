package io.github.yudigaga.lightfreeze.control;

import java.io.IOException;
import java.util.List;

final class RootBackend implements FreezeBackend {
    @Override public String name() { return "Root"; }
    @Override public void check() throws BackendException {
        ShortProcess.Result result = execute("id -u");
        if (result.exitCode != 0 || !result.output.trim().equals("0"))
            throw new BackendException("Root 未授权：" + detail(result.output), false);
    }
    @Override public void write(String packageName, int userId, int setting) throws BackendException {
        ShortProcess.Result result = execute(ControlRules.command(packageName, userId, setting));
        if (result.exitCode != 0) throw new BackendException("Root 命令失败：" + detail(result.output), false);
    }
    @Override public void write(String packageName, int userId, int setting, WriteGate gate) throws BackendException {
        ShortProcess.Result result = GatedRootSession.run(List.of("su"),
                ControlRules.command(packageName, userId, setting), 10000, gate);
        if (result.exitCode != 0) throw new BackendException("Root 命令失败：" + detail(result.output), false);
    }
    private ShortProcess.Result execute(String command) throws BackendException {
        try {
            ShortProcess.Result result = ShortProcess.run(List.of("su", "-c", command), 10000);
            if (result.timedOut) throw new BackendException("Root 操作超时，已尝试关闭本工具会话，系统结果待核对", true);
            return result;
        } catch (IOException e) {
            throw new BackendException("无法启动 su，请安装并授权 Root 管理器", false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("Root 操作中断，系统结果待核对", true, e);
        }
    }
    private static String detail(String output) {
        String text = output.trim();
        return text.isEmpty() ? "无有效输出" : text.substring(0, Math.min(300, text.length()));
    }
}
