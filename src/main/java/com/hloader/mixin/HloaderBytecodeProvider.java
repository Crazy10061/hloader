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
 * Reads raw (un-transformed) class bytecode from the classpath, keeping each opened {@link JarFile}
 * for the process lifetime rather than going through {@code ClassLoader.getSystemResourceAsStream}
 * ({@code URLJarFile}'s short-lived {@code ZipFile}s can deadlock with the Common-Cleaner thread
 * when Mixin calls this while holding its own processor lock).
 */
final class HloaderBytecodeProvider implements IClassBytecodeProvider {

    static final HloaderBytecodeProvider INSTANCE = new HloaderBytecodeProvider();

    private final ConcurrentHashMap<String, JarFile> openJars = new ConcurrentHashMap<>();
    private volatile RuntimeClassMap classMap = RuntimeClassMap.EMPTY;

    private HloaderBytecodeProvider() {
    }

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
        // Mixin sometimes asks by the class's real (obfuscated) name, sometimes by a @Mixin
        // target's declared named/deobfuscated one - the classpath only has the obfuscated one.
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
        // Not a classpath jar entry - likely a JDK platform class served via jrtfs, which doesn't
        // share the ZipFile/Cleaner deadlock this class otherwise avoids.
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
