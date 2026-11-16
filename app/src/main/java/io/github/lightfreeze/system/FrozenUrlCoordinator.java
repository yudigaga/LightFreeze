package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.automation.FrozenUrlSnapshot;
import io.github.yudigaga.lightfreeze.automation.UrlAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Confined to the system worker; no bearer URI is written to persistent state. */
final class FrozenUrlCoordinator {
    interface Host {
        int moduleSetting(int user) throws Exception;
        void enable(int user, int original) throws Exception;
        void disable(int user) throws Exception;
        void prepareFreeze() throws Exception;
        void persist(String json) throws Exception;
        boolean post(Runnable job, long delayMs);
        void launch(String ticket, String uri, int user) throws Exception;
        void log(String problem);
    }
    static final long TIMEOUT_MS = 10 * 60 * 1000L;
    static final class State {
        final FrozenUrlSnapshot authority;
        final String owner, epoch, phase;
        final boolean armed, wakeCheckpoint;
        final int userId, original;
        State(FrozenUrlSnapshot authority, String owner, String epoch, boolean armed,
              int userId, int original, String phase, boolean checkpoint) {
            this.authority = authority; this.owner = owner; this.epoch = epoch; this.armed = armed;
            this.userId = userId; this.original = original; this.phase = phase; wakeCheckpoint = checkpoint;
        }
        static State empty(String owner) { return new State(null, owner, nonce(), false, -1, 0, "idle", false); }
        State status(String phase, boolean armed, boolean checkpoint, boolean newEpoch) {
            return new State(authority, owner, newEpoch ? nonce() : epoch, armed, userId, original, phase, checkpoint);
        }
        String encode() {
            try {
                return new JSONObject().put("version", 1).put("owner", owner).put("epoch", epoch)
                        .put("armed", armed).put("userId", userId).put("original", original).put("phase", phase)
                        .put("wakeCheckpoint", wakeCheckpoint).put("authority", authority == null
                                ? JSONObject.NULL : new JSONObject(authority.encode())).toString();
            } catch (Exception e) { throw new IllegalStateException("cannot encode frozen URL state", e); }
        }
        static State decode(String json) {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                throw new IllegalArgumentException("frozen URL state too large");
            try {
                JSONObject o = new JSONObject(json);
                if (o.length() != 9 || !Integer.valueOf(1).equals(o.get("version"))
                        || !(o.get("owner") instanceof String) || !(o.get("epoch") instanceof String)
                        || !(o.get("armed") instanceof Boolean) || !(o.get("wakeCheckpoint") instanceof Boolean)
                        || !(o.get("userId") instanceof Integer) || !(o.get("original") instanceof Integer)
                        || !(o.get("phase") instanceof String)) throw new IllegalArgumentException("invalid frozen URL state");
                String owner = o.getString("owner"), epoch = o.getString("epoch"), phase = o.getString("phase");
                int user = o.getInt("userId"), original = o.getInt("original");
                if (owner.isEmpty() || owner.length() > 1024 || !UUID.fromString(epoch).toString().equals(epoch)
                        || user < -1 || user > 21474 || (original != 0 && original != 1)
                        || !Set.of("idle", "arming", "frozen", "waking", "running", "refreeze", "uncertain").contains(phase))
                    throw new IllegalArgumentException("invalid frozen URL state values");
                FrozenUrlSnapshot authority = o.isNull("authority") ? null : FrozenUrlSnapshot.decode(o.getJSONObject("authority").toString());
                if ((authority != null && authority.userId != user) || (o.getBoolean("armed") && authority == null))
                    throw new IllegalArgumentException("invalid frozen URL authority binding");
                return new State(authority, owner, epoch, o.getBoolean("armed"), user, original, phase, o.getBoolean("wakeCheckpoint"));
            } catch (Exception e) { throw new IllegalArgumentException("invalid frozen URL state", e); }
        }
    }
    private static final class Delivery {
        final String epoch, digest;
        boolean claimed;
        Delivery(String epoch, String digest) { this.epoch = epoch; this.digest = digest; }
    }
    private final Host host;
    private final Map<String, Delivery> deliveries = new HashMap<>();
    private State state = State.empty("uninitialized");
    private boolean healthy = true;
    private long jobGeneration;
    private String problem = "";
    FrozenUrlCoordinator(Host host) { this.host = host; }
    FrozenUrlSnapshot authority() { return state.authority; }
    String ownerBinding() { return state.owner; }
    String epoch() { return state.epoch; }
    String digest() { return state.authority == null ? "" : state.authority.digest(); }
    String phase() { return state.phase; }
    String problem() { return problem; }
    boolean healthy() { return healthy; }
    boolean armed() { return healthy && state.armed; }
    int userId() { return state.userId; }
    int activeCount() { return deliveries.size(); }
    void restore(String json, String ownerBinding) throws Exception {
        requireOwner(ownerBinding); deliveries.clear(); jobGeneration++;
        State loaded = json == null ? State.empty(ownerBinding) : State.decode(json);
        if (!ownerBinding.equals(loaded.owner)) loaded = State.empty(ownerBinding);
        if (loaded.armed && (loaded.wakeCheckpoint || !"frozen".equals(loaded.phase)
                || host.moduleSetting(loaded.userId) != 3)) {
            loaded = loaded.status("uncertain", false, false, true);
            problem = "previous frozen URL wake did not finish; module kept enabled";
        }
        commit(loaded);
    }
    void sync(FrozenUrlSnapshot snapshot, String ownerBinding) throws Exception {
        requireHealthy(); requireOwner(ownerBinding);
        if (armed()) throw new IllegalStateException("keep module enabled before changing frozen URL authority");
        if (snapshot == null) throw new IllegalArgumentException("missing frozen URL authority");
        commit(new State(snapshot, ownerBinding, nonce(), false, snapshot.userId, 0, "idle", false));
        jobGeneration++; problem = "";
    }
    void arm(int user, String expectedDigest, String expectedEpoch, boolean configurationConfirmed,
             boolean recoveryConfirmed) throws Exception {
        requireHealthy();
        if (!configurationConfirmed || !recoveryConfirmed) throw new SecurityException("explicit configuration and recovery confirmation required");
        if (state.authority == null || user != state.userId || !digest().equals(expectedDigest) || !epoch().equals(expectedEpoch))
            throw new IllegalStateException("frozen URL authority changed; sync it before self-freeze");
        int original = host.moduleSetting(user);
        if (original != 0 && original != 1) throw new IllegalStateException("module is not available for self-freeze");
        if (deliveries.isEmpty()) host.prepareFreeze();
        commit(new State(state.authority, state.owner, state.epoch, true, user, original, "arming", !deliveries.isEmpty()));
        problem = ""; if (deliveries.isEmpty()) scheduleFreeze();
    }
    void keepEnabled() throws Exception {
        jobGeneration++;
        commit(state.status("idle", false, false, true));
        deliveries.entrySet().removeIf(e -> !e.getValue().claimed);
        problem = "";
    }
    boolean route(String uri, int user) throws Exception {
        requireHealthy();
        UrlAction action;
        try { action = UrlAction.parse(uri); } catch (IllegalArgumentException e) { return false; }
        if (!armed() || state.authority == null || !state.authority.accepts(action, user)) return false;
        if (deliveries.size() >= 64) throw new IllegalStateException("too many frozen URL deliveries");
        int current = host.moduleSetting(user);
        if (current != 3 && (current != state.original || "frozen".equals(state.phase))) {
            keepEnabled(); return false;
        }
        jobGeneration++;
        commit(state.status("waking", true, true, false));
        String ticket = nonce(); deliveries.put(ticket, new Delivery(epoch(), FrozenUrlSnapshot.actionDigest(action)));
        try {
            if (current == 3) {
                host.enable(user, state.original);
                if (host.moduleSetting(user) != state.original) throw new IllegalStateException("module enable was not confirmed");
            }
            host.launch(ticket, uri, user);
            String deliveryEpoch = epoch();
            if (!host.post(() -> timeout(ticket, deliveryEpoch), TIMEOUT_MS))
                uncertain("frozen URL completion timer unavailable; module kept enabled");
        } catch (Exception e) {
            Delivery pending = deliveries.get(ticket);
            if (pending != null && !pending.claimed) {
                deliveries.remove(ticket);
                problem = "frozen URL launch failed: " + e.getClass().getSimpleName(); host.log(problem);
                if (deliveries.isEmpty() && host.moduleSetting(user) == state.original) scheduleFreeze();
                else if (host.moduleSetting(user) != state.original) uncertain(problem);
            } else uncertain("frozen URL launch result unknown; module kept enabled");
        }
        return true;
    }
    boolean begin(String ticket, String actionDigest) throws Exception {
        requireHealthy(); Delivery delivery = deliveries.get(ticket);
        if (!armed() || delivery == null || delivery.claimed || !delivery.epoch.equals(epoch())
                || !delivery.digest.equals(actionDigest)) return false;
        commit(state.status("running", true, true, false)); delivery.claimed = true; return true;
    }
    void end(String ticket) throws Exception {
        requireHealthy(); Delivery delivery = deliveries.get(ticket);
        if (delivery == null || !delivery.claimed) return;
        deliveries.remove(ticket);
        if (armed() && deliveries.isEmpty()) {
            commit(state.status("refreeze", true, true, false)); scheduleFreeze();
        }
    }
    private void scheduleFreeze() throws Exception {
        long generation = ++jobGeneration; String epoch = epoch();
        if (!host.post(() -> {
            if (!healthy || generation != jobGeneration || !epoch.equals(epoch()) || !armed() || !deliveries.isEmpty()) return;
            try {
                host.prepareFreeze();
                if (host.moduleSetting(state.userId) != state.original) { uncertain("module state changed outside frozen URL lease"); return; }
                // Restore accepts this checkpoint only when PM also confirms state 3.
                commit(state.status("frozen", true, false, false));
                host.disable(state.userId);
                if (host.moduleSetting(state.userId) != 3) throw new IllegalStateException("module disable was not confirmed");
            } catch (Exception e) { uncertain("self-freeze not confirmed: " + e.getClass().getSimpleName()); }
        }, 500)) uncertain("self-freeze worker unavailable; module kept enabled");
    }
    private void timeout(String ticket, String epoch) {
        Delivery d = deliveries.get(ticket);
        if (d != null && d.epoch.equals(epoch)) uncertain("frozen URL completion was not confirmed; module kept enabled");
    }
    private void uncertain(String message) {
        jobGeneration++; deliveries.clear(); problem = message; host.log(message);
        try { commit(state.status("uncertain", false, false, true)); }
        catch (Exception ignored) { /* No disable is attempted after a persistence failure. */ }
    }
    private void commit(State next) throws Exception {
        try { host.persist(next.encode()); state = next; healthy = true; }
        catch (Exception e) { healthy = false; jobGeneration++; problem = "frozen URL state persistence failed"; host.log(problem); throw e; }
    }
    private void requireHealthy() { if (!healthy) throw new IllegalStateException(problem); }
    private static void requireOwner(String owner) {
        if (owner == null || owner.isEmpty() || owner.length() > 1024) throw new IllegalArgumentException("invalid module owner binding");
    }
    private static String nonce() { return UUID.randomUUID().toString(); }
}
