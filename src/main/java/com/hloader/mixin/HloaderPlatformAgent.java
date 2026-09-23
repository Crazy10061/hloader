package com.hloader.mixin;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import org.spongepowered.asm.launch.platform.IMixinPlatformServiceAgent;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.util.IConsumer;

/**
 * {@code MixinServiceAbstract.getSideName()} - which {@code MixinEnvironment.Side.detect()}
 * queries to tell a client run from a server run - is {@code final} and only reads from whatever
 * one of these agents (instantiated by class name via {@link HloaderMixinService#getPlatformAgents()})
 * returns; there's no simpler way to plug a side name into it. {@code RunDevClient}/
 * {@code RunDevServer} set {@code -Dhloader.side} since they already know which jar they launched.
 */
public final class HloaderPlatformAgent implements IMixinPlatformServiceAgent {

    // MixinEnvironment.init(Phase.PREINIT) creates a *new* agent instance via reflection to wire
    // it - not one hloader controls the lifecycle of - so the phase-transition callback it hands
    // over has to be stashed somewhere static for HloaderAgent to reach once mod/config
    // registration is actually done.
    private static volatile IConsumer<Phase> phaseConsumer;

    @Override
    public String getSideName() {
        String configured = System.getProperty("hloader.side");
        return configured != null ? configured.toUpperCase(Locale.ROOT) : null;
    }

    @Override
    public void init() {
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public void wire(Phase phase, IConsumer<Phase> consumer) {
        phaseConsumer = consumer;
    }

    /**
     * Advances Mixin's global bootstrap phase from PREINIT to DEFAULT - without this, mixin
     * configs (which default to targeting phase DEFAULT) never actually get selected/applied to
     * any class, since the environment would otherwise stay in PREINIT forever. FML/LaunchWrapper
     * do the equivalent transition themselves once their own mod-loading step finishes; hloader
     * has to trigger it explicitly, once all mod jars are scanned and all their mixin configs are
     * registered - see HloaderAgent.
     */
    static void advanceToDefaultPhase() {
        IConsumer<Phase> consumer = phaseConsumer;
        if (consumer != null) {
            consumer.accept(Phase.DEFAULT);
        }
    }

    @Override
    public void unwire() {
    }

    @Override
    public AcceptResult accept(MixinPlatformManager manager, IContainerHandle handle) {
        return AcceptResult.REJECTED;
    }

    @Override
    public String getPhaseProvider() {
        return null;
    }

    @Override
    public void prepare() {
    }

    @Override
    public void initPrimaryContainer() {
    }

    @Override
    public void inject() {
    }
}
