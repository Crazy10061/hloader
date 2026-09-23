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
 * {@code MixinServiceAbstract.getSideName()} (client vs. server) is {@code final} and only reads
 * from whatever agent is registered via {@link HloaderMixinService#getPlatformAgents()}; this is
 * that agent. {@code RunDevClient}/{@code RunDevServer} set {@code -Dhloader.side}.
 */
public final class HloaderPlatformAgent implements IMixinPlatformServiceAgent {

    // Mixin instantiates a new agent via reflection to wire it, so the phase-transition callback
    // it hands over is stashed statically for HloaderAgent to reach later.
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

    /** Advances Mixin's bootstrap phase PREINIT -> DEFAULT, so mixin configs (which target
     * DEFAULT) actually get selected. Called from HloaderAgent once mods are scanned. */
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
