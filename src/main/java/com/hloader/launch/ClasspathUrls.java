package com.hloader.launch;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces a {@link URLClassLoader} whose {@link URLClassLoader#getURLs()} reports the JVM's
 * actual {@code -cp} entries. {@link LegacyClassLoaderCastTransformer} calls {@link #wrap()} in
 * place of code that does {@code (URLClassLoader) someClass.getClassLoader()} - a pattern old,
 * pre-1.6 Minecraft versions use (via {@code net.minecraft.launchwrapper.Launch}) to read back
 * the classpath, which throws a {@code ClassCastException} on Java 9+ because the real system
 * class loader stopped being a {@code URLClassLoader}. Setting
 * {@code -Djava.system.class.loader} to a {@code URLClassLoader} subclass does *not* fix this:
 * on modern JDKs the main class is loaded by the true built-in system loader before that
 * property ever takes effect, so {@code Launch.class.getClassLoader()} still returns the
 * non-URLClassLoader instance regardless. Patching the bytecode is the only reliable fix.
 */
public final class ClasspathUrls {

    private ClasspathUrls() {
    }

    public static URLClassLoader wrap() {
        return new URLClassLoader(classpathUrls(), null);
    }

    private static URL[] classpathUrls() {
        List<URL> urls = new ArrayList<>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                urls.add(new File(entry).toURI().toURL());
            } catch (MalformedURLException e) {
                // Skip it - a single unparsable classpath entry shouldn't stop the game booting.
            }
        }
        return urls.toArray(new URL[0]);
    }
}
