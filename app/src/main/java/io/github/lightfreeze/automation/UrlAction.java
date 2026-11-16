package io.github.yudigaga.lightfreeze.automation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** The complete allowlist for public automation input. No value is interpreted as a command. */
public final class UrlAction {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern TAG = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public final String operation;
    public final String targetType;
    public final String targetId;
    public final String token;

    private UrlAction(String operation, String targetType, String targetId, String token) {
        this.operation = operation;
        this.targetType = targetType;
        this.targetId = targetId;
        this.token = token;
    }

    public static UrlAction parse(String input) {
        if (input == null || input.length() > 512 || input.indexOf('%') >= 0) throw invalid();
        try {
            URI uri = new URI(input);
            if (!"lightfreeze-yudigaga".equals(uri.getScheme()) || !"v1".equals(uri.getHost())
                    || !"v1".equals(uri.getRawAuthority()) || uri.getRawFragment() != null
                    || uri.getRawQuery() == null) throw invalid();
            String operation = switch (uri.getRawPath()) {
                case "/freeze" -> "freeze";
                case "/unfreeze" -> "unfreeze";
                default -> throw invalid();
            };
            String[] pairs = uri.getRawQuery().split("&", -1);
            if (pairs.length != 2) throw invalid();
            Map<String, String> values = new HashMap<>();
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                if (separator <= 0 || separator != pair.lastIndexOf('=')) throw invalid();
                String key = pair.substring(0, separator);
                if (!key.equals("package") && !key.equals("tag") && !key.equals("tab") && !key.equals("token")) throw invalid();
                if (values.put(key, pair.substring(separator + 1)) != null) throw invalid();
            }
            String type = values.containsKey("package") ? "package" : values.containsKey("tag") ? "tag" : "tab";
            String target = values.get(type);
            String token = values.get("token");
            validate(operation, type, target, token);
            return new UrlAction(operation, type, target, token);
        } catch (URISyntaxException e) {
            throw invalid();
        }
    }

    public static String build(String operation, String targetType, String targetId, String token) {
        validate(operation, targetType, targetId, token);
        return "lightfreeze-yudigaga://v1/" + operation + "?" + targetType + "=" + targetId + "&token=" + token;
    }

    public static boolean isOperation(String operation) {
        return "freeze".equals(operation) || "unfreeze".equals(operation);
    }

    public static boolean isTarget(String type, String id) {
        return id != null && ("package".equals(type) && id.length() <= 255 && PACKAGE.matcher(id).matches()
                || "tag".equals(type) && TAG.matcher(id).matches()
                || "tab".equals(type) && ("all".equals(id) || "managed".equals(id)
                || "frozen".equals(id) || "available".equals(id)));
    }

    private static void validate(String operation, String type, String id, String token) {
        if (!isOperation(operation) || !isTarget(type, id) || !AutomationAuth.isToken(token)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("无效的轻冻操作链接");
    }
}
