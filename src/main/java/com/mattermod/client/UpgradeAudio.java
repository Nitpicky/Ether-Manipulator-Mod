package com.mattermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;

import java.util.*;

/**
 * Local UI audio with priority over a single quiet hover voice. Confirmations follow synced state.
 */
final class UpgradeAudio {
    enum Profile {
        CRYSTAL("Crystal"),
        ECHO("Crystal Echo"),
        REVERSE("Reverse Crystal"),
        DEEP("Deep Resonance");
        final String name;

        Profile(String n) {
            name = n;
        }
    }

    private static Profile profile = Profile.REVERSE;

    private record Cue(
            double at, String event, float pitch, float volume, float seconds, boolean hover) {}

    private final List<Cue> pending = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();
    private double lastHover = -10, lastSwitch = -10, hoverBlockedUntil;
    private int lastTier = -1, lastBody;
    private String lastBuild = "";
    private int lastSetCount = -1;
    private String lastNames = "";

    private static double time() {
        return System.nanoTime() / 1e9;
    }

    private int melodyStep;
    private double lastNote = -10, lastPress = -10;
    private String intent = "";

    String profileName() {
        return profile.name;
    }

    private void cue(double delay, String event, float pitch, float volume, float length) {
        hoverBlockedUntil = Math.max(hoverBlockedUntil, time() + delay + length + .18);
        pending.removeIf(Cue::hover); // Already-playing hover ticks finish naturally.
        pending.add(new Cue(time() + delay, event, pitch, volume, length, false));
    }

    void tick() {
        double now = time();
        for (var it = pending.iterator(); it.hasNext(); ) {
            var c = it.next();
            if (now < c.at()) continue;
            var id = ResourceLocation.parse(c.event());
            var event =
                    id.getNamespace().equals("mattermod")
                            ? Optional.of(SoundEvent.createVariableRangeEvent(id))
                            : BuiltInRegistries.SOUND_EVENT.getOptional(id);
            if (event.isPresent()) {
                var voice = new Voice(event.get(), c.pitch(), c.volume(), c.seconds(), c.hover());
                voices.add(voice);
                Minecraft.getInstance().getSoundManager().play(voice);
            }
            it.remove();
        }
        voices.removeIf(v -> v.finished());
    }

    void hover() {
        double now = time();
        if (now < hoverBlockedUntil
                || now - lastHover < .23
                || !pending.isEmpty()
                || voices.stream().anyMatch(v -> !v.finished())) return;
        lastHover = now;
        pending.add(new Cue(now, "minecraft:block.note_block.hat", 1.8f, .007f, .07f, true));
    }

    void press() {
        press("node");
    }

    void press(String key) {
        intent = key;
        lastPress = time();
        if (key.startsWith("theme")) {
            cue(0, "ui.button.click", 1.15f, .07f, .16f);
            return;
        }
        if (key.startsWith("dye") && !key.equals("dye17") || key.equals("apply-colour")) {
            paint();
            return;
        }
        if (key.startsWith("set") || key.equals("save") || key.equals("delete")) {
            setSound(key);
            return;
        }
        if (key.startsWith("node") || key.startsWith("module-pick")) {
            cue(
                    0,
                    profile == Profile.REVERSE
                            ? "mattermod:ui.reverse_tap"
                            : "block.amethyst_block.hit",
                    1.5f,
                    .028f,
                    .12f);
            return;
        }
        cue(0, "ui.button.click", 1.25f, .045f, .12f);
    }

    private void paint() {
        cue(0, "entity.fishing_bobber.splash", 1.45f, .11f, .3f);
        cue(.015, "block.slime_block.place", 1.6f, .045f, .18f);
    }

    private void setSound(String key) {
        cue(0, "item.book.page_turn", key.equals("delete") ? .85f : 1.35f, .07f, .23f);
        if (!key.equals("delete"))
            cue(.04, "mattermod:ui.crystal_note", key.equals("save") ? 1.5f : 1f, .055f, .3f);
    }

    private void note(boolean off) {
        double now = time();
        if (now - lastNote > 1.1) melodyStep = 0;
        int[] tones = {0, 3, 5, 7, 10, 12, 10, 7};
        float pitch = (float) (.9 * Math.pow(2, tones[melodyStep++ % tones.length] / 12d));
        lastNote = now;
        cue(0, "mattermod:ui.crystal_note", pitch, off ? .075f : .10f, .42f / pitch);
    }

    void open() {
        cue(0, "block.amethyst_block.hit", 1.3f, .055f, .16f);
    }

    /** A choice that can't be taken yet: the deselect sound, as a refusal. */
    void refused() {
        cue(0, "block.amethyst_block.hit", .85f, .085f, .18f);
        cue(.065, "block.amethyst_block.hit", .60f, .075f, .25f);
    }

