package io.github.yudigaga.lightfreeze.control;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A newly woken App can receive the existing Shizuku binder shortly after its provider starts. */
final class InitialBinderWait {
    interface Signal { boolean available(); void add(Runnable callback); void remove(Runnable callback); }
    private boolean attempted;
    boolean await(Signal signal, long timeoutMs) throws InterruptedException {
        if (signal.available()) return true;
        if (attempted) return false;
        attempted = true;
        CountDownLatch ready = new CountDownLatch(1); Runnable callback = ready::countDown;
        signal.add(callback);
        try { return signal.available() || (ready.await(timeoutMs, TimeUnit.MILLISECONDS) && signal.available()); }
        finally { signal.remove(callback); }
    }
}
