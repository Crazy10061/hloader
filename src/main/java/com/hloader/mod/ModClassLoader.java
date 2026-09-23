package com.hloader.mod;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * One instance per mod jar, so two mods bundling different (and differently-versioned) copies of
 * the same library never collide - each mod's own dependency classes are only ever visible
 * through its own loader, not to any other mod.
 *
 * <p>This can't isolate everything: hloader's agent transforms game classes in place rather than
 * loading them through a custom classloader, so any class a mixin weaves into a game class - the
 * mixin's own class, and anything its injected method body references - still resolves through
 * whatever classloader actually defines that game class (the shared parent), not this mod's own
 * loader. What this isolates is a mod's "normal" code - its entrypoint and anything only it calls
 * into directly - which is where real dependency collisions between mods actually happen.</p>
 */
public final class ModClassLoader extends URLClassLoader {

    /**
     * Always resolved through the parent rather than redefined locally, so every mod (and hloader
     * itself) sees the same type for these - a mod that bundled its own copy of, say, Mixin's
     * {@code CallbackInfo} would otherwise get one that's a different type from hloader's own,
     * breaking every cast and {@code instanceof} check against it.
     */
    private static final String[] SHARED_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.",
            "com.hloader.", "org.spongepowered.", "org.objectweb.asm.",
    };

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public ModClassLoader(String modId, URL jarUrl, ClassLoader parent) {
        super("hloader-mod-" + modId, new URL[] {jarUrl}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isShared(name) ? getParent().loadClass(name) : loadLocalFirst(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> loadLocalFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return getParent().loadClass(name);
        }
    }

    private static boolean isShared(String name) {
        for (String prefix : SHARED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
