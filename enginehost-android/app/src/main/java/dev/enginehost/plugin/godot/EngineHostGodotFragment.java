package dev.enginehost.plugin.godot;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import dev.enginehost.api.EngineHost;
import org.godotengine.godot.Godot;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Godot fragment configured entirely from the resolved Enginehost session.
 *
 * On this engine line the fragment IS the engine: {@code Godot extends
 * Fragment}. GodotFragment, and a GodotHost the fragment itself implements,
 * arrive with 4.2; here the host is looked for only in the parent fragment
 * and the activity (Godot.onAttach), and Enginehost's activity is neither.
 * So the two things the later lines answer through GodotHost are answered by
 * overriding the engine's own methods: getCommandLine() and restart().
 */
public final class EngineHostGodotFragment extends Godot {
    private static final String TAG = "EnginehostGodot";

    private final File gameRoot;
    private final File pack;
    private final String optionsJson;
    private final EngineHost host;

    /**
     * @param pack what {@code --main-pack} should name, or null to open
     *             {@code gameRoot} as a loose project
     */
    EngineHostGodotFragment(File gameRoot, File pack, String optionsJson, EngineHost host) {
        this.gameRoot = gameRoot;
        this.pack = pack;
        this.optionsJson = optionsJson;
        this.host = host;
    }

    /**
     * A game that asks the engine to restart (OS.set_restart_on_exit, a
     * language change that needs a clean engine) arrives here from the native
     * side, and upstream's body only forwards to a GodotHost this fragment
     * does not have: the engine has already stopped drawing, so the person
     * would be left on a black screen for good (rig, 2026-09-18, the 4.4
     * line, Anomalous Coffee Machine 2's language dialog). Upstream's own app
     * answers by killing its process and relaunching
     * (FullScreenGodotApp.onGodotRestartRequested), because the engine cannot
     * be de-initialised in place. Enginehost owns the process, so it is asked
     * to do the same. Called on the render thread; the host works on the UI
     * thread.
     */
    @Override public void restart() {
        android.app.Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            Log.i(TAG, "The game asked to be restarted");
            try {
                host.restart();
            } catch (IncompatibleClassChangeError olderHost) {
                // An Enginehost from before restart() existed. This plugin
                // compiles against its own copy of the interface and runs
                // against the host's, so the missing method surfaces as
                // NoSuchMethodError (the interface does not declare it) or
                // AbstractMethodError (declared, not implemented); both are
                // IncompatibleClassChangeErrors. Closing the game is the
                // honest outcome; a dead screen is not.
                Log.w(TAG, "This Enginehost cannot restart a game; closing it instead");
                host.finish();
            }
        });
    }

    @Override protected String[] getCommandLine() {
        try {
            List<String> arguments = new ArrayList<>(java.util.Arrays.asList(super.getCommandLine()));
            if (pack != null) {
                Log.i(TAG, "Loading pack " + pack.getAbsolutePath());
                arguments.add("--main-pack");
                arguments.add(pack.getAbsolutePath());
            } else {
                Log.i(TAG, "Loading project at " + gameRoot.getAbsolutePath());
                arguments.add("--path");
                arguments.add(gameRoot.getAbsolutePath());
            }
            JSONArray extra =
                    new JSONObject(optionsJson == null ? "{}" : optionsJson).optJSONArray("commandLine");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    String value = extra.getString(i);
                    if (value.indexOf('\0') >= 0) {
                        throw new IOException("an engine setting for this folder contains a NUL");
                    }
                    arguments.add(value);
                }
            }
            return arguments.toArray(new String[0]);
        } catch (Exception error) {
            // Godot.onCreate calls this unguarded on this engine line, so
            // the failure leaves the fragment transaction and reaches
            // Enginehost as this plugin's startup error, message intact.
            Log.e(TAG, "Godot launch could not be resolved", error);
            throw new IllegalStateException(
                    "Godot cannot start this game: " + error.getMessage(), error);
        }
    }
}
