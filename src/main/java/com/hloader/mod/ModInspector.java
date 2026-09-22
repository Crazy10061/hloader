package com.hloader.mod;

import java.lang.reflect.Modifier;

final class ModInspector {

    private ModInspector() {
    }

    static boolean isEntrypoint(Class<?> clazz) {
        return clazz.isAnnotationPresent(HMod.class)
                && ModEntrypoint.class.isAssignableFrom(clazz)
                && !Modifier.isAbstract(clazz.getModifiers())
                && !Modifier.isInterface(clazz.getModifiers());
    }
}
