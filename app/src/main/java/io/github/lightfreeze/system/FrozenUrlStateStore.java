package io.github.yudigaga.lightfreeze.system;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** System-owned URL wake state, separate from target recovery and never in App private storage. */
final class FrozenUrlStateStore {
    private static final int LIMIT = 256 * 1024;
    private final AtomicFile file;
    FrozenUrlStateStore() throws Exception {
        File directory = new File("/data/system/lightfreeze-yudigaga");
        if (!directory.exists() && !directory.mkdir()) throw new IOException("cannot create frozen URL state directory");
        StructStat stat = Os.lstat(directory.getAbsolutePath());
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 1000
                || !directory.getAbsolutePath().equals(directory.getCanonicalPath()))
            throw new SecurityException("unsafe frozen URL state directory");
        Os.chmod(directory.getAbsolutePath(), 0700);
        File target = new File(directory, "url-state.json");
        if (target.exists()) {
            StructStat saved = Os.lstat(target.getAbsolutePath());
            if (!OsConstants.S_ISREG(saved.st_mode) || saved.st_uid != 1000)
                throw new SecurityException("unsafe frozen URL state file");
        }
        file = new AtomicFile(target);
    }
    String load() throws Exception {
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int size;
            while ((size = input.read(bytes)) != -1) {
                if (output.size() + size > LIMIT) throw new IOException("frozen URL state too large");
                output.write(bytes, 0, size);
            }
            String value = output.toString(StandardCharsets.UTF_8);
            FrozenUrlCoordinator.State.decode(value); return value;
        } catch (FileNotFoundException e) { return null; }
    }
    void invalidate() throws IOException {
        file.delete();
        File base = file.getBaseFile();
        if (base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists())
            throw new IOException("cannot invalidate old frozen URL authority");
    }
    void save(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("frozen URL state too large");
        FrozenUrlCoordinator.State.decode(json);
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(bytes); file.finishWrite(output);
            Os.chmod(file.getBaseFile().getAbsolutePath(), 0600);
        } catch (Exception e) { if (output != null) file.failWrite(output); throw e; }
    }
}
