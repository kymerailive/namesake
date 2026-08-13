# WORKPLAN — Namesake

**The ledger.** What happens next, in order, with exit criteria. Read first, update last.
Where any other document disagrees on sequence, this wins.

- **Status:** session 02 complete. **The attach bet holds** and **the authorization gate is real** —
  a persona rides a vanilla `Villager` through save, chunk unload and zombification on both loaders,
  and an unguarded serverbound handler cannot reach a green build. Repo live at
  https://github.com/kymerailive/namesake
- **Target:** 16 sessions to the ship-or-kill test (session 10), playable slice at 15.
- **Companion:** `DESIGN.md` owns *what* we build. This owns *what happens next*.

---

## Status board

| Session | Block | State |
|---|---|---|
| 00 | Repo and skeleton | **done** — 2026-08-13 |
| 01 | Persona, persistence, attach bet | **done** — 2026-08-13 |
| 02 | Authorization layer | **done** — 2026-08-13 |
| 03 | Traits, cultures, settlement detection | **NEXT** |
| 04 | Profiler spike | pending |
| 05 | Bonds and deeds | pending |
| 06 | Episodic memory | pending |
| 07 | Headless simulation harness | pending |
| 08 | Gossip and distortion | pending |
| 09 | Dialogue pools and residency | pending |
| 10 | Roads and propagation — **SHIP-OR-KILL** | pending |
| 11 | Notice Board | pending |
| 12 | Standing bands | pending |
| 13 | Day plan I — free slots | pending |
| 14 | Day plan II — ERRAND activity | pending |
| 15 | Art, config, playtest | pending |

---

## Phase 1 · Foundations

### Session 00 — Repo and skeleton
**Build.** `gh repo create kymerailive/namesake --public` (LGPL-3.0). Multiloader Gradle skeleton:
`common` / `fabric` / `neoforge`, hand-rolled after MCA's 96%-common split — *study the structure,
copy no code*. Mod id `namesake`, package `net.namesake`. CI running **build and test** on both
loaders. Commit `CLAUDE.md`, `WORKPLAN.md`, `DESIGN.md`, `.gitignore` (excluding `reference/`).

**Exit.** Empty mod loads in-game on *both* loaders, CI green, and the push is confirmed on origin.

### Session 01 — Persona, persistence, and the attach bet
**Build.** The `Persona` record. Loader-agnostic attachment to the vanilla `Villager` (Fabric
attachment API / NeoForge data attachments behind one common interface). `NpcRegistry extends
SavedData`, UUID-keyed, with `schemaVersion`. A DataFixer that actually runs, plus a load test
against a saved pre-change world.

**Proves.** *The entire architectural bet.* If a persona cannot ride a vanilla villager through
save/load/unload/zombie-conversion, everything downstream changes — and you learn it now, not at
session 8.

**Exit — verify by effect.** Spawn a villager, attach a persona, save, quit, reload: same UUID.
Unload the chunk and reload: survives. Convert to zombie villager and cure: survives. Then bump the
schema version, load the old save, and *watch the datafixer run*.

### Session 02 — The authorization layer
**Build.** `ServerboundVerb` base that cannot be registered without an explicit
`authorize(ServerPlayer sender, Target target)`. Reach, session-token and rate checks in the base.
One trivial packet end to end.

**Session token, ruled 2026-08-13: a per-interaction nonce issued by the server.** When the server
opens an interaction for a player — a Notice Board, a dialogue screen — it issues a short-lived
token, and packets about that interaction must present a live one. This closes the hole MCA actually
has: a modified client forging packets for a screen it never opened. It is not the vanilla login
session, and not a per-packet replay nonce.

**Also — the rule 5 enforcement test, carried from session 01.** DESIGN §1 says a social value with
no non-display consumer is caught by *a failing test, not by intention*, and session 01 shipped
`Persona.traits` with a ledger risk instead. Write the test: each social field names the non-display
consumer it feeds, and the build goes red when one cannot. `traits` is listed with an **explicit
grant that expires at session 05**, so if 05 ships without the personality weight table the build
fails on its own rather than waiting for someone to remember. Dev-gate `/namesake debug settrait`
in the same pass — it rewrites personality and currently works in any world at op level 2.

**Exit — revert and watch it fail.** Deliberately add an unguarded handler; the build must go red.
Remove it; green. Only then is the gate real.

---

## Phase 2 · Generation and measurement

