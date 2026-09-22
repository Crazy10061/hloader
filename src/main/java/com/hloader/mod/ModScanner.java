package com.hloader.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Walks every class file in the mods directory's jars looking for mod
 * entrypoints (classes annotated {@code @HMod}). Mod jars are expected to
 * already be visible on the system classloader (the agent appends them via
 * {@code Instrumentation.appendToSystemClassLoaderSearch}), so this just uses
 * {@code Class.forName}.
 */
public final class ModScanner {

    private ModScanner() {
    }

    public static List<Class<?>> scan(List<Path> jarPaths) throws IOException {
        List<Class<?>> found = new ArrayList<>();
        for (Path jarPath : jarPaths) {
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory() || !entryName.endsWith(".class") || entryName.equals("module-info.class")) {
                        continue;
                    }
                    String className = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
                    try {
                        Class<?> clazz = Class.forName(className, false, ClassLoader.getSystemClassLoader());
                        if (ModInspector.isEntrypoint(clazz)) {
                            found.add(clazz);
                        }
                    } catch (Throwable t) {
                        System.err.println("hloader: skipping " + className + " in " + jarPath.getFileName()
                                + " (" + t + ")");
                    }
                }
            }
        }
        return found;
    }
}
