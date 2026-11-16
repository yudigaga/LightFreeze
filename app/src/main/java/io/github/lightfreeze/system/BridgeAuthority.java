package io.github.yudigaga.lightfreeze.system;

final class BridgeAuthority {
    private BridgeAuthority() { }
    static boolean accepts(String owner, int protectionBase, String loadedDigest, String installedDigest) {
        return "io.github.yudigaga.lightfreeze".equals(owner) && protectionBase == 2
                && loadedDigest != null && !loadedDigest.isEmpty() && loadedDigest.equals(installedDigest);
    }
}
