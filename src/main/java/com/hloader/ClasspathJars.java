package com.hloader;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Jars added to the runtime classpath after the JVM started (mod jars, appended via
 * {@code Instrumentation.appendToSystemClassLoaderSearch}) don't show up in the
 * {@code java.class.path} system property, which only reflects the original {@code -cp}. Code
 * that needs to enumerate every jar actually on the classpath - such as
 * {@code com.hloader.mixin.HloaderBytecodeProvider} reading raw bytecode - needs both; this is
 * where the dynamically-appended half is tracked.
 */
public final class ClasspathJars {

    private static final List<Path> EXTRA = new CopyOnWriteArrayList<>();

    private ClasspathJars() {
    }

    public static void register(Path jar) {
        EXTRA.add(jar);
    }

    public static List<Path> extra() {
        return EXTRA;
    }
}
