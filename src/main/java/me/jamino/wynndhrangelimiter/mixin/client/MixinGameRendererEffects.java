package me.jamino.wynndhrangelimiter.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import me.jamino.wynndhrangelimiter.effects.WorldEffects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Runs world effects on the finished world image: after every world pass (LODs included), before the hand. */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererEffects {
    @WrapOperation(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void wynnvista$renderWorldEffects(WorldRenderer worldRenderer, ObjectAllocator allocator,
                                              RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera,
                                              Matrix4f positionMatrix, Matrix4f basicProjectionMatrix,
                                              Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor,
                                              boolean renderSky, Operation<Void> original) {
        // Copied first: other mods are free to modify the matrices they are handed.
        Matrix4f view = new Matrix4f(positionMatrix);
        Matrix4f projection = new Matrix4f(basicProjectionMatrix);
        WorldEffects.beginFrame();
        original.call(worldRenderer, allocator, tickCounter, renderBlockOutline, camera, positionMatrix,
                basicProjectionMatrix, projectionMatrix, fogBuffer, fogColor, renderSky);
        WorldEffects.render(MinecraftClient.getInstance(), camera, view, projection, fogColor,
                tickCounter.getTickProgress(false));
    }
}
