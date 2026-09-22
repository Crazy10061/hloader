package com.hloader.mixin;

import org.spongepowered.asm.service.IPropertyKey;

record HloaderPropertyKey(String name) implements IPropertyKey {

    @Override
    public boolean equals(Object obj) {
        return obj instanceof HloaderPropertyKey other && other.name.equals(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
