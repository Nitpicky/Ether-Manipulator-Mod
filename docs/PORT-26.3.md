# Port to Minecraft 26.3 (NeoForge)

- Branch `port/26.3` is the only place this work happens. It was cut from main at 1.0.1 (2dc1c5b).
- `main` stays on 1.21.1 and keeps getting the 1.21.1 releases. Nothing here is merged into main.
- Fixes made on main later are brought over with `git merge main` (never the other way round).
- When the port is done, it becomes its own long-lived line (e.g. `mc/26.3`), and the jar is versioned `X.Y.Z+26.3`.

Targets: NeoForge 26.3.0.115, GeckoLib 5.5.6, Java 25, ModDevGradle 2.0.148, Gradle 9.2.1.
The playtest, shader and K-screen workflows don't run on `port/**` by themselves; dispatch them by hand.

## Status

| Part | State |
|---|---|
| Common (server) code | Compiles. A 26.3 dedicated server boots with the mod. |
| Data (recipes, loot modifiers) | Converted to the 26.3 formats. |
| Client (rendering, HUD, screens, input) | Playtested on CI (Vulkan, lavapipe): gameplay checks 44/44, UI probe 192 pass + 12 parked (SKIP), screenshot tour complete. |
| GeckoLib 5 model and animations | Compiles. Model and animation moved to `assets/ethermod/geckolib/`. |

## How the client was rebuilt

- Fx glows and the world overlays (Beacon column, Airwalk tiles, Ore Sense) are recorded during the
  frame and drawn once the level is done, with the mod's own pipelines (`ModPipelines`, `WorldDraw`).
- Entity renderers use render states. Layers that need the entity look it up with
  `RenderStateEntities` (filled by `RenderStateEntityMixin`).
- The Manipulator renders through GeckoLib's item special model (`items/ether_manipulator.json`);
  the renderer comes from `EtherManipulatorItem.createGeoRenderer`.
- The K-screen turntable is a picture-in-picture renderer (`KModelPip`).
- Server pushes on a player (tether, zipline, gusts) also send the velocity straight to that
  player (`util/MotionSync`): `needsSync` alone no longer reaches the player's own client.
- The hurt cooldown moved to `LivingEntity.damageCooldownTime`; the beam clears it each hit.
- Loot modifiers take one `condition`, keyed by `type`.
- `Muzzle` keeps the first-person nozzle on screen (hand FOV = the FOV option) and the third-person
  one as a camera-relative world point.

## Parked (not ported yet)

- Gem glint tint (GemGlint + mixin), soul flame on burning mobs, the no-use dip, the gravity overlay.
- LMB swing suppression (the swing state is private now).
- View bobbing under sideways gravity (walk distance is fixed, the bob is vanilla's).
- The shader-pack (Iris) path in Fx.
- Probe checks reported as SKIP: crack overlay, gravity overlay, third-person camera positions.

## CI helpers (this branch only)

- Each build pushes its log to `playtest-results:port/<sha>/build.log`, and the server boot its log
  to `port/<sha>/server.log`.
- Editing `.github/port-lookup.txt` runs `port-api.yml`, which prints 26.3 / NeoForge / GeckoLib
  signatures to `playtest-results:port/api/<sha>.txt` (format in the workflow header).
