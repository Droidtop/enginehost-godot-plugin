package dev.enginehost.plugin.godot;

import androidx.fragment.app.FragmentActivity;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;

/** In-process Godot runtime hosted by Enginehost's FragmentActivity. */
public final class GodotEnginePlugin implements EnginePlugin {
    private EngineHostGodotFragment fragment;

    @Override public void onCreate(EnginePluginSession session) throws Exception {
        if (!"godot".equals(session.engine()) || !"standard".equals(session.engineContext()))
            throw new IOException("Unsupported Godot context");
        File root = new File(session.gamePath()).getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Godot game folder is unreadable");
        // Resolved before the engine starts so a folder Godot cannot load
        // fails here, as a plugin startup error Enginehost shows, rather
        // than inside the engine where the report is the engine's to make.
        GodotPackResolver.Pack pack = GodotPackResolver.resolve(root, session.execFile());
        FragmentActivity activity = (FragmentActivity) session.host().context();
        requireGodotCanRead(activity, pack == null ? root : pack.file);
        loadNativeRuntime(session.bundleDirectory());
        if (session.display().getId() == android.view.View.NO_ID)
            session.display().setId(android.view.View.generateViewId());
        fragment = new EngineHostGodotFragment(root, session.execFile(), session.optionsJson());
        activity.getSupportFragmentManager().beginTransaction()
            .add(session.display().getId(), fragment, "enginehost-godot-runtime")
            .commitNow();
    }

    /**
     * Godot's own file layer refuses any path outside the app's own
     * directories and shared storage unless the host holds All Files
     * Access, whatever the filesystem permits. Games live on removable
     * storage, so without it the engine would start and then fail to open
     * the pack it was handed. Say so here instead.
     */
    private static void requireGodotCanRead(android.content.Context context, File target)
            throws IOException {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager()) {
            return;
        }
        String path = target.getCanonicalPath();
        File[] reachable = {
            context.getFilesDir(), context.getCacheDir(), context.getExternalFilesDir(null),
            android.os.Environment.getExternalStorageDirectory(),
        };
        for (File directory : reachable) {
            if (directory != null && path.startsWith(directory.getCanonicalPath())) return;
        }
        throw new IOException(
                "Enginehost does not have All Files Access, and Godot cannot read a game "
                        + "outside shared storage without it. Grant it to Enginehost in "
                        + "Android's settings, then launch again.");
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
