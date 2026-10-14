package dev.enginehost.plugin.godot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The engine's own words for why it did not start.
 *
 * Godot prints the reason to the log before it gives up ("Cannot open
 * resource pack '...'", "Can't open encrypted pack directory") and says
 * only "Unable to setup the Godot engine" to its host. The last error line
 * this process logged under the engine's tag is that reason, read back from
 * the process's own log, which an app may always read.
 */
final class GodotEngineLog {
    private static final int MAX_CHARACTERS = 300;

    private GodotEngineLog() {}

    /** A sentence for a failed start, with the engine's own reason when it logged one. */
    static String failureMessage(String hostMessage) {
        String reason = lastError();
        if (reason != null) return "Godot could not start this game: " + reason;
        if (hostMessage != null && !hostMessage.isEmpty()) return "Godot could not start this game: " + hostMessage;
        return "Godot could not start this game; its log has no reason.";
    }

    private static String lastError() {
        try {
            java.lang.Process logcat = new ProcessBuilder(
                    "logcat", "-d", "-v", "raw", "-s", "godot:E",
                    "--pid=" + android.os.Process.myPid())
                    .redirectErrorStream(true).start();
            String last = null;
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("ERROR:")) last = trimmed.substring("ERROR:".length()).trim();
                }
            }
            logcat.waitFor();
            if (last == null || last.isEmpty()) return null;
            return last.length() > MAX_CHARACTERS ? last.substring(0, MAX_CHARACTERS) + "…" : last;
        } catch (Exception unreadable) {
            return null;
        }
    }
}
