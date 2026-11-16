package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.core.FreezeTriggers;
import io.github.yudigaga.lightfreeze.core.HighRiskPolicy;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** System UID owns one private, atomic snapshot. No application-private path is opened. */
final class SystemStateStore {
    private static final int LIMIT = 512 * 1024;
    private final AtomicFile file;
    SystemStateStore() throws Exception {
        File directory = new File("/data/system/lightfreeze-yudigaga");
        if (!directory.exists() && !directory.mkdir()) throw new IOException("cannot create /data/system/lightfreeze-yudigaga (check SELinux)");
        StructStat stat = Os.lstat(directory.getAbsolutePath());
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 1000
                || !directory.getAbsolutePath().equals(directory.getCanonicalPath()))
            throw new SecurityException("unsafe system snapshot directory");
        Os.chmod(directory.getAbsolutePath(), 0700);
        File state = new File(directory, "state.json");
        if (state.exists()) {
            StructStat saved = Os.lstat(state.getAbsolutePath());
            if (!OsConstants.S_ISREG(saved.st_mode) || saved.st_uid != 1000)
                throw new SecurityException("unsafe system snapshot file");
        }
        file = new AtomicFile(state);
    }
    void load(AutomationEngine engine, long now) throws Exception {
        byte[] bytes;
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > LIMIT) throw new IOException("system snapshot exceeds limit");
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        } catch (FileNotFoundException e) { return; }
        restore(engine, new JSONObject(new String(bytes, StandardCharsets.UTF_8)), now);
    }
    static void restore(AutomationEngine engine, JSONObject root, long now) throws Exception {
        if (root.getInt("schema") != 1) throw new IOException("unsupported system snapshot schema");
        engine.policy = parsePolicy(root.getJSONObject("config"));
        engine.revision = root.getLong("revision");
        if (root.has("ownedFreezes") && !(root.get("ownedFreezes") instanceof JSONArray))
            throw new IOException("invalid automatic recovery collection");
        JSONArray frozen = root.optJSONArray("ownedFreezes");
        if (frozen != null) {
            if (frozen.length() > 500) throw new IOException("too many automatic recovery records");
            for (int i = 0; i < frozen.length(); i++) {
                JSONObject item = frozen.getJSONObject(i); String pkg = item.getString("packageName"); validatePackage(pkg);
                int user = recoveryInt(item, "userId"), uid = recoveryInt(item, "uid"), original = recoveryInt(item, "original");
                String digest = item.getString("digest");
                if (user < 0 || uid < 0 || uid / 100000 != user
                        || (original != 0 && original != 1) || digest.isEmpty() || digest.length() > 256
                        || !(item.get("confirmed") instanceof Boolean))
                    throw new IOException("invalid automatic recovery record");
                AutomationEngine.Target t = new AutomationEngine.Target(pkg, user);
                if (engine.ownedFreezes.put(t, new AutomationEngine.OwnedFreeze(uid, digest, original,
                        item.getBoolean("confirmed"))) != null) throw new IOException("duplicate automatic recovery record");
            }
        }
        JSONArray receipts = root.optJSONArray("receipts");
        if (receipts != null) {
            if (receipts.length() > 500) throw new IOException("too many saved receipts");
            for (int i = 0; i < receipts.length(); i++) {
                JSONObject r = receipts.getJSONObject(i); String pkg = r.getString("packageName"); validatePackage(pkg);
                engine.receipts.put(new AutomationEngine.Target(pkg, r.getInt("userId")),
                        new AutomationEngine.Receipt(r.getString("requestId"), r.getLong("lease")));
            }
        }
        JSONArray array = root.getJSONArray("sessions");
        if (array.length() > 500) throw new IOException("too many system sessions");
        for (int i = 0; i < array.length(); i++) {
            AutomationEngine.Session s = parseSession(array.getJSONObject(i), now);
            if (!engine.policy.managed().contains(s.target.packageName())
                    || engine.policy.modeFor(s.target.packageName()) == 0 || s.cancelled
                    || (s.uid % 100000 < 10000
                    && !HighRiskPolicy.isAllowed(engine.policy.highRiskAllowed(), s.target.packageName()))) {
                if (s.phase == AutomationEngine.Phase.ACTIVE) continue;
                s.cancelled = true;
            }
            if (engine.sessions.put(s.target, s) != null) throw new IOException("duplicate saved session");
        }
    }
    private static int recoveryInt(JSONObject item, String key) throws JSONException, IOException {
        Object value = item.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IOException("invalid automatic recovery integer");
        return number.intValue();
    }
    void save(AutomationEngine engine) throws Exception {
        byte[] bytes = snapshot(engine).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("system snapshot exceeds limit");
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(bytes); file.finishWrite(out);
            Os.chmod(file.getBaseFile().getAbsolutePath(), 0600);
        } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    static JSONObject snapshot(AutomationEngine engine) throws JSONException {
        if (engine.ownedFreezes.size() > 500) throw new IllegalStateException("automatic recovery record limit exceeded");
        JSONObject root = new JSONObject().put("schema", 1).put("revision", engine.revision)
                .put("config", policyJson(engine.policy));
        JSONArray array = new JSONArray();
        for (AutomationEngine.Session s : engine.sessions.values()) array.put(sessionJson(s));
        JSONArray receipts = new JSONArray();
        for (Map.Entry<AutomationEngine.Target,AutomationEngine.Receipt> r : engine.receipts.entrySet())
            receipts.put(new JSONObject().put("packageName", r.getKey().packageName()).put("userId", r.getKey().userId())
                    .put("requestId", r.getValue().requestId()).put("lease", r.getValue().lease()));
        JSONArray frozen = new JSONArray();
        for (Map.Entry<AutomationEngine.Target,AutomationEngine.OwnedFreeze> entry : engine.ownedFreezes.entrySet()) {
            AutomationEngine.OwnedFreeze owned = entry.getValue();
            frozen.put(new JSONObject().put("packageName", entry.getKey().packageName()).put("userId", entry.getKey().userId())
                    .put("uid", owned.uid()).put("digest", owned.digest()).put("original", owned.original())
                    .put("confirmed", owned.confirmed()));
        }
        return root.put("sessions", array).put("receipts", receipts).put("ownedFreezes", frozen);
    }
    static AutomationEngine.Policy parsePolicy(JSONObject json) throws JSONException {
        if (json.getInt("version") != 1) throw new JSONException("unsupported config version");
        int mode = FreezeTriggers.normalizeLegacy(json.getInt("mode"));
        long lock = delay(json.getLong("lockDelayMs")), leave = delay(json.getLong("leaveDelayMs"));
        JSONArray managedArray = json.getJSONArray("managed");
        if (managedArray.length() > 500) throw new JSONException("managed package limit is 500");
        Set<String> managed = new LinkedHashSet<>();
        for (int i = 0; i < managedArray.length(); i++) {
            String pkg = managedArray.getString(i); validatePackage(pkg); managed.add(pkg);
        }
        JSONObject appModes = json.getJSONObject("appModes"); Map<String,Integer> modes = new HashMap<>();
        Iterator<String> names = appModes.keys();
        while (names.hasNext()) {
            String pkg = names.next(); validatePackage(pkg); int value = appModes.getInt(pkg);
            if (value == -1) continue;
            value = FreezeTriggers.normalizeLegacy(value); if (managed.contains(pkg)) modes.put(pkg, value);
        }
        Map<String,Integer> tabModes = new HashMap<>();
        if (json.has("tabModes")) {
            JSONObject tabs = json.getJSONObject("tabModes");
            if (tabs.length() > 104) throw new IllegalArgumentException("tab rule limit is 104");
            Iterator<String> ids = tabs.keys();
            while (ids.hasNext()) {
                String id = ids.next(); validateSourceTab(id);
                int value = FreezeTriggers.normalizeLegacy(tabs.getInt(id)); tabModes.put(id, value);
            }
        }
        Set<String> highRiskAllowed = new LinkedHashSet<>();
        if (json.has("highRiskAllowed")) {
            JSONArray allowed = json.getJSONArray("highRiskAllowed");
            if (allowed.length() > 500) throw new IllegalArgumentException("high-risk allowance limit is 500");
            for (int i = 0; i < allowed.length(); i++) {
                Object item = allowed.get(i);
                if (!(item instanceof String) || !highRiskAllowed.add((String) item))
                    throw new IllegalArgumentException("invalid or duplicate high-risk package");
            }
        }
        return new AutomationEngine.Policy(mode, lock, leave, json.getBoolean("protectMedia"),
                json.optBoolean("protectRecents", false), Set.copyOf(managed), Map.copyOf(modes), Map.copyOf(tabModes),
                HighRiskPolicy.checked(highRiskAllowed));
    }
    private static long delay(long value) {
        if (value != 0 && value != 3000 && value != 10000 && value != 30000) throw new IllegalArgumentException("unsupported delay");
        return value;
    }
    static void validatePackage(String value) {
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("invalid package name");
    }
    static void validateSourceTab(String value) {
        if ("builtin:all".equals(value) || "builtin:managed".equals(value)
                || "builtin:frozen".equals(value) || "builtin:available".equals(value)) return;
        if (value != null && value.length() == 36) try {
            if (UUID.fromString(value).toString().equals(value)) return;
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("invalid source tab");
    }
    static JSONObject sessionJson(AutomationEngine.Session s) throws JSONException {
        return new JSONObject().put("packageName", s.target.packageName()).put("userId", s.target.userId())
                .put("uid", s.uid).put("digest", s.digest).put("original", s.original)
                .put("requestId", s.requestId).put("lease", s.lease).put("generation", s.generation)
                .put("phase", s.phase.name()).put("temporary", s.temporary).put("cancelled", s.cancelled)
                .put("launchExpected", s.launchExpected).put("sourceTab", s.sourceTab).put("firstVisible", s.firstVisible)
                .put("followed", s.followed);
    }
    static AutomationEngine.Session parseSession(JSONObject item, long now) throws JSONException {
        AutomationEngine.Session s = new AutomationEngine.Session();
        String pkg = item.getString("packageName"); validatePackage(pkg);
        int user = item.getInt("userId"); if (user < 0) throw new IllegalArgumentException("negative saved user");
        s.target = new AutomationEngine.Target(pkg, user); s.requestId = item.getString("requestId");
        s.digest = item.getString("digest"); s.uid = item.getInt("uid"); s.original = item.getInt("original");
        if (s.uid < 0 || s.uid / 100000 != user) throw new IllegalArgumentException("invalid saved UID");
        if (s.original != 0 && s.original != 1) throw new IllegalArgumentException("invalid saved original state");
        s.lease = item.getLong("lease"); s.generation = item.getLong("generation");
        s.phase = AutomationEngine.Phase.valueOf(item.getString("phase"));
        s.temporary = item.getBoolean("temporary"); s.cancelled = item.getBoolean("cancelled");
        s.launchExpected = item.optBoolean("launchExpected", true);
        if (item.has("sourceTab")) {
            Object source = item.get("sourceTab");
            if (!(source instanceof String)) throw new JSONException("invalid saved source tab");
            s.sourceTab = (String) source;
            if (!s.sourceTab.isEmpty()) validateSourceTab(s.sourceTab);
        }
        // Reset elapsed clocks, but do not turn an already observed launch into a new failed launch.
        if (item.has("firstVisible") && !(item.get("firstVisible") instanceof Boolean))
            throw new JSONException("invalid saved launch observation");
        s.activeSince = now; s.firstVisible = !s.launchExpected || item.optBoolean("firstVisible", false);
        if (item.has("followed") && !(item.get("followed") instanceof Boolean))
            throw new JSONException("invalid saved follow marker");
        s.followed = item.optBoolean("followed", false);
        if (s.followed && (!s.temporary || s.launchExpected || !s.sourceTab.isEmpty()
                || s.uid < 0 || s.uid / 100000 != user))
            throw new JSONException("invalid followed session");
        if (s.phase == AutomationEngine.Phase.ENABLING) s.phase = AutomationEngine.Phase.UNRESOLVED;
        return s;
    }
    static JSONObject policyJson(AutomationEngine.Policy p) throws JSONException {
        JSONObject modes = new JSONObject();
        for (Map.Entry<String,Integer> entry : p.appModes().entrySet()) modes.put(entry.getKey(), entry.getValue());
        JSONObject tabs = new JSONObject();
        for (Map.Entry<String,Integer> entry : p.tabModes().entrySet()) tabs.put(entry.getKey(), entry.getValue());
        return new JSONObject().put("version", 1).put("mode", p.mode()).put("lockDelayMs", p.lockDelay())
                .put("leaveDelayMs", p.leaveDelay()).put("protectMedia", p.protectMedia()).put("protectRecents", p.protectRecents())
                .put("managed", new JSONArray(p.managed())).put("appModes", modes).put("tabModes", tabs)
                .put("highRiskAllowed", new JSONArray(new TreeSet<>(p.highRiskAllowed())));
    }
}
