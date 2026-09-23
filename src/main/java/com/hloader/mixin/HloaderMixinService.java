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
        // MixinBootstrap.start() warns and skips mixins if this is already Phase.DEFAULT, assuming
        // pre-init already ran elsewhere (as it does for FML/LaunchWrapper). hloader registers
        // every config before main() runs, so it's genuinely still pre-init.
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

    // MixinServiceAbstract's default logger discards everything, silently swallowing warnings
    // (e.g. an unresolvable @Inject target) that would otherwise look identical to a no-op mixin.
    @Override
    protected ILogger createLogger(String name) {
        return new LoggerAdapterConsole(name);
    }
}
