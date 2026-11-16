package io.github.yudigaga.lightfreeze.ui;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Bounded private export snapshot. Only its random identifier belongs in saved state. */
public final class ExportDraft {
    public static final int MAX_BYTES = 1024 * 1024;
    public final String json, filename, summary;
    public ExportDraft(String json, String filename, String summary) {
        this.json = json; this.filename = filename; this.summary = summary;
    }
    private static File file(File directory, String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Invalid export draft identifier");
        return new File(directory, id + ".draft");
    }
    public void save(File directory, String id) throws IOException {
        File target = file(directory, id);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES || filename.length() > 512 || summary.length() > 2048)
            throw new IOException("Export draft too large");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export directory");
        File[] old = directory.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File candidate : old) {
                if (candidate.isFile() && candidate.lastModified() < cutoff)
                    Files.deleteIfExists(candidate.toPath());
            }
            File[] remaining = directory.listFiles();
            if (remaining != null && remaining.length >= 16)
                throw new IOException("导出草稿过多，请清理应用缓存后重试");
        }
        File temp = new File(directory, id + ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
                out.writeInt(1); out.writeUTF(filename); out.writeUTF(summary);
                out.writeInt(data.length); out.write(data);
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    public static ExportDraft load(File directory, String id) throws IOException {
        File source = file(directory, id);
        if (source.length() > MAX_BYTES + 10000L) throw new IOException("Export draft too large");
        try (DataInputStream in = new DataInputStream(new FileInputStream(source))) {
            if (in.readInt() != 1) throw new IOException("Unknown export draft version");
            String filename = in.readUTF(), summary = in.readUTF();
            int size = in.readInt();
            if (size < 0 || size > MAX_BYTES || filename.length() > 512 || summary.length() > 2048)
                throw new IOException("Invalid export draft size");
            byte[] bytes = new byte[size]; in.readFully(bytes);
            if (in.read() != -1) throw new IOException("Trailing export draft data");
            return new ExportDraft(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(), filename, summary);
        }
    }
    public static void remove(File directory, String id) throws IOException {
        if (id != null) Files.deleteIfExists(file(directory, id).toPath());
    }
}