### Session 03 — Traits, cultures, settlement detection
**Build.** Eight-axis trait vector with **three-layer rolling**: settlement mean → household ±20 →
individual ±25, clamped to ±100 (never to the parental range). Per-culture phonotactic syllable
grammar — >10⁶ names per culture, **must never exhaust**. Culture palette. Settlement registration
from vanilla POI clusters (bell + workstations), with a one-shot 128-block survey producing
specialty, defensibility and the needs vector.

**Exit.** `/namesake debug dump` lists 20 NPCs with names, traits, culture. Walk to a second
village: the names are audibly from somewhere else, and households are recognisably related.

### Session 04 — The profiler spike *(2 days, highest technical risk)*
**Build.** 400 dummy `Persona` records, the 20-bucket rotating sweep, 8 simulated players each
tracking 12 entities. Profile at `mob tick → villagerBrain` (`Villager.java:283-285`), plus sensing,
navigation, goalSelector.

**Order matters.** **Baseline vanilla first** — 400 loaded vanilla villagers with zero mod code —
then add ours. Without that number ours means nothing.

**Profile a populated world, not a spawned-in 400.** Session 01 ruled minting-on-sight: every
`Villager` that loads gets a persona immediately and keeps it, so the record count tracks *world
population*, not player experience. 400 is the working figure but it is an assumption — measure what
a real generated world actually produces before trusting it.

**Exit.** Real numbers written into this ledger. Population target confirmed or revised *before*
anything depends on it. Expected: ours ~5.95 µs/tick against ~18 ms/tick of vanilla ticking, which
is what forces the record-is-authority architecture and a 60–100 loaded-entity ceiling.

---

## Phase 3 · The social core

### Session 05 — Bonds and deeds
**Build.** `Bond` (4 signed axes) and `Deed` records. Six deed types: `GIFT_WANTED`,
`GIFT_UNWANTED`, `FED_HUNGRY`, `STRUCK_RESIDENT`, `KILLED_RESIDENT`, `DEFENDED_RAID`. Witness scan —
`AABB.inflate(24)` + `canSee()`, cap 12, **on emit only, never polled**. Daily cap 8, lazy decay
toward `peak × 0.4`, one static `float[8][6]` personality weight table.

**Tests.** Cap holds; negatives bypass both cap and personality softening; warmth floors at 0;
trust and respect floor at −64; decay is lazy and idempotent.

**Exit.** Feed a hungry villager with 3 witnesses: subject +3, each witness +1. Repeat nine times
in a day: cap holds at 8. The same gift lands differently on a suspicious smith than a warm innkeeper.

### Session 06 — Episodic memory
**Build.** 32-entry deed ring per NPC (~768 B). Exact dedupe on `(npcUuid, deedId)` — possible only
because deeds are structs, not sentences.

**Exit.** Emit 40 deeds at one NPC: ring holds newest 32, zero duplicates, survives save/load.

### Session 07 — The headless simulation harness *(highest leverage in the plan)*
**Build.** Fast-forward a settlement N in-game days without rendering, dumping the chronicle and an
earn-rate report. `DialogueStats` SavedData, `/namesake debug stats`, `/namesake debug earnrate`.

**Why here.** LNK set skill gates at 35–205 against an observed max affinity of **32** — zero
players ever reached the lowest gate, and nobody noticed, because nothing measured earn rates. You
cannot tune a system whose payoff is at hour 30 by playing it.

**Exit.** Run 100 in-game days in under a minute; get a readable chronicle and a bond-earn-rate
histogram. **Every band threshold from here is set from this data, never from intuition.**

### Session 08 — Gossip and distortion
**Build.** Per-settlement deque, cap 32. Drain 4/in-game hour. Same-settlement transfer at 0.35;
`confidence × 0.85` per hop; below confidence 50 the actor blurs to "someone from the north". Max 2
hops. **Nothing is ever invented — facts only degrade.**

**Exit.** In the harness: a deed emitted in A is held by 60%+ of A's residents within two in-game
days at descending confidence, with at least one holder unable to name the actor.

---

## Phase 4 · The thesis

### Session 09 — Dialogue pools and residency
**Build.** Four pools — stranger / known / warm / hostile — × 5 registers × ~8 lines ≈ 160 lines.
Per-culture tics: openers, tag questions, address terms, formality bias. The residency threshold and
**the name swap**.

**Rule.** The stranger pool must *explicitly say it does not know you*. Silence reads as permission;
a gap has to be authored as a negative.

**Exit.** Acceptance steps 1–3 pass. Cross residency and villagers stop saying *stranger* and start
using your name.

