# Port to Minecraft 26.3 (NeoForge)

- Branch `port/26.3` is the only place this work happens. It was cut from main at 1.0.1 (2dc1c5b).
- `main` stays on 1.21.1 and keeps getting the 1.21.1 releases. Nothing here is merged into main.
- Fixes made on main later are brought over with `git merge main` (never the other way round).
- When the port is done, it becomes its own long-lived line (e.g. `mc/26.3`), and the jar is versioned `X.Y.Z+26.3`.

Targets: NeoForge 26.3.0.115, GeckoLib 5.5.6, Java 25, ModDevGradle 2.0.148, Gradle 9.2.1.
The playtest, shader and K-screen workflows don't run on `port/**` until the port compiles.

## Status

| Part | State |
|---|---|
| Common (server) code | Compiles. A 26.3 dedicated server boots with the mod. |
| Data (recipes, loot modifiers) | Converted to the 26.3 formats. |
| Client (rendering, HUD, screens, input) | Not ported yet: 26.3 has a new renderer. |
| GeckoLib 5 model and animations | Not ported yet. |

While `.github/port-common-only` exists, CI builds and boots the common code only (`-PcommonOnly`
leaves the client package and the client mixins out). Delete the file once the client compiles.

## CI helpers (this branch only)

- Each build pushes its log to `playtest-results:port/<sha>/build.log`, and the server boot its log
  to `port/<sha>/server.log`.
- Editing `.github/port-lookup.txt` runs `port-api.yml`, which prints 26.3 / NeoForge / GeckoLib
  signatures to `playtest-results:port/api/<sha>.txt` (format in the workflow header).
