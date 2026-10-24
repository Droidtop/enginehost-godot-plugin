package dev.enginehost.plugin.godot;

import android.util.Log;
import dev.enginehost.api.EngineHost;
import dev.enginehost.api.EnginePluginSession;

/**
 * A link the game opens (Godot's OS.shell_open): offered to the person by
 * Enginehost (EngineHost.openLink; Enginehost docs/SPEC.md, "Links a game
 * opens"), never opened by the engine itself. Each line's engine calls
 * {@link #openLink} from OS_Android::shell_open (java_godot_wrapper.cpp looks
 * it up by name, since Godot's Java side is the stock AAR).
 */
final class GodotLinks {
    private static final String TAG = "EnginehostGodot";
    private static volatile EngineHost host;

    private GodotLinks() {}

    /** From onCreate, before the engine starts: this launch's links go to its host. */
    static void answerFor(EnginePluginSession session) {
        host = session.host();
    }

    /** Called by the engine; whether Enginehost took the link. False opens nothing. */
    static boolean openLink(String url) {
        EngineHost answering = host;
        if (answering == null || url == null) return false;
        try {
            return answering.openLink(url);
        } catch (IncompatibleClassChangeError olderHost) {
            // An Enginehost from before openLink(): the link is not opened, rather than
            // opened unasked.
            Log.i(TAG, "This Enginehost cannot offer a link; it was not opened");
            return false;
        }
    }
}