### Session 10 — Roads and cross-settlement propagation — **SHIP-OR-KILL**
**Build.** Delaunay over settlement centres pruned to the **relative-neighbourhood graph** — a
complete graph looks like nothing, an MST looks like a tree, the RNG between them looks like roads
someone chose. Edge weight from A* over a 16×16-chunk coarse heightmap grid, one edge per tick
off-thread. Materialise the winning path as real road blocks. Cross-settlement gossip hop at 0.15
with a 1200–6000 tick delay.

**Exit — the test that decides the project.** Acceptance steps 4–5 pass, and **a playtester who was
not told it was coming reacts audibly** when a villager in town B mentions what they did in town A.
If they don't, stop and reconsider the design before writing another line.

### Session 11 — The Notice Board
**Build.** A `lectern` with a block entity — zero new art. Computed on GUI open, never ticked.
Per-viewer standing section, deed rows with day and place, hearsay rows naming where the story came
from. **Every section prints its own absence.**

**Doubles as** the entire onboarding surface. There is no tutorial; this is it.

**Exit.** A new player who has read nothing can open the board and correctly explain what the mod
tracks.

### Session 12 — Standing bands — exactly three consumers
**Build.** Five bands driving *only*: trade price multiplier (1.35 / 1.15 / 1.00 / 0.90 / 0.75),
whether one recipe is taught, and which dialogue pool is selected. Thresholds from session 07 data.
**Resist adding a fourth consumer.**

**Exit.** Full acceptance script green, including step 7 — a second player who has done nothing gets
stranger lines and 1.00 prices everywhere, proving per-player scoping.

---

## Phase 5 · Making it alive

### Session 13 — Day plan I, the free slots
**Build.** Slot LUT, boundary offset with the **hard `spread ≥ 64` floor**, the 8-per-tick
transition governor, the `id % 7` path gate. Slots 0, 1, 4, 5, 7-sleep — the five needing no custom
activity. The industry standoff mechanic.

**Verify first.** Confirm `WorkAtPoi`'s **1.73 m** reach in-engine. `closeEnough = 9` is confirmed
at `VillagerGoalPackages.java:85`; the 1.73 half was not verifiable from the sources on disk, and the
whole mechanic rests on that gap.

**Exit.** At 09:00 every workstation has someone within arm's reach — except the ones eight blocks
away doing nothing, and *you can hear which is which*. Lazy villagers demonstrably do not restock.

### Session 14 — Day plan II, the ERRAND activity *(highest risk)*
**Build.** The custom `ERRAND` activity plus slots 2 (HAUL), 3 (NOON), 6b and 7-watch. The
`addActivitySafely` helper. The deactivation watchdog.

**Risk.** The ERRAND escalation loop is the only identified TPS-killer. This is also **the emergency
parachute**: merging slots 2+3 and deleting this activity removes the entire custom-activity risk
class, at the cost of the midday economy being invisible and the village going quiet at 18:00. The
mod still works without it.

**Exit.** The at-a-glance test passes, and a 400-record harness run shows no TPS regression.

### Session 15 — Art, config, playtest
**Build.** ~25 greyscale textures. Renderer swap for `EntityType.VILLAGER` onto the vanilla humanoid
model. Skin and hair colormaps. Culture palette tinting. The documented "gentle" config preset.

**Note.** Art is genuinely deferrable to here — a dividend of the attach architecture. Everything
through session 14 is playable and testable with vanilla villager appearance.

**Exit.** A stranger plays 45 minutes and can describe, unprompted, something a villager remembered
about them.

---

## After the slice — sequence only, not scheduled

The drama engine is **deliberately outside the slice**. A drama engine built on a propagation system
that turns out not to land is sixteen sessions wasted. Prove the thesis first.

| Sessions | Block |
|---|---|
| 16–20 | Grievance engine — wants, scarcity, 5-stage ladder, arbitration, character drift |
| 21–23 | Mortality, funerals, grief, inheritance, ruins |
| 24–27 | Era ladder 0–3, offices, charters, treasury, prosperity display |
| 28–30 | Secrets, named factions, migration, rival settlements |
| 31+ | Era 4–5, addon API hardening, animals in the social graph |

**One open question parked here deliberately: how optional LLM enrichment is delivered** — players
routing their own API key, or a hosted service. `DESIGN.md` already rules the half that matters
(*optional enrichment only, nothing may depend on it*); this is the delivery detail underneath it,
and it is cheap to decide late and expensive to decide early. **Do not open it before session 10**:
if the propagation thesis fails ship-or-kill, the question evaporates.

