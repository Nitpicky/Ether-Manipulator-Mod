package com.ethermod.client;

import java.lang.reflect.Method;

/**
 * Shader pack support (Iris / Oculus) without depending on them.
 *
 * <p>Shader packs render the world a second time from the sun's point of view to build the shadow
 * map, and they fire the same render events during that pass. Our effects are positioned relative
 * to the player's camera, so drawn in the shadow pass they show up as a second beam coming from the
 * sky. Every custom effect skips the shadow pass; it also must not sample the nozzle position
 * there.
 */
public final class ShaderCompat {

    private ShaderCompat() {}

    private static Object api;
    private static Method shadowPass;
    private static Method packInUse;
    private static boolean looked;

    /** True while a shader pack is active (custom off-screen layers are avoided then). */
    public static boolean shadersActive() {
        if (com.ethermod.config.ClientConfig.forceSimpleShaders()) return true; // client setting
        inShadowPass(); // looks the API up
        if (api == null || packInUse == null) return false;
        try {
            return (Boolean) packInUse.invoke(api);
        } catch (Throwable e) {
            return true; // unsure: take the safe path
        }
    }

    public static boolean inShadowPass() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                shadowPass = c.getMethod("isRenderingShadowPass");
                packInUse = c.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
                api = null; // no shader mod installed
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) shadowPass.invoke(api);
        } catch (Throwable e) {
            return false;
        }
    }
}
