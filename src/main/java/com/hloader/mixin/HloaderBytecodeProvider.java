package com.hloader.mixin;

import com.hloader.ClasspathJars;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

/**
 * Reads raw (un-transformed) bytecode for a class straight off the JVM's actual classpath
 * entries, rather than through {@code ClassLoader.getSystemResourceAsStream}.
 *
 * <p>That more obvious approach opens jar entries via
 * {@code sun.net.www.protocol.jar.URLJarFile}, whose backing {@code java.util.zip.ZipFile}
 * instances are short-lived and reclaimed by the JVM's Common-Cleaner thread. Mixin can call
 * this method from the main thread - while holding its own {@code MixinProcessor} lock - at the
 * same moment Common-Cleaner is finalizing an earlier {@code URLJarFile} for the same underlying
 * zip; the two threads then deadlock on {@code ZipFile}'s internal shared-source cache lock, a
 * JVM-level hazard rather than a bug in Mixin or ASM. Opening each jar exactly once and holding
 * it open for the life of the process - instead of letting {@code URLJarFile} hand out
 * short-lived, GC-reclaimed wrappers - sidesteps the Cleaner race entirely.</p>
 */
final class HloaderBytecodeProvider implements IClassBytecodeProvider {

    static final HloaderBytecodeProvider INSTANCE = new HloaderBytecodeProvider();

    private final ConcurrentHashMap<String, JarFile> openJars = new ConcurrentHashMap<>();
    private volatile RuntimeClassMap classMap = RuntimeClassMap.EMPTY;

    private HloaderBytecodeProvider() {
    }

    /** Set once, from {@code HloaderAgent}, as soon as the runtime obf<->named class map loads. */
    void setClassMap(RuntimeClassMap classMap) {
        this.classMap = classMap;
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException, IOException {
        // Mixin asks for this by whatever name it currently has in hand - sometimes the class's
        // real (obfuscated) name, sometimes a @Mixin target's declared named/deobfuscated name
        // (e.g. resolving the target class itself, before any per-member remapping happens) - but
        // the classpath only ever has the obfuscated one on disk.
        String obfName = classMap.toObfuscatedName(name.replace('/', '.')).replace('.', '/');
        String resource = obfName + ".class";
        try (InputStream in = openClasspathResource(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            ClassReader reader = new ClassReader(in);
            ClassNode node = new ClassNode();
            reader.accept(node, readerFlags);
            return node;
        }
    }

    private InputStream openClasspathResource(String resource) throws IOException {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                InputStream in = openFrom(new File(entry), resource);
                if (in != null) {
                    return in;
                }
            }
        }
        for (Path jar : ClasspathJars.extra()) {
            InputStream in = openFrom(jar.toFile(), resource);
            if (in != null) {
                return in;
            }
        }
        // Not a classpath jar entry - most likely a JDK platform class (e.g. java.lang.System),
        // which lives in the runtime's module system rather than any -cp jar. Those are served
        // through jdk.internal.jrtfs, not sun.net.www.protocol.jar, so they don't share the
        // ZipFile/Cleaner deadlock this class otherwise avoids; safe to fall back here.
        return ClassLoader.getSystemResourceAsStream(resource);
    }

    private InputStream openFrom(File file, String resource) throws IOException {
        if (file.isDirectory()) {
            File candidate = new File(file, resource);
            return candidate.isFile() ? new FileInputStream(candidate) : null;
        }
        JarFile jar = openJar(file);
        if (jar == null) {
            return null;
        }
        JarEntry jarEntry = jar.getJarEntry(resource);
        return jarEntry == null ? null : jar.getInputStream(jarEntry);
    }

    private JarFile openJar(File file) {
        return openJars.computeIfAbsent(file.getPath(), path -> {
            try {
                return new JarFile(path);
            } catch (IOException e) {
                return null;
            }
        });
    }
}
