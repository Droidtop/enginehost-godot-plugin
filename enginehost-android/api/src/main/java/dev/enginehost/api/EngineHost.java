package dev.enginehost.api;
import android.content.Context;
import java.io.File;
public interface EngineHost {
    Context context();
    File saveDirectory();
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
