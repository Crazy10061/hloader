package com.hloader.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/** Backs Mixin's "blackboard" with a simple in-memory map. Discovered via ServiceLoader. */
public final class HloaderGlobalPropertyService implements IGlobalPropertyService {

    private final Map<String, HloaderPropertyKey> keys = new ConcurrentHashMap<>();
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return keys.computeIfAbsent(name, HloaderPropertyKey::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) values.get(((HloaderPropertyKey) key).name());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        values.put(((HloaderPropertyKey) key).name(), value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = values.get(((HloaderPropertyKey) key).name());
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = values.get(((HloaderPropertyKey) key).name());
        return value == null ? defaultValue : value.toString();
    }
}
