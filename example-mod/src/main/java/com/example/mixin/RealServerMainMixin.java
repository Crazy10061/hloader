package com.example.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

//? if >=1.0 {
import com.hloader.MinecraftVersion;
import net.minecraft.client.Minecraft;
//? }

//? if <=c0.0.13a_03 {
//$$ import com.mojang.minecraft.Minecraft;
//? }

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=c0.0.13a_03 {
@Mixin(Minecraft.class)
//? } else {
//$$ @Mixin(com.mojang.rubydung.RubyDung.class)
//? }
public class RealServerMainMixin {

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void hloaderOnRealMain(CallbackInfo ci) {
        System.out.println("Minecraft version: " + MinecraftVersion.get() + " : " + Runtime.version());
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
