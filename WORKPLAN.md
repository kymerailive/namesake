# WORKPLAN — Namesake

**The ledger.** What happens next, in order, with exit criteria. Read first, update last.
Where any other document disagrees on sequence, this wins.

- **Status:** session 04 complete, **and the budget is real**. Four hundred loaded vanilla villagers
  cost **14.75 ms of server tick** and `DESIGN.md`'s ~18 ms turns out to be that distribution's p90;
  ours does not appear in a whole-tick measurement at all, and the record sweep it hides — 400
  records at one bucket of twenty per tick — costs **1.2–3.3 µs**. Before that: **the attach bet
  holds**, **the authorization gate is real**, and **a villager is from somewhere**. Repo live at
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
| 03 | Traits, cultures, settlement detection | **done** — 2026-08-13 |
| 04 | Profiler spike | **done** — 2026-08-14 |
| 05 | Bonds and deeds | **NEXT** |
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
tracking 12 entities. Profile at `mob tick → villagerBrain` — vanilla pushes that profiler section
itself, in **`Villager#customServerAiStep`**, so the built-in profiler already reports it. Plus
sensing, navigation, goalSelector.

*(This line cited `Villager.java:283-285` until 2026-08-13; the brain tick is at 278-281 in the
current NeoForm decompile. Decompiled line numbers drift with mappings — anchor on the method.)*

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
   travel loop collapses around hour 45 and no era ladder saves it. **The first read passed —
   ruled by the owner, 2026-08-13.** Households read as related and a second village read as
   foreign unprompted. Six cultures differ in phonotactics, baseline disposition and conformity,
   and three properties are machine-checked besides: two points a village apart land in different
   cultures more than 60% of the time, two points inside one village's reach share one more than
   75% of the time, and no two cultures draw from substantially the same consonants.

   **Not retired, and the reason is the number in its own first sentence.** The failure this risk
   names happens at hour 45; the read that passed took minutes. Six cultures is enough to make the
   second village surprising and may not be enough to make the twentieth one, and nothing yet
   distinguishes two settlements *of the same culture* beyond their survey and a ±10 jitter.
   Playtest again at session 15, and again before era 4–5, specifically for whether it is still
   working at the far end of a session rather than at the start of one.
4. **Traits have no consumer yet.** `Persona.traits` is written, persisted and displayed, and
   nothing branches on it — precisely the failure `DESIGN.md` §1 forbids. The first real consumer is
   the personality weight table in session 05. **No longer carried by memory as of 2026-08-13:**
   `SocialValueLedgerTest` grants `traits` an exemption that expires after session 05 and compares
   it against this ledger's own status board, so the build goes red at the close of session 05 if
   the weight table is not there. The risk stays listed because the field is still unconsumed — but
   it now fails loudly rather than quietly. Every other `Persona` field is classified the same way;
   the expiry sessions are in the session 02 log and can be ruled differently.

   **Session 03 raised the stakes rather than lowering them.** Traits are no longer eight zeroes; a
   villager now carries eight rolled values that took a settlement survey, a culture table and three
   layers of arithmetic to produce. Every one of those inputs is now consumed, which means the
   *only* thing in that chain still terminating in a renderer is the output. If session 05 does not
   land the weight table, the honest response is to delete more than one field.

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

**A fourth, added 2026-08-14, and it is deliberately not in CI: the profiler.** `ProfilerHarness`
is armed by `-Dnamesake.profile=<phase>` — its own switch, not another phase of the attach-bet
harness, so a measurement run cannot arm the thing standing between us and a persistence
regression by typo. It gets **no CI job**, and that is a ruling rather than an omission: a
wall-clock number from a shared runner whose neighbours we cannot see is not evidence, and a green
job that means nothing is worse than no job. What *is* in CI is everything about it that is a
property of arithmetic rather than of a machine — the histogram's layout and percentiles, the
sweep's coverage and bucket stability, and the guard that refuses to persist a profiling fixture.
Rerun the measurement when something plausibly moved the budget, and record the machine with the
number; a number without its conditions is the same mistake in a different unit.

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

### Session 03 — 2026-08-13 — traits, cultures, settlement detection

**Shipped.** `6f054f8..781fa26` plus this correction, pushed to `origin/main`. CI green on all three
jobs — build and test, and the harness on each loader.

**A villager is now from somewhere, and it shows in their name and in their numbers.** A settlement
detected from a real bell gives its residents a culture, a household and eight rolled axes, and all
of it survives a save and a reload on both loaders.

**What shipped.**

- **Six cultures**, each with its own phonotactics, baseline disposition, conformity and palette.
  Not a record and not persisted: a persona stores a culture *id*, and the table is code, so the
  palette can never reach a save file and the table can never drift from one.
