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
    private final String execFile;
    private final String optionsJson;

    EngineHostGodotFragment(File gameRoot, String execFile, String optionsJson) {
        this.gameRoot = gameRoot;
        this.execFile = execFile;
        this.optionsJson = optionsJson;
    }

    @Override public List<String> getCommandLine() {
        try {
            List<String> arguments = new ArrayList<>(super.getCommandLine());
            GodotPackResolver.Pack pack = GodotPackResolver.resolve(gameRoot, execFile);
            if (pack != null) {
                Log.i(TAG, "Loading pack " + pack.file.getAbsolutePath()
                        + " (embedded=" + pack.embedded + ", built by " + pack.engineVersion + ")");
                arguments.add("--main-pack");
                arguments.add(pack.file.getAbsolutePath());
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
