package dev.enginehost.plugin.godot;

import android.util.Log;
import dev.enginehost.api.EngineHost;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;

/**
 * Runtime components: what a launch loads beside the engine, handed to Godot
 * as GDExtension configs (Enginehost docs/engine-bundle-format.md, "Runtime
 * components" and "Godot: components are GDExtensions").
 *
 * Which components a launch loads is {@link EnginePluginSession#runtimeComponents()};
 * the game is never edited and never consulted here (Enginehost already
 * matched its {@code runtimeRequirements}). Each one is found in one of three
 * places:
 * <ul>
 * <li>a separately installed component bundle
 *     ({@link EnginePluginSession#runtimeComponentDirectory(String)}), which
 *     carries one build per Godot series at
 *     {@code godot-<series>/<name>.gdextension};
 * <li>this bundle's payload, at {@code components/<name>/<version>/<name>.gdextension};
 * <li>the engine library itself (compiled in): nothing to load.
 * </ul>
 *
 * Godot asks every registered Android plugin for
 * {@link GodotPlugin#getPluginGDExtensionLibrariesPaths()} from
 * OS_Android::load_platform_gdextensions, which GDExtensionManager runs
 * during core registration, before any game script parses. A component's
 * classes therefore exist by the time the game's resources name them, the
 * same guarantee a module compiled into the engine gives. Godot 4.2 is the
 * first series whose host can register such a plugin; a component bundle
 * says which series it serves and Enginehost selects only those.
 */
final class GodotComponents {
    private static final String TAG = "EnginehostGodot";
    /** Component names and versions become path segments; nothing else may. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SERIES = Pattern.compile("^(\\d+\\.\\d+)");

    /**
     * Which component provides a class, by the prefix its classes share. A
     * component's classes are its own namespace (spine-godot's are all
     * Spine*), and a game's own named script classes never reach here: the
     * engine asks only for a class neither ClassDB nor the game's scripts
     * define.
     */
    private static final String[][] PROVIDERS = {{"Spine", "spine-godot"}};

    /** Who answers this launch's requests, and for which series; set before the engine starts. */
    private static volatile EngineHost answering;
    private static volatile String answeringSeries;
    /** Components already asked for in this run: one ask each, whatever the answer. */
    private static final Set<String> ASKED = new HashSet<>();

    private GodotComponents() {}

    /** From onCreate, before the engine starts: this launch's requests go to its host. */
    static void answerRequests(EnginePluginSession session) throws IOException {
        answeringSeries = series(session.runtimeVersion());
        answering = session.host();
    }

    /**
     * Called by the engine (java_godot_wrapper.cpp, on a line whose engine
     * asks; ClassDB::set_missing_class_handler) when the game names a class
     * nothing registered: the GDExtension config that provides it, which the
     * engine then loads, or null. The game asks, and receives (Enginehost
     * docs/engine-bundle-format.md, "A game asks, and receives"): Enginehost
     * picks the version the game's files call for, and installs it or has the
     * person accept its licence while this waits, on the engine's thread.
     */
    static String requestGDExtensionFor(String className) {
        String name = null;
        for (String[] provider : PROVIDERS) {
            if (className.startsWith(provider[0])) name = provider[1];
        }
        EngineHost host = answering;
        if (name == null || host == null) return null;
        synchronized (ASKED) {
            if (!ASKED.add(name)) return null;
        }
        File root;
        try {
            root = host.requestComponent(name);
        } catch (IncompatibleClassChangeError olderHost) {
            // An Enginehost from before requestComponent(): it can only
            // supply components chosen at launch (configs()).
            Log.i(TAG, "This Enginehost cannot supply " + name + " while a game runs");
            return null;
        }
        if (root == null) {
            Log.i(TAG, "The game asked for " + name + " (" + className + "); none was given");
            return null;
        }
        File config = new File(root, "godot-" + answeringSeries + "/" + name + ".gdextension");
        if (!config.isFile()) {
            Log.w(TAG, "The " + name + " component has no build for Godot " + answeringSeries);
            return null;
        }
        Log.i(TAG, "The game asked for " + name + " (" + className + "); loading " + config);
        return config.getAbsolutePath();
    }

    /** Absolute paths of the configs to load for this launch, in a stable order. */
    static Set<String> configs(EnginePluginSession session) throws IOException {
        Map<String, String> components;
        try {
            components = session.runtimeComponents();
        } catch (NoSuchMethodError olderHost) {
            // An Enginehost from before runtimeComponents() existed. It still
            // selected this capability against the game's requirements, so
            // those name what the capability carries for this game.
            components = session.runtimeRequirements();
        }
        if (components == null || components.isEmpty()) return Collections.emptySet();
        Set<String> configs = new LinkedHashSet<>();
        for (Map.Entry<String, String> component : components.entrySet()) {
            String name = component.getKey();
            String version = component.getValue();
            if (!SEGMENT.matcher(name).matches() || !SEGMENT.matcher(version).matches()
                    || name.contains("..") || version.contains("..")) {
                throw new IOException("the runtime component \"" + name + "\" " + version
                        + " has a name this core cannot load");
            }
            File separate = separateDirectory(session, name);
            if (separate != null) {
                String series = series(session.runtimeVersion());
                File config = new File(separate, "godot-" + series + "/" + name + ".gdextension");
                if (!config.isFile()) {
                    throw new IOException("the installed " + name + " " + version
                            + " component has no build for Godot " + series);
                }
                Log.i(TAG, "Loading runtime component " + name + " " + version + " (installed separately)");
                configs.add(config.getAbsolutePath());
                continue;
            }
            File carried = new File(session.bundleDirectory(),
                    "components/" + name + "/" + version + "/" + name + ".gdextension");
            if (carried.isFile()) {
                Log.i(TAG, "Loading runtime component " + name + " " + version + " (carried)");
                configs.add(carried.getAbsolutePath());
            } else {
                // Declared by the capability and carried by neither: the
                // engine library has it compiled in, which the build checked.
                Log.i(TAG, "Runtime component " + name + " " + version + " is part of the engine");
            }
        }
        return Collections.unmodifiableSet(configs);
    }

    private static File separateDirectory(EnginePluginSession session, String name) {
        try {
            return session.runtimeComponentDirectory(name);
        } catch (NoSuchMethodError olderHost) {
            // An Enginehost from before separate component bundles: it never
            // selects a capability for a component it would have to supply.
            return null;
        }
    }

    /** "4.5.1" -> "4.5": the series a component bundle keys its builds by. */
    private static String series(String runtimeVersion) throws IOException {
        Matcher m = SERIES.matcher(runtimeVersion == null ? "" : runtimeVersion);
        if (!m.find()) throw new IOException("no Godot series in runtime version " + runtimeVersion);
        return m.group(1);
    }

    /** The Android plugin through which Godot reads the configs. */
    static final class Plugin extends GodotPlugin {
        private final Set<String> configs;

        Plugin(Godot godot, Set<String> configs) {
            super(godot);
            this.configs = configs;
        }

        @Override public String getPluginName() {
            return "EnginehostRuntimeComponents";
        }

        @Override public Set<String> getPluginGDExtensionLibrariesPaths() {
            return configs;
        }
    }
}
