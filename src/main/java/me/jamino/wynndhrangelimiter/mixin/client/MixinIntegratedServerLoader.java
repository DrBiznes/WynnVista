package me.jamino.wynndhrangelimiter.mixin.client;

import me.jamino.wynndhrangelimiter.debug.FixtureSelection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServerLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServerLoader.class)
public class MixinIntegratedServerLoader {
    @Inject(method = "start(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"))
    private void wynnvista$selectFixture(String saveName, Runnable onCancel, CallbackInfo ci) {
        FixtureSelection.onSaveSelected(MinecraftClient.getInstance(), saveName);
    }
}