    void changed(boolean off) {
        if (intent.startsWith("set") || intent.equals("save") || intent.equals("delete")) {
            if (time() - lastPress > .5) setSound(intent);
            return;
        }
        if (off) {
            cue(0, "block.amethyst_block.hit", .85f, .085f, .18f);
            cue(.065, "block.amethyst_block.hit", .60f, .075f, .25f);
            return;
        }
        note(false);
    }

    void switchPanel() {
        double now = time();
        if (now - lastSwitch < .08) return;
        lastSwitch = now;
        switch (profile) {
            case CRYSTAL -> {
                cue(.05, "block.amethyst_block.hit", .8f, .07f, .18f);
                cue(.2, "block.amethyst_block.chime", 1.5f, .09f, .65f);
                cue(1.23, "block.amethyst_block.hit", 1.25f, .065f, .14f);
                cue(1.32, "block.amethyst_block.hit", 1.7f, .065f, .18f);
            }
            case ECHO -> {
                cue(.05, "block.amethyst_block.hit", .9f, .065f, .18f);
                cue(.2, "mattermod:ui.echo_chime", 1.2f, .075f, .66f);
                cue(1.23, "block.amethyst_block.hit", 1.4f, .055f, .14f);
                cue(1.32, "block.amethyst_block.hit", 1.8f, .045f, .18f);
            }
            case REVERSE -> {
                cue(.02, "mattermod:ui.reverse_hit", 1.05f, .075f, .25f);
                cue(.2, "mattermod:ui.reverse_chime", 1.05f, .075f, .66f);
                cue(1.23, "mattermod:ui.crystal_note", 1f, .075f, .32f);
                cue(1.32, "mattermod:ui.crystal_note", 1.5f, .10f, .38f);
            }
            case DEEP -> {
                cue(.05, "block.amethyst_block.hit", .65f, .065f, .2f);
                cue(.2, "mattermod:ui.deep_chime", .85f, .075f, .88f);
                cue(1.23, "mattermod:ui.reverse_tap", .8f, .055f, .18f);
                cue(1.32, "block.amethyst_block.hit", 1.1f, .06f, .2f);
            }
        }
    }

    void auditionNext() {
        stop();
        profile = Profile.values()[(profile.ordinal() + 1) % Profile.values().length];
        lastSwitch = -10;
        switchPanel();
    }

    void observe(net.minecraft.world.item.ItemStack stack) {
        int tier = com.mattermod.trait.TraitBuild.openSlots(stack),
                body = com.mattermod.item.ManipulatorColors.body(stack);
        var entries = com.mattermod.trait.Loadouts.list(stack);
        String names =
                entries.stream()
                        .map(com.mattermod.trait.Loadouts.Entry::name)
                        .reduce("", (a, b) -> a + "/" + b);
        StringBuilder build = new StringBuilder();
        int picks = 0;
        for (int slot = 0; slot < 3; slot++) {
            build.append(com.mattermod.trait.TraitBuild.line(stack, slot));
            for (int t = 0; t < 3; t++) {
                String pick = com.mattermod.trait.TraitBuild.pick(stack, slot, t);
                build.append('/').append(pick);
                if (!pick.isEmpty()) picks++;
            }
        }
        String current = build.toString();
        if (lastTier >= 0) {
            if (tier > lastTier) {
                cue(0, "block.amethyst_block.chime", 1.1f, .12f, .5f);
            } else if (body != lastBody) {
                if (time() - lastPress > .5) paint();
            } else if (entries.size() != lastSetCount) {
                changed(entries.size() < lastSetCount);
            } else if (!names.equals(lastNames)) {
                changed(false);
            } else if (!current.equals(lastBuild) && time() - lastSwitch > .1) {
                changed(picks < lastPicks);
            }
        }
        lastTier = tier;
        lastBody = body;
        lastBuild = current;
        lastSetCount = entries.size();
        lastNames = names;
        lastPicks = picks;
    }

    private int lastPicks;

    void stop() {
        pending.clear();
        for (var v : voices) Minecraft.getInstance().getSoundManager().stop(v);
        voices.clear();
    }

    private static final class Voice extends AbstractTickableSoundInstance {
        final float peak;
        final int length;
        final boolean hover;
        final double ends;
        int age;

        Voice(SoundEvent event, float pitch, float volume, float seconds, boolean hover) {
            super(event, SoundSource.MASTER, RandomSource.create());
            this.pitch = pitch;
            this.volume = volume;
            this.hover = hover;
            peak = volume;
            length = Math.max(2, (int) (seconds * 20));
            ends = time() + seconds + .05;
            relative = true;
            attenuation = Attenuation.NONE;
            looping = false;
        }

        boolean finished() {
            return isStopped() || time() >= ends;
        }

        void cancel() {
            stop();
        }

        @Override
        public void tick() {
            if (hover) return;
            age++;
            if (age >= length) {
                stop();
                return;
            }
            volume = peak * Math.min(1, (length - age) / Math.max(1f, length * .3f));
        }
    }
}
