package dev.enginehost.api;
public interface EnginePlugin {
    void onCreate(EnginePluginSession session) throws Exception;
    default void onStart() {}
    default void onResume() {}
    default void onPause() {}
    default void onStop() {}
    default void onDestroy() {}
    default boolean onControllerEvent(EngineControllerEvent event) { return false; }
}
