package me.jamino.wynndhrangelimiter.mixin.client;

import me.jamino.wynndhrangelimiter.effects.SceneDepth;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the world's depth for the world effects when the game is about to clear it. The world's last pass,
 * {@code late_debug}, clears the depth buffer whenever a gizmo is to be drawn on top of everything; the
 * effects run after it and would otherwise find the world empty.
 */
@Mixin(WorldRenderer.class)
public abstract class MixinWorldRendererGizmoDepth {
    /** The renderer of the {@code late_debug} pass, set up in {@code renderLateDebug}. */
    @Inject(method = "method_75413", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void wynnvista$keepDepthBeforeClear(CallbackInfo ci) {
        if (MinecraftClient.getInstance().getFramebuffer().getDepthAttachment() instanceof GlTexture depth) {
            SceneDepth.keep(depth.getGlId());
        }
    }
}
