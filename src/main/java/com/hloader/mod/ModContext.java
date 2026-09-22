package com.hloader.mod;

/**
 * Handed to a mod's lifecycle methods so it can log and identify itself.
 */
public record ModContext(String modId, String modVersion) {

    public void log(String message) {
        System.out.println("[" + modId + "] " + message);
    }
}
