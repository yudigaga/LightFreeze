package io.github.yudigaga.lightfreeze.automation;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlActionTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TAG = "123e4567-e89b-12d3-a456-426614174000";

    @Test public void parsesOnlyOneApplicationWithoutLaunchingIt() {
        UrlAction value = UrlAction.parse("lightfreeze-yudigaga://v1/freeze?package=com.example.app&token=" + TOKEN);
        assertEquals("freeze", value.operation);
        assertEquals("package", value.targetType);
        assertEquals("com.example.app", value.targetId);
        assertEquals(TOKEN, value.token);
    }

    @Test public void parsesStableTagWithEitherQueryOrder() {
        UrlAction value = UrlAction.parse("lightfreeze-yudigaga://v1/unfreeze?token=" + TOKEN + "&tag=" + TAG);
        assertEquals("unfreeze", value.operation);
        assertEquals("tag", value.targetType);
        assertEquals(TAG, value.targetId);
    }

    @Test public void buildsCopyableCanonicalUrls() {
        assertEquals("lightfreeze-yudigaga://v1/unfreeze?tag=" + TAG + "&token=" + TOKEN,
                UrlAction.build("unfreeze", "tag", TAG, TOKEN));
        assertEquals("lightfreeze-yudigaga://v1/freeze?package=com.example.app&token=" + TOKEN,
                UrlAction.build("freeze", "package", "com.example.app", TOKEN));
    }

    @Test public void allFourBuiltInTabsHaveAuthenticatedFreezeAndUnfreezeUrls() {
        for (String tab : new String[]{"all", "managed", "frozen", "available"}) {
            for (String operation : new String[]{"freeze", "unfreeze"}) {
                String expected = "lightfreeze-yudigaga://v1/" + operation + "?tab=" + tab + "&token=" + TOKEN;
                UrlAction action = UrlAction.parse(expected);
                assertEquals(operation, action.operation);
                assertEquals("tab", action.targetType);
                assertEquals(tab, action.targetId);
                assertEquals(TOKEN, action.token);
                assertEquals(expected, UrlAction.build(operation, "tab", tab, TOKEN));
            }
        }
        assertEquals("available", UrlAction.parse("lightfreeze-yudigaga://v1/unfreeze?token=" + TOKEN
                + "&tab=available").targetId);
    }

    @Test public void builtInTabUrlsRejectAliasesMissingCapabilitiesAndMixedTargets() {
        for (String tab : new String[]{"", "builtin:all", "ALL", "All", "work", "system", TAG,
                "all+", "%61ll", "all&package=com.example.app"}) {
            rejects("lightfreeze-yudigaga://v1/freeze?tab=" + tab + "&token=" + TOKEN);
            try { UrlAction.build("freeze", "tab", tab, TOKEN); fail("accepted unknown tab"); }
            catch (IllegalArgumentException expected) { }
        }
        rejects("lightfreeze-yudigaga://v1/freeze?tab=all");
        rejects("lightfreeze-yudigaga://v1/freeze?tab=all&token=");
        rejects("lightfreeze-yudigaga://v1/freeze?tab=all&token=short");
        rejects("lightfreeze-yudigaga://v1/freeze?tab=all&tab=managed&token=" + TOKEN);
        rejects("lightfreeze-yudigaga://v1/freeze?tab=all&tag=" + TAG + "&token=" + TOKEN);
    }

    @Test public void rejectsOtherSchemesHostsPathsAndUriDecorations() {
        String query = "?package=com.example.app&token=" + TOKEN;
        for (String prefix : new String[]{"http://v1/freeze", "Lightfreeze://v1/freeze", "lightfreeze-yudigaga://V1/freeze",
                "lightfreeze-yudigaga://v2/freeze", "lightfreeze-yudigaga://v1.evil/freeze", "lightfreeze-yudigaga://user@v1/freeze",
                "lightfreeze-yudigaga://v1:80/freeze", "lightfreeze-yudigaga:///v1/freeze", "lightfreeze-yudigaga://v1/open",
                "lightfreeze-yudigaga://v1/freeze/", "lightfreeze-yudigaga://v1/a/../freeze", "lightfreeze-yudigaga://v1/%66reeze"}) {
            rejects(prefix + query);
        }
        rejects("lightfreeze-yudigaga://v1/freeze" + query + "#anything");
        rejects("lightfreeze-yudigaga://v1/freeze" + query + "#");
    }

    @Test public void rejectsDuplicateTargetsUnknownParametersAndMissingCapabilities() {
        String base = "lightfreeze-yudigaga://v1/freeze?package=com.example.app";
        for (String query : new String[]{"&token=" + TOKEN + "&package=com.other.app",
                "&token=" + TOKEN + "&tag=" + TAG, "&token=" + TOKEN + "&token=" + TOKEN,
                "&token=" + TOKEN + "&userId=10", "&token=" + TOKEN + "&callback=https://evil.test",
                "&token=" + TOKEN + "&", "&token=", "", "&token=" + TOKEN + "=", "&token=short"}) {
            rejects(base + query);
        }
        rejects("lightfreeze-yudigaga://v1/freeze?token=" + TOKEN);
        rejects("lightfreeze-yudigaga://v1/freeze?tag=" + TAG + "&package=com.example.app");
    }

    @Test public void rejectsEncodedAndUnsafeValuesInsteadOfNormalizingThem() {
        for (String target : new String[]{"com.example;id", "com.example app", "com.example\napp",
                "com.%65xample.app", "com.example.app%26userId=10", "com.example.app+", "android",
                "com.example/other", "com.example'", "com.例子.app", "a." + "b".repeat(254)}) {
            rejects("lightfreeze-yudigaga://v1/freeze?package=" + target + "&token=" + TOKEN);
        }
        rejects("lightfreeze-yudigaga://v1/freeze?tag=work&token=" + TOKEN);
        rejects("lightfreeze-yudigaga://v1/freeze?tag=123E4567-e89b-12d3-a456-426614174000&token=" + TOKEN);
        rejects("lightfreeze-yudigaga://v1/freeze?%70ackage=com.example.app&token=" + TOKEN);
        rejects("lightfreeze-yudigaga://v1/freeze?package=com.example.app&token=%41" + TOKEN.substring(1));
        rejects(null);
        rejects("");
    }

    @Test public void generatorRejectsUnsafeOrUnsupportedTargets() {
        for (String[] value : new String[][]{{"open", "package", "com.example.app", TOKEN},
                {"freeze", "shell", "id", TOKEN}, {"freeze", "package", "com.example;id", TOKEN},
                {"unfreeze", "tag", "work", TOKEN}, {"freeze", "tag", TAG, "invalid"}}) {
            try { UrlAction.build(value[0], value[1], value[2], value[3]); fail("accepted unsafe generated route"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static void rejects(String input) {
        try { UrlAction.parse(input); fail("accepted invalid URL"); }
        catch (IllegalArgumentException expected) { }
    }
}