- **`Cultures`** — the culture map. Voronoi over jittered 512-block cells, leaned by biome
  temperature and floored so every culture stays possible in every climate. Region size is chosen
  against vanilla's village spacing.
- **`Names`** — a total function from a seed to a name. No used-names set, no redraw loop, so it
  cannot exhaust. Names are derived, never stored, so a villager's name cannot drift from the
  fields it comes from.
- **`Settlement`** and the survey — a bell plus what a one-shot 128-block POI census concluded:
  trade, defensibility, and a four-wide needs vector. Persisted inside `namesake_npcs.dat`.
- **`SettlementRegistrar`** — census on the server thread at sixteen chunks a tick, scoring
  off-thread, commit back on the server thread. One survey per 128-block cell, ever.
- **`TraitRoll`** — settlement mean → household ±20 → individual ±25, clamped to ±100 and to
  nothing else.
- **`Personas`** — minting and generating as two steps, with the gap between them a state a save
  file can legitimately hold.
- **Schema 3**, with a fixer that has been watched to run against worlds written by the previous
  build on both loaders.
- **`/namesake debug dump`** and **`settlements`**.
- **A fourth harness leg**: build a village, watch the mod find it.

**Six decisions worth recording.**

1. **Settlements live in the registry's own file, under the same schema version.** A persona
   references its settlement by id. Two files can be torn apart by a crash between two writes, and
   the result is every villager in a village pointing at an id nothing answers to. One file, one
   version number that cannot disagree with itself, one load path to get right.
2. **The thread split is where safety puts it, not where `DESIGN.md` §8 would prefer.**
   `PoiManager` is a `SectionStorage` over a plain hash map that reads a whole chunk column off
   disk on a miss; it is not thread-safe and no amount of wanting the survey off-thread changes
   that. What it *can* stop being is one blocking lump — a 128-block survey is 289 chunks, and it
   now spends sixteen a tick. The scoring, which is the part that would actually cost something, is
   pure arithmetic over immutable values and runs off-thread as written. **The census half is
   recorded as a cost session 04 should measure rather than as a solved problem.**
3. **`Settlement` has no culture field.** It had one until rule 5 was applied to it honestly: a
   settlement's culture is a pure function of the world seed and the biome at its bell, both stable
   for the life of a world, so storing it is caching — and a cache with no behaviour of its own is
   a persisted value with no consumer. Deleting it was easier than justifying it. A *persona* still
   stores its own culture, because a person's culture is where they were born and not where they
   currently live, and session 28's migration will need that distinction.
4. **A household is a 16-block cell of the world, measured from the bell.** Derived, not stored:
   nothing to persist, nothing to migrate, nothing that can drift out of step with the positions it
   describes. The cost is a grid — two neighbours either side of a boundary are not family — and
   that is the trade, recorded rather than hidden. The alternative, clustering beds at registration,
   costs a pass over every bed and a persisted table, to answer a question no mechanic asks yet.
5. **Per-culture conformity scales the individual layer, and only ever narrows it.** It is what
   makes `Persona.cultureId` change persisted numbers rather than only a string, and it means a
   Tal-Qir household produces people who resemble each other while a Meridian one does not. The
   ruled ±25 remains the bound for every culture. **Ruled by me, not by the ledger — overrule it
   if it reads as invention.**
6. **`WORKPLAN.md` asks for 10⁶ names per culture, and that is measured on full names.** A name in
   this system is what a villager is called — Bram Ashwood, not Bram. Full names clear a million by
   three orders of magnitude everywhere. Given names alone clear a quarter million everywhere and a
   million in five of six; Yun is the exception, and it cannot be widened without either lengthening
   its names or making it sound like Ashani. **Flagged rather than quietly decided.**

**What the exit criteria actually showed.** `/namesake debug dump`, read out of a running game at
the built village:

```
 9 of 9 persona(s) — loaded, nearest first
  axes: war ind bol cur tra acq tem soc
settlement 0  Karsk  FARMING  defensibility 82  needs food=0 tools=34 shelter=0 trade_goods=17
  household 1896895516
    Kranzhirn Gvirnsk      Karsk  -012 +048 +030 -014 +038 +034 +037 -036
    Zhorzannak Gvirnsk     Karsk  -020 +043 +011 -015 +033 +031 +041 -042
    Razovur Gvirnsk        Karsk  -023 +033 +023 -013 +036 +007 +043 -021
  household 1872263607
    Zokzhyurk Stuksk       Karsk  -002 +031 +032 -028 +032 -018 +036 -054
    Tryrgen Stuksk         Karsk  +012 +036 +018 -021 +033 -033 +003 -051
    Kroztusk Stuksk        Karsk  -002 +020 +026 -018 +051 -027 +010 -048
unsettled
    Thulvisdrar Sterbrook  Vale   +011 -004 +001 +010 +005 +020 +007 +036
    Grolwomteath Sterbrook Vale   +022 -001 +005 -020 +021 -016 -010 +038
    Tamgeall Sterbrook     Vale   +033 +008 -011 +010 +016 +013 -024 +043
```

