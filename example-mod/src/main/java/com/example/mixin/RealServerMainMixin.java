package com.example.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Minecraft.class)
public class RealServerMainMixin {

    @Inject(method = "main", at = @At("HEAD"), remap = false)
    private static void hloaderOnRealMain(CallbackInfo ci) {
        System.out.println("CHEESE MR SQUIDWARD!!!!");
    }
}
