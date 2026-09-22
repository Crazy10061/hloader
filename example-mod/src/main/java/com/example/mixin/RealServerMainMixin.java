package com.example.mixin;

import net.minecraft.server.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Main.class)
public class RealServerMainMixin {

    @Inject(method = "main", at = @At("HEAD"), remap = false)
    private static void hloaderOnRealMain(String[] args, CallbackInfo ci) {
        System.out.println("[example-mod mixin] injected into the REAL net.minecraft.server.Main#main(), not just the bundler");
    }
}
