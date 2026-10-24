package dev.enginehost.api;
import android.content.Context;
import java.io.File;
public interface EngineHost {
    Context context();
    File saveDirectory();
    /** The game's own save folder from the launching app, used as is; null otherwise (Enginehost's plugin API). */
    default File gameSaveDirectory() { return null; }
    default void reportSaveDirectory(File directory) {}
    /**
     * A runtime component the running game asked for ("spine-godot"): the verified, read-only root
     * of its bundle once installed and approved, or null. Blocks while the person decides; never on
     * the UI thread. On an older host the call throws an IncompatibleClassChangeError, as for
     * {@link #restart}.
     */
    default File requestComponent(String name) { return null; }
    /**
     * A link the game asks to open, offered to the person by Enginehost and never opened by the
     * core; returns at once, true when Enginehost took it. Same compatibility rule as
     * {@link #restart}.
     */
    default boolean openLink(String url) { return false; }
    File cacheDirectory();
    EngineFileSystem fileSystem();
    void finish();
    /**
     * Ends this runtime and starts the same game again in a fresh process.
     * {@code arguments} are the game's own restart arguments; the next run
     * reads them from {@link #restartArguments()}. Added to Enginehost after
     * the first hosts shipped: on an older host the call throws an
     * IncompatibleClassChangeError (NoSuchMethodError when the host's
     * interface does not declare it), and the caller falls back to finish().
     */
    void restart(String[] arguments);

    /**
     * What the previous run passed to {@link #restart}; empty on a launch
     * that is not a restart. Same compatibility rule as {@link #restart}.
     */
    String[] restartArguments();
}
