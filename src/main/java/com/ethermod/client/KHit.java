package com.ethermod.client;

import net.minecraft.network.chat.Component;

import java.util.List;

/** A clickable / hoverable rectangle registered for one frame of the K-screen. */
record KHit(
        String key,
        int x,
        int y,
        int w,
        int h,
        Runnable action,
        List<Component> tip,
        int colour,
        boolean pulse) {
    boolean contains(double a, double b) {
        return a >= x && a < x + w && b >= y && b < y + h;
    }
}
