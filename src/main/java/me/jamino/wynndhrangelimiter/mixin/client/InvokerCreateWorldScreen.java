package me.jamino.wynndhrangelimiter.mixin.client;

import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the opt-in fixture world creator confirm the world-creation screen without clicking through it. */
@Mixin(CreateWorldScreen.class)
public interface InvokerCreateWorldScreen {
    @Invoker("createLevel")
    void wynnvista$createLevel();
}
