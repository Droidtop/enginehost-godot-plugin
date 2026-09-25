package dev.enginehost.plugin.godot;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.fragment.app.Fragment;
import dev.enginehost.api.EngineHost;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotHost;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hosts the Godot engine, configured entirely from the resolved Enginehost
 * session.
 *
 * On this engine line the engine is itself a fragment ({@code Godot extends
 * Fragment}) and takes its command line and its restart requests from a
 * GodotHost it looks for in exactly two places: its parent fragment and its
 * activity (Godot.onAttach). GodotFragment, which is its own host, arrives
 * with 4.2. Enginehost's activity is not a GodotHost, so this fragment is the
 * parent: it holds the engine as its one child, which is the arrangement
 * upstream's interface was written for.
 */
public final class EngineHostGodotFragment extends Fragment implements GodotHost {
    private static final String TAG = "EnginehostGodot";
    private static final String ENGINE = "godot-engine";

    private final File pack;
    private final String optionsJson;
    private final EngineHost host;

    /** @param pack what {@code --main-pack} names: a .pck, or the export that carries one */
    EngineHostGodotFragment(File pack, String optionsJson, EngineHost host) {
        this.pack = pack;
        this.optionsJson = optionsJson;
        this.host = host;
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setId(View.generateViewId());
        return frame;
    }

    /**
     * commitNow, so a launch the engine refuses (getCommandLine below throws
     * from Godot.onCreate) leaves through the plugin's own commitNow and
     * reaches Enginehost as this plugin's startup error, message intact.
     */
    @Override public void onViewCreated(View view, Bundle state) {
        if (getChildFragmentManager().findFragmentByTag(ENGINE) != null) return;
        getChildFragmentManager().beginTransaction()
                .add(view.getId(), new Godot(), ENGINE)
                .commitNow();
        // The engine starts here, after the plugin's onCreate returned, so a
        // failed start cannot be thrown to Enginehost; it is handed to it
        // instead, in place of the engine's own alert over a black screen.
        if (!engineStarted()) {
            String message = GodotEngineLog.failureMessage(null);
            try {
                host.fail(message);
            } catch (IncompatibleClassChangeError olderHost) {
                Log.w(TAG, message);
                host.finish();
            }
        }
    }

    /**
     * A game that restarts itself (OS.set_restart_on_exit, then quit: a
     * language change that needs a clean engine) does not arrive as a restart
     * request. The native side leaves it to Main::cleanup
     * (java_godot_lib_jni.cpp, "Whether restarting is handled by
     * Main::cleanup()"), which calls OS::create_instance with the game's
     * restart arguments, and that lands here. On this line upstream's own
     * game app leaves GodotHost's default in place, which does nothing, and
     * the engine is already gone: the last frame stays on screen for good
     * (rig, 2026-09-18, the 4.4 line, Anomalous Coffee Machine 2's language
     * dialog). On a desktop the same call starts the game again with those
     * arguments, and from 4.2 upstream's Android app does too, by process
     * rebirth, because the engine cannot be de-initialised in place.
     * Enginehost owns the process, so it is asked to do that. Anything but
     * -1 is success to create_instance.
     */
    @Override public int onNewGodotInstanceRequested(String[] args) {
        restartGame(args == null ? new String[0] : args);
        return 0;
    }

    /** The engine's own restart (a lost rendering context): same answer, no arguments. */
    @Override public void onGodotRestartRequested(Godot instance) {
        restartGame(new String[0]);
    }

    /** Whether the engine got through its setup: its render view exists only after that. */
    boolean engineStarted() {
        androidx.fragment.app.Fragment engine = getChildFragmentManager().findFragmentByTag(ENGINE);
        return engine instanceof Godot && ((Godot) engine).getRenderView() != null;
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

    /** Appended by the engine to whatever its own {@code _cl_} asset holds (here, nothing). */
    @Override public List<String> getCommandLine() {
        try {
            List<String> arguments = new ArrayList<>();
            Log.i(TAG, "Loading pack " + pack.getAbsolutePath());
            arguments.add("--main-pack");
            arguments.add(pack.getAbsolutePath());
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
            Log.e(TAG, "Godot launch could not be resolved", error);
            throw new IllegalStateException(
                    "Godot cannot start this game: " + error.getMessage(), error);
        }
    }
}
