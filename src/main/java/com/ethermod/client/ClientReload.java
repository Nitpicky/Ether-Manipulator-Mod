package com.ethermod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client resource reload (F3+T, a resource pack change): drops what was built from old assets. */
@OnlyIn(Dist.CLIENT)
public final class ClientReload {

    private ClientReload() {}

    public static void onReload() {
        BodyTextures.clear();
        GemGlint.clear();
        UpgradeVisuals.reset();
    }
}
