package com.example;

import com.hloader.mod.ModContext;
import com.hloader.mod.ModEntrypoint;

public final class ExampleMod implements ModEntrypoint {

    @Override
    public void onInitialize(ModContext context) {
        context.log("initialized");
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