The invariant that keeps both options open, and costs nothing to hold: dialogue is *selected* from
authored pools by struct state, and a model may only decorate a line that is already complete and
shippable — never produce one. Enrichment can then be generated ahead of time and cached per
`(culture, pool, register)` rather than per utterance, which bounds the cost to thousands of
generations and keeps latency out of the interaction path entirely.

---

## Standing risks

1. ~~**Session 01 falsifies the attach bet.**~~ **Retired 2026-08-13 — the bet holds.** A persona
   survives save/quit/reload, chunk unload/reload, and villager → zombie villager → cured, on both
   loaders, keeping its id and field values across two entity-UUID changes. The 16-session estimate
   stands.
2. **Session 10 fails ship-or-kill.** If nobody reacts when town B knows their name, the central
   thesis is wrong. Better to learn it at session 10 than 60.
3. **Cultures don't feel foreign.** If settlement two sounds and behaves like settlement one, the
   travel loop collapses around hour 45 and no era ladder saves it. Session 03 is the first read;
   playtest specifically for this before building era 4–5.
4. **Traits have no consumer yet.** `Persona.traits` is written, persisted and displayed, and
   nothing branches on it — precisely the failure `DESIGN.md` §1 forbids. The first real consumer is
   the personality weight table in session 05. **No longer carried by memory as of 2026-08-13:**
   `SocialValueLedgerTest` grants `traits` an exemption that expires after session 05 and compares
   it against this ledger's own status board, so the build goes red at the close of session 05 if
   the weight table is not there. The risk stays listed because the field is still unconsumed — but
   it now fails loudly rather than quietly. Every other `Persona` field is classified the same way;
   the expiry sessions are in the session 02 log and can be ruled differently.

## How a session is verified — ruled 2026-08-13

Two instruments, and the line between them is fixed. **Anything a unit test can prove belongs in a
unit test.** The in-game harness is for what only a running game can show: lifecycle, persistence,
and real engine behaviour. Bond arithmetic, decay curves, gossip confidence and cap logic are unit
tests, not harness legs — never spend six minutes of CI on what a 10 ms test proves.

The harness grows **one leg per session that has one**, and no more. Sessions whose exit criterion
is about how something *feels* (03's foreign-sounding names, 10's audible reaction) are not
machine-checkable at all and stay with the owner.

**A third instrument, added 2026-08-13: the build itself.** Some invariants are neither unit-testable
behaviour nor observable in a running game — they are properties of the *source tree*, and the only
place to enforce them is where the code is compiled. Two exist so far, both for hard rule 6: a
Gradle task per loader module that refuses to compile if anything but the transport seam registers a
payload, and the abstract `authorize` that makes an unguarded verb a compile error. Prefer this over
a test where it applies; a check that runs before compilation cannot be forgotten by a test runner
that was never invoked.

## Never cut — load-bearing walls, not tuning knobs

The `spread ≥ 64` boundary-jitter floor · the 8/tick transition governor · the `id % 7` path gate ·
the player-relative particle emission gate · the `dayDelta ≤ 64` clamp · the `addActivitySafely`
helper · the sleep-skip cold-start mode.

---

## Session log

*(append one entry per session: what shipped, what the exit criterion actually showed, what changed
in this ledger, and the commit range pushed)*

### Session 00 — 2026-08-13 — repo and skeleton

**Shipped.** `67cbbc8..07a49b4`, pushed to `origin/main`.
Repo live at https://github.com/kymerailive/namesake (public, LGPL-3.0).
Hand-rolled multiloader on Gradle 9.6.1 / JDK 21: `common` on NeoForm, `fabric` on Loom 1.17.19,
`neoforge` on ModDev 2.0.141. Version matrix calibrated against MCA's proven 1.21.1 branch.
`net.namesake.platform.Platform` establishes the loader seam via `ServiceLoader`.

**What the exit criteria actually showed.**

- Clean build from scratch green; `namesake-fabric-*.jar` and `namesake-neoforge-*.jar` produced.
- 2 unit tests executed with real JUnit XML — `tests=2 failures=0 errors=0`, not an inferred pass.
- `net/namesake/Namesake.class` present in **both** jars, each compiled against its own remapped
  Minecraft. **The multiloader source-sharing approach is confirmed working.**
