package io.github.yudigaga.lightfreeze.tags;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import io.github.yudigaga.lightfreeze.core.FreezeTriggers;

public final class Tag {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    public final String id, name;
    public final int mode;
    public final Set<String> packages;
    public Tag(String id, String name, int mode, Set<String> packages) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("标签标识需为标准小写 UUID");
        if (name == null) throw new IllegalArgumentException("请输入标签名称");
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 40)
            throw new IllegalArgumentException("标签名称需为 1–40 个字符");
        for (int i = 0; i < normalized.length();) {
            int codePoint = normalized.codePointAt(i);
            if (Character.isISOControl(codePoint) || (codePoint >= 0xd800 && codePoint <= 0xdfff))
                throw new IllegalArgumentException("标签名称含无效字符");
            i += Character.charCount(codePoint);
        }
        if (mode != -1) FreezeTriggers.checked(mode);
        if (packages == null || packages.size() > TagCodec.MAX_PACKAGES)
            throw new IllegalArgumentException("每个标签最多包含 500 个应用");
        TreeSet<String> copied = new TreeSet<>();
        for (String pkg : packages) {
            if (pkg == null || pkg.length() > 255 || !PACKAGE.matcher(pkg).matches())
                throw new IllegalArgumentException("标签含无效应用包名");
            copied.add(pkg);
        }
        this.id = id; this.name = normalized; this.mode = mode;
        this.packages = Collections.unmodifiableSet(copied);
    }
}
