package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class GatedRootSessionTest {
    // The subprocess is a fresh JVM; allow the production budget so startup is not the timeout under test.
    private static final long SESSION_BUDGET_MS = 10000;
    private List<String> child(Path dir, String delay) throws Exception {
        return List.of(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath(),
                Child.class.getName(), dir.toString(), delay);
    }
    @Test public void readyThenGateThenMutationUsesOneProcess() throws Exception {
        Path dir = Files.createTempDirectory("root-gate-order");
        AtomicBoolean checked = new AtomicBoolean();
        ShortProcess.Result result = GatedRootSession.run(child(dir, "0"), "MUTATE", SESSION_BUDGET_MS, () -> {
            assertTrue(Files.exists(dir.resolve("ready")));
            assertFalse(Files.exists(dir.resolve("mutated")));
            checked.set(true);
        });
        assertTrue(checked.get());
        assertEquals(0, result.exitCode);
        assertTrue(Files.exists(dir.resolve("mutated")));
    }
    @Test public void rejectedGateNeverSendsMutation() throws Exception {
        Path dir = Files.createTempDirectory("root-gate-reject");
        try {
            GatedRootSession.run(child(dir, "0"), "MUTATE", SESSION_BUDGET_MS,
                    () -> { throw new FreezeBackend.BackendException("protected", false); });
            fail("gate rejection was ignored");
        } catch (FreezeBackend.BackendException expected) { assertFalse(expected.unknown); }
        assertFalse(Files.exists(dir.resolve("mutated")));
    }
    @Test public void authorizationDelayRechecksChangedProtectionBeforeMutation() throws Exception {
        Path dir = Files.createTempDirectory("root-gate-auth-delay");
        AtomicBoolean recent = new AtomicBoolean();
        Thread changedDuringPrompt = new Thread(() -> {
            try {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SESSION_BUDGET_MS);
                while (!Files.exists(dir.resolve("authorizing")) && System.nanoTime() < until) Thread.sleep(5);
                recent.set(true);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        changedDuringPrompt.start();
        try {
            GatedRootSession.run(child(dir, "250"), "MUTATE", SESSION_BUDGET_MS, () -> {
                assertTrue(recent.get());
                throw new FreezeBackend.BackendException("app entered recents during authorization", false);
            });
            fail("protection changed during root authorization was ignored");
        } catch (FreezeBackend.BackendException expected) { assertFalse(expected.unknown); }
        finally { changedDuringPrompt.interrupt(); changedDuringPrompt.join(500); }
        assertFalse(Files.exists(dir.resolve("mutated")));
    }
    @Test public void authorizationTimeoutIsKnownBeforeMutation() throws Exception {
        Path dir = Files.createTempDirectory("root-gate-auth-timeout");
        try { GatedRootSession.run(child(dir, "10000"), "MUTATE", 200, () -> { }); fail("timeout ignored"); }
        catch (FreezeBackend.BackendException expected) { assertFalse(expected.unknown); }
        assertFalse(Files.exists(dir.resolve("mutated")));
    }
    @Test public void timeoutAfterSendingMutationIsUnknown() throws Exception {
        Path dir = Files.createTempDirectory("root-gate-write-timeout");
        try { GatedRootSession.run(child(dir, "hang-after-write"), "MUTATE", SESSION_BUDGET_MS, () -> { }); fail("timeout ignored"); }
        catch (FreezeBackend.BackendException expected) { assertTrue(expected.unknown); }
        assertTrue(Files.exists(dir.resolve("mutated")));
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]);
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            String probe = input.readLine();
            Matcher match = Pattern.compile("LF_READY_([a-f0-9]+)").matcher(probe);
            if (!match.find()) System.exit(4);
            String nonce = match.group(1);
            Files.writeString(dir.resolve("authorizing"), "waiting");
            if (!args[1].equals("hang-after-write")) Thread.sleep(Long.parseLong(args[1]));
            Files.writeString(dir.resolve("ready"), "0");
            System.out.println("LF_READY_" + nonce); System.out.flush();
            String command = input.readLine();
            if (!"MUTATE".equals(command)) System.exit(5);
            Files.writeString(dir.resolve("mutated"), "yes");
            if (args[1].equals("hang-after-write")) Thread.sleep(60000);
            System.out.println("LF_DONE_" + nonce + " 0"); System.out.flush();
        }
    }
}