- Loaded in-game on both loaders:
  - `[15:01:39] (Namesake) Namesake initialising on Fabric (Minecraft 1.21.1, dev)`
  - `[15:03:22] (Namesake) Namesake initialising on NeoForge (Minecraft 1.21.1, dev)`

**Six defects, all past a green build.** The important one: `fabric.mod.json` declared
`minecraft "[1.21, 1.22)"` — a Maven range. Fabric parses semver predicates and refused to load the
mod entirely with *"requires an unsatisfiable version range"*. It built perfectly and produced a
valid-looking jar. **This is the single best argument for the exit criteria being written as
"loads in-game" rather than "compiles".** Also fixed: generic jar names, a referenced `icon.png`
that does not exist, the wrong NeoForge version range, `minecraftVersion()` wrongly placed on the
`Platform` seam when vanilla already answers it via `SharedConstants`, and `gradlew` heading for
CRLF-without-exec-bit which fails Linux CI.

**New guardrail.** `fabric/build.gradle` now fails the build if a Fabric version range is
Maven-shaped. Verified per rule 3 by feeding it `[1.21, 1.22)` and watching it fail at line 59,
then restoring and watching it pass.

**Carried into session 01.** `$env:JAVA_HOME` must be pinned to JDK 21 (system default is 26).
`runServer` needs Mojang EULA acceptance, so it was deliberately not used — `runClient` proves mod
init on both sides without it. See `HANDOFF_SESSION_01.md`.

**Ledger change.** Session 00 → done, session 01 → NEXT. No plan changes; the 16-session shape
holds.

### Session 01 — 2026-08-13 — persona, persistence, and the attach bet

**Shipped.** `9736e07..5c73416`, pushed to `origin/main`.

**The attach bet holds.** A persona rides a vanilla `Villager` through the whole entity lifecycle on
*both* loaders. Risk 1 is retired and the 16-session estimate stands.

**What shipped.** `Persona` (DESIGN §3) · `PersonaLink` loader seam (Fabric attachment API /
NeoForge data attachments, neither type in `common`) · `NpcRegistry extends SavedData` on the
overworld, UUID-keyed, `schemaVersion` in the NBT from the first save · `NpcSchema` fix ladder ·
`/namesake debug persona|registry|settrait|prune` · `AttachBetHarness`, a dev-only scripted run of
these exit criteria inside a real client.

**Two design calls worth recording.** The entity stores a bare persona **UUID**, not the record: the
registry is the authority, so a persona is never duplicated between two stores that can disagree.
And the persona→entity **binding lives in the registry, not on the persona**, because the entity
UUID changes on every zombification and cure while the identity must not.

**What the exit criteria actually showed.** Both loaders, every leg green:

| Leg | Evidence |
|---|---|
| attach | 3/3 spawned villagers carry a persona |
| chunk unload → reload | 0 resident after walking 800 blocks away; ids and trait values identical on return |
| villager → zombie villager | `Persona 9b1a4daa… survived minecraft:villager -> minecraft:zombie_villager` |
| zombie villager → cured | `… -> minecraft:villager`, trait value intact, registry count unchanged |
| save → quit → reload | 3/3 personas back with the same id and values, each still bound to a live entity |
| datafixer | `NPC registry datafixer: schema 1 -> 2 … rewrote 3 record(s)`, then 3/3 records verified holding the new sentinel |

Subject 0 kept persona `9b1a4daa…` across **two entity-UUID changes** and then a full save and
reload. That is the whole architecture in one line.

**Schema 1 was genuinely shipped before the bump.** Commit `9736e07` is schema 1; the worlds used
for the migration test were written by that build, not fabricated. Hard rule 1 followed as written.

**Four defects. Three were found by running the game rather than building it.**

1. **A migration that never reached disk.** The fixer ran, rewrote the records in memory — and
   Minecraft never wrote them back, because a `SavedData` is only saved when dirty and migrating
   does not make it dirty. The file stayed at schema 1 and re-migrated on *every* load. Found only
   by loading the same world a second time and seeing the migration line again. Now `load()` marks
   the registry dirty when a fix ran, with a unit test both ways.
2. **The two loaders fire their conversion event at opposite ends of `Mob#convertTo`.** Fabric
   before the new entity joins the level, NeoForge after — so on NeoForge the entity-join hook has
   already minted the cured villager a persona of its own. That stray is reaped, guarded on both
   "bound to this entity" and "born this tick". Proven by effect in both directions: the reap line
   appears on NeoForge and is absent on Fabric, and the registry count is unchanged either way.
