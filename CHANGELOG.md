# Changelog

Versions are MAJOR.FEATURE.PATCH. A version that isn't marked as a release rolls into the
next release's notes.

## 1.0.1 (in progress, not released)

### Fixes
- Ziplines: riding no longer swings you from side to side. You now build up speed along the
  rope, turn around smoothly, keep the momentum you jumped on with, and stop cleanly at the ends.
- The Ether Manipulator can no longer be put into, or taken by, any display: item frames, armor
  stands, allays, decorated pots, and modded pedestals, display cases and cutting boards. Blocks
  and creatures act exactly as if your hand were empty while it is summoned: chests, doors,
  buttons and machine screens work as usual.
- Items that a display or creature hands back while the Manipulator is out now go into your
  inventory instead of replacing the item in your selected hotbar slot.
- The Ether Manipulator can no longer be duplicated or lost through other mods' interactions: it
  always stays a single item, and stray dropped copies vanish.
- Ordinary soul fire again follows the vanilla rule (soul sand and soul soil only). Soul fire from
  Soulwake and Soul Blazelings still burns on any ground until it fades.
- Collector: items being pulled toward you no longer stay floating if the server stops, the chunk
  unloads or they pass through a portal mid-pull.
- Mass Driver: lifting a container could empty it if the lifted block failed to appear.
- A jump held while logging out no longer counts as held on the next join.
- Frost bridges no longer leave leftover data behind on long-running servers.

### Sounds and effects
- Tether: the hook now sounds as designed. The shot, the latch, the start of the reel and the
  hook letting go each get a faint End layer, and the latch and release give off small purple
  sparks.
- Water orb: a little slosh in flight and a lighter, cup-sized splash on impact.
- Growth pollen cloud: a soft pop as plants grow under it, once a second at most.

### Compatibility
- Claim and protection checks no longer post extra fake block-break events while the beam rests
  on a block, while the Mass Driver lifts, or while a sweep or harvest is planned. Grief-logger,
  quest and stats mods see far fewer phantom breaks. The `respect_protection` server config
  comment explains this.

### Under the hood
- Large parts of the code were reorganised into smaller pieces for stability and easier fixes.
  No gameplay changes.

## 1.0.0 (release)

First release.
