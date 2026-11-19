package com.ethermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/** The shooter's side of a refused shot: a low beep, a red flash on the counter, emitter smoke. */
public final class DryFireFx {

    private DryFireFx() {}

    /** When the last refused shot happened (ms), for the counter's flash. */
    static long flashAt = -100000;

    public static void fire() {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.level == null) return;
        flashAt = net.minecraft.Util.getMillis();
        // two short square-wave blips, the second lower: "no"
        mc.getSoundManager().play(SimpleSoundInstance.forUI(com.ethermod.sound.ModSounds.UI_DRY_BEEP, .9f, .45f));
        mc.getSoundManager().playDelayed(SimpleSoundInstance.forUI(com.ethermod.sound.ModSounds.UI_DRY_BEEP, .7f, .45f), 3);
        // a few wisps of smoke where the shot would leave the emitter
        Vec3 look = p.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        Vec3 at = p.getEyePosition()
                .add(look.scale(firstPerson ? .55 : .75))
                .add(right.scale(firstPerson ? .28 : .35))
                .add(0, firstPerson ? -.22 : -.45, 0);
        for (int i = 0; i < 5; i++)
            mc.level.addParticle(
                    ParticleTypes.SMOKE,
                    at.x + (p.getRandom().nextDouble() - .5) * .06,
                    at.y + (p.getRandom().nextDouble() - .5) * .06,
                    at.z + (p.getRandom().nextDouble() - .5) * .06,
                    look.x * .02,
                    .02 + p.getRandom().nextDouble() * .02,
                    look.z * .02);
    }

    /** 1 just after a refused shot, easing to 0 over 450ms. */
    static float flash() {
        long since = net.minecraft.Util.getMillis() - flashAt;
        return since >= 450 ? 0 : 1 - since / 450f;
    }
}
