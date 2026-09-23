package com.example.mixin;


import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//@Mixin(Main.class)
//@Mixin(Minecraft.class)
public class RealServerMainMixin {

    //@Inject(method = "main", at = @At(value = "HEAD"))
    private static void hloaderOnRealMain(CallbackInfo ci) {
        System.out.println("CHEESE MR SQUIDWARD!!!!");
    }
}
