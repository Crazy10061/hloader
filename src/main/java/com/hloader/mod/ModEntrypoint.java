package com.hloader.mod;

/**
 * Lifecycle hooks for an hloader mod. All methods have empty default bodies
 * so a mod only needs to override the ones it cares about.
 */
public interface ModEntrypoint {

    /** Called once, right after the mod's classes have been loaded. */
    default void onInitialize(ModContext context) {
    }

    /** Called after every mod has been initialized, in load order. */
    default void onEnable(ModContext context) {
    }

    /** Called when the host process shuts down, in reverse load order. */
    default void onDisable(ModContext context) {
    }
}