The Gvirnsk household runs acquisitive (+7 to +36); the Stuksk household, twenty blocks away in the
same village, runs the other way (−18 to −33). Both are unmistakably Karsk against the Vale trio.
That is the machine-checkable half.

**The other half is ruled: it landed, both halves, on the owner's playtest at close.** Households
read as related, and a second village 3,000 blocks away read as foreign without being pointed at.
No culture pair was reported as weak, so nothing was widened. That is the exit criterion met on the
question no test in this repo could have had an opinion about — and it is the first real evidence
against standing risk 3.

Every harness leg green on both loaders, in two launches each:

| Leg | Evidence |
|---|---|
| generate | 3/3 villagers 800 blocks from anywhere became people with a culture and no settlement |
| no invention | no settlement was registered for a place with no bell |
| detect | exactly one settlement registered, centred on the bell we placed at 800, 63, 300 |
| survey | census read FARMING from three composters against one each of three other trades |
| survey | needs food=0 tools=34 — a farming town feeds itself and is short of a smith |
| survey | defensibility 82 from a compact 6-workstation, 6-bed cluster |
| residents | 6/6 villagers placed in the settlement the survey registered |
| households | two cells twenty blocks apart made two households, one family name each, not shared |
| reload | every settlement field survived, and 6/6 residents came back with the same name |
| datafixer | `schema 2 -> 3 (culture 0 now means unassigned (-1); settlements added) rewrote 3 record(s)` |
| backfill | 3/3 migrated personas were given a culture and rolled traits on load |

**Hard rule 1 followed as written.** The migration was run against worlds *written by commit
`6f054f8`* — the setup phase was run on the pre-change build on both loaders before a line of
session 03 was written, and those saves were kept. The fix is the quiet kind: schema 2 wrote
`culture = 0` meaning "none", session 03 gives culture 0 to Vale, so without it an existing world
loads perfectly, throws nothing, and every villager on the map is silently Vale — same names, same
palette, same disposition, in every settlement. Not a crash. A save that looks like it worked.

**111 unit tests**, real JUnit XML, `failures=0 errors=0 skipped=0`.

**Five defects. Four of them were found by an instrument rather than by reading the code.**

1. **A name space that counted twelve million and behaved like a hundred and thirty thousand.**
   Yun's grammar summed to 12,450,816 given names and repeated itself after eight hundred draws.
   The syllable count is drawn uniformly, so half of all names came out of a 64,512-name
   two-syllable space that the twelve-million headline had buried. Found by the distinctness test,
   which measured 84,674 distinct names in 100,000 draws — and the arithmetic of the birthday
   problem said that was exactly right for a space of 257,000, not of twelve million. The
   requirement is now measured against the collision-equivalent size, which is dominated by the
   *shortest* length rather than the longest, and the empirical test checks real draws against
   that figure rather than against a percentage somebody picked. Three of six grammars were widened
   as a result. **A requirement checked against the flattering number is a requirement that is not
   checked.**
2. **Fifteen- and nineteen-character names.** `Theardraelthild`. `Hseingtsainhianng`. A
   three-syllable draw could stack three diphthongs between three consonant clusters. Heavy nuclei
   are word-final now, which is a real phonotactic pattern and caps a name at fourteen characters
   with a mean of eight. **The fix had to be a rule rather than "redraw if it comes out too long"**
   — a redraw is a loop, and a loop is the thing the generator is not allowed to have. There is now
   a layout-budget test, which is session 02's action-bar lesson generalised: a string nobody has
   measured against the space it must sit in.
3. **Every unsettled villager in the world was one family.** The household grid is anchored at the
   settlement's bell; with no settlement the first version anchored it at the villager's own feet,
   which puts every unsettled villager into cell (0,0). Three wilderness villagers and a villager
   in a player's base a thousand blocks away would all have been Sterbrooks. **Found by reading the
   debug dump's output rather than by any test** — the instrument earning its place the first time
   it was pointed at something.
4. **A village built at y = −64, inside the deepslate.** `LevelReader#getHeight` returns the world
   floor for a chunk that is not loaded, and the harness read the heightmap before teleporting
   anyone there. Three of six villagers suffocated and the leg reported "not placed in the
   settlement", which is true and useless. Session 01's rule, third application: poll for the world,
   never assume it. The site now asserts it is on real ground, so the next occurrence says so.
