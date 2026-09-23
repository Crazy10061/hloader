package com.example.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class RealServerMainMixin {

    @Inject(method = "run", at = @At(value = "HEAD"))
    private void hloaderOnRealMain(CallbackInfo ci) {
        System.out.println("CHEESE MR SQUIDWARD!!!!");
        checkAccessTransformer();
    }

    /** Proves hloader's access-transformer support actually widened field "a" - by the time this
     * mixin fires, the class is already loaded (through the game's own natural loading), so
     * reflecting on it here doesn't risk the java.applet.Applet-removed-in-JDK17 issue that
     * eagerly forcing the class to load earlier (e.g. from a mod's onEnable()) would hit. */
    private void checkAccessTransformer() {
        try {
            Field field = Minecraft.class.getDeclaredField("a");
            System.out.println("[example-mod] access transformer check: field 'a' is now "
                    + (Modifier.isPublic(field.getModifiers()) ? "public (widened correctly)" : "still NOT public"));
        } catch (ReflectiveOperationException e) {
            System.out.println("[example-mod] access transformer check failed: " + e);
        }
    }
}
