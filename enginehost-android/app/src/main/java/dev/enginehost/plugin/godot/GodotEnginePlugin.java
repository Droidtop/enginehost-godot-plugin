package dev.enginehost.plugin.godot;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.fragment.app.FragmentActivity;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** In-process Godot runtime hosted by Enginehost's FragmentActivity. */
public final class GodotEnginePlugin implements EnginePlugin {
    /** The bundle payload carrying this plugin's compiled resources. */
    private static final String RESOURCE_APK = "runtime/godot.apk";

    private EngineHostGodotFragment fragment;
    private final List<AutoCloseable> resourceHandles = new ArrayList<>();

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
        attachResourcesWhereGodotLooks(activity, session.bundleDirectory());
        loadNativeRuntime(session.bundleDirectory());
        if (session.display().getId() == android.view.View.NO_ID)
            session.display().setId(android.view.View.generateViewId());
        fragment = new EngineHostGodotFragment(root, session.execFile(), session.optionsJson());
        activity.getSupportFragmentManager().beginTransaction()
            .add(session.display().getId(), fragment, "enginehost-godot-runtime")
            .commitNow();
    }

    /**
     * Godot keeps {@code context.applicationContext}, not the activity
     * ({@code Godot.getInstance} in the engine's own Kotlin), and looks up
     * things like {@code R.dimen.text_edit_height} through that context's
     * Resources. Enginehost attaches this bundle's resources to the
     * activity's Resources, which is a different object, so the engine
     * would read straight past them. Attach them where it actually looks.
     */
    private void attachResourcesWhereGodotLooks(Context activity, File bundle) throws IOException {
        Context application = activity.getApplicationContext();
        if (application == null || application.getResources() == activity.getResources()) {
            return;
        }
        File apk = new File(bundle, RESOURCE_APK).getCanonicalFile();
        if (!apk.isFile()) {
            throw new IOException("This Godot bundle is missing " + RESOURCE_APK);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IOException(
                    "Godot needs Android 11 or newer here: below it the engine cannot be "
                            + "given this bundle's resources.");
        }
        // Held open for as long as the engine may read them.
        resourceHandles.addAll(ApiR.addLoader(application, apk));
    }

    /** Kept apart so a pre-11 device never has to verify API 30 classes. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private static final class ApiR {
        private ApiR() {}

        static List<? extends AutoCloseable> addLoader(Context context, File apk)
                throws IOException {
            ParcelFileDescriptor descriptor =
                    ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
            ResourcesProvider provider = ResourcesProvider.loadFromApk(descriptor);
            ResourcesLoader loader = new ResourcesLoader();
            loader.addProvider(provider);
            context.getResources().addLoaders(loader);
            return java.util.Arrays.asList(provider, descriptor);
        }
    }

    /**
     * Godot's own file layer refuses any path outside the app's own
     * directories and shared storage unless the host holds All Files
     * Access, whatever the filesystem permits. Games live on removable
     * storage, so without it the engine would start and then fail to open
     * the pack it was handed. Say so here instead.
     */
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
        for (int i = resourceHandles.size() - 1; i >= 0; i--) {
            try {
                resourceHandles.get(i).close();
            } catch (Exception ignored) {
                // Nothing useful remains to be done with a handle we are dropping.
            }
        }
        resourceHandles.clear();
    }
}