5. **3/6 residents on NeoForge and 6/6 on Fabric, from pure timing.** Laying the village stalls the
   server for half a second, it then runs a dozen catch-up ticks, and the three villagers one chunk
   east are briefly unloaded while the chunk tickets around the player's new position settle. The
   personas were never lost — they generate when their chunk comes back — but a check that reads
   *loaded entities* has to wait for them. Session 01's rule, fourth application, and the fourth
   time this project has been bitten by asserting on a world that had not finished arriving.
   Diagnosed by logging rather than by theorising, after three wrong hypotheses.

A sixth, minor and in the other direction: a survey test fixture claimed twelve beds for thirteen
job sites and expected no shelter need. The code was right and the fixture was wrong.

**Rule 3 applied to every new guard.** Six deliberate breakages, each watched to fail and then
removed. The status board had already been moved to session 04, so the first of these is the
forcing function firing for real rather than a simulation of it:

| Breakage | Result |
|---|---|
| `settlementId`'s exemption restored exactly as session 02 wrote it | **Red.** *"WORKPLAN.md says session 04 is next, so these exemptions have expired: Persona.settlementId"* |
| `cultureId`'s consumer pointed at `TraitRoll.settlementMean`, which does not read it | **Red.** *"never reads it. It calls none of [cultureId]"* |
| The schema 2 → 3 fix made to run, log, and change nothing | **Red.** *"expected: &lt;-1&gt; but was: &lt;0&gt;"* — a fixer that does nothing loads without crashing too |
| A child clamped to its parent's range | **2 red.** *"an individual can never reach +100"*, *"the top of the ±25 range is never reached"* |
| A `Set<String> ISSUED` added to `Names` | **Red.** *"Names.ISSUED is a static Set. Even final, a collection can be added to"* |
| `Names.tidy` made to truncate, shrinking the realised space below the counted one | **2 red.** *"expected about 90286 distinct names from YUN in 100000 draws and got 53960"* |

The last one is worth its place: it is the defect that actually happened this session, reintroduced
deliberately, and the test that found it the first time found it again with a number rather than a
shrug.

**The forcing function fired, and one exemption did not land where it predicted.** `settlementId`,
`householdId` and `cultureId` all expired at the close of this session and all three now name
`TraitRoll`. But the note against `cultureId` said the *syllable grammar* would consume it, and a
grammar turns a culture id into a string shown to a person — a display, however non-display the
package it happens to live in. Naming it would have been exactly the technicality
`SocialValueLedgerTest` exists to refuse, and moving the date is the one thing it exists to stop.
So the answer had to be a real consumer: the roll reads a culture's baseline for the settlement
mean and its conformity for the individual layer. **The mechanism worked, and it worked by being
wrong in an interesting way — it caught a consumer that was named in good faith and was not one.**

`Settlement`'s six fields each name a consumer, with **no new exemptions at all**. That is not
restraint; it is what happens when the rule is applied while the record is being written rather
than afterwards.

**Carried into session 04.**

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs.
- **Two numbers to measure, both created this session.** The census is 289 chunk POI reads per
  settlement, spread at sixteen a tick — bounded and one-shot, but unmeasured. And
  `PersonaService.onEntityLoad` now runs `Personas.onPersonaLoaded` for every villager on every
  chunk load; it returns on its first line for anyone already generated and settled, which is
  everybody almost always, but "almost always" is the kind of claim session 04 exists to check.
- The population figure the profiler needs is now easier to get honestly: `/namesake debug
  settlements` reports residents per settlement in a real generated world.
- Minting on sight still stands, so the record count tracks world population.

**Ledger change.** Session 03 → done, session 04 → NEXT. Risk 3 rewritten around its first read
passing, and deliberately **not** retired: the failure it names is at hour 45 and the read took
minutes. Risk 4 raised rather than retired — `traits` is still the only thing in the generation
chain terminating in a renderer, and everything feeding it is now consumed, so if session 05 does
not land the weight table the honest response is to delete more than one field. No changes to the
16-session shape.

**Ruled at close, by the owner.** The exit criterion landed on both halves: households read as
related, and a second village 3,000 blocks out read as foreign without being pointed at. ±20 and
±25 stand as shipped, and no culture pair was called out as weak, so no inventory was widened. The
±25 individual spread and the per-culture conformity that narrows it are therefore ruled rather than
merely proposed.

**Deliberately still open, and the honest limit of what that playtest proved.** Two settlements of
the *same* culture are separated only by their survey and a ±10 jitter, which is thin — it did not
show at two villages and it is the first thing to look at if the world starts feeling repetitive at
scale. The right instrument for that is session 07's headless harness, not another playthrough.

