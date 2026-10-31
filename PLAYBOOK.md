# The playbook — measured facts and traps that live in no other doc

Ported 2026-08-24 from the accumulated session record. `WORKPLAN.md` is the
ledger and owns state; `DESIGN.md` owns the why; `CLAUDE.md` owns the rules.
This file owns the rest: what was **measured**, and the traps that produce a
green build and a wrong runtime. Search it before harness, profiler, or
loader-boundary work.

## Build and loader traps — each shipped past a passing build once

- **Fabric and NeoForge parse version ranges differently.** NeoForge wants
  Maven (`[1.21, 1.22)`), Fabric wants semver (`>=1.21 <1.22`). Hence separate
  `minecraft_version_range` and `fabric_minecraft_version_range`, and a guard
  in `fabric/build.gradle` that fails a Maven-shaped Fabric range.
- **`gradlew` must be LF with the exec bit** (`.gitattributes` +
  `git update-index --chmod=+x`) or Linux CI dies. Windows cannot see this.
- **`runServer` requires accepting Mojang's EULA — never accept it on the
  owner's behalf.** `runClient` proves mod init on both sides.
- **NeoForge patches vanilla, so "compiles on both" says nothing about
  "behaves the same on both."** NeoForge widens vanilla APIs (e.g.
  `SavedData.Factory`'s 2-arg null-permitting form does not exist in vanilla
  and throws only on Fabric, only once a save exists) — when an API looks
  forgiving, read the *vanilla* decompiled source. The loaders fire
  entity-conversion events at opposite ends of `Mob#convertTo`, and their
  client-side interact events on opposite sides of the packet send — **cancel
  entity interactions on the server** (`Player#interactOn`), and on NeoForge
  listen to `EntityInteract`, not `EntityInteractSpecific`. NeoForge accepts
  payload registrations only inside `RegisterPayloadHandlersEvent`; shared
  code queues them behind a seam.
- **The world is not there yet:** `LevelReader#getHeight` returns the world
  floor for an unloaded chunk (structures end up in the deepslate — poll
  `hasChunk` first, assert above `getMinBuildHeight()`). Large builds stall
  the server and briefly unload neighbouring entities — **any assertion
  reading loaded entities must poll with a deadline, never sample once.**
- **`PoiManager` is not thread-safe** (plain map + blocking disk reads); POI
  queries stay on the server thread, spread across ticks. 128 blocks =
  289 chunk columns.
- **`Sensing#hasLineOfSight` caches per entity id** and is cleared only by
  the mob's own AI step — a `setNoAi` fixture answers from a stale cache.
  Use `LivingEntity#hasLineOfSight` outside a mob's own tick.
- **The dev client rolls a fresh offline UUID every launch** — record
  player-keyed values in the handoff file, never re-derive them in `verify`.
- **Use `MinecraftServer#getTickCount()` for anything that expires**
  (`/time set` jumps game time thousands of ticks); use
  `getGameTime()/24000` for anything counting days, never `getDayTime()`.
- **A villager is 1.95 blocks tall** — clear three blocks of air, not two.
- **A witness scan makes violence a 200-block problem** — `DeedBus` inflates
  24 blocks on emit, so destructive social tests happen on empty ground far
  from anything the run built.
- **`level.getEntity(id)` and "the villager has a persona" become true at
  different moments** — and a persona is *minted* on load but *generated*
  thousands of ticks later. Poll for the condition the next step actually
  needs, not a neighbouring one. This family has cost more red CI runs than
  anything else.
- **Vanilla still runs `Villager#updateSpecialPrices`** and it fires on the
  same events we do — assert your own contribution off your own return
  value; assert the screen as a composition. One punch = `MINOR_NEGATIVE`
  gossip = every later price reading two emeralds high.
- **`ServerGamePacketListenerImpl.handleInteract` returns silently on an
  unresolvable target** — assert the interaction *happened* separately from
  what it produced.

## Harness and CI

- **The harness verdict is `<loader>/run/namesake-harness-result.txt`**,
  written unbuffered (`RUNNING` until it concludes) — logs are buffered and
  Gradle's exit code lies when the watchdog hard-exits a wedged client.
  A wedged client sits near 2% of a core; a working one near 50%.
