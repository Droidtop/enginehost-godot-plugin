package dev.enginehost.plugin.godot;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.godotengine.godot.GodotFragment;
import org.json.JSONArray;
import org.json.JSONObject;

/** Godot fragment configured entirely from the resolved Enginehost session. */
public final class EngineHostGodotFragment extends GodotFragment {
    private static final String TAG = "EnginehostGodot";

    private final File gameRoot;
    private final File pack;
    private final String optionsJson;

    /**
     * @param pack what {@code --main-pack} should name, or null to open
     *             {@code gameRoot} as a loose project
     */
    EngineHostGodotFragment(File gameRoot, File pack, String optionsJson) {
        this.gameRoot = gameRoot;
        this.pack = pack;
        this.optionsJson = optionsJson;
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
