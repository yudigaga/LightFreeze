package io.github.yudigaga.lightfreeze.control;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ShortProcess {
    static final class Result {
        final int exitCode;
        final boolean timedOut, truncated;
        final String output;
        Result(int code, boolean timeout, boolean truncated, String output) {
            exitCode = code; timedOut = timeout; this.truncated = truncated; this.output = output;
        }
    }
    static Result run(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        InputStream output = process.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                int read;
                while ((read = output.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(read, 8192 - bytes.size());
                        bytes.write(buffer, 0, keep);
                        if (keep != read) truncated[0] = true;
                    }
                }
            } catch (IOException ignored) { /* Closing a timed-out process also closes its pipe. */ }
        }, "freezer-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean complete = false;
        int exit = -1;
        try {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            complete = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (complete) exit = process.exitValue();
        } finally {
            try {
                if (!complete) {
                    process.destroy();
                    process.destroyForcibly();
                } else {
                    reader.join(500);
                }
            } finally {
                try { output.close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
            reader.join(500);
        }
        synchronized (bytes) {
            return new Result(exit, !complete, truncated[0], bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
