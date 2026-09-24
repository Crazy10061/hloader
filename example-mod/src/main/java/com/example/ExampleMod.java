package com.example;

import com.hloader.mod.ModContext;
import com.hloader.mod.ModEntrypoint;

public final class ExampleMod implements ModEntrypoint {

    @Override
    public void onInitialize(ModContext context) {
        context.log("initialized");
        //? if >=1.13 {
        context.log("running on a flattened-registry version (1.13+)");
        //? } else {
        //$$ context.log("running on a pre-flattening version");
        //? }
    }

    @Override
    public void onEnable(ModContext context) {
        context.log("enabled");
    }

    @Override
    public void onDisable(ModContext context) {
        context.log("disabled");
    }
}