The decision parked in the session 02 log — "only the server opens an interaction", proposed for
promotion into `DESIGN.md` — is still parked and was deliberately not actioned.

### Session 04 — 2026-08-14 — the profiler spike

**Shipped.** `COMMITRANGE`, pushed to `origin/main`. CI green on all three jobs.

**Hard rule 4 first, and the number it was protecting is right.** Four hundred loaded vanilla
villagers cost **14.75 ms of server tick, mean** — p50 14.68, p95 19.40, p99 22.02, max 27.22, over
1,200 consecutive ticks. `DESIGN.md` §8 has carried ~18 ms since before there was any code to
measure. **18 ms is that distribution's p90.** The figure stands; it was a tail number rather than a
typical one, and it now has a shape instead of a value.

**Ours does not appear in that measurement at all.** Same world, same seed, same terrain, same four
hundred villagers, our hooks live rather than inert: **14.43 ms** — 0.3 ms *below* the vanilla run,
which is smaller than the spread between two runs of the same phase. The report says it in the
plainest available way: at the 400-villager cell the meters record *"no meter recorded a sample and
no counter moved"*. Not a small number. **Zero calls.** So the sweep had to be measured directly,
and it costs **1.2–3.3 µs a tick** for a 400-record population.

#### What was measured, and under what conditions

Windows 11, single-player integrated server, dev client at render distance 10, simulation distance
10, 30 fps cap, no vsync. Fixed seed 20260814, **world deleted and regenerated before every run**.
Sixteen sites on a 4×4 grid at 56-block spacing, each 24×32 of cleared *real terrain* — not a
platform — with a bell, 25 workstations and 25 beds. Day frozen at 2000, which is LABOUR_I in
`DESIGN.md` §7 and the busiest hour a villager brain has. Per cell: 400 warm-up ticks discarded,
then 1,200 ticks measured.

**Whole-tick times come from vanilla's own `MinecraftServer.tickTimesNanos`**, not from a clock of
ours: the number is produced by the thing being measured and our sampling costs one array read.

#### The vanilla curve

| loaded villagers | employed | mean | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| 0 | — | 1.73 ms | 1.64 | 2.36 | 2.88 | 9.28 |
| 96 (8 sites × 12) | 88 | 4.59 ms | 4.46 | 6.29 | 7.47 | 15.06 |
| 100 (4 × 25) | 94 | 4.75 ms | 4.46 | 7.08 | 8.65 | 47.38 |
| 200 (8 × 25) | 179 | 7.93 ms | 7.34 | 11.27 | 12.85 | 17.83 |
| **400 (16 × 25)** | **339** | **14.75 ms** | **14.68** | **19.40** | **22.02** | **27.22** |
| 400, MC profiler recording | 341 | 19.35 ms | 18.87 | 24.64 | 28.31 | 69.39 |

**Linear at ~32 µs per loaded, employed villager per tick** — 29.8, 30.2, 31.0 and 32.6 µs across
the four points. Reproduced across two independent runs of the whole phase: 14.58 and 14.75 ms for
the 400 cell, 1.2% apart.

#### The same cells with our hooks live

| loaded | vanilla | namesake | difference |
|---|---|---|---|
| 0 | 1.73 ms | 1.66 ms | −0.07 |
| 100 | 4.75 ms | 4.88 ms | +0.13 |
| 200 | 7.93 ms | 7.97 ms | +0.04 |
| 400 | 14.75 ms | 14.43 ms | −0.32 |
| 400 + the 400-record sweep | — | 14.49 ms | — |

Every difference is inside ±0.35 ms and two of the four are negative.

#### Both loaders, because "it compiles on both" has never meant "it behaves the same"

Every cell was run again on NeoForge, same machine, same seed, world deleted first:

| | Fabric | NeoForge | apart |
|---|---|---|---|
| idle | 1.73 ms | 1.72 ms | 0.6% |
| 96 loaded | 4.59 ms | 4.64 ms | 1.1% |
| 100 loaded | 4.75 ms | 4.85 ms | 2.1% |
| 200 loaded | 7.93 ms | 7.93 ms | 0.0% |
| **400 loaded** | **14.75 ms** | **14.95 ms** | **1.4%** |
| 400, with our hooks live | 14.43 ms | 15.25 ms | 5.4% |
| `villagerBrain` at 400 | 10.41 ms | 10.84 ms | 4.1% |
| the sweep, warm | 1.28 µs | 1.51 µs | — |

**The loaders agree.** Every vanilla point is within 2.1% and most within 1%; NeoForge's patched
entity tick is very slightly the more expensive of the two and the difference is smaller than the
spread between two runs of one phase. This is the one place in three sessions where the answer has
been "no difference worth recording", and it is worth recording that it was checked.

