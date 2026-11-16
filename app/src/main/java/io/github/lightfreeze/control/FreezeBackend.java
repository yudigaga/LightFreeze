package io.github.yudigaga.lightfreeze.control;

interface FreezeBackend {
    @FunctionalInterface interface WriteGate { void check() throws BackendException; }
    void check() throws BackendException;
    void write(String packageName, int userId, int setting) throws BackendException;
    void write(String packageName, int userId, int setting, WriteGate gate) throws BackendException;
    String name();
    final class BackendException extends Exception {
        final boolean unknown;
        BackendException(String message, boolean unknown) { super(message); this.unknown = unknown; }
        BackendException(String message, boolean unknown, Throwable cause) { super(message, cause); this.unknown = unknown; }
    }
}
