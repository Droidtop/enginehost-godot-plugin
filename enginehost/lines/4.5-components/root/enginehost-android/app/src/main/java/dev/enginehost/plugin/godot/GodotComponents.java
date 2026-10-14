package dev.enginehost.plugin.godot;

import android.util.Log;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;

/**
 * Runtime components: what a launch loads beside the stock engine, handed to
 * Godot as GDExtension configs (Enginehost docs/engine-bundle-format.md,
 * "Runtime components (subplugins)" and "The module system").
 *
 * A component is signed bundle payload under
 * {@code components/<name>/<version>/}: its shared libraries in
 * {@code lib/<abi>/}, its licence, and {@code <name>.gdextension}, whose
 * {@code [libraries]} entries name those libraries relative to itself. Which
 * components a launch loads is exactly the selected capability's
 * {@code runtimeComponents}; the game is never edited and never consulted
 * here (Enginehost already matched its {@code runtimeRequirements}).
 *
 * Godot asks every registered Android plugin for
 * {@link GodotPlugin#getPluginGDExtensionLibrariesPaths()} from
 * OS_Android::load_platform_gdextensions, which GDExtensionManager runs
 * during core registration, before any game script parses. A component's
 * classes therefore exist by the time the game's resources name them, the
 * same guarantee a module compiled into the engine gives.
 */
final class GodotComponents {
    private static final String TAG = "EnginehostGodot";
    /** Component names and versions become path segments; nothing else may. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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
                        + " has a name this bundle cannot carry");
            }
            File config = new File(session.bundleDirectory(),
                    "components/" + name + "/" + version + "/" + name + ".gdextension");
            if (!config.isFile()) {
                throw new IOException("this bundle declares the runtime component " + name + " "
                        + version + " but does not carry it");
            }
            Log.i(TAG, "Loading runtime component " + name + " " + version);
            configs.add(config.getAbsolutePath());
        }
        return Collections.unmodifiableSet(configs);
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
