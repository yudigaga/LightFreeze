package io.github.yudigaga.lightfreeze.control;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One authorized shell; no state-changing command is sent before the final gate. */
final class GatedRootSession {
    static ShortProcess.Result run(List<String> command, String mutation, long timeoutMs, FreezeBackend.WriteGate gate)
            throws FreezeBackend.BackendException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Capture capture = new Capture(nonce);
        Process process = null;
        Thread reader = null;
        FutureTask<Void> gateTask = null;
        boolean mutationSent = false;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            InputStream output = process.getInputStream();
            reader = new Thread(() -> capture.read(output), "freezer-root-output");
            reader.setDaemon(true); reader.start();
            OutputStream input = process.getOutputStream();
            String probe = "if [ \"$(id -u)\" = \"0\" ]; then printf '%s\\n' 'LF_READY_" + nonce
                    + "'; else printf '%s\\n' 'LF_DENIED_" + nonce + "'; exit 1; fi\n";
            input.write(probe.getBytes(StandardCharsets.UTF_8)); input.flush();
            if (!capture.readyOrClosed.await(remaining(deadline), TimeUnit.NANOSECONDS))
                throw failure("Root 授权等待超时，未发送冻结命令", false, null);
            if (!capture.ready) throw failure("Root 未授权，未发送冻结命令：" + capture.text(), false, null);
            gateTask = new FutureTask<>(() -> { gate.check(); return null; });
            Thread gateThread = new Thread(gateTask, "freezer-root-gate");
            gateThread.setDaemon(true); gateThread.start();
            gateTask.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (remaining(deadline) <= 0 || !process.isAlive())
                throw failure("Root 会话已结束或超时，未发送冻结命令", false, null);
            String write = mutation + "\n__lf_status=$?\nprintf '%s %s\\n' 'LF_DONE_" + nonce
                    + "' \"$__lf_status\"\nexit \"$__lf_status\"\n";
            // An interrupted/partial pipe write can already have submitted the command.
            mutationSent = true;
            input.write(write.getBytes(StandardCharsets.UTF_8)); input.flush(); input.close();
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS))
                throw failure("Root 命令超时，已尝试关闭本工具会话，系统结果待核对", true, null);
            reader.join(Math.min(500, TimeUnit.NANOSECONDS.toMillis(remaining(deadline)) + 1));
            int exit = process.exitValue();
            if (capture.doneCode == null || capture.doneCode != exit)
                throw failure("Root 命令终态回执不完整，系统结果待核对", true, null);
            return new ShortProcess.Result(exit, false, capture.truncated, capture.text());
        } catch (IOException e) {
            throw failure(mutationSent ? "Root 写入中断，系统结果待核对" : "无法启动或连接 Root 会话，未发送冻结命令", mutationSent, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(mutationSent ? "Root 操作中断，系统结果待核对" : "Root 操作中断，未发送冻结命令", mutationSent, e);
        } catch (TimeoutException e) {
            throw failure("Root 最终保护检查超时，未发送冻结命令", false, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            throw failure(cause.getMessage() == null ? "最终保护检查失败，未发送冻结命令" : cause.getMessage(), false, cause);
        } finally {
            if (gateTask != null && !gateTask.isDone()) gateTask.cancel(true);
            if (process != null) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
                close(process.getOutputStream()); close(process.getInputStream()); close(process.getErrorStream());
            }
            if (reader != null) {
                try { reader.join(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }
    private static FreezeBackend.BackendException failure(String message, boolean unknown, Throwable cause) {
        return new FreezeBackend.BackendException(message, unknown, cause);
    }
    private static void close(java.io.Closeable stream) { try { stream.close(); } catch (IOException ignored) { } }
    private static final class Capture {
        final CountDownLatch readyOrClosed = new CountDownLatch(1);
        final String readyMarker, doneMarker;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        volatile boolean ready, truncated;
        volatile Integer doneCode;
        boolean lineTooLong;
        Capture(String nonce) { readyMarker = "LF_READY_" + nonce; doneMarker = "LF_DONE_" + nonce + " "; }
        void read(InputStream input) {
            byte[] buffer = new byte[1024];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(read, 8192 - bytes.size()); bytes.write(buffer, 0, keep);
                        if (keep != read) truncated = true;
                    }
                    for (int i = 0; i < read; i++) {
                        int value = buffer[i] & 255;
                        if (value == '\n') {
                            if (!lineTooLong) parse(line.toString(StandardCharsets.UTF_8).trim());
                            line.reset(); lineTooLong = false;
                        } else if (line.size() < 512) line.write(value);
                        else lineTooLong = true;
                    }
                }
            } catch (IOException ignored) { }
            finally { readyOrClosed.countDown(); }
        }
        void parse(String value) {
            if (value.equals(readyMarker)) { ready = true; readyOrClosed.countDown(); }
            else if (value.startsWith(doneMarker)) {
                try { doneCode = Integer.parseInt(value.substring(doneMarker.length())); }
                catch (NumberFormatException ignored) { }
            }
        }
        String text() { synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); } }
    }
}
