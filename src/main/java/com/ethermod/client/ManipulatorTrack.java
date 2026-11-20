package com.ethermod.client;

import com.ethermod.item.OrbMode;

/** Per-holder memory to detect "just summoned", "just released" and "just let go". */
final class ManipulatorTrack {
    boolean held;
    boolean using;
    float heldSince = -100;
    float releasedAt = -100;
    boolean releaseFires;
    OrbMode releaseMode = OrbMode.ENERGY;
    boolean caught;
    float caughtEndedAt = -100;
    boolean carrying;
    float carryEndedAt = -100;
    boolean drift;
    float driftEndedAt = -100;
    long orbTick = -1;
    boolean orbCaught, orbFlying, flying;
    boolean orbDrift;
    OrbMode lastMode;
    float modeSwitchAt = -100;
    float leftPressAt = -100;
    float grenadeAt = -100;
    OrbMode leftPressMode;
    // the left click was a set-down (it came while carrying), not a grab
    boolean leftPressPlaces;
    float inspectAt = -100;
    float putAwayAt = -100;
    boolean beaming;
    boolean beamTill;
    float beamSince = -100;
    float beamEndedAt = -100;
    // a one-shot was triggered again while still playing: restart it
    boolean restart;
    float idleSince = -100;
    float nextFidget = 40 + (float) Math.random() * 80;
    float fidgetAt = -100;
    // the ring's own angle, the last keyframed angle seen, last frame time; skip one frame's
    // movement when a new animation starts
    float ringRaw = Float.NaN;
    float ringAngle;
    float ringTime = -1;
    boolean ringSkip;
    boolean ringWasBlending;
    // the ring's inertia: where the motor drives it, its speed, and how heavy it feels now
    float ringTarget;
    float ringVel;
    float ringHz = 5f;
    float ringZeta = 0.5f;
    // ring spin, integrated per frame
    float spinTarget;
    float spinSpeed;

}