- **Clicking away from the dev client pauses the integrated server**
  (`pauseOnLostFocus`, 500 ms) — every server-tick deadline just stops, no
  error. `HarnessClient.unattended` sets `pauseOnLostFocus=false`; if a run
  goes quiet anyway, it is something NEW — do not assume this.
- Gate client automation on "no level, no overlay, some screen", never on
  `TitleScreen` (fresh NeoForge shows `AccessibilityOnboardingScreen`).
- **GitHub's runner is 3–4× slower than the owner's machine.** Never a
  wall-clock assertion in a harness leg; record the number, gate on an
  order-of-magnitude ceiling. The profiler deliberately has no CI job.
- **`Util.backgroundExecutor()` is shared with worldgen** — poll off-thread
  results with a deadline; locally they are always back, on CI they are not.
- **A leg that is green here and a coin toss on CI is a race, not flake** —
  the slow runner is the better instrument for *finding* races. Before
  touching anything: run the same commit locally, AND re-run CI on the
  previous green commit. Those two experiments separate "my change" from
  "always been a coin toss."
- **Run a new harness leg more than once before believing it** — session 15
  found three instrument defects for every mod defect (a leg that drove the
  mechanism it watched; 3-D distance against horizontal radii; assertions
  ahead of `stillWaiting`).
- A long `/tick sprint` outruns the chunk loader (sprint in ~200-tick
  bursts, assert on `entity.tickCount`); a dev client left running holds
  `session.lock`.

## Delivery

- **Pushing from a git worktree does not update the working copy** —
  `git push origin HEAD:main` moves the remote and leaves
  `C:\MCA Reborn Rework` behind; the owner then playtests a build without
  the feature. **Finish every push with
  `git -C "C:\MCA Reborn Rework" merge --ff-only origin/main`.**

## The tick budget (measured, both loaders within 2.1%)

- 400 loaded vanilla villagers = **14.75 ms** mean server tick (p95 19.40);
  linear at **~32 µs per loaded employed villager**; `villagerBrain` is
  10.41 ms of it.
- Our per-tick cost is unmeasurable at whole-tick resolution; the 20-bucket
  sweep over 400 records costs **1.2 µs warm / 3.3 µs cold** — roughly
  **125–225 ns per record visit** of headroom.
- **A virtual record is ~10,000× cheaper than a loaded villager.**
- **400 is a record count, not an entity count** — seven real villages gave
  52 personas, never more than 11 villagers loaded at once.
- The settlement census costs 19 ticks / 289 columns; one cold
  `PoiManager.getInChunk` has hit **72 ms** (disk, not code) — design
  against 72.
- Rerun with `-Pprofile=vanilla|namesake|sections|world`; delete
  `<loader>/run/saves/namesake_profiler` first; record the machine with the
  number.

## Profiler traps — confident wrong numbers

- **`Entity.discard()` does not release POIs** (only `Villager#die` does) —
  release `HOME`, `JOB_SITE`, `POTENTIAL_JOB_SITE`, `MEETING_POINT` before
  discarding, or employment quietly collapses across a run.
- **Clear every entity between measurements** — iron golems persist, dropped
  inventories tick for five minutes. Delete the world between runs; print a
  census of what else is standing.
- **Minecraft's own profiler**: percentages only, inflates the tick ~31%,
  stops itself after 10 s; `villagerBrain` is eleven levels deep and a
  shorter walk prints nothing.
- **Use `MinecraftServer.getTickTimesNanos()`** for whole-tick times; sample
  tick n−1 (loaders fire end-of-tick hooks at different points).
- **Assert the mobs actually ticked** (`entity.tickCount`) — a baseline on
  loaded-but-not-ticking entities is the most confident wrong number
  available.
- Before trusting any timing: an empty measurement for the floor, a fixed
  spin timed by a different clock for the units, two instruments on one
  quantity.

## Reading vanilla

The decompiled 1.21.1 sources are already on disk in the NeoForm cache:
`unzip ~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_*_output.jar 'net/minecraft/world/entity/**'`
into a scratch dir — seconds, where a bare filesystem search times out.
`net.minecraft.world.entity.ai.behavior` is the villager brain. **Before
widening a vanilla member, count who reads it; before building on a vanilla
tolerance, enumerate every behaviour that writes the memory, not just the
one the design names.**
