package dev.enginehost.plugin.godot;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import dev.enginehost.api.EngineHost;
import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotFragment;
import org.json.JSONArray;
import org.json.JSONObject;

/** Godot fragment configured entirely from the resolved Enginehost session. */
public final class EngineHostGodotFragment extends GodotFragment {
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
     * A game that restarts itself (OS.set_restart_on_exit, then quit: a
     * language change that needs a clean engine) does not arrive as a restart
     * request. The native side leaves it to Main::cleanup
     * (java_godot_lib_jni.cpp, "Whether restarting is handled by
     * 'Main::cleanup()'"), which calls OS::create_instance with the game's
     * restart arguments, and that lands here. GodotHost's default does
     * nothing, and the engine is already gone: the last frame stays on screen
     * for good (rig, 2026-09-18, Anomalous Coffee Machine 2's language
     * dialog). Upstream's GodotActivity answers with a process rebirth whose
     * command line is these arguments, because the engine cannot be
     * de-initialised in place. Enginehost owns the process, so it is asked to
     * do the same. The return value is upstream's "fake process id": anything
     * but -1, which create_instance reads as failure.
     */
    @Override public int onNewGodotInstanceRequested(String[] args) {
        restartGame(args == null ? new String[0] : args);
        return 0;
    }

    /** The engine's own restart (a lost rendering context): same answer, no arguments. */
    @Override public void onGodotRestartRequested(Godot instance) {
        restartGame(new String[0]);
    }

    /** Called on the render thread; the host works on the UI thread. */
    private void restartGame(String[] arguments) {
        android.app.Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            Log.i(TAG, "The game asked to be restarted");
            try {
                host.restart(arguments);
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

    /** What the run before this one passed to restart; none on an older Enginehost. */
    private String[] restartArguments() {
        try {
            String[] arguments = host.restartArguments();
            return arguments == null ? new String[0] : arguments;
        } catch (IncompatibleClassChangeError olderHost) {
            return new String[0];
        }
    }

    @Override public List<String> getCommandLine() {
        try {
            List<String> arguments = new ArrayList<>(super.getCommandLine());
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
            // Last, as upstream's rebirth makes them the new instance's
            // command line: what the game itself asked to be restarted with.
            for (String value : restartArguments()) arguments.add(value);
            return arguments;
        } catch (Exception error) {
            // Deliberately not IllegalArgumentException. GodotFragment
            // reads that one as "the APK expansion pack is missing" and
            // silently swaps the game for its OBB downloader UI, which is
            // how a bad launch used to disappear without a message.
            // IllegalStateException is the failure it actually reports.
            Log.e(TAG, "Godot launch could not be resolved", error);
            throw new IllegalStateException(
                    "Godot cannot start this game: " + error.getMessage(), error);
        }
    }
}
