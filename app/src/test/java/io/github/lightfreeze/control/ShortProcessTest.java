package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

public class ShortProcessTest {
    private List<String> command(String mode) throws Exception {
        return List.of(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath(), Child.class.getName(), mode);
    }
    @Test public void completesAndPreservesFailureCode() throws Exception {
        ShortProcess.Result result = ShortProcess.run(command("failure"), 3000);
        assertEquals(7, result.exitCode);
        assertFalse(result.timedOut);
        assertTrue(result.output.contains("denied"));
    }
    @Test public void outputHasABoundEvenWhenChildWritesALot() throws Exception {
        ShortProcess.Result result = ShortProcess.run(command("output"), 3000);
        assertEquals(0, result.exitCode);
        assertTrue(result.output.length() <= 8192);
        assertTrue(result.truncated);
    }
    @Test public void timeoutReturnsUnknownInsteadOfSuccess() throws Exception {
        long start = System.nanoTime();
        ShortProcess.Result result = ShortProcess.run(command("sleep"), 100);
        assertTrue(result.timedOut);
        assertEquals(-1, result.exitCode);
        assertTrue((System.nanoTime() - start) / 1000000 < 2500);
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "sleep" -> Thread.sleep(10000);
                case "output" -> System.out.print("a".repeat(200000));
                default -> { System.out.print("denied"); System.exit(7); }
            }
        }
    }
}