#### The sweep, measured directly

`PersonaSweep.advance` — one bucket of twenty, so twenty records visited per tick:

| population | per tick | p50 | p95 | p99 | per record |
|---|---|---|---|---|---|
| 400 records, empty visitor | 2.64 µs | 2.11 | 3.90 | 4.61 | ~132 ns |
| 400 records, probe payload | 3.26 µs | 3.14 | 4.03 | 6.02 | ~163 ns |
| 304 records, probe, warm | 1.21 µs | 1.12 | 1.92 | 2.30 | ~80 ns |
| 400 records + 400 villagers, warm | 1.28 µs | 1.22 | 1.82 | 2.43 | ~64 ns |

The last two are the same code later in the same run, and the difference between them is the JIT —
which is what "not one sample on a warm JIT" was asking to see, arriving from the other direction.
**Take 1.2 µs as the steady state and 3.3 µs as the cold worst case.**

`DESIGN.md` §8 budgets ~5.95 µs/tick for all of our code. The frame plus a trivial payload spends a
fifth to a half of it before sessions 05–14 have written a line, which leaves **roughly 2.5–4.5 µs
a tick, or 125–225 ns per record visit**, for everything the sweep will actually do. That is the
number to hold future sessions to.

**The 100× claim is off by two orders of magnitude, in our favour.** A loaded villager costs ~32 µs
a tick. A virtual record costs 1.28 µs ÷ 400 = **3.2 ns a tick amortised** — ten thousand times
cheaper, not a hundred. That margin is headroom for a payload, not a reason to spend it.

**And the architecture's own scenario, measured end to end.** `DESIGN.md` §8's world — 96 loaded as
entities and the other 304 as records the sweep advances — costs **4.51 ms a tick**, against
14.75 ms for the same four hundred people all loaded. The record-is-authority design is worth
**3.3× of the tick** at this population, and that is the whole argument for it, now with a number
under it.

#### Session 03's two costs

Measured in a real generated world while walking into seven villages.

**The census.** Ten completed, 190 server ticks and 2,890 chunk columns between them: **19 ticks and
289 columns each**, exactly the shape session 03 predicted.

| | n | mean | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| `PoiManager.getInChunk`, one column | 2,890 | 61.7 µs | 10.5 | 30.2 | 75.8 | **72.43 ms** |
| `SettlementRegistrar.step`, 16 columns | 190 | 946 µs | 176 | 623 | 53.5 ms | **72.44 ms** |
| `SettlementSurvey.score`, off-thread | 7 | 3.75 ms | 32 µs | 26.1 ms | — | 26.07 ms |

**Bounded in total, and not bounded in the tail.** A census costs under a second of game time and
never runs twice for one place, so session 03's "bounded and one-shot" is true. But a single cold
chunk column took **72 ms** — more than a whole tick's budget in one blocking read — and the p99 of
a census tick is 53 ms. A second run of the same phase saw 10.5 ms as its worst column, so the tail
is disk-bound and varies by a factor of seven between runs on one machine. **That is the census's
real cost: not its mean, which is nothing, but two or three visible hitches per world explored.**
Nothing to do about it yet — `PoiManager` cannot leave the server thread — but it is a measured
number now rather than a hope, and it is the first thing to point at if a player reports a stutter
on walking into a new village.

**`Personas.onPersonaLoaded`, and what "almost always" turned out to mean.** Session 03 claimed it
"returns on its first line for anyone already generated and settled, which is everybody, almost
always". Both halves check out, and the second needed counting rather than reading:

| branch | when | n | mean | p50 |
|---|---|---|---|---|
| `SETTLED` — the first-line return | chunk reload | 2 (11 in an earlier run) | 3.85 µs | **0.41 µs** (0.20 earlier) |
| `GENERATED_IN_SETTLEMENT` | first contact | 25 | 74.2 µs | 51.2 |
| `AWAITING_SURVEY` | first contact | 27 | 33.2 µs | 6.5 |
| `STILL_UNSETTLED` | — | **0** | — | — |

The cheap path is **200–400 ns**, and it is *100% of calls* once a village is known and *0% of them*
during first contact, where the other two branches cost 180× and 80× more. A villager is discovered
once and reloaded forever after, so the claim holds — but it holds because of the mix, and the mix
is what nobody had looked at. `PersonaService.onEntityLoad` around it costs **2–5 µs at the median**
per villager entering the world.