3. **`SavedData.Factory` differs between loaders.** Vanilla's only constructor takes a non-null
   `DataFixTypes` and dereferences it unconditionally; NeoForge patched in a two-arg form that
   permits null. Passing null compiles on both and throws only on Fabric, only once a save exists —
   session 00's failure mode exactly. Caught by reading the decompiled sources for both before
   writing the call.
4. **The fix for defect 1 defeated the damaged-file guard.** Marking the registry dirty for a
   migration ran *before* the unreadable-record check, so a file that both needed migrating and had
   a record we could not parse would have been rewritten — dropping those records permanently, in
   the exact case the guard exists for. Caught on review, not by a test, and the test written for it
   only earns its place because moving the call back to the old position was watched to fail it.
   Position, not just presence, is the invariant.

**Rule 3 applied five times.** Each of these was reverted and watched to fail before being called
done: the read-only guard, the `Persona.equals` override (without it two personas printing identical
field values compare unequal, which would have made every "the fields survived" assertion vacuous),
the fix-ladder test (bumping `CURRENT` with no matching fix turns the build red — hard rule 1 is now
enforced by CI rather than by intention), the migration/damaged-file ordering, and the harness
itself, which produced two genuine red runs before it produced a green one.

One of those reverts is worth remembering: the first attempt at the ordering test passed with the
guard removed, because the guard was redundant with the `setDirty` override. **The test only became
real when the actual original mistake — the call's position — was restored and watched to fail.**
Reverting the fix you wrote is not the same as reverting the defect you had.

**Three of those red runs were the harness lying, not the mod.** Worth knowing before trusting it
again. A long `/tick sprint` outruns the chunk loader, so the mob never enters the entity tick list
and simply does not tick — `entityTicks=0` while thousands of server ticks elapsed. A zombie left
alive among the spare villagers hunted them out of the loaded area during the cure, which read as
"the persona was lost"; the subjects are `setNoAi` fixtures now and the zombie is discarded once it
has done its one job. And the same sprint defect surfaced a *third* time, in CI, on the leg that
waits for chunks to come back — the records were perfect and only the entity check failed.

**So it was fixed as a class, not a third instance.** Every wait is now a poll against the condition
it actually cares about, with a deadline, and sprinting is a per-wait choice with a stated reason:
sprint for waits on game time (a chunk ticket expiring, a cure counting down), never for waits on
chunk IO. **A blind sprint cannot tell "not yet" from "never", so it reports a slow machine as a
lost persona.** That is the general lesson, and it will apply again to session 07's harness.

**One thing left unexplained.** On the first CI run the integrated server wedged after logging
vanilla's "Saving worlds", inside `saveAllChunks`, and the process never exited — 28 minutes before
it was cancelled, against about three seconds locally. Nothing of ours is on that path and it has
not recurred since. It is *bounded*, not diagnosed: a watchdog hard-exits 45 s after the harness is
finished and warns when it fires, so if it comes back it will say so instead of hanging a runner.
Everything under test is saved and the verdict written before the game is asked to stop, and the
`verify` phase reloads from disk, so a truncated save fails the run rather than hiding in it.

**Ruled at close, by the owner.**

- **The harness stays, and CI runs it on every push** — both loaders, in two launches, under `xvfb`
  with software GL, reading the verdict file the harness writes rather than the exit code (the game
  exits 0 either way, so a job watching the exit code would be green forever). **Done and green.**
  Session 01's exit criteria are now re-checked continuously instead of being true only on the day
  they were first proven, and the cross-loader asymmetry is protected with them: the CI log shows
  exactly one `Reaped stray persona` on NeoForge and none on Fabric.
- **Minting on sight stays.** Every `Villager` that loads gets a persona immediately and keeps it.
  Every villager is a person whether or not you have met them, and witnesses in session 05 need
  identity to already exist. Consequence recorded against session 04: the record count tracks world
  population, so the profiler must baseline against a real generated world.
- **The rule 5 enforcement test lands in session 02**, with `traits` granted an exemption that
  expires at session 05. See the session 02 entry.

