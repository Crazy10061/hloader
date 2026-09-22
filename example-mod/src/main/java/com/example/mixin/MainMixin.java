package com.example.mixin;

import net.minecraft.bundler.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proof-of-concept mixin. No refmap needed since this class isn't obfuscated.
 */
@Mixin(Main.class)
public class MainMixin {

    @Inject(method = "main", at = @At("TAIL"), remap = false)
    private static void hloaderOnMain(String[] args, CallbackInfo ci) {
        System.out.println("CHEESE MR SQUIDWARD!!!!!!");
    }
}
