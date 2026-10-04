package me.jamino.wynndhrangelimiter.mixin.client;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import me.jamino.wynndhrangelimiter.compat.dh.DhBlazeExactState;
import me.jamino.wynndhrangelimiter.compat.dh.DhOpenGlExactState;
import me.jamino.wynndhrangelimiter.WynnVistaMod;
import me.jamino.wynndhrangelimiter.visibility.VisibilityMask;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderBufferHandler.class, remap = false)
public abstract class MixinDhRenderBufferHandler {
    @Unique private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh");
    @Unique private VisibilitySnapshot wynnvista$snapshot;
    @Unique private long wynnvista$lastLoggedRevision = -1;
    @Unique private boolean wynnvista$lastLoggedExact;
    @Unique private int wynnvista$inside;
    @Unique private int wynnvista$intersecting;
    @Unique private int wynnvista$outside;

    @Inject(method = "buildRenderList", at = @At("HEAD"))
    private void wynnvista$beginList(RenderParams params, CallbackInfo ci) {
        wynnvista$snapshot = WynnVistaMod.refreshForRender(MinecraftClient.getInstance());
        wynnvista$inside = 0;
        wynnvista$intersecting = 0;
        wynnvista$outside = 0;
    }

    @Inject(method = "buildRenderList", at = @At("TAIL"))
    private void wynnvista$finishList(RenderParams params, CallbackInfo ci) {
        VisibilitySnapshot snapshot = wynnvista$snapshot;
        boolean exact = DhBlazeExactState.canClip() || DhOpenGlExactState.canClip();
        if (snapshot != null && (snapshot.revision() != wynnvista$lastLoggedRevision
                || exact != wynnvista$lastLoggedExact)
                && wynnvista$inside + wynnvista$intersecting + wynnvista$outside > 0) {
            wynnvista$lastLoggedRevision = snapshot.revision();
            wynnvista$lastLoggedExact = exact;
            String path = DhBlazeExactState.canClip() ? "exact Blaze3D"
                    : DhOpenGlExactState.canClip() ? "exact OpenGL" : "conservative";
            WYNNVISTA_LOGGER.info("DH {} mask revision {}: mode={}, inside={}, mixed={}, outside={}",
                    path, snapshot.revision(), snapshot.mode(), wynnvista$inside,
                    wynnvista$intersecting, wynnvista$outside);
        }
        wynnvista$snapshot = null;
    }

    @Redirect(method = "buildRenderList", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/objects/SortedArraySet;add(Ljava/lang/Object;)V"))
    private void wynnvista$filterSection(SortedArraySet<LodBufferContainer> list, Object value) {
        LodBufferContainer section = (LodBufferContainer) value;
        VisibilitySnapshot snapshot = wynnvista$snapshot;
        if (snapshot == null || snapshot.mode() == me.jamino.wynndhrangelimiter.visibility.MaskMode.PASSTHROUGH) {
            list.add(section);
            return;
        }
        long pos = section.pos;
        VisibilityMask.Classification classification = snapshot.mask().classify(
                DhSectionPos.getMinCornerBlockX(pos), DhSectionPos.getMinCornerBlockZ(pos),
                Integer.toUnsignedLong(DhSectionPos.getBlockWidth(pos)));
        boolean exact = DhBlazeExactState.canClip() || DhOpenGlExactState.canClip();
        switch (classification) {
            case INSIDE -> { wynnvista$inside++; list.add(section); }
            case INTERSECTING -> {
                wynnvista$intersecting++;
                if (exact) list.add(section);
            }
            case OUTSIDE -> wynnvista$outside++;
        }
    }
}
