package io.github.yudigaga.lightfreeze.system;

import java.util.LinkedHashSet;
import java.util.Set;

/** Only task notifications inside a successful recents caller removal count as card gestures. */
final class RecentsRemovalScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private RecentsRemovalScope() { }
    static Scope begin(boolean fromRecents) {
        Scope scope = new Scope(CURRENT.get(), fromRecents);
        if (fromRecents) CURRENT.set(scope); else CURRENT.remove();
        return scope;
    }
    static boolean active() { return CURRENT.get() != null; }
    static void record(AutomationEngine.Target target) {
        Scope scope = CURRENT.get();
        if (scope != null) scope.targets.add(target);
    }
    static final class Scope {
        private final Scope previous;
        private final boolean fromRecents;
        private final Set<AutomationEngine.Target> targets = new LinkedHashSet<>();
        private Scope(Scope previous, boolean fromRecents) { this.previous = previous; this.fromRecents = fromRecents; }
        Set<AutomationEngine.Target> finish(boolean success) {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            return success && fromRecents ? Set.copyOf(targets) : Set.of();
        }
    }
}
