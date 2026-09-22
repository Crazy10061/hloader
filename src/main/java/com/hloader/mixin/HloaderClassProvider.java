package com.hloader.mixin;

import java.net.URL;
import org.spongepowered.asm.service.IClassProvider;

/** Everything (game, libraries, and mods) lives on the system classloader, so just delegate to it. */
final class HloaderClassProvider implements IClassProvider {

    static final HloaderClassProvider INSTANCE = new HloaderClassProvider();

    private HloaderClassProvider() {
    }

    @Override
    @Deprecated
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, ClassLoader.getSystemClassLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, ClassLoader.getSystemClassLoader());
    }
}
