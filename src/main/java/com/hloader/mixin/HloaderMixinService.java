package com.hloader.mixin;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

/**
 * hloader's Mixin host service. Unlike LaunchWrapper/ModLauncher this
 * environment doesn't own class loading (a JVM {@code java.lang.instrument}
 * agent does the actual bytecode interception instead — see
 * {@code com.hloader.agent.HloaderAgent}), so this service just wires Mixin's
 * internals to the plain system classloader. Discovered via ServiceLoader.
 */
public final class HloaderMixinService extends MixinServiceAbstract implements IMixinService {

    private final IContainerHandle primaryContainer = new ContainerHandleVirtual("hloader");
    private volatile IMixinTransformerFactory transformerFactory;

    @Override
    public String getName() {
        return "hloader";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public Phase getInitialPhase() {
        return Phase.DEFAULT;
    }

    @Override
    public void offer(IMixinInternal internal) {
        super.offer(internal);
        if (internal instanceof IMixinTransformerFactory factory) {
            this.transformerFactory = factory;
        }
    }

    public IMixinTransformerFactory getTransformerFactory() {
        return transformerFactory;
    }

    @Override
    public IClassProvider getClassProvider() {
        return HloaderClassProvider.INSTANCE;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return HloaderBytecodeProvider.INSTANCE;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return primaryContainer;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return ClassLoader.getSystemResourceAsStream(name);
    }
}
