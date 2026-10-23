package dev.enginehost.plugin.godot;

import android.util.Log;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
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

    private GodotComponents() {}

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
