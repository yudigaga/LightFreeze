package io.github.yudigaga.lightfreeze.core;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeClient {
    private BridgeClient() {}

    public static Bundle request(Context context, String operation, Bundle input, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("系统请求必须在工作线程执行");
        Bundle arguments = input == null ? new Bundle() : new Bundle(input);
        if (!arguments.containsKey("requestId"))
            arguments.putString("requestId", UUID.randomUUID().toString());
        String requestId = arguments.getString("requestId");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> reply = new AtomicReference<>();
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (data == null || !requestId.equals(data.getString("requestId"))) return;
                if (reply.compareAndSet(null, new Bundle(data))) latch.countDown();
            }
        };
        Intent intent = new Intent(Protocol.ACTION).setPackage("android")
                .putExtra("operation", operation).putExtra("input", arguments)
                .putExtra("receiver", receiver);
        try {
            context.sendBroadcast(intent, Protocol.PERMISSION);
            if (!latch.await(Math.max(100L, Math.min(timeoutMs, 30000L)), TimeUnit.MILLISECONDS))
                return failed(requestId, "系统模块未响应，请在框架中启用并重启；操作结果未确认", true);
            return reply.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(requestId, "操作被中断，结果未确认", true);
        } catch (RuntimeException e) {
            return failed(requestId, "无法联系系统模块：" + e.getClass().getSimpleName(), true);
        }
    }

    private static Bundle failed(String requestId, String message, boolean unavailable) {
        Bundle result = new Bundle();
        result.putBoolean("ok", false);
        result.putBoolean("unavailable", unavailable);
        result.putString("requestId", requestId);
        result.putString("message", message);
        return result;
    }
}
