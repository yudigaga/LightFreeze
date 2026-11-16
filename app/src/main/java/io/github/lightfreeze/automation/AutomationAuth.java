package io.github.yudigaga.lightfreeze.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Local bearer capabilities; callers select the expected token for exactly one target and channel. */
public final class AutomationAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private AutomationAuth() { }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean authorize(boolean enabled, boolean targetExists, String expected, String supplied) {
        return enabled && targetExists && isToken(expected) && isToken(supplied)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    static boolean isToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    static String key(String channel, String targetType, String targetId) {
        if (!("url".equals(channel) || "shortcut".equals(channel)) || !UrlAction.isTarget(targetType, targetId))
            throw new IllegalArgumentException("无效的自动化凭据范围");
        return channel + ":" + targetType + ":" + targetId;
    }
}
