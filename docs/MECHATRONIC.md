# Mechatronic Module: shelved

The Mechatronic Module (the Redstone Monstrosity Golem companion) is **off for the first release**
while its copyright status is questioned: the Golem and Ministrosity models follow Minecraft
Dungeons designs closely, the sounds are mixed from Minecraft Dungeons audio, and the animations
recreate theirs. Nothing of it ships in the jar any more. It is **not deleted**: everything is kept
and comes back with one command.

## What was taken out

Commit `c360a7e` ("Shelve the Mechatronic Module for the first release") removes, and only removes:

| Part | Files |
| --- | --- |
| Golem, Ministrosity, Redstone Bolt entities | `entity/RedstoneGolem`, `Ministrosity`, `GolemControl`, `RedstoneBolt` |
| Client side | `client/RedstoneGolemRenderer`, `MinistrosityRenderer`, `GolemFx`, `GolemArcs`, `GolemRiding`, `GolemSignalLayer`, `beam/RedstoneBoltRenderer`, `network/payload/GolemFxPayload` |
| Sounds | `registry/ModSounds`, `sounds/golem/*`, `sounds/mini/*`, the `golem.*` / `mini.*` entries in `sounds.json` |
| Models, animations, textures | `geo/entity/monstrosity|ministrosity`, `animations/entity/*`, `textures/entity/monstrosity*|ministrosity*` |
| Module and gem | `trait_line/mechatronic.json`, Tinkerite item + texture, `module_icon_mechatronic`, badge `gui/badge/mechatronic.png`, K-screen entry and icon in `ui/screen.json` |
| Loot | `tinkerite_pillager_outpost`, `tinkerite_woodland_mansion` modifiers, Tinkerite in `chests/any_gem` |
| Advancements | Spoils of War (`tinkerite`), Heavyweight Bout |
| Text | 70 lang keys in each of the 18 languages |
| Hooks in shared code | `OrbMode.GOLEM` (it was the last mode, so saved mode numbers don't shift), Golem left/right click, HUD health and Super Move bar, Badges, animations, ally check, registrations |
| CI playtest | the Golem scene (ticks 1210 to 1562, shots 31 to 37), 6 UI-probe Golem checks; the probe gate went from 205 to 202 PASS |

One side effect: the Plasmic beam's extra "electric snap" on arcing strikes used the Golem's crackle
sounds (cut from Dungeons spark recordings), so it is gone too. The amethyst tick still plays.

A separate commit (`8c5deab`) makes login clear any Manipulator slot whose Module no longer exists,
so an old save that had Mechatronic equipped just shows an empty slot. Loot, K-screen and Sets all
read the Modules from data, so they show nine Modules without further changes.

## Backups

1. **Git history.** Commit `a4a960f` (on `main`) is the last full state with Mechatronic working.
2. **Project files**, outside GitHub, in `mechatronic-archive/`:
   - `mechatronic-files-a4a960f.zip`: every removed file as it was at `a4a960f`.
   - `shelve-mechatronic.patch`: the removal commit as a patch.
   - `ethermod-full-history.bundle`: the whole repository history (`git clone ethermod-full-history.bundle`).
3. Source material (Blockbench files, sound bench, generators) stays in project files under `golem-model/`.

## Bringing it back

```sh
git revert c360a7e
```

That restores every file and hook exactly as it was (checked: after the revert the tree equals
`a4a960f` plus the login cleanup). Then:

- Raise the UI-probe gate in `.github/workflows/playtest.yml` back to 205 (or the current count).
- If code touched by the removal has changed since, the revert may conflict in those shared files
  (`OrbMode`, `LeftClick`, `EtherManipulatorItem`, `ManipulatorHud`, `ModEntities`, lang files).
  Keep both sides; the Mechatronic side is always a self-contained `case GOLEM`, registration or key.
- New strings or Modules added after shelving need their Mechatronic counterparts by hand
  (lang keys for new languages, K-screen pairs).

To bring it back **without the Dungeons-derived parts** (own model, own sounds), revert as above
and then replace only the assets: `geo/entity/*`, `animations/entity/*`, `textures/entity/monstrosity*`
and `ministrosity*`, and the `.ogg` files under `sounds/golem` and `sounds/mini` (the names in
`sounds.json` can stay).
