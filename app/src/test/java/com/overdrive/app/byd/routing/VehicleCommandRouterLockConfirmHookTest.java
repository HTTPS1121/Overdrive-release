package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import org.junit.Test;

/**
 * Pins the router's post-command lock-confirmation hook: {@code finishCommand} calls
 * {@code confirmLockStateAfterCommand(cmd, result)}, which is reached only for a successful
 * {@code LockCommand}/{@code UnlockCommand} and never lets collector bookkeeping change the result.
 */
public class VehicleCommandRouterLockConfirmHookTest {
    private static final String HOOK_START =
            "    /** Rules that check the lock after this command must see the car's new state, not the poll's. */";
    /** SHA-256 of the hook method text (javadoc through closing brace) on the proximity base. */
    static final String HOOK_SHA256 =
            "7b3591b40d6e8a942c998bb759185a7fd5a02dba63fb984f54db063101333b3f";

    @Test
    public void finishCommandCallsTheLockConfirmationHook() throws Exception {
        String router = read("app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        int finish = router.indexOf("    private CommandResult finishCommand(VehicleCommand cmd, CommandResult result) {");
        assertTrue(finish > 0);
        String body = router.substring(finish, router.indexOf("\n    }\n", finish));
        assertTrue(body.contains("confirmLockStateAfterCommand(cmd, result);"));
        assertTrue(body.indexOf("confirmLockStateAfterCommand(cmd, result);") < body.indexOf("return result;"));
    }

    @Test
    public void hookTextIsPinnedAndSuccessOnlyLockOnlyAndThrowSafe() throws Exception {
        String router = read("app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        int start = router.indexOf(HOOK_START);
        assertTrue("hook javadoc missing", start >= 0);
        int end = router.indexOf("\n    }\n", start) + "\n    }\n".length();
        String hook = router.substring(start, end);
        assertEquals(HOOK_SHA256, sha256(hook));
        assertTrue(hook.contains(
                "    private static void confirmLockStateAfterCommand(VehicleCommand cmd, CommandResult result) {"));
        assertTrue(hook.contains("if (result == null || result.outcome != Outcome.SUCCESS) return;"));
        assertTrue(hook.contains("if (!(cmd instanceof LockCommand) && !(cmd instanceof UnlockCommand)) return;"));
        assertTrue(hook.contains(
                "BydDataCollector.getInstance().confirmLockStateAfterCommand(cmd instanceof LockCommand);"));
        assertTrue(hook.contains("} catch (Throwable t) {"));
        assertEquals(1, count(router, HOOK_START));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
