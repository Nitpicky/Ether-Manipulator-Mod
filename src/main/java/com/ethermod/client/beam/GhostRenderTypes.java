package com.ethermod.client.beam;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A see-through block that still hides what is behind it: its shape is written to the depth
 * buffer first ({@link #DEPTH_ONLY}, no colour), then drawn see-through on top, so only its
 * front-most faces show. Extends {@link RenderType} only to reach the protected render states.
 */
@OnlyIn(Dist.CLIENT)
final class GhostRenderTypes extends RenderType {

    private GhostRenderTypes() {
        super("ethermod_ghost_unused", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false, () -> {}, () -> {});
    }

    /** Block-atlas quads that only write depth. */
    static final RenderType DEPTH_ONLY =
            create(
                    "ethermod_ghost_depth",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                            .setTextureState(new TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false))
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setWriteMaskState(DEPTH_WRITE)
                            .createCompositeState(false));
}
