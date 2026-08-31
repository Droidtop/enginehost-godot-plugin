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
        FragmentActivity activity = (FragmentActivity) session.host().context();
        if (session.display().getId() == android.view.View.NO_ID)
            session.display().setId(android.view.View.generateViewId());
        fragment = new EngineHostGodotFragment(root, session.host().saveDirectory(), session.execFile(), session.optionsJson());
        activity.getSupportFragmentManager().beginTransaction()
            .add(session.display().getId(), fragment, "enginehost-godot-runtime")
            .commitNow();
    }

    @Override public void onDestroy() { fragment = null; }
}