**Carried into session 02.** `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client
between runs — starting the next one too early hits the world's `session.lock` and the crash blames
the world, not the timing. Fabric and NeoForge disagree about which screen is up when client ticks
begin (NeoForge showed `AccessibilityOnboardingScreen` on a fresh run directory), so gate on "no
level, no overlay, some screen" rather than on `TitleScreen`. The harness runs in CI now, so a
change that breaks persona persistence turns the build red without anyone remembering to check —
but it costs ~6 minutes per loader, so expect CI to take longer than session 00's 90 seconds.

**Ledger change.** Session 01 → done, session 02 → NEXT. Risk 1 retired; a new risk 4 added for
`Persona.traits`, which is persisted and displayed with no consumer branching on it — the exact
failure `DESIGN.md` §1 forbids, due to be paid off by the personality weight table in session 05.
No changes to the 16-session shape.

### Session 02 — 2026-08-13 — the authorization layer

**Shipped.** `0af54f6..6695c7b` plus this ledger commit, pushed to `origin/main`.

**Both gates are real, and both have been watched to reject something.** An unguarded serverbound
handler cannot reach a green build, and a persisted social value cannot reach one either without a
named non-display consumer or an exemption that expires by itself.

**What shipped.**

- **`ServerboundVerb`** — the base every packet from a client must extend. `authorize` is abstract
  and `receive` is final, so a verb chooses what it *does* and never what it *permits*. The gate
  runs rate → resolve → validity → dimension → reach → interaction token → `authorize`, in that
  order, and nothing in it is overridable.
- **`VerbRegistry`** — the only door in. Refuses, by reflection at registration time, any verb whose
  own class does not declare `authorize`. That is the half a compiler cannot do: a subclass
  inheriting somebody else's permissive answer compiles perfectly, and it is exactly the shape a
  third-party addon verb would take.
- **`InteractionTokens`** — the session token as ruled above. One live interaction per player, bound
  to the target as well as the player, 1200-tick TTL refreshed on use.
- **`RateLimiter`** — a continuously-refilling token bucket per (sender, verb), checked first
  because every check downstream of it costs the server something.
- **`namesake:greet`**, end to end, with the conversation gesture: sneak, empty main hand,
  right-click a villager. First click opens the conversation, the next greets.
- **`VerbTransport`** — the loader seam, plus a Gradle guard per loader module that fails the build
  if anything else registers a payload.
- **`SocialValueLedgerTest`** — rule 5, enforced. Nine entries, one per `Persona` field.
- **`/namesake debug settrait`** and `prune` are now development-only, hidden from the command tree
  and refusing if reached. `settrait` rewrites persisted personality and permission level 2 was not
  a meaningful gate on that.

**Three decisions worth recording.**

1. **Every serverbound packet requires a token — no policy flag, no exemption.** The token lives on
   the `ServerboundPayload` interface, so a serverbound payload cannot be *declared* without one.
   That is only possible because of decision 2.
2. **Only the server opens an interaction.** It does so from the vanilla interact hook, which it has
   already reach-checked; the client can never ask for one. So there is no bootstrap packet needing
   an exemption, and the strictness in decision 1 costs nothing. **This is a design decision, not
   just an implementation one — promote it into `DESIGN.md` if it reads right.**
3. **`authorize` keeps the `ServerPlayer` signature the ledger specified**, and the gate around it
   runs on a small `VerbSender` view instead. A `ServerPlayer` cannot be built without a running
   server, and a gate whose ordering has only ever been exercised through a live game is a gate
   whose ordering nobody has checked. The fake sender returns null for `player()` precisely so that
   the gate touching it would fail loudly.

**What the exit criteria actually showed.** Five deliberate breakages, each watched to fail and then
removed:

| Breakage | Result |
|---|---|
| A verb with no `authorize` | **Does not compile.** `UnguardedVerb is not abstract and does not override abstract method authorize(ServerPlayer,NpcTarget)` |
| A verb inheriting `GreetVerb`'s `authorize`, put in the catalog | **3 tests red.** `Verb namesake:greet cannot be registered: … does not declare its own authorize … See CLAUDE.md hard rule 6` |
| A serverbound payload with no verb | **Red.** *"These serverbound payloads have no verb, so nothing gates them"* |
| A payload declaring neither direction | **Red.** *"net.namesake.verb.UnguardedVerb (neither direction)"* |
| A direct `registerGlobalReceiver` / `playToServer` in a loader module | **Red on both loaders, before compilation**, naming the file |

And four for rule 5:

| Breakage | Result |
|---|---|
| A new persisted record with no ledger entry | **Red.** *"These records are persisted and have no entry in the social value ledger: net.namesake.npc.Rumour"* |
| A field with no ledger entry | **Red.** *"Persona has fields with no entry in the social value ledger: [cultureId]"* |
| `birthTick`'s consumer pointed at an accessor it never calls | **Red.** *"PersonaService.reapStrayMint never reads it. It calls none of [settlementId]"* |
| `traits`' exemption set to a session already past | **Red.** *"WORKPLAN.md says session 02 is next, so these exemptions have expired: Persona.traits"* |

54 unit tests, real JUnit XML, `failures=0 errors=0 skipped=0`.

**The forcing function has no constant to forget.** An exemption's expiry is compared against *this
document's own status board*, parsed at test time. `CLAUDE.md` already makes updating the ledger the
last act of a session and hard rule 2 makes the push mandatory, so the status board is the one thing
that provably moves every session. Comparison is `currentSession > expiresAfterSession`, so the
build goes red at the **close** of the owing session rather than its start — `main` stays green
while the work is being done. `theExpiryComparisonWorks` guards the comparison itself, because an
inverted one would be green forever and nobody would find out until session 05 shipped without the
weight table.

**Exemption sessions, for the owner to rule differently if they disagree.** Only `traits` → 05 was
ruled in the session 01 log; the rest are read off this ledger's own sequence. `settlementId`,
`householdId`, `cultureId` → **03** (the three-layer trait roll and the syllable grammar branch on
all three). `professionId` → **12** (whether one recipe is taught). This is the longest and weakest
exemption: the field duplicates what vanilla already stores on the villager, and if session 12 does
not read it, the honest move is to delete it rather than move the number. `eraOfMajority` → **24**,
which is outside the slice; it moves with the era ladder rather than with anyone's memory.

**One harness step, and why it is not a new leg.** The ledger said session 02 should need no harness
work. It needed one step inside the existing setup phase, for one reason: *everything else in this
session is a pure function and is unit tested* — check order, reach arithmetic, token lifetime, rate
buckets, registration. The single claim no unit test in `common` can make is that Fabric's
`PayloadTypeRegistry` and NeoForge's deferred `RegisterPayloadHandlersEvent` flush actually produce
a payload that survives a round trip, and sessions 00 and 01 both lost time to precisely that gap
between "compiles" and "works in a game". No new CI job, no extra launch, five extra assertions.

**It anchors its negative to a positive**, which is session 01's lesson applied. A refused packet
leaves no mark, so "nothing happened after N ticks" would pass just as well if the packet were still
in flight. Instead: every packet that reaches the handler spends a rate token whether it is accepted
or refused, so the harness polls until the rate bucket appears — that proves arrival — and *then*
checks that the interaction's expiry has not moved, which proves refusal. The accepted packet is the
mirror image.

Both loaders, all legs green. The line that matters:

```
Verb namesake:greet refused for Player370: NO_LIVE_INTERACTION      (Fabric)
Verb namesake:greet refused for Dev: NO_LIVE_INTERACTION            (NeoForge)
PASS  GATE the forged token was refused (interaction expiry unmoved at 9944)
PASS  GATE the real token was accepted (interaction expiry moved 9944 -> 9950)
```

That is a forged packet — right villager, right player, in reach, correct entity id, token the
server never issued — being refused over a real socket in a running game, on both loaders. It is
the hole MCA has 29 of.

**Session 01's asymmetry lesson repeated, and it changed the design.** Fabric fires its client-side
interact callback from `Minecraft.startUseItem`, *before* the vanilla interact packet is sent;
NeoForge fires its equivalent from `MultiPlayerGameMode.interact`, *after* the send. So cancelling
the vanilla trade on the client works on Fabric and cannot work on NeoForge. The cancel therefore
lives on the **server**, where both loaders fire at the same point (`Player#interactOn`). Read out
of the patched sources before writing a line, exactly as session 01's `SavedData.Factory` note says
to. The greet packet still overtakes the vanilla one on Fabric and trails it on NeoForge; that is
harmless only because opening an interaction refreshes rather than re-mints.

**Playtested at close, and it found one.** The gesture works — sneak, empty hand, right-click, and
the villager turns. The placeholder line did not: it put the persona *and* entity UUIDs into the
action bar, which does not wrap, so it clipped at both ends and was unreadable. Now eight hex
characters, which is all it takes to tell two villagers apart in a crowd. Trivial, and worth
recording because it is the working agreement doing its job — a defect no test could have had an
opinion about, found in ten seconds of looking at it.

**Ledger change.** Session 02 → done, session 03 → NEXT. Risk 4 stays listed — `traits` is still
unconsumed — but it is no longer carried by memory. A third verification instrument recorded: the
build itself, for invariants that are properties of the source tree rather than of behaviour. No
changes to the 16-session shape.
