package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.core.FreezeTriggers;
import io.github.yudigaga.lightfreeze.core.HighRiskPolicy;

import java.util.*;

/** Confined to the system worker. Host PM calls and manual lease grants share this queue. */
final class AutomationEngine {
    record Target(String packageName, int userId) { }
    enum Phase { ENABLING, ACTIVE, UNRESOLVED }
    static final class Session {
        Target target; String requestId, digest; int uid, original; long lease, generation, activeSince;
        String sourceTab = "", lastDiagnostic = "";
        Phase phase; boolean temporary, cancelled, firstVisible, followed, launchExpected = true;
        // Trigger clocks and card gestures belong to this boot; they are deliberately not persisted.
        long lockDeadline = -1;
        boolean cardPending;
    }
    record Observation(boolean identityMatches, int setting, boolean visibilityKnown, boolean visible,
                       boolean lockKnown, boolean locked, boolean protectionKnown, boolean protectedNow) { }
    record Receipt(String requestId, long lease) { }
    record OwnedFreeze(int uid, String digest, int original, boolean confirmed) { }
    record FollowCandidate(Target target, int uid, String digest, int original) { }
    record Policy(int mode, long lockDelay, long leaveDelay, boolean protectMedia, boolean protectRecents,
                  Set<String> managed, Map<String,Integer> appModes, Map<String,Integer> tabModes,
                  Set<String> highRiskAllowed) {
        Policy {
            FreezeTriggers.checked(mode);
            appModes.values().forEach(FreezeTriggers::checked);
            tabModes.values().forEach(FreezeTriggers::checked);
            highRiskAllowed = HighRiskPolicy.checked(highRiskAllowed);
        }
        Policy(int mode, long lockDelay, long leaveDelay, boolean protectMedia, boolean protectRecents,
               Set<String> managed, Map<String,Integer> appModes, Map<String,Integer> tabModes) {
            this(mode, lockDelay, leaveDelay, protectMedia, protectRecents, managed, appModes, tabModes, Set.of());
        }
        Policy(int mode, long lockDelay, long leaveDelay, boolean protectMedia,
               Set<String> managed, Map<String,Integer> appModes) {
            this(mode, lockDelay, leaveDelay, protectMedia, false, managed, appModes, Map.of());
        }
        Policy(int mode, long lockDelay, long leaveDelay, boolean protectMedia, boolean protectRecents,
               Set<String> managed, Map<String,Integer> appModes) {
            this(mode, lockDelay, leaveDelay, protectMedia, protectRecents, managed, appModes, Map.of());
        }
        int modeFor(String pkg) { return appModes.getOrDefault(pkg, mode); }
        int modeFor(String pkg, String sourceTab) {
            // Entry metadata from old clients/snapshots never overrides the current owner rule.
            return modeFor(pkg);
        }
    }
    interface Host {
        long now(); Observation observe(Session session) throws Exception; void freeze(Session session) throws Exception;
        void persist(AutomationEngine engine) throws Exception;
        void schedule(Target target, long delay, Runnable runnable); void cancel(Target target);
        default boolean hasRecentTask(Session session) throws Exception { return false; }
        default void diagnostic(String message) { }
    }
    final Map<Target,Session> sessions = new LinkedHashMap<>();
    final Map<Target,Receipt> receipts = new LinkedHashMap<>();
    final Map<Target,OwnedFreeze> ownedFreezes = new LinkedHashMap<>();
    final Map<Target,Long> deadlines = new HashMap<>();
    Policy policy = new Policy(0, 3000, 10000, true, Set.of(), Map.of());
    long revision; boolean healthy = true; String problem = "";
    private final Host host;
    AutomationEngine(Host host) { this.host = host; }
    void setPolicy(Policy p) throws Exception {
        requireHealthy();
        if (policy.equals(p)) { requireCancellationComplete(); return; }
        Policy before = policy; policy = p; revision++;
        for (Session s : new ArrayList<>(sessions.values())) {
            int oldMode = before.modeFor(s.target.packageName), newMode = p.modeFor(s.target.packageName);
            boolean lockChanged = ((oldMode ^ newMode) & 1) != 0 || ((newMode & 1) != 0 && before.lockDelay != p.lockDelay);
            boolean changed = oldMode != newMode || lockChanged
                    || before.protectMedia != p.protectMedia || before.protectRecents != p.protectRecents;
            if (changed && s.phase == Phase.ACTIVE) {
                if (lockChanged) s.lockDeadline = -1;
                if ((newMode & 4) == 0) s.cardPending = false;
                clearTimer(s.target); s.generation = revision;
            }
            boolean allowanceRevoked = before.highRiskAllowed.contains(s.target.packageName)
                    && !p.highRiskAllowed.contains(s.target.packageName);
            if (!p.managed.contains(s.target.packageName) || newMode == 0 || allowanceRevoked) {
                clearTimer(s.target);
                if (s.phase == Phase.ACTIVE) sessions.remove(s.target);
                else s.cancelled = true;
            }
        }
        persist(); onEvent();
        requireCancellationComplete();
    }
    private void requireCancellationComplete() {
        if (sessions.values().stream().anyMatch(s -> s.cancelled && s.phase != Phase.ACTIVE))
            throw new IllegalStateException("configuration persisted; cancellation awaits the previous backend lease terminal receipt");
    }
    boolean follow(Target t, int uid, String digest, int original) throws Exception {
        return followAll(List.of(new FollowCandidate(t, uid, digest, original))) != 0;
    }
    int followAll(Collection<FollowCandidate> candidates) throws Exception {
        requireHealthy();
        List<Session> prepared = new ArrayList<>(); Set<Target> targets = new HashSet<>();
        for (FollowCandidate candidate : candidates) {
            if (!targets.add(candidate.target)) continue;
            Session s = prepareFollow(candidate);
            if (s != null) prepared.add(s);
        }
        if (prepared.isEmpty()) return 0;
        for (Session s : prepared) {
            s.lease = ++revision; s.generation = revision;
            // A confirmed external enable ends an older freeze's restore ownership.
            ownedFreezes.remove(s.target); sessions.put(s.target, s);
        }
        persist();
        for (Session s : prepared) diagnose(s, "rule_followed mode=" + policy.modeFor(s.target.packageName));
        return prepared.size();
    }
    private Session prepareFollow(FollowCandidate candidate) throws Exception {
        requireHealthy();
        Target t = candidate.target; int uid = candidate.uid, original = candidate.original; String digest = candidate.digest;
        if (sessions.containsKey(t) || !policy.managed.contains(t.packageName) || policy.modeFor(t.packageName) == 0
                || (original != 0 && original != 1)) return null;
        if (uid < 0 || uid / 100000 != t.userId || (uid % 100000 < 10000
                && !HighRiskPolicy.isAllowed(policy.highRiskAllowed, t.packageName)) || digest == null || digest.isEmpty())
            throw new IllegalArgumentException("invalid followed installation identity");
        Session s = new Session(); s.target = t; s.uid = uid; s.digest = digest; s.original = original;
        s.sourceTab = ""; s.requestId = "follow:" + UUID.randomUUID();
        s.phase = Phase.ACTIVE; s.temporary = true; s.followed = true;
        s.firstVisible = true; s.launchExpected = false; s.activeSince = host.now();
        Observation current = host.observe(s);
        return current.identityMatches && current.setting == original ? s : null;
    }
    Integer ownedBaseline(Target t, int uid, String digest) throws Exception {
        requireHealthy(); OwnedFreeze owned = ownedFreezes.get(t);
        if (owned == null) return null;
        if (owned.uid != uid || !owned.digest.equals(digest)) {
            ownedFreezes.remove(t); persist(); return null;
        }
        return owned.confirmed ? owned.original : null;
    }
    void reconcileOwnedFreeze(Target t, int uid, String digest, int setting) throws Exception {
        requireHealthy(); OwnedFreeze owned = ownedFreezes.get(t);
        if (owned != null && (owned.uid != uid || !owned.digest.equals(digest)
                || (setting >= 0 && setting <= 4 && setting != 3))) {
            ownedFreezes.remove(t); persist();
        }
    }
    long acquire(Target t, String id, int uid, String digest, int original, boolean temporary) throws Exception {
        return acquire(t, id, uid, digest, original, temporary, true);
    }
    long acquire(Target t, String id, int uid, String digest, int original, boolean temporary,
                 boolean launchExpected) throws Exception {
        return acquire(t, id, uid, digest, original, temporary, launchExpected, "");
    }
    long acquire(Target t, String id, int uid, String digest, int original, boolean temporary,
                 boolean launchExpected, String sourceTab) throws Exception {
        requireHealthy();
        if (!validEntry(sourceTab)) throw new IllegalArgumentException("invalid entry metadata");
        Receipt receipt = receipts.get(t);
        if (receipt != null && receipt.requestId.equals(id)) throw new IllegalStateException("request already completed");
        Session prior = sessions.get(t);
        if (prior != null && prior.phase != Phase.ACTIVE) {
            if (prior.phase == Phase.ENABLING && prior.requestId.equals(id) && prior.uid == uid && prior.digest.equals(digest)
                    && prior.temporary == temporary && prior.launchExpected == launchExpected
                    && prior.sourceTab.equals(sourceTab) && !prior.cancelled) return prior.lease;
            throw new IllegalStateException("manual lease is unresolved; verify the previous backend first");
        }
        if (temporary && (!policy.managed.contains(t.packageName) || policy.modeFor(t.packageName) == 0))
            throw new IllegalStateException("target has no active automatic rule");
        if (original != 0 && original != 1) throw new IllegalArgumentException("original setting must be DEFAULT or ENABLED");
        clearTimer(t);
        Session s = new Session(); s.target = t; s.requestId = id; s.uid = uid; s.digest = digest;
        s.sourceTab = sourceTab;
        s.original = original; s.lease = ++revision; s.generation = revision;
        s.phase = Phase.ENABLING; s.temporary = temporary; s.launchExpected = launchExpected; sessions.put(t, s);
        persist();
        diagnose(s, "lease_begin temporary=" + temporary + " mode=" + policy.modeFor(t.packageName));
        return s.lease;
    }
    void finish(Target t, String id, long lease, boolean success, boolean unknown, int setting) throws Exception {
        requireHealthy();
        Receipt receipt = receipts.get(t);
        if (receipt != null && receipt.lease == lease && receipt.requestId.equals(id)) return;
        Session s = requireLease(t, id, lease);
        if (s.phase == Phase.ACTIVE) return;
        if (unknown) {
            s.phase = Phase.UNRESOLVED; problem = "backend result unknown; exclusive lease retained"; persist(); return;
        }
        Observation actual = host.observe(s);
        if (!actual.identityMatches || actual.setting != setting) {
            s.phase = Phase.UNRESOLVED; persist();
            throw new IllegalStateException("identity or actual PackageManager state differs from backend receipt");
        }
        if (setting == 0 || setting == 1) ownedFreezes.remove(t);
        if (success && s.temporary && !s.cancelled && (setting == 0 || setting == 1)
                && policy.managed.contains(t.packageName) && policy.modeFor(t.packageName) != 0) {
            s.phase = Phase.ACTIVE; s.activeSince = host.now(); s.firstVisible = !s.launchExpected;
        } else { sessions.remove(t); complete(s); }
        persist();
        diagnose(s, "lease_finish success=" + success + " setting=" + setting + " phase=" + s.phase);
        onEvent();
    }
    void abortLaunch(Target t, String id, long lease) throws Exception {
        requireHealthy(); Session s = requireLease(t, id, lease);
        if (s.phase != Phase.ACTIVE || !s.temporary || !s.launchExpected)
            throw new IllegalStateException("no confirmed launch session; lease retained");
        s.activeSince = host.now() - 15000; s.firstVisible = false; clearTimer(t); onEvent();
    }
    private Session requireLease(Target t, String id, long lease) {
        Session s = sessions.get(t);
        if (s == null || s.lease != lease || !s.requestId.equals(id)) throw new IllegalStateException("stale manual lease");
        return s;
    }
    void checkFreezeLease(Target t, String id, long lease, int uid, String digest) {
        requireHealthy(); Session s = requireLease(t, id, lease);
        if (s.phase != Phase.ENABLING || s.temporary || s.cancelled)
            throw new IllegalStateException("freeze requires a pending manual lease");
        if (s.uid != uid || !s.digest.equals(digest)) throw new SecurityException("manual lease installation identity changed");
    }
    void onEvent() {
        if (!healthy) return;
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.phase != Phase.ACTIVE) continue;
            try { evaluate(s); }
            catch (Exception e) { problem = e.getClass().getSimpleName() + ": " + e.getMessage(); diagnose(s, "rule_error " + problem); clearTimer(s.target); }
        }
    }
    void onTaskRemoved(Target target) {
        Session s = sessions.get(target);
        if (!healthy || s == null || s.phase != Phase.ACTIVE || (policy.modeFor(target.packageName) & 4) == 0) {
            host.diagnostic("card_ignored package=" + target.packageName + " user=" + target.userId
                    + " reason=" + (!healthy ? "unhealthy" : s == null ? "no_active_session" : "rule_or_lease"));
            return;
        }
        diagnose(s, "card_removed");
        s.cardPending = true;
        try {
            // Removing one of several cards must not arm a later unrelated disappearance.
            if (host.hasRecentTask(s)) { s.cardPending = false; return; }
        } catch (Exception e) { problem = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        try { evaluate(s); }
        catch (Exception e) { problem = e.getClass().getSimpleName() + ": " + e.getMessage(); clearTimer(target); }
    }
    private void evaluate(Session s) throws Exception {
        Observation o = host.observe(s);
        if (!o.identityMatches || o.setting != s.original) {
            clearTimer(s.target); sessions.remove(s.target); persist(); return;
        }
        // A protected app can still have launched successfully; remember its real window.
        if (o.visibilityKnown && o.visible && !s.firstVisible) { s.firstVisible = true; clearTimer(s.target); persist(); }
        if (!o.visibilityKnown || !o.protectionKnown || o.protectedNow) {
            diagnose(s, "rule_wait visibilityKnown=" + o.visibilityKnown + " protectionKnown=" + o.protectionKnown
                    + " protected=" + o.protectedNow);
            s.lockDeadline = -1; clearTimer(s.target); return;
        }
        diagnose(s, "rule_observed visible=" + o.visible + " lockKnown=" + o.lockKnown + " locked=" + o.locked);
        int mode = policy.modeFor(s.target.packageName);
        boolean card = cardTriggered(s, o, mode);
        if (!s.firstVisible) {
            if (card) { freeze(s); return; }
            long remaining = 15000 - (host.now() - s.activeSince);
            if (remaining > 0) { arm(s, remaining); return; }
            freeze(s); return;
        }
        updateClocks(s, o, mode);
        long next = s.lockDeadline < 0 ? Long.MAX_VALUE : s.lockDeadline;
        if (card || next <= host.now()) { freeze(s); return; }
        if (next == Long.MAX_VALUE) clearTimer(s.target);
        else arm(s, next - host.now());
    }
    private void updateClocks(Session s, Observation o, int mode) {
        if ((mode & 1) != 0 && o.lockKnown && o.locked) {
            if (s.lockDeadline < 0) s.lockDeadline = host.now() + policy.lockDelay;
        } else s.lockDeadline = -1;
    }
    private boolean cardTriggered(Session s, Observation o, int mode) {
        if ((mode & 4) == 0 || !s.cardPending) return false;
        try {
            if (host.hasRecentTask(s)) { s.cardPending = false; return false; }
        } catch (Exception e) {
            // CARD is unavailable; independent triggers still use the observation's protection gate.
            problem = e.getClass().getSimpleName() + ": " + e.getMessage(); return false;
        }
        return !o.visible;
    }
    private void arm(Session s, long delay) {
        long deadline = host.now() + delay;
        if (Objects.equals(deadlines.get(s.target), deadline)) return;
        clearTimer(s.target); deadlines.put(s.target, deadline);
        long generation = s.generation;
        host.schedule(s.target, delay, () -> {
            Session current = sessions.get(s.target);
            if (current == s && current.generation == generation && healthy) {
                try { evaluate(current); }
                catch (Exception e) { problem = e.getClass().getSimpleName() + ": " + e.getMessage(); diagnose(s, "rule_error " + problem); clearTimer(s.target); }
            }
        });
    }
    private void freeze(Session s) throws Exception {
        // Persist the in-flight marker before a PM write. A crash cannot revive an ACTIVE timer.
        if (!ownedFreezes.containsKey(s.target) && ownedFreezes.size() >= 500)
            throw new IllegalStateException("automatic recovery capacity reached; restore saved applications first");
        ownedFreezes.put(s.target, new OwnedFreeze(s.uid, s.digest, s.original, false));
        s.phase = Phase.UNRESOLVED; persist(); clearTimer(s.target);
        // The atomic fsync may block; re-read events after it, immediately before the PM call.
        Observation current;
        try { current = host.observe(s); }
        catch (Exception e) { ownedFreezes.remove(s.target); s.phase = Phase.ACTIVE; persist(); throw e; }
        if (!current.identityMatches || current.setting != s.original) {
            ownedFreezes.remove(s.target); sessions.remove(s.target); persist(); return;
        }
        int mode = policy.modeFor(s.target.packageName);
        boolean card = cardTriggered(s, current, mode);
        boolean stillTriggered = card || (!s.firstVisible ? !current.visible && host.now() - s.activeSince >= 15000
                : ((mode & 1) != 0 && current.lockKnown && current.locked && s.lockDeadline >= 0 && s.lockDeadline <= host.now()));
        if (!current.visibilityKnown || !current.protectionKnown || current.protectedNow
                || !stillTriggered) {
            if (current.visibilityKnown && current.visible) s.firstVisible = true;
            updateClocks(s, current, mode);
            if (!current.visibilityKnown || !current.protectionKnown || current.protectedNow) {
                s.lockDeadline = -1;
            }
            ownedFreezes.remove(s.target); s.phase = Phase.ACTIVE; persist();
            if (current.visibilityKnown && current.protectionKnown && !current.protectedNow) {
                long next = s.lockDeadline < 0 ? Long.MAX_VALUE : s.lockDeadline;
                if (next != Long.MAX_VALUE && next > host.now()) arm(s, next - host.now());
            }
            return;
        }
        host.freeze(s);
        Observation actual = host.observe(s);
        if (!actual.identityMatches || actual.setting != 3)
            throw new IllegalStateException("PackageManager did not confirm DISABLED_USER");
        ownedFreezes.put(s.target, new OwnedFreeze(s.uid, s.digest, s.original, true));
        sessions.remove(s.target); persist(); diagnose(s, "rule_frozen setting=3");
    }
    private void diagnose(Session s, String value) {
        if (value.equals(s.lastDiagnostic)) return;
        s.lastDiagnostic = value;
        host.diagnostic(value + " package=" + s.target.packageName + " user=" + s.target.userId + " source=" + s.sourceTab);
    }
    private void complete(Session s) {
        receipts.remove(s.target); receipts.put(s.target, new Receipt(s.requestId, s.lease));
        while (receipts.size() > 500) receipts.remove(receipts.keySet().iterator().next());
    }
    private void clearTimer(Target target) { deadlines.remove(target); host.cancel(target); }
    void prepareSelfFreeze() throws Exception {
        requireHealthy();
        if (sessions.values().stream().anyMatch(s -> s.phase != Phase.ACTIVE))
            throw new IllegalStateException("manual operation still in flight");
        persist();
    }
    private void persist() throws Exception {
        try { host.persist(this); }
        catch (Exception e) {
            healthy = false; problem = "Atomic snapshot failed: " + e.getMessage();
            for (Target t : new ArrayList<>(deadlines.keySet())) clearTimer(t);
            throw e;
        }
    }
    private void requireHealthy() {
        if (!healthy) throw new IllegalStateException(problem);
    }
    private static boolean validEntry(String value) {
        if (value == null) return false;
        if (value.isEmpty() || Set.of("builtin:all", "builtin:managed", "builtin:frozen", "builtin:available").contains(value)) return true;
        if (value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }
}