**One cost named and deliberately not paid.** `STILL_UNSETTLED` never fired in this world, and it is
the branch that scales badly: a villager who is generated and belongs to no settlement runs
`Settlements.containing` — a linear scan of the whole settlement table — on **every chunk load, for
the life of the world**. With seven settlements that is free. With two hundred, in a world someone
has walked a long way across, it is not. Recorded here rather than fixed: the fix is an index, and
the need for one is session 08's problem at the earliest.

#### The population target: confirmed as records, revised as entities

Seven villages found by vanilla's own structure locator in a fresh world, each stood in for 900
ticks so the outlying chunks had arrived before anybody counted. Residents, in order: **11, 9, 2, 4,
9, 7, 10**.

**52 personas after seven villages — a median of 9 each and a mean of 7.4.** Minting on sight means
the record count tracks world population, so 400 records is a real target: it is the state of a save
after roughly **fifty villages have been visited**, which is a long playthrough rather than an
afternoon.

**As a loaded-entity count, 400 is wrong, and it was never claimed otherwise.** The most villagers
loaded at once anywhere in this world was **eleven**. `DESIGN.md` §8's 60–100 loaded ceiling would
need six to ten villages inside one simulation distance, which vanilla's 34-chunk village spacing
makes almost impossible without a player building it. So the number the tick budget actually has to
survive is not 14.75 ms — **it is the 96-entity row, at 4.51 ms with our sweep running.** 400 stays
in `DESIGN.md` as the record target and as the deliberately pessimistic entity figure the
architecture is sized against; what changed is that both readings are now measured and the gap
between them is the argument for the architecture rather than an assumption behind it.

#### Was Minecraft's own profiler enough?

Half, and the half it does is the half we cannot do ourselves. It is the only way to reach the
sections `WORKPLAN.md` names — `Mob#serverAiStep` pushes `sensing`, `targetSelector`,
`goalSelector` and `navigation`, and `Villager#customServerAiStep` pushes `villagerBrain` inside
`mob tick` — and without a mixin there is no other route to them. It answered what it was pointed
at:

```
minecraft:villager                     32.30%   16.13 ms/tick   (n=80400)
  ai       24.23%  12.10 ms       newAi  24.07%  12.02 ms
    mob tick                           21.09%   10.53 ms/tick
      villagerBrain                    20.84%   10.41 ms/tick
        the brain itself               15.82%    7.90 ms/tick
        pathfind                        4.98%    2.49 ms/tick  (n=1406)
    controls 0.67% · goalSelector 0.50% · targetSelector 0.52% · navigation 0.40% · sensing 0.21%
```

**Two thirds of a villager is its brain, and a quarter of its brain is pathfinding.** Everything
`Mob#serverAiStep` pushes outside `mob tick` — sensing, both selectors, navigation, controls — comes
to 2.3% of the tick between them.

It is no use at all for measuring us, for four independent reasons. `ProfileResults` hands out
**percentages only** — the absolute durations live in a private map on `FilledProfileResults` — and
prints them to two decimal places, against a budget that is 0.008% of a tick. It keeps a sum, a
count and a max per section and **never a distribution**. Turning it on **inflates the tick from
14.75 ms to 19.35 ms, +31%**, so it changes what it measures. And the tree is eleven levels deep
before it reaches `villagerBrain`, because `LivingEntity#tick` pushes `ai` and `newAi` before `Mob`
pushes anything — a walk cut off at nine prints "not reported" for the exact section this session is
named after, which it did, twice.

So: **vanilla's profiler for vanilla's sections, our own histograms for ours.** The two were checked
against each other on the one quantity they share — vanilla's tree says the tick is 19.41 ms and our
sampling of `tickTimesNanos` over the same window says 19.35 ms, **0.3% apart**.

#### The instrument, and what it was pointed at before it was believed

`net.namesake.profile` — a log-bucketed nanosecond `Histogram` with exact count, sum, min and max
and ~3% percentiles; `Meter` and `Meters`; `PersonaSweep`, the twenty-bucket rotating sweep of
`DESIGN.md` §8; and `SyntheticPersonas`, the fixture population. `Profiling.MOD_INERT` is the switch
that makes hard rule 4 possible at all: "400 loaded vanilla villagers with zero mod code" and "the
same 400 with ours" have to be the same world, terrain, JVM and JIT state, and the only way to get
that is to be able to switch ourselves off.

Four calibrations, each against something already known:

| | |
|---|---|
| An empty measurement | mean 28 ns, p95 101 ns, max 12.6 µs — **the floor; nothing below it is resolvable** |
| A 20 ms spin timed by `currentTimeMillis`, read by `nanoTime` | **20.02 ms**. Two clocks; a factor-of-1000 unit slip survives neither |
| Vanilla's profiler against our `tickTimesNanos` sampling | 19.41 vs 19.35 ms over one window |
| Every villager actually ticked | `n=80400` villager section ticks over 201 ticks = 400 × 201 exactly, and the harness asserts the least-ticked villager advanced with the window |

