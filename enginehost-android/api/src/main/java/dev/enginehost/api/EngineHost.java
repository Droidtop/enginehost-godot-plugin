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
     * Added to Enginehost after the first hosts shipped: on an older host the
     * call throws an IncompatibleClassChangeError (NoSuchMethodError when the
     * host's interface does not declare it), and the caller falls back to
     * finish().
     */
    void restart();
}
