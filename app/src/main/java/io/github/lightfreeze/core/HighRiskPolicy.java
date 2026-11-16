package io.github.yudigaga.lightfreeze.core;

import java.util.Set;
import java.util.regex.Pattern;

/** An explicit allowance applies to one package, including packages sharing a system UID. */
public final class HighRiskPolicy {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private HighRiskPolicy() { }

    public static Set<String> checked(Set<String> packages) {
        if (packages == null || packages.size() > 500) throw new IllegalArgumentException("高危放行列表最多 500 项");
        for (String pkg : packages) {
            if (pkg == null || pkg.length() > 255 || !PACKAGE.matcher(pkg).matches()
                    || "android".equals(pkg) || "io.github.yudigaga.lightfreeze".equals(pkg))
                throw new IllegalArgumentException("高危放行目标无效；本体请使用专用冻结开关");
        }
        return Set.copyOf(packages);
    }
    public static boolean isAllowed(Set<String> packages, String pkg) { return packages.contains(pkg); }
}
