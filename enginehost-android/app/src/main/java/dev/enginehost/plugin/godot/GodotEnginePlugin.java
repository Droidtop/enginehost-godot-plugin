package dev.enginehost.plugin.godot;

import androidx.fragment.app.FragmentActivity;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;

/** In-process Godot 4.7 runtime hosted by Enginehost's FragmentActivity. */
public final class GodotEnginePlugin implements EnginePlugin {
    private EngineHostGodotFragment fragment;

    @Override public void onCreate(EnginePluginSession session) throws Exception {
        if (!"godot".equals(session.engine()) || !"standard".equals(session.engineContext()))
            throw new IOException("Unsupported Godot context");
        File root = new File(session.gamePath()).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Godot game folder is unreadable");
        loadNativeRuntime(session.bundleDirectory());
        FragmentActivity activity = (FragmentActivity) session.host().context();
        if (session.display().getId() == android.view.View.NO_ID)
            session.display().setId(android.view.View.generateViewId());
        fragment = new EngineHostGodotFragment(root, session.host().saveDirectory(),
            session.host().cacheDirectory(), session.execFile(), session.optionsJson());
        activity.getSupportFragmentManager().beginTransaction()
            .add(session.display().getId(), fragment, "enginehost-godot-runtime")
            .commitNow();
    }

    private static void loadNativeRuntime(File bundle) throws IOException {
        IOException missing = null;
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            File directory = new File(bundle, "lib/" + abi);
            File cxx = new File(directory, "libc++_shared.so");
            File godot = new File(directory, "libgodot_android.so");
            if (!cxx.isFile() || !godot.isFile()) {
                missing = new IOException("Godot bundle has no native runtime for " + abi);
                continue;
            }
            System.load(cxx.getAbsolutePath());
            System.load(godot.getAbsolutePath());
            return;
        }
        throw missing == null ? new IOException("Device reports no supported ABIs") : missing;
    }

    @Override public void onDestroy() { fragment = null; }
}
