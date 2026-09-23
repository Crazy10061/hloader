package com.example.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class RealServerMainMixin {

    @Inject(method = "run", at = @At(value = "HEAD"))
    private void hloaderOnRealMain(CallbackInfo ci) {
        System.out.println("CHEESE MR SQUIDWARD!!!!");
        hloader$checkAccessTransformer();
    }

    @Unique
    private void hloader$checkAccessTransformer() {
        try {
            Field field = this.getClass().getDeclaredField("instance");
            System.out.println("[example-mod] access transformer check: field 'instance' is now "
                    + (Modifier.isPublic(field.getModifiers()) ? "public (widened correctly)" : "still NOT public"));
        } catch (ReflectiveOperationException e) {
            System.out.println("[example-mod] access transformer check failed: " + e);
        }
    }
}