The last is session 01's lesson made structural. **A baseline measured on villagers that never ran
their brains is the most confident wrong number available**, and three of the defects below were
found because the harness insisted on checking.

#### Five defects, every one of which produced a confident report first

1. **A discarded villager keeps its workstation and its bed.** Vanilla releases a villager's points
   of interest in `Villager#die` and nowhere else, so tearing a cell down leaked a POI ticket per
   employed villager. Employment fell from 95/96 to **52/200** across one run, and every tick time
   after that was measuring a village of the unemployed. Nothing threw; found by reading the
   employment column.
2. **Iron golems accumulate.** A teardown that removed only villagers left their golems behind, so
   each cell inherited every golem the cells before it had produced and the second launch inherited
   the first launch's. Two hundred villagers read **8.75 ms in one phase and 15.50 ms in the other**
   with nothing of ours running per tick in either. Very nearly attributed to our code.
3. **And discarding a villager drops its inventory** — four hundred item entities ticking for the
   five minutes it takes them to despawn, so a cell that ran inside those five minutes measured
   eight hundred entities while its report said four hundred. Every cell now clears every entity but
   the player, and every cell prints a census of what else was standing in the grid with it.
4. **The measurement grid landed in an ocean.** Sixteen stone rafts, four cows and three elder
   guardians in the profile. The site is now chosen from a list of candidates with all sixteen sites
   checked for dry land — sampled at the sites, not at the centre, because a coastline is exactly
   the case where the centre reads fine.
5. **The client tick hook was registered only when the attach-bet harness was armed**, so the
   profiler sat at the title screen saying nothing at all.

Defects 1–3 share one shape, and it is the shape this session was written to catch: **state left
behind by the previous measurement, silently changing the next one.** The cure was not cleverness.
It was making every cell print what was in the world with it.

#### What is enforced, and what was reverted to watch fail

**Four hundred fake personas must never reach a save.** They live in a reserved id range —
`Persona.PROFILING_NAMESPACE`, disjoint from a minted id by construction because `UUID.randomUUID`
stamps version 4 into a nibble this range leaves 0 — and `NpcRegistry` refuses that range at both
its write door and its save door. The fixtures are held in the profiler's own list and never offered
to the registry at all, which is the only version of "removed afterwards" that survives a run that
crashes halfway through.

Proven by querying rather than by intending: after a run that built **1,504 fixtures**, the registry
held 1,197 personas and **0** of them were fixtures, and `namesake_npcs.dat`, read back off disk,
held 1,197 records and **0** fixtures. Identically on both loaders.

Rule 3 applied to each new guard — each broken and watched to fail before being called done:

| Breakage | Result |
|---|---|
| `NpcRegistry.put`'s fixture refusal removed | **Red.** *"NpcRegistry.put must refuse a fixture; sixty of them in a save look exactly like sixty people — expected: 0 but was: 60"* |
| The sweep's buckets derived from position instead of identity | **2 red.** *"tick 0 swept a different set after a reorder"* — the coverage test still passed, which is why both tests exist |
| The histogram's bucket layout shifted by one bit | **Red.** *"expected about 5000 and got 6655, which is 33.1% out"* |

**126 unit tests**, real JUnit XML, `failures=0 errors=0 skipped=0` — fifteen new ones, all of them
about the instrument rather than about the mod.

#### Carried into session 05

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs.
- **The budget to hold to is 125–225 ns per record visit**, because the sweep frame has already
  spent the rest of `DESIGN.md`'s 5.95 µs. If the personality weight table needs more, say so and
  re-rule the budget rather than quietly exceeding it — there is 35 ms of real headroom at 20 tps
  and 5.95 µs is a discipline, not a wall.
- The witness scan session 05 ships — `AABB.inflate(24)` + `canSee()` on deed emit — is not on the
  sweep's budget and is not polled. Give it a meter of its own when it lands; `Meters` and
  `Histogram` are already there and cost nothing when the profiler is off.
- Delete `<loader>/run/saves/namesake_profiler` before a measurement run. Three of this session's
  five defects were the previous run's leftovers changing the next one's numbers.

**Ledger change.** Session 04 → done, session 05 → NEXT. A fourth verification instrument recorded,
with the ruling that it gets no CI job and why. `DESIGN.md` §8's four numbers — 18 ms, 5.95 µs,
100× and the 60–100 loaded ceiling — are now measured rather than assumed: three confirmed, and the
100× wrong by two orders of magnitude in our favour and left exactly as it is. No changes to the
16-session shape.
