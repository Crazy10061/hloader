package com.hloader.mixin;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.LoggerAdapterConsole;
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
        // MixinBootstrap.start() checks this: if it's already Phase.DEFAULT, Mixin assumes its own
        // subsystem was bootstrapped *after* the game's pre-init/mod-scanning phase already ran
        // (normal for FML/LaunchWrapper, which transition PREINIT -> DEFAULT themselves once mod
        // loading finishes) and logs "Initialising mixin subsystem after game pre-init phase! Some
        // mixins may be skipped." - which is exactly what happened here, since this used to return
        // Phase.DEFAULT even though hloader bootstraps and registers every config before the game's
        // main() ever runs, i.e. still genuinely in pre-init.
        return Phase.PREINIT;
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

    /** Called from {@code HloaderAgent} once the runtime obf&lt;-&gt;named class map is loaded. */
    public static void setRuntimeClassMap(RuntimeClassMap classMap) {
        HloaderBytecodeProvider.INSTANCE.setClassMap(classMap);
    }

    /** Called from {@code HloaderAgent} once every mod jar is scanned and its mixin configs are
     * registered - see {@link HloaderPlatformAgent#advanceToDefaultPhase()}. */
    public static void advanceToDefaultPhase() {
        HloaderPlatformAgent.advanceToDefaultPhase();
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
        // MixinServiceAbstract.getSideName() is final and only reads from an agent registered
        // here - see HloaderPlatformAgent.
        return Collections.singletonList("com.hloader.mixin.HloaderPlatformAgent");
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return primaryContainer;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return ClassLoader.getSystemResourceAsStream(name);
    }

    /**
     * {@code MixinServiceAbstract}'s own default ({@code LoggerAdapterDefault}) discards
     * everything - the "Logger Adapter Type: Default Logger (No Logging)" line in Mixin's startup
     * banner is this. That silently swallows Mixin's own warnings when e.g. an {@code @Inject}
     * target can't be resolved at runtime, making a real application failure look identical to a
     * mixin quietly doing nothing.
     */
    @Override
    protected ILogger createLogger(String name) {
        return new LoggerAdapterConsole(name);
    }
}
