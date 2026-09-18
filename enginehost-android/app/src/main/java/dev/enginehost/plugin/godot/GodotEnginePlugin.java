package dev.enginehost.plugin.godot;

import android.content.Context;
import android.os.Build;
import androidx.fragment.app.FragmentActivity;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** In-process Godot runtime hosted by Enginehost's FragmentActivity. */
public final class GodotEnginePlugin implements EnginePlugin {
    /** The bundle payload carrying this plugin's compiled resources. */

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
        requireRunnableHere(pack, session.runtimeVersion());
        supplyPackKey(root, pack);
        // Where user:// goes: the save folder the person chose in Enginehost.
        // The engine reads this in OS_Android::get_user_data_dir and puts the
        // project's own user directory inside it, as it does on a desktop.
        // Exported before the engine library loads, so it is there whenever
        // the engine first asks.
        android.system.Os.setenv("ENGINEHOST_SAVE_PATH",
                session.host().saveDirectory().getAbsolutePath(), true);
        loadNativeRuntime(session.bundleDirectory());
        if (session.display().getId() == android.view.View.NO_ID)
            session.display().setId(android.view.View.generateViewId());
        fragment = new EngineHostGodotFragment(
            root, pack == null ? null : pack.file, session.optionsJson());
        activity.getSupportFragmentManager().beginTransaction()
            .add(session.display().getId(), fragment, "enginehost-godot-runtime")
            .commitNow();
    }

    /**
     * Hands the engine the key an encrypted pack needs, recovered from the
     * game's own executable.
     *
     * A pack exported with an encryption key has PACK_DIR_ENCRYPTED in its
     * header, and try_open_pack decrypts its file directory with the 32 bytes
     * of script_encryption_key that were compiled into the export template the
     * game shipped with. Enginehost's engine carries its own constant, never
     * that game's, so without this every encrypted pack fails in
     * ProjectSettings::_setup with "Can't open encrypted pack directory". The
     * key is exported for the engine to pick up in
     * platform/android/java_godot_lib_jni.cpp before Main::setup, and is
     * neither logged nor written anywhere.
     */
    private static void supplyPackKey(File gameRoot, GodotPackResolver.Pack pack)
            throws IOException {
        if (pack == null || !pack.encrypted) return;
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack.file, pack.directoryOffset);
        File folder = pack.file.getParentFile() == null ? gameRoot : pack.file.getParentFile();
        // The carrier of an embedded pack holds its own key; a loose pack's key
        // is in the executable the export named to match it.
        File preferred = pack.embedded ? pack.file : GodotPackKey.siblingOf(pack.file);
        List<File> executables = GodotPackKey.executablesIn(folder, preferred);
        StringBuilder tried = new StringBuilder();
        for (File executable : executables) {
            byte[] key = GodotPackKey.recover(executable, directory);
            if (key != null) {
                try {
                    android.system.Os.setenv("ENGINEHOST_GODOT_PACK_KEY",
                            GodotPackKey.hex(key), true);
                } catch (android.system.ErrnoException refused) {
                    throw new IOException("the key for this game's pack could not be "
                            + "passed to the engine", refused);
                }
                return;
            }
            if (tried.length() > 0) tried.append(", ");
            tried.append('"').append(executable.getName()).append('"');
        }
        throw new IOException("\"" + gameRoot.getName() + "\" ships an encrypted pack, and "
                + "the key it was encrypted with could not be found. Godot keeps that key "
                + "inside the game's own program, so this folder needs the .exe or .x86_64 "
                + "the pack was exported beside. "
                + (tried.length() == 0
                        ? "There is no Windows or Linux program in this folder to read it from."
                        : "Read without finding it: " + tried + "."));
    }

    /**
     * Godot refuses a pack from a later engine than the one reading it,
     * and reports that as a failure to open the pack at all. The pack says
     * which engine built it, so say the useful thing instead: which bundle
     * this game needs.
     */
    private static void requireRunnableHere(GodotPackResolver.Pack pack, String runtimeVersion)
            throws IOException {
        if (pack == null || pack.engineVersion == null || runtimeVersion == null) return;
        int[] built = series(pack.engineVersion);
        int[] here = series(runtimeVersion);
        if (built == null || here == null) return;
        if (built[0] > here[0] || (built[0] == here[0] && built[1] > here[1])) {
            throw new IOException("this game was built with Godot " + pack.engineVersion
                    + ", and this bundle runs " + runtimeVersion + ". Install the Godot "
                    + built[0] + "." + built[1] + " bundle and launch it again.");
        }
    }

    /** The major and minor of a dotted version, or null if unreadable. */
    private static int[] series(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    private static void requireGodotCanRead(Context context, File target) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
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

    @Override public void onDestroy() {
        fragment = null;
    }
}
