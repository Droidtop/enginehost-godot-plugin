package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.godotengine.godot.GodotFragment;
import org.json.JSONArray;
import org.json.JSONObject;

/** Godot fragment configured entirely from the resolved Enginehost session. */
public final class EngineHostGodotFragment extends GodotFragment {
    private final File gameRoot;
    private final File saveRoot;
    private final String execFile;
    private final String optionsJson;

    EngineHostGodotFragment(File gameRoot, File saveRoot, String execFile, String optionsJson) {
        this.gameRoot = gameRoot;
        this.saveRoot = saveRoot;
        this.execFile = execFile;
        this.optionsJson = optionsJson;
    }

    @Override public List<String> getCommandLine() {
        try {
            List<String> arguments = new ArrayList<>(super.getCommandLine());
            File pack = selectedPack();
            if (pack != null) {
                arguments.add("--main-pack"); arguments.add(pack.getAbsolutePath());
            } else if (new File(gameRoot, "project.godot").isFile()) {
                arguments.add("--path"); arguments.add(gameRoot.getAbsolutePath());
            } else throw new IOException("No project.godot or selected PCK/ZIP");
            arguments.add("--user-data-dir"); arguments.add(saveRoot.getAbsolutePath());
            JSONArray extra = new JSONObject(optionsJson == null ? "{}" : optionsJson).optJSONArray("commandLine");
            if (extra != null) for (int i = 0; i < extra.length(); i++) {
                String value = extra.getString(i);
                if (value.indexOf('\0') >= 0) throw new IOException("Godot argument contains NUL");
                arguments.add(value);
            }
            return arguments;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Godot launch: " + error.getMessage(), error);
        }
    }

    private File selectedPack() throws IOException {
        if (execFile != null && !execFile.isBlank()) return confined(execFile);
        File[] packs = gameRoot.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".pck") || lower.endsWith(".zip");
        });
        return packs != null && packs.length == 1 ? packs[0].getCanonicalFile() : null;
    }

    private File confined(String relative) throws IOException {
        if (new File(relative).isAbsolute()) throw new IOException("execFile must be relative");
        File file = new File(gameRoot, relative).getCanonicalFile();
        String root = gameRoot.getCanonicalPath() + File.separator;
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!file.isFile() || !file.getPath().startsWith(root) || !(lower.endsWith(".pck") || lower.endsWith(".zip")))
            throw new IOException("execFile leaves the folder or is not a Godot pack");
        return file;
    }
}
