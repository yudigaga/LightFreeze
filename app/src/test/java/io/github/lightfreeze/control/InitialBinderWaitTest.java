package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import static org.junit.Assert.*;

public class InitialBinderWaitTest {
    private static final class Signal implements InitialBinderWait.Signal {
        int added, removed; boolean available, notifyOnSubscribe;
        public boolean available() { return available; }
        public void add(Runnable callback) { added++; if (notifyOnSubscribe) { available = true; callback.run(); } }
        public void remove(Runnable callback) { removed++; }
    }
    @Test public void coldBinderCallbackIsAwaitedAndListenerAlwaysRemoved() throws Exception {
        Signal signal = new Signal(); signal.notifyOnSubscribe = true;
        assertTrue(new InitialBinderWait().await(signal, 1000));
        assertEquals(1, signal.added); assertEquals(1, signal.removed);
    }
    @Test public void missingServiceIsWaitedOnlyOncePerBackendObject() throws Exception {
        Signal signal = new Signal(); InitialBinderWait wait = new InitialBinderWait();
        assertFalse(wait.await(signal, 0)); assertFalse(wait.await(signal, 0));
        assertEquals(1, signal.added); assertEquals(1, signal.removed);
        signal.available = true; assertTrue(wait.await(signal, 0));
    }
    @Test public void readyConnectionDoesNotRegisterAnotherListener() throws Exception {
        Signal signal = new Signal(); signal.available = true;
        assertTrue(new InitialBinderWait().await(signal, 0)); assertEquals(0, signal.added);
    }
}
