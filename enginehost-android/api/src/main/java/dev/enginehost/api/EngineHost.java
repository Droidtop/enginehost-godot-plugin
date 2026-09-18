package dev.enginehost.api;
import android.content.Context;
import java.io.File;
public interface EngineHost {
    Context context();
    File saveDirectory();
    File cacheDirectory();
    EngineFileSystem fileSystem();
    void finish();
}
