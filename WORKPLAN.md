# WORKPLAN — Namesake

**The ledger.** What happens next, in order, with exit criteria. Read first, update last.
Where any other document disagrees on sequence, this wins.

- **Status:** session 08 complete, **and a deed now reaches people who were not standing there.** Feed
  a villager in a square and within two in-game days **78% of the village holds it** — at three
  descending confidences, and at least one of them can no longer say who did it. The villager behind
  a wall records nothing when it happens and has heard about it an hour later, which is the wall
  stopping them seeing and not stopping the village talking. It clears 60% **with nobody watching at
  all**, in eleven villages of twelve, so the criterion does not rest on the one input nobody has
  measured. The session opened on a contradiction session 06 had already proved — at
  `confidence × 0.85` and max two hops the blur can never fire — and closed it by moving the
  retention to **0.70** and nothing else, because that is the only one of the four available levers
  that does not break session 10's ship-or-kill. **Nothing counts hops:** the two-hop bound falls out
  of the retention and the attribution floor, so `Deed` still has seven fields. And it hands session
  09 an answer rather than a problem: **gossip does not make the residency threshold reachable on
  warmth** — never, both ways, at every mark — while trust arrives four days sooner.
  Before that: session 07, **and time runs forward**. A settlement of nine can be advanced a
  hundred in-game days in **six to eight milliseconds on a live server thread**, through the shipped
  record layer rather than a copy of it, and it dumps a chronicle, an earn-rate report and a real
  deed ring. It immediately found a threshold nobody could ever have crossed: `DESIGN.md` §5 grants
  residency on a *known* band with three residents, and **no three residents reach 20 warmth in a
  hundred days, ever**, because a witness's share of a gift is one point and warmth decays one point
  a day. Trust, which does not decay, gets there on day 29. The owner's close-of-05 ruling is also
  paid: two villagers a week apart on identical treatment now end **28 points apart** where they
  ended 14. Before that: session 06, **and a village remembers**. Feed a villager in front of three
  others and each of the four keeps the deed in a thirty-two entry ring that survives the disk — and
  feed them nine times and they keep it *once*, because a deed's id is derived from the deed rather
  than assigned, so a ring cannot be ground out by repetition. Before that: session 05, **and a
  village notices what you do in front of it**. Feed a
  hungry villager with three watching and the one you fed gains +3 while each witness gains +1; the
  one behind a wall and the one out of range gain nothing; nine feedings in a day stop at eight. The
  same gift is worth **+2 warmth to a suspicious smith and +4 to a warm innkeeper**, and a receptive
  villager's whole day is worth more than a closed one's — which is the static `float[8][6]` weight
  table paying off `Persona.traits`, the exemption standing risk 4 has carried since session 01.
  Before that: **the attach bet holds**, **the authorization gate is
  real**, **a villager is from somewhere**, and **the budget is real** — 400 loaded vanilla
  villagers cost **14.75 ms of server tick** against our **1.2–3.3 µs** sweep. Repo live at
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
| 05 | Bonds and deeds | **done** — 2026-08-14 |
| 06 | Episodic memory | **done** — 2026-08-14 |
| 07 | Headless simulation harness | **done** — 2026-08-14 |
| 08 | Gossip and distortion | **done** — 2026-08-14 |
| 09 | Dialogue pools and residency | **NEXT** |
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
4. ~~**Traits have no consumer yet.**~~ **Retired 2026-08-14 — the weight table landed.**
   `Personality.scale` reads all eight axes and multiplies what a deed is worth by them; the same
   loaf is +2 warmth to a suspicious smith and +5 to a warm innkeeper, and `PersonalityTest` fails
   the build if any axis's row goes to zero. Nothing in the generation chain still terminates in a
   renderer. The forcing function that carried this since session 01 was never called upon to fire.
5. **A bond's four axes have no consumer yet, and neither does its debt.** Session 05's own version
   of risk 4, and it is listed because it is the same shape: five persisted social values written
   this session and read by none of it. The exemptions are honest ones — trust and warmth expire at
   **09** (the residency threshold, a persisted state change rather than a line of dialogue),
   respect at **12** (the trade price band), and fear and debt at **16** (force-resolution and the
   favour that settles a stage-2 grievance).

   **The count is the point, so it is stated rather than buried: session 05 paid off one exemption
   and opened five.** Session 03 shipped `Settlement` with none, and the difference is not care — it
   is that a settlement's fields were read by the trait roll in the same session and a bond's axes
   genuinely are not read by anything until 09. The temptation was to name `Bond.apply`, which reads
   all four, is not a display, and would have passed every check in `SocialValueLedgerTest`. It is
   also the writer looking at its own work, which is the exact lie `cultureId` told in session 03.

   **Armed the same way risk 4 was**, against this ledger's own status board, so it fails loudly at
   the close of the owing session rather than quietly. `debt` is the longest of the five and the one
   with the least behind it: nothing in the sixteen-session slice writes it, let alone reads it.
   **Deletion was offered at the close of session 05 and the owner ruled the field stays** — so it
   is carried deliberately rather than by inertia, and the expiry at 16 stands unchanged. Session 16
   reads it or deletes it; moving the number is still the thing the mechanism exists to stop.

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

**A fifth, added 2026-08-14, and it *is* in CI — which is the interesting half: the simulation.**
`net.namesake.sim` advances a settlement N in-game days through the shipped record layer and reports
what happened. It is the opposite of the profiler in exactly the way that matters. The profiler
measures **wall clock**, which is a property of a machine, so a number from a runner we cannot see is
worthless. The simulation measures **arithmetic over time**, which is a property of the code, so it
is deterministic in its plan alone and reproduces bit for bit on any machine — and a report that
cannot be reproduced is not evidence, when every band threshold from here comes out of one.

**What makes it more than a unit test, since it is made of them.** A unit test asserts an answer
somebody already knows. This one *produces* an answer nobody knows: what a population's bonds and
rings look like after a hundred days, which is emergent over time and over a village and has no
expected value to assert. Its arithmetic is unit-tested; its output is a report. Both are true and
they are different jobs.

**Its honesty rests on one structural decision and it is worth restating here rather than only in the
class.** The record layer's only input from the passage of time is an integer day, so a hundred
simulated days are a hundred real days exactly. What is *modelled* is two things: what a player does,
and who was standing close enough to see it. The first is five named archetypes rather than one; the
second is the least grounded number in the instrument and is swept rather than asserted. Everything
between a deed and the save file is called through `DeedBus.record`, the same door the game uses.

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

**Shipped.** `67eca48..cef6355` plus this correction, pushed to `origin/main`. CI green on all three
jobs — build and test, and the attach-bet harness on each loader.

**Hard rule 4 first, and the number it was protecting is right.** Four hundred loaded vanilla
villagers cost **14.75 ms of server tick, mean** — p50 14.68, p95 19.40, p99 22.02, max 27.22, over
1,200 consecutive ticks. `DESIGN.md` §8 has carried ~18 ms since before there was any code to
measure. **18 ms sits between that distribution's p50 and its p95** — interpolating the two, about
one tick in five exceeds it. The figure stands; it was a tail number rather than a typical one, and
it now has a shape instead of a value.

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
with the ruling that it gets no CI job and why. `DESIGN.md` §8's four numbers — 18 ms, 5.95 µs, 100×
and the 60–100 loaded ceiling — are now measured rather than assumed. No changes to the 16-session
shape.

**Ruled at close, by the owner.** Four, and two of them changed `DESIGN.md`:

- **§8 is corrected rather than left to drift.** It now carries the measured numbers and points here
  for the distributions and the conditions. Its stale survey line went with them — session 03 moved
  the census onto the server thread at sixteen chunks a tick and §8 still said "once, off-thread, at
  registration". Two documents disagreeing is the thing `CLAUDE.md` names as the problem, and the
  session log is not a licence for the design document to be wrong.
- **The 5.95 µs budget stands, re-ruled rather than inherited.** The measurement found ~35 ms of
  real headroom and the budget was deliberately not raised to meet it. Session 05's weight table is
  held to **125–225 ns per record visit**. Raise it when a payload has been priced and does not fit,
  never the first time it pinches — a budget re-ruled the moment it binds is not a budget.
- **"Only the server opens an interaction" is promoted into `DESIGN.md`**, parked since session 02.
  It is load-bearing rather than incidental: the strictness in "every serverbound packet carries a
  live token" is only free because the client can never ask for one, and an addon author needs that
  before writing a verb. §2's Platform table, and the count goes 41 → 42.
- **The census tail is recorded and not acted on.** One cold POI column read took 72 ms, but the
  census is one-shot per place and only fires on first arrival somewhere new. It is a measured
  number in this log now and the first thing to point at if a playtest reports a stutter on walking
  into a new village. Lowering `CHUNKS_PER_TICK` would shrink the aggregate and cannot bound the
  worst case, which is a single blocking disk read.

**One gap, stated rather than glossed.** The measurement phases ran on both loaders and agreed
within 2.1%. The population-and-costs phase ran on **Fabric only** — the census and
`onPersonaLoaded` numbers above are one-loader evidence. Nothing in that path is loader-specific,
which is exactly the reasoning that has been wrong three times in this project, so treat those two
tables as Fabric's until somebody runs `-Pprofile=world` on NeoForge.

***Closed 2026-08-14 at the end of session 05 — see that log's last section.** The population number
reproduces exactly and the census's shape reproduces exactly; its tail does not, in the direction
this paragraph should have expected.*

### Session 05 — 2026-08-14 — bonds and deeds

**Shipped.** `6847700..cc4d697` plus this correction, pushed to `origin/main`. CI green on all three
jobs. Six commits: the pipeline, the ledger, session 04's closed gap, the owner's `debt` ruling, and
then the two the playtest produced — the centring and the personality-scaled ceiling, with their own
ledger entry.

**A village now notices what you do in front of it.** Feed a hungry villager with three others
watching and the one you fed gains +3 while each witness gains +1 — and the one behind a wall and
the one forty blocks up gain nothing at all, which is the witness scan doing exactly the two things
only a running game could prove it does.

**What shipped.**

- **`Bond`** — four signed axes, a debt scalar, and three fields of bookkeeping that make the axes
  behave like a relationship rather than a score. Immutable; every write goes through `apply`,
  because the cap, the two floors, the ceiling and the high-water mark are five invariants and a
  mutable field with five setters is five places to forget one.
- **`Bonds`** — the table, and `NpcRegistry.putBond`, the only door that marks the file dirty.
- **`Deed`** and **`DeedType`** — six types, each with four nominal deltas and a witness share.
- **`Deeds`** — what a deed is worth to one person. Four weights, two structural and two softening.
- **`Personality`** — the static `float[8][6]`. **Standing risk 4, paid.**
- **`DeedBus`** — emit, witness scan, record, bond update. Steps 1–4 of `DESIGN.md` §4 and nothing
  else; the settlement effect and the gossip deque were deliberately not built early.
- **`SocialEvents`** — the three engine doors deeds actually come from: the **give gesture** (sneak,
  hand full, right-click — session 02's conversation gesture with something in your hand), the
  damage hook and the death hook. No new packet, no new tick loop.
- **Schema 4**, with a fixer watched to run against worlds written by the previous build on both
  loaders — and then watched *not* to run on the second load of the same world.
- **`/namesake debug bonds`** and **`bond`**.
- **A fifth harness leg**, and only one, for the two claims a unit test cannot make.

**One departure from the session brief, flagged rather than quietly taken.** The brief said "`Bond`
and `Deed` are persisted records"; only `Bond` is. `Deed` has no codec and nothing stores one,
because its store is session 06's 32-entry ring and giving it one now meant either an unbounded
per-NPC list — a leak, not a feature — or building the ring early against a bond system nobody had
watched work yet. Hard rule 1 fires on `Bond` alone, so the instruction's purpose is met in full:
schema 4, a fixer, and a load test against a save written before the change. See the carried note
below, and overrule this if the reasoning does not hold.

**The two decisions the session opened with, and what each costs.**

1. **A bond is keyed on `(the NPC who holds it → whoever it is about)`, both bare UUIDs.** The shape
   is general and the population is not: `NpcRegistry.putBond` refuses a bond whose subject is a
   persona this world knows about. Both obvious answers are worse. Keying strictly on (npc → player)
   makes session 16's grievance engine a migration of every bond ever written. Letting NPC-to-NPC
   bonds fall out for free is worse than that: twelve witnesses per deed means a village going about
   its business accumulates bonds toward n² of itself, and at four hundred personas that is 160,000
   persisted social values that no `if` statement reads before session 16 — the precise thing
   `DESIGN.md` §1 forbids.

   **What it costs, plainly.** A bare UUID cannot say whether it is a player or a persona by looking
   at it; today that is free because everything stored is a player, and at session 16 something will
   have to ask the registry — which is what the guard already does, in the other direction. And
   because the format allows what the guard forbids, that one method is the only thing standing
   between here and the n² table. It has been reverted and watched to fail.

2. **Bonds live in `namesake_npcs.dat`, under the same `NpcSchema` version**, on session 03's
   argument and with more force. A bond references a persona by id. Two files torn apart by a crash
   between two writes do not produce a missing file — they produce a save that loads, in which every
   villager in a village has forgotten one particular player. The size counter-argument does not
   hold: the table is bounded by (personas × players who have done something), which at §8's four
   hundred records and one player is four hundred rows of about fifty bytes.

**Five decisions worth recording besides.**

1. **Nothing may soften a harmful deed — not the cap, not personality, not rounding.** A weight
   *above* neutral still sharpens one, which is what gives the table a column for the harmful deeds
   at all and what makes `temper` mean temper. Stated as one invariant so it can be tested three
   ways: you cannot spend the day's allowance and then hit someone for free, a placid villager does
   not charge less for a killing, and a structural weight cannot round −0.45 away to nothing.
2. **Which of the three kindnesses a gift is, is decided by vanilla.** `Villager#wantsMoreFood` is
   the engine's own hunger threshold and `Villager#wantsToPickUp` is its own answer to "would this
   villager pick this up off the ground", which already folds in the profession's requested items.
   Writing a wanted-items table here would have been a second source of truth for a question the
   game already answers; the addon API is where a modpack gets to extend it.
3. **A deed is only emitted if the item actually changed hands.** Not politeness. An offer a
   villager cannot accept, emitting `GIFT_UNWANTED`, makes a full-inventory villager eight free
   points of trust a day for ever. Every deed costs a real item out of a real inventory.
4. **The witness scan sorts before it casts rays.** `DESIGN.md` says "filtered by `canSee()`, capped
   at 12 nearest"; walking the candidates nearest-first and stopping at twelve is the same set for
   twelve rays instead of one per villager in a 48-block cube.
5. **`LivingEntity#hasLineOfSight`, not `Sensing`.** `Sensing` caches per entity id and is cleared
   only from the mob's own AI step, so any villager that is loaded and not running its brain — every
   fixture in this harness, which sets `setNoAi` — would answer from a cache populated before there
   was a wall in the way. A cached instrument reporting yesterday's answer is this project's
   commonest defect wearing a new hat.

**What the exit criteria actually showed.** Both loaders, in two launches each, every leg green:

| Leg | Evidence |
|---|---|
| witness | the scan found **3** witnesses: three could see it, one was behind a wall, one was out of range |
| deed | the villager who was fed gained **+3** on trust and warmth |
| deed | **3/3** witnesses who could see it gained **+1** |
| canSee | the villager behind the wall recorded **nothing**, five blocks away and well inside the box |
| range | the villager forty blocks up recorded **nothing**, with nothing at all in its way |
| cap | nine feedings in one day left trust 8 and warmth 8 — and all nine landed on one in-game day |
| bond reload | **4/4** bonds survived save → quit → reload with every axis intact |

The two negatives are deliberately opposite: the walled villager has range and no sight, the high
one has sight and no range. **A single mistake cannot pass both.**

And the instrument, read out of a running game after the nine feedings:

```
day 0 — 6 loaded NPC(s), nearest first; 4 bond(s) in the world
  who                         trust warmth respect fear   cap   gift×
  Vlizgyrn Gvirnsk               +8     +8      +0   +0   8/8/0/0  1.00
  Gatisgit Gvirnsk               +8     +8      +0   +0   8/8/0/0  1.00
  Gykvur Gvirnsk                 +8     +8      +0   +0   8/8/0/0  1.00
  Svakvysverk Stuksk             +8     +8      +0   +0   8/8/0/0  1.00
  Stiznysmyar Stuksk             +0     +0      +0   +0   0/0/0/0  1.00
  Trusmyrmest Stuksk             +0     +0      +0   +0   0/0/0/0  1.00
```

**The second half of the criterion is machine-checked and its ruling was the owner's.** The same
wanted gift is `{trust +1, warmth +2}` to a suspicious smith and `{trust +3, warmth +4}` to a warm
innkeeper — a personality multiplier of **0.68 against 1.47**. `DeedsTest` asserts both numbers, and
`/namesake debug bonds` prints the multiplier in its last column next to the name, which is where to
look. **Whether that difference is *legible* is not something any test in this repo can have an
opinion about, so it was handed over — and the playtest that followed changed the design twice. See
below.**

**Hard rule 1 followed as written, and the pre-change half was done first.** The attach-bet harness
`setup` phase was run on commit `5921797` — schema 3 — on **both loaders before a line of session 05
was written**, and those saves were kept. The schema-4 build then loaded them:

```
NPC registry datafixer: schema 3 -> 4 (bonds added; nothing to rewrite, an absent
table means nobody has met anyone) rewrote 0 record(s)
Loaded 9 persona(s), 9 bound to an entity, 1 settlement(s), 0 bond(s) (schema 4)
```

**This migration is the additive kind, and saying so out loud is the point.** The two fixes below it
were both the same collision — a stored `0` that used to mean "none" and now means a real value —
and both had to rewrite every record holding one. This has no collision: bonds are a table that did
not exist. The whole content of the migration is the *assumption* that an absent `bonds` key reads
as "nobody has met anyone" rather than as damage, which is the same free migration the settlement
half of schema 3 was.

So the evidence had to be inverted. Session 03 caught a fixer made to run, log and change nothing
and turned the build red for it; that fix was *meant* to rewrite. Here the assertions are all
negatives — three personas kept the culture and placement schema 3 gave them, the settlement table
came through, the bond table read as empty rather than damaged, and the registry stayed writable —
plus the one thing that is a real hazard rather than a bookkeeping one. **Read as damage, the
registry goes read-only and a world that has bonds in it silently stops saving them.** Breaking
`Bonds.readFrom` to do exactly that turns four tests red, two of which are about personas.

**Then the same world was loaded a second time**, which is the only way session 01's defect 1 is
ever caught: `no migration expected: world is already at schema 4`. The fix reached disk.

**185 unit tests**, real JUnit XML, `failures=0 errors=0 skipped=0` — 59 new, and every one of them
a claim `WORKPLAN.md` says belongs in a unit test rather than in six minutes of CI.

**Four defects, and three of them were found by applying a rule rather than by a red test.**

1. **A record consuming its own field is invisible to the rule 5 ledger.** `SocialValueLedgerTest`
   proves a consumer by reading its bytecode for a *call*, and `Bond.decayedTo` touching
   `this.lastSeenDay` compiles to a `getfield` that no such check can see. Three fields —
   `lastSeenDay`, `gainedToday`, `peakWarmth` — are genuinely consumed by the rule they implement
   and by nothing else, so the fix was to read them through their accessors and say why in a comment
   next to each. Worth recording as a property of the enforcement mechanism rather than of this
   record: **an invariant enforced by bytecode has a shape, and code has to be written to fit it.**
2. **`Deed.subject` was written and never read.** The subject share was being passed into
   `Deeds.deltaFor` as a boolean by every caller, so the field on the record fed nothing. Caught by
   applying rule 5 while writing the record — session 03's lesson, applied on purpose. The fix is
   better than the defect: `deltaFor` now compares the holder's persona id against the deed's own
   subject, so a deed can no longer be *told* who it happened to and told wrong.
3. **`DESIGN.md` §3 said `byte gainedToday`, and a byte cannot hold it.** Four axes each counting to
   eight need four bits apiece, which is sixteen bits and not eight. Corrected to `short`, four
   nibbles, with a test that fails if the cap is ever raised past fifteen — because a cap of sixteen
   would wrap into the neighbouring axis's counter and read as a bond that had already spent an
   allowance on something nobody did.
4. **A harness fixture standing on terrain nobody had checked.** The walled villager was first
   placed two blocks past the edge of the platform this harness builds, on whatever the world seed
   put there. It passed — on this seed, at this y. Session 03 lost a whole leg to exactly that
   (`getHeight` on an unloaded chunk putting a village at y = −64), so it was moved back onto the
   platform and the leg re-run. **Found by reading the coordinates, not by a red test**, which is the
   only way this class of defect is ever found before it costs an afternoon.

**Rule 3 applied to every new guard. Ten deliberate breakages, each watched to fail and removed:**

| Breakage | Result |
|---|---|
| The daily cap removed | **4 red.** *"the positive axis stopped at the cap — expected: &lt;8&gt; but was: &lt;12&gt;"* |
| Negatives sent through the cap like everything else | **5 red.** *"four blows is four blows, whatever day it is — expected: &lt;-24&gt; but was: &lt;0&gt;"* |
| A personality weight below neutral allowed to soften a blow | **Red.** *"a weight below neutral must be ignored on the way down — expected: &lt;-6&gt; but was: &lt;-4&gt;"* |
| A harmful axis allowed to round to nothing | **Red.** *"expected: &lt;-1&gt; but was: &lt;0&gt;"* |
| Rounding toward positive infinity, as `Math.round` does | **Red.** *"Math.round would give -4 and lose the half"* |
| The decay floor dropped to zero | **3 red.** *"the view is up to date — expected: &lt;20&gt; but was: &lt;0&gt;"* |
| The `dayDelta ≤ 64` clamp removed | **Red.** *"an in-game decade must catch up by the clamp, not by ten thousand"* |
| An absent bond table read as damage | **4 red**, two of them about personas: *"a migrated registry must be written back, or it migrates again on every load"* |
| The NPC-to-NPC bond guard removed | **Red.** *"nothing may be written — expected: &lt;0&gt; but was: &lt;1&gt;"* |
| The `industry` row of the weight table zeroed | **Red.** *"industry changes nothing about any deed. It is persisted on every villager in the world and feeds no `if` statement"* |

The last one is worth its place: it is rule 5 in miniature, one level below the ledger. A ledger
entry can name `Personality` truthfully while one of its eight rows quietly does nothing, and the
whole point of an eight-axis personality is that all eight of them are load-bearing.

**And an eleventh, which is the forcing function itself.** With the status board already moved to
session 06, `Persona.traits`' exemption was restored exactly as session 02 wrote it:

```
WORKPLAN.md says session 06 is next, so these exemptions have expired:
  Persona.traits (exempt only through session 5)
```

That is the mechanism firing on the session it was aimed at, four sessions after it was armed and
against a date nobody had to remember. It did not have to fire for real, because the table landed —
but it was watched to fire, which is the only way to know it would have.

**Session 01's wedge came back twice, both on NeoForge, and it is still bounded rather than
diagnosed.** Session 01 saw the integrated server stop making progress only on a CI runner. It
happened twice on the owner's machine across this session's seventeen client launches, and never
once on Fabric:

- **At shutdown**, the documented shape: the server hung after logging "Saving worlds" and the
  watchdog hard-exited it 45 seconds later, exactly as designed. The verdict was already on disk and
  read `PASS`. **The thing to know is that the hard exit makes Gradle report the task as failed** —
  a green harness inside a red build — so a CI job watching the exit code rather than the verdict
  file would fail on it. This one reads the verdict file, which is why that was ruled in session 01.
- **Mid-run, which is new.** A `setup` phase logged "phase setup starting" and then stopped: no
  further output for four minutes, the process alive at **2.4 seconds of CPU across ninety seconds
  of wall clock** — idle, not working. Nothing in the harness recovers from that, because every
  deadline it has is counted in server ticks and the server had stopped producing them. Killed,
  world deleted, re-run: **green on the first retry, all 37 legs.**

**Diagnosing it is not this session's job, and guessing at it would be worse than leaving it.** What
is worth carrying is the discriminator, which cost ten minutes to work out and will cost ten seconds
next time: **the Gradle console log is buffered and Minecraft's own log is buffered, so neither can
tell you whether a run is alive.** Two things can — the verdict file, which the harness writes
unbuffered, and the process's CPU time. A wedged run and a slow one look identical in every log and
completely different in `Get-Process java | Select-Object CPU`.

**One thing is instrumented and not measured, and it is named rather than glossed.**
`DeedBus.witnessScan` and `DeedBus.emit` have meters of their own and two counters beside them,
costing nothing when the profiler is off, exactly as session 04's carried note asked. They have not
been *pointed at anything*: measuring the scan honestly needs a player standing in a crowd emitting
into it, which is a new profiler phase rather than a new cell, and a number from nine emits on a
cold JIT would be the confident wrong kind. What is known without measuring bounds it usefully: the
scan is one entity query over a 48-block cube plus **at most twelve** ray casts of ≤24 blocks, it
runs on emit and never on a tick where nothing happened, and it therefore **cannot appear in a
steady-state budget at all**. Session 04's 125–225 ns per record visit is untouched — the bond
system adds **zero** per-tick work, because the decay is lazy and nothing else ticks. If the scan is
ever suspected, the phase is the first thing to write.

**Carried into session 06.**

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs.
- **A harness run that has gone quiet is not necessarily stuck.** Both logs buffer; read
  `<loader>/run/namesake-harness-result.txt`, which is written unbuffered, and the client's CPU time.
  Seconds of CPU per minute of wall clock is the tell.
- **The deed ring is session 06's, and `Deed` has been left un-persisted for it.** It declares no
  codec and nothing stores a deed; giving it one now would have meant either an unbounded per-NPC
  list, which is a leak, or building the ring early. Its store, its 32-entry bound, its dedupe key
  and its schema bump are all one session's work and they belong together. Every field is already
  held to rule 5 — the ledger lists `Deed` by name rather than by whether it declares a codec.
- **`Bond.DAILY_CAP = 8` and the one-point-a-day decay are provisional and due for calibration
  against session 07's earn-rate histogram**, not against anyone's intuition. That is the mistake
  LNK made when it set skill gates at 35–205 against an observed maximum of 32.
- **Session 07 inherits one number and one instrument.** The number: the median settlement's
  week-apart is **14** and the ruling wants **25**. The instrument:
  `PersonalityDistributionTest` already prints the percentiles, the within-town spread, the
  per-culture medians and both week-aparts, so the tuning pass has a before-and-after rather than an
  impression. **Widen the weights, not the base cap** — the cap is what stops a village being ground
  out, and it is the same number the band thresholds will be set against.
- **`Personality.TYPICAL` is a measurement with a shelf life.** Retune a specialty bias, change a
  culture's baseline or add a seventh culture and it stops describing the population; the test says
  so with the new vector in the failure message, and the constant is a one-line update.
- The give gesture takes the click a villager would have used to open a trade screen while sneaking.
  It takes nothing away — vanilla already routes a sneaking right-click on a villager to
  `Villager#mobInteract` — but it is the first place to look if a playtest reports trading feeling
  odd.

**Ledger change.** Session 05 → done, session 06 → NEXT. **Risk 4 retired**: the weight table landed
and nothing in the generation chain still terminates in a renderer. **Risk 5 added** in its place
and armed the same way, for the five social values this session persisted and did not read. Five
decisions added to `DESIGN.md` §2 — the bond key, bond storage, bond decay, and after the playtest
the centring and the personality-scaled daily allowance — taking the count 42 → 47, one correction
to §3's `gainedToday`, and §4 step 4 rewritten to state all six weights and which two of them may
never soften a harmful deed. No changes to the 16-session shape.

#### The gap session 04 left open, closed

`-Pprofile=world` on NeoForge, same machine, same seed 20260814, world generated fresh. Session 04
ran this phase on Fabric only and said to treat its two tables as Fabric's until somebody ran it
here. Somebody has.

**The population number reproduces exactly.** Seven villages found by vanilla's own locator at the
same seven coordinates, and **52 personas and 7 settlements** in the registry afterwards — the same
total, from the same villages, on the other loader. Per-village residents came out 11, 9, 2, 4, 9,
4, 10 against Fabric's 11, 9, 2, 4, 9, 7, 10; the two that differ are villages 5 and 6, where the
count was taken while chunk tickets were still settling and residents had not all arrived. That is
session 03's defect 5 for the fifth time, it moves nothing in the total, and it is exactly why the
figure that matters is the registry total rather than the per-village column.

**The census's shape reproduces exactly, and its tail does not.**

| | Fabric (session 04) | NeoForge |
|---|---|---|
| censuses completed | 10 | 8 |
| server ticks and chunk columns each | 19 and 289 | **19 and 289** |
| `PoiManager.getInChunk`, one column, mean | 61.7 µs | 25.2 µs |
| …p50 / p95 / p99 | 10.5 / 30.2 / 75.8 µs | 13.8 / 32.3 / 73.7 µs |
| …**max** | **72.43 ms** | **9.90 ms** |
| `SettlementRegistrar.step`, 16 columns, mean | 946 µs | 392 µs |
| …p99 / max | 53.5 ms / 72.44 ms | 4.98 ms / 10.23 ms |
| `SettlementSurvey.score`, off-thread | 3.75 ms mean | 445 µs mean |

**The middle of the distribution is the same to within a few microseconds and the tail is seven
times smaller — which is the number session 04 already predicted.** Its own note says the tail is
disk-bound and varied by a factor of seven between two runs *on one loader*, so a 72 ms worst column
against a 9.9 ms one is that same variance and not a loader difference. **The honest reading is
therefore the opposite of what a table this shape usually means: this run does not lower the census's
worst case, it confirms that the worst case belongs to the disk rather than to either loader.** The
72 ms column stands as the number to design against.

**And `STILL_UNSETTLED` fired zero times here too** — the branch that scans the whole settlement
table on every chunk load, for the life of the world. Zero out of 52 on both loaders now. Session
04 recorded the fix for it (an index) as session 08's problem at the earliest; that still holds.

One number is a single sample rather than a distribution and is flagged as such: the `SETTLED`
first-line return measured 9.00 µs at n=1 on NeoForge against Fabric's 0.41 µs at n=2 and 0.20 µs at
n=11. One call on a cold path is the JIT, not a loader. The claim session 04 made from it — that the
cheap branch is 100% of calls once a village is known — is about the *mix*, and the mix reproduced.

#### The playtest, and the two things it changed

**The owner played it, and the criterion did not pass on the first read.** Ten villagers of one Yun
village spanned ×0.94 to ×1.29 on a wanted gift — a third of what the smith-and-innkeeper fixtures
in `DeedsTest` span, because **those fixtures are ones I chose**. A test that only ever asks about
its own extremes cannot notice that the villagers a real world produces are closer together than
that. Two of my readings of it were wrong and are recorded here rather than quietly fixed:

- I said the table was **biased**, mean ×1.16, nobody below neutral. Measured across the generator's
  whole space the population median is **×1.04**; ×1.16 was that one village standing above it.
- I said the spread was **too timid at 0.16**. It is **0.68** across the population and **0.36**
  inside one settlement — the latter matching the owner's ten villagers almost exactly. What looked
  flat was the within-town number, and the gap between the two is culture: **Karsk medians ×0.74
  against Meridian's ×1.25**, a 65% difference in what the same loaf is worth.

**`PersonalityDistributionTest` is the instrument that settled it**, and it is the one this session
was missing. It rolls 4,536 personas through the real three-layer roll across every culture,
specialty, defensibility and needs vector, and reports percentiles per deed type — **decomposed into
population spread and within-settlement spread, because the criterion is about two villagers you can
walk between, not two villagers three thousand blocks apart.** It also simulates a week of gifts
through the real records, so the cap, the ceiling, the rounding and the lazy decay all bite.

**And it found the thing the playtest was actually pointing at, which was not the magnitudes.** The
daily cap and the weight table were pulling against each other:

- at **one gift a day** the cap never binds, and the per-deed step compounds to a **14-point** warmth
  gap over a week;
- at **enough gifts to fill the cap** every villager converges on the same eight, and the gap is
  **zero**. Personality decided how many gifts it took, and nothing about where anybody ended up.

Which is why the ruling was to move personality onto **the ceiling**: scaling the daily allowance
survives the cap because it *is* the cap. Re-measured after the change, the saturating player's gap
is a median of **14** and a widest of **28** — the full standing band the owner asked for, at the
top end, with the median still short of it. **That gap is the magnitude work, and it is session
07's**, exactly as ruled: shape now, numbers against real earn-rate data later.

**Centring, and the honest note about it.** The owner ruled that nominal should mean *typical*, and
re-ruled it after being told the premise I gave was wrong — it is a 4% correction on gifts, not the
16% I first reported, and 13% on a defended raid. `Personality.TYPICAL` is the measured population
mean, the per-column offsets are **derived from it at class initialisation rather than written
down**, and the invariant *a typical villager scores exactly one* therefore holds by construction on
every column. What can still rot is whether that vector is still the population's, so a test
re-measures it on a different seed and fails with the new numbers in the message.

**Six more breakages, each watched to fail and removed.** The fifth is the one worth its place:

| Breakage | Result |
|---|---|
| The centring removed | **5 red**, across two files: *"STRUCK_RESIDENT axis 1 must be its own nominal value for a typical villager — expected: &lt;-8&gt; but was: &lt;-9&gt;"* |
| `Personality.TYPICAL` drifted from the population | **Red**, and the message carries the fix: *"TYPICAL says industry=10, but the generator now averages 25"* |
| `Bond.apply` ignoring the allowance it was handed | **2 red.** *"a bigger allowance must let a day go further — expected: &lt;11&gt; but was: &lt;8&gt;"* |
| `DeedBus` back on the base cap for everybody | **Red.** *"DeedBus.applyTo never calls Personality.allowance, so the ruling is a method nobody calls"* |
| The allowance built from the harmful columns too | **NOTHING FAILED.** The guard did not exist. |
| …the same breakage, after writing the guard | **Red.** *"a villager whose kindness columns average typical must get a typical day, however hard they take a blow — expected: &lt;8&gt; but was: &lt;10&gt;"* |

**That fifth row is the whole reason rule 3 is a rule.** The filter that keeps the harmful columns
out of the daily allowance is load-bearing — without it a short temper *raises* a villager's capacity
for warmth, because they score high on being hit — and nothing tested it. Worse, the obvious fixture
for it does not catch it: a villager cold enough to be interesting clamps three benign columns to the
floor and comes out under the base cap either way. The guard that works needed a villager whose
kindness columns average almost exactly typical while their harmful ones sit at the ceiling, and that
fixture only got written because the breakage was actually run rather than reasoned about.

**Ruled at close, by the owner.**

- **`Bond.debt` stays.** Deletion was offered — nothing in the sixteen-session slice writes it, let
  alone reads it — and the ruling is that the field exists. It is therefore carried deliberately
  rather than by inertia. The exemption is untouched: session 16 reads it or deletes it, and
  `SocialValueLedgerTest` still turns the build red at the close of 16 if neither happens.
- **Personality controls the ceiling, not the step.** The daily allowance is scaled by the same
  weight table; the base of 8 is what a typical villager gets.
- **Nominal means typical.** The table is centred on the measured population, re-ruled after the
  first premise turned out to be wrong.
- **Two villagers a week apart on identical treatment should end up a full band apart**, ~25 points.
  Currently a median of 14 and a widest of 28. **The target belongs to session 07.**
- **Shape now, magnitude at session 07.** The weights themselves are untouched.

**What "legible" can and cannot mean before session 09.** The criterion asks whether the same gift
*lands differently* on two villagers. Two things are true at once and it is worth separating them:

- **The difference exists and is measurable today.** `/namesake debug bonds` prints a `gift×` column
  next to each name, and giving the same item to two villagers moves their rows by different amounts.
- **There is no player-facing surface for it yet, and that is by design.** `DESIGN.md` rules the bond
  UI as bands and a deed ring, **never raw integers** — so the debug command is an instrument, not
  the answer. The pools that would let a villager *sound* differently are session 09; the board that
  would show it without asking is session 11. Until then, "did you notice?" cannot be tested,
  because there is nothing to notice with.

So the ruling available now was the narrower and more useful one: **is the spread the generator
actually produces wide enough to be worth building a surface on?** It was asked, it was answered
with a real village, and the answer moved the design twice — see the playtest section above. What
that leaves for session 07 is a number rather than an adjective: **close the median week-apart from
14 to 25**, against real earn-rate data and not against anyone's eye.

### Session 06 — 2026-08-14 — episodic memory

**Shipped.** `759ce8d..f98ffb3` plus this correction, pushed to `origin/main`. CI green on all three
jobs — build and test, and the attach-bet harness on each loader.

**A villager now remembers what it saw, and it survives the disk.** Feed one in front of three
others and all four keep the deed. Feed them nine more times and they still keep it **once** — a
deed's id is derived from the deed rather than assigned, so a ring cannot be ground out by
repetition. Give them something else the same afternoon and that is a second memory, in order, still
there after a save and a full reload.

**What shipped.**

- **`Deed.CODEC` and `Deed.id()`** — the record session 05 deliberately left un-persisted now has a
  store and a key. The id is a 64-bit mix of the deed's own six identity fields, derived rather than
  assigned, and never written to disk.
- **`Memories`** — a 32-entry ring per persona, oldest first, newest-32 on overflow, exact dedupe on
  `(npcUuid, deedId)`. A side table keyed by persona id, exactly as `Bonds` is.
- **`NpcRegistry.remember`** — the one door that marks the file dirty, and only when the ring
  actually changed.
- **Schema 5**, with a fixer watched to run against worlds written by the previous build on both
  loaders — and then watched *not* to run on the next load of the same world.
- **`DeedBus` does step 3 of `DESIGN.md` §4**, before step 4 and independently of it.
- **`/namesake debug deeds`**, and a `mem` column on `debug bonds`.
- **No new harness leg.** The existing bond-reload check in the `verify` phase grew rings.
- **25 unit tests** (185 → 210), and one diagnosis that was not in the brief — see below.

#### The three decisions the session opened with

**1. What a deed id is: derived, not assigned.** The question is not "which emit was this" but *are
two identical feedings on the same day one deed or two?* A counter or a random UUID keeps both; a
hash of the deed's own fields collapses them.

**Collapsing them is what stops the ring being grindable, and that is the property the ring exists to
have.** The store is a memory, not a log. With assigned ids an afternoon of standing in the square
handing out bread evicts every distinct thing an NPC knows about you and replaces it with thirty-two
copies of one gift — the exact failure `Bond.DAILY_CAP` prevents one level down, arriving through a
door the cap does not watch. Content addressing is the ring's version of that cap and it costs
nothing to hold. `MemoriesTest.theRingIsNotGrindable` is that sentence as a test: five hundred
identical gifts against a full ring push out two days and leave the killing.

Nothing is softened by it. A second identical blow still moves the bond — negatives bypass the cap
entirely and `Bond.apply` has already run by the time the ring is consulted. **The bond is the tally
of how much and the ring is the record of what**; collapsing a repeat in one does not forgive it in
the other. It also costs **zero persisted bytes**, because it is a pure function of fields already on
disk — storing it would be a cache, and session 03 deleted `Settlement.culture` for being exactly
that. `DESIGN.md` §3's "~24 B, ring ≈ 768 B" stays true instead of becoming 40 B and 1,280.

**What it costs at session 08, in three parts, because the brief asked for it plainly.**

- **`confidence` is deliberately outside the derivation, and that *is* the session 08 decision.** A
  rumour retold is the same event known less well, so the same deed arriving twice by different
  routes collapses to one ring entry rather than two rows for one murder. Which of two copies
  survives when they disagree is session 08's to rule; `Memories` keeps the one it already has and
  does not reorder for a duplicate, because being told a thing again is not the thing happening again
  — and refreshing the slot would let gossip push first-hand memories out of a ring.
- **Blurring the actor produces a different id.** Session 08 blurs an actor below confidence 50, and
  a blurred deed will not dedupe against the first-hand one, so a villager could hold both "you
  killed the smith" and "someone from the north killed the smith". **Bounded rather than solved:** at
  `confidence × 0.85` a hop and max two hops from first-hand, confidence floors at 72, so within the
  propagation session 08 actually ships the blur cannot fire at all. If that changes, the paragraph
  to come back to is in `Deed.id()`.
- **The derivation is behaviour and must not drift.** Changing the mix re-partitions every ring in
  every existing save — no corruption, but yesterday's duplicates become distinct. There is no schema
  break to catch that because nothing is stored, so `MemoriesTest` pins the id of one fixed deed to a
  literal. **The literal was computed outside this codebase** from the documented mix rather than
  copied out of a first run, so it says the implementation matches the algorithm the javadoc
  describes rather than pinning whatever the code happens to do.

**2. Where the ring lives: a side table, not a field on `Persona`.** Three reasons, and the third
decided it.

- Bonds set the precedent one session ago and the shape is identical — per-persona social state,
  written by `DeedBus` on emit, read by sessions 09 and 11. One beside the persona and one inside it
  would be two answers to one question.
- `Persona` is the durable *identity* and is rebuilt whole on every write; its `equals` is
  hand-written because `byte[] traits` would otherwise compare by identity. A ring on that record
  means up to thirteen record rebuilds per deed, and it quietly turns "the fields survived the
  reload" — session 01's exit criterion, still asserted by the harness — into a claim about history.
- **The load path.** `Persona.CODEC` is parsed per record and a parse failure counts the whole record
  unreadable. **A single malformed deed inside a persona would take that person's name, culture,
  household and traits with it.** As a table of its own it is counted in its own lane, next to
  settlements and bonds, and the worst a bad deed can do is cost one villager one memory.

**3. Which file: the same one, under the same schema version.** Sessions 03 and 05's tear argument,
unchanged — a deed references a persona and a settlement by id, and two files torn apart by a crash
between two writes produce a save that loads, in which a village remembers things about people no
longer in it.

**The size counter-argument is real this time and was measured rather than dismissed.** Four hundred
personas each holding a full ring is 12,800 deeds:

| | |
|---|---|
| NBT tag tree, built on every save | **1,565,620 B** — 122.3 B a deed |
| gzipped, as written to `namesake_npcs.dat` | **46,506 B** — 3.6 B a deed |

Gzip pays for the readable key names thirty-four times over, because twelve thousand copies of seven
strings and one actor UUID is what a compressor is for. **So the cost actually paid is the tag tree,
not the file** — and it is bounded by a test rather than by this paragraph: one more `int` on `Deed`
would add ~17 B a deed and still fit, two would not. Two things bound it in practice besides: a ring
only fills for a villager a player has done thirty-two *distinct* things in front of, and the derived
id makes a day of repeating yourself one entry.

**And a fourth thing, decided by not doing it.** `DESIGN.md` §4 step 3 says *the subject records it
weighted higher*, and the brief warned to read that before inventing a field. Nothing new needed
storing: a deed already carries its own `subject`, so anyone reading a ring can tell whether it
happened to them by comparing their persona id against it — which is exactly what `Deeds.deltaFor`
does one step later to give the subject the whole share and a bystander a fraction. Storing the
weighting would be storing an answer the struct already contains, and session 05 already found the
version of that mistake worth avoiding: a deed that has to be *told* who it happened to can be told
wrong. **`SocialValueLedgerTest` therefore gained no entries this session and lost none**, and the
comment above `Deed`'s block now says why the obvious eighth field was not added — including the trap
it would have walked into, since the check reads bytecode for a *call* and a record touching its own
field compiles to a `getfield` no such check can see.

#### What the exit criteria actually showed

The ledger's criterion is arithmetic and is therefore a unit test: **forty deeds at one NPC leave the
newest thirty-two, in order, with zero duplicates, and survive a save and a load.** `MemoriesTest`
asserts each clause separately, including that the oldest survivor is day 8 and the newest day 39.

The in-game half needed no new fixture, because **the setup phase's nine feedings already are the
dedupe**: same type, same actor, same subject, same settlement, same day, same severity. Both
loaders, in two launches each, every leg green:

| Leg | Evidence |
|---|---|
| RING | nine identical feedings left **1** memory rather than nine |
| RING | a different kind of deed the same day is a **second** memory, `[FED_HUNGRY, GIFT_WANTED]`, oldest first |
| RING | the subject and all **3** witnesses recorded it (4) |
| RING | the gift moved **0** bonds — every allowance was spent — and was remembered by **4** people regardless |
| RING | the villager behind the wall remembers nothing either: no bond and no memory |
| MEMORY RELOAD | **4/4** rings survived save → quit → reload holding **8** deeds, in order, every field intact |

**That fourth row is the one no unit test in `:common` can make**, because it is about `DeedBus`
rather than about `Memories`: step 3 does not depend on step 4. All four villagers had spent their
whole daily allowance on the nine feedings, so the tenth deed moved nothing at all and was remembered
by every one of them. **Seeing something is not the same as it changing your mind about somebody**,
and moving the ring append below the bond guard turns that line red on its own.

And the instruments, read out of a running game:

```
Gvirkezh Gvirnsk — 2 of 32 remembered, newest first; today is day 0
  day 0     GIFT_WANTED   to them   by ac813ddb  settlement 0  severity 100  confidence 100
  day 0     FED_HUNGRY    to them   by ac813ddb  settlement 0  severity 100  confidence 100

day 0 — 6 loaded NPC(s), nearest first; 4 bond(s) and 8 deed(s) across 4 ring(s) in the world
  who                         trust warmth respect fear   cap   gift×  mem
  Gvirkezh Gvirnsk               +8     +8      +0   +0   8/8/0/0  1.00   2
  Taztanyak Stuksk               +0     +0      +0   +0   0/0/0/0  1.00   0
```

**The second criterion is the owner's and is deliberately not in the ledger:** *a villager who
watched you do something last week can still tell you what it was.* The mechanism is proven — the
ring holds it, in order, across a reload, and `/namesake debug deeds` prints the day it happened on.
Whether **32 feels like memory rather than a buffer** is a ruling and not a test, and it is handed
over. What can honestly be judged before session 09 is narrower than the sentence sounds, for the
same reason session 05's "legible" was: there is no player-facing surface yet. Dialogue is 09 and the
Notice Board is 11, so today the only way to see a ring is the debug command, which is an instrument
rather than the answer. **The question that can be answered now is whether 32 is the right depth**,
and the honest note is that nothing has yet exhausted one — session 07's hundred-day harness is the
instrument that can.

#### Hard rule 1, and the pre-change half done first

The attach-bet harness `setup` phase was run on commit `99dd510` — schema 4 — on **both loaders
before a line of session 06 was written**, and those saves were archived. Mid-session the working
tree was stashed rather than trusted, so the NeoForge pre-change run compiled schema 4 rather than
the half-finished schema 5 sitting in the editor. The schema-5 build then loaded them:

```
NPC registry datafixer: schema 4 -> 5 (deed rings added; nothing to rewrite, an absent
table means nobody has witnessed anything) rewrote 0 record(s)
Loaded 9 persona(s), 9 bound to an entity, 1 settlement(s), 4 bond(s),
0 deed(s) across 0 ring(s) (schema 5)
```

**This is the additive kind, and saying so out loud is the point — for the second time running.**
Schema 4 said it and schema 5 says it again, because "additive" is a claim rather than a default.
What makes it checkable here is stronger than it was at 4: **`Deed` did not arrive with session 06.**
The record and all seven of its fields shipped in session 05; what 06 added is a codec and a store.
So there is no older shape of a deed anywhere on disk to reconcile — a schema-4 save cannot contain
one in any shape, because nothing could write one.

**And the assertion is on the thing that would actually break, not on the rewrite count**, exactly as
the brief asked. Zero rewrites is also what a fixer that does nothing at all reports — session 03
broke the 2 → 3 fix into precisely that and turned the build red for it. The hazard here is the
absent `memories` key being read as damage, which turns the registry read-only; and because
settlements, bonds and rings share one file, a world somebody has played for a week then silently
stops saving **all three**. So the evidence is the four bonds the schema-4 build wrote coming through
intact on a writable registry:

```
PASS  DATAFIXER 4->5 the 4 bond(s) the schema-4 build wrote came through intact — which is
      what an absent ring table being read as damage would have cost this world
PASS  DATAFIXER 4->5 the registry is writable, so the migrated file will be written back at
      schema 5 rather than migrating again on every load
```

**Then the same world was loaded again**, which is the only way session 01's defect 1 is ever caught:
`no migration expected: world is already at schema 5`. The fix reached disk.

#### Rule 3: sixteen deliberate breakages, each watched to fail and removed

| Breakage | Result |
|---|---|
| The exact dedupe removed | **5 red.** *"five hundred identical gifts on one day must not push out a killing"* |
| The 32-entry bound removed | **6 red.** *"the newest 32 of 40 — expected: &lt;32&gt; but was: &lt;40&gt;"* |
| A duplicate allowed to refresh its slot | **4 red.** *"expected: &lt;[1, 2]&gt; but was: &lt;[2, 1]&gt;"* |
| An over-long ring on disk refused instead of truncated | **Red.** *"a bound this build does not share is not damage — expected: &lt;0&gt; but was: &lt;18&gt;"* |
| An absent ring table read as damage | **5 red**, three of them about bonds and personas |
| `confidence` folded into the deed id | **2 red**, including the pinned literal |
| `severity` dropped from the deed id | **3 red.** *"the derivation collided, so it is dropping an input — expected: &lt;120000&gt; but was: &lt;30000&gt;"* |
| The subject dropped from the deed id | **2 red.** *"a different subject"* |
| The `setDirty` removed from `NpcRegistry.remember` | **Red.** *"a ring written into a clean registry never reaches the file"* |
| A duplicate allowed to mark the registry dirty | **Red** |
| Unreadable deeds left out of the damaged-file guard | **Red.** *"a villager who has quietly lost a memory is exactly as unsafe to write back as a village that has lost its bell"* |
| A pruned persona allowed to keep its ring | **Red** |
| `RING_CAPACITY` doubled to 64 | **6 red**, one of them the budget: *"the tag tree built on every save was 3114420 B, over the 2,000,000 B ceiling"* |
| `Deed.CODEC` renamed out from under `Memories` | **Does not compile** |
| The schema bumped to 6 with no matching fix | **12 red.** *"the fix chain has a hole between 5 and 6"* |
| `Deed.severity`'s ledger entry deleted | **Red.** *"Deed has fields with no entry in the social value ledger: [severity]"* |

**The last one reported NOTHING FAILED the first time it was run, and that turned out to be the
script rather than the guard.** The regex did not match, so nothing was deleted and a green build was
reported for a breakage that had never been applied. Worth recording because it is the *inverse* of
session 05's fifth row and just as dangerous: **a breakage that silently fails to apply reads exactly
like a guard that works.** Every breakage after it is checked for a non-empty diff before the tests
are run.

#### One defect, and it is not in this session's code

**`GameRenderer.render` pauses a single-player world when the window loses focus, and a paused
integrated server does not tick.** The first pre-change run stopped dead after
`PASS CURE conversion started`: no error, no timeout, no further output for eight minutes, the
process alive at about five seconds of CPU a minute. Vanilla's own log says it two lines later —
`Saving and pausing game...` — and the mechanism is `GameRenderer.render` calling
`Minecraft.pauseGame(false)` after 500 ms of an inactive window, which opens a `PauseScreen`, which
sets `Minecraft.pause`, which stops the server. **Every deadline this harness has is counted in
server ticks**, so the script simply stops.

**This is session 05's undiagnosed mid-run wedge.** That entry records the symptoms — *"no further
output for four minutes, the process alive at 2.4 seconds of CPU across ninety seconds of wall clock
— idle, not working"* — and that a re-run passed first time. Both follow from this: a re-run passes
because a freshly launched window has focus. It read as a NeoForge problem because of when the owner
happened to click away, and it never was one. **It was diagnosable this session only because the
owner was working alongside the run**, which is the condition that produces it.

`HarnessClient` now turns `pauseOnLostFocus` off on every armed run, beside muting the client — the
two properties an unattended run needs from a machine nobody is sitting in front of. With it off, the
same pre-change run finished in about four minutes, and eight further client launches across both
loaders did not stall once. **Session 05's carried note is therefore half retired:** the CPU-time
discriminator is still the right instrument and worth keeping, but the thing it was detecting has a
name and a fix. What remains undiagnosed is only session 01's *shutdown* hang — a different failure
at a different point, still bounded by the watchdog.

Two smaller defects, both mine and both in tests rather than in code: a grindability fixture that put
its killing on a day the ring already held, and an off-by-one count of identity fields. Neither
survived first contact with the test runner, which is what it is for.

#### The gap session 05 left open, and why it is still open

`DeedBus.witnessScan` and `DeedBus.emit` have meters and have still never been pointed at anything.
The brief offered a `-Pprofile=deeds` phase if it were cheap. **It is not, and the reason is specific
to today rather than general.**

Session 04 ruled the profiler gets no CI job because *"a wall-clock number from a shared runner whose
neighbours we cannot see is not evidence"*. This session's runs happened on the owner's machine
**while the owner was working on it** — and the proof that this matters is the defect above, which is
the machine's other user reaching into a measurement and stopping the server. A deed cost measured
under those conditions would be the confident-wrong kind, and a new phase needs its own crowd
fixture, warm-up and teardown, which is exactly the shape of session 04's five defects.

What can be said without measuring has moved, though, and it is worth saying because **the cost is no
longer where the brief expected it.** The emit side is bounded by construction: at most thirteen ring
appends, each a linear walk of at most thirty-two `long` comparisons, on emit only and never on a
tick where nothing happened. What session 06 actually added is on the **save** path, not the tick
path — an emit now marks the registry dirty, and a dirty registry is up to 1.5 MB of NBT built at the
next autosave. That number is measured and guarded above. A `-Pprofile=deeds` phase would have
measured the wrong half. `Meters.count("DeedBus ring entries written")` is there for whoever writes
it anyway.

#### The playtest, and the two things it found

**The owner played it at close and both exit criteria held in their hands rather than in a fixture.**
A fresh dev client picks a fresh username, so they arrived as a player the village had never met —
which made the evidence better than the harness's, because it separated two actors:

- `debug bonds` read **+0 on every axis** for six villagers who were sitting on four bonds, because
  those bonds are about a different player. Per-player scoping, shown rather than asserted.
- Feeding one villager produced a ring holding **`FED_HUNGRY by beda00ca`** *next to* the harness's
  **`FED_HUNGRY by ac813ddb`** — the same deed type, the same day, the same village, two entries,
  because the actor is part of the id. That is the derivation working in a running game.
- Three distinct deeds gave a ring of five. **Repeating one gave a ring of five.** The dedupe, done
  by a person rather than by a loop.

**And it found two defects, both invisible to every test in the repo, because every instrument here
reads these commands out of a log file and a log file has no width.**

1. **A carriage return in every deed row.** `String.format("%n")` is the *platform* separator, so on
   Windows it emits `\r\n`; Minecraft has no glyph for a carriage return and draws it as a
   missing-character box. Every row ended in a small square. Nothing in the codebase had used `%n`
   before — this session introduced the first one.
2. **The tables wrapped.** The deed row was ninety characters and wrapped *in the middle of a
   table*, which reads as two rows rather than one. `debug bonds` wrapped too — and that one was
   already over the chat width before this session, which added a `mem` column and made it worse.

**This project has now shipped this exact class of defect three times**, so it is enforced rather
than remembered: session 02's action bar took two full UUIDs and clipped, session 03's grammars
produced `Hseingtsainhianng`, and now this. `CommandLayoutTest` measures both commands against a
budget. The deed row lost three columns that were *noise* — severity, confidence and settlement are
nominal on every deed anything currently emits, and a column reading `100` on every row for two
sessions is not information — so they now print only when they carry some, which is also when they
are the most interesting thing on the row. `debug bonds` pads its name column to the widest name in
the report rather than to the 27 characters session 03's budget allows, which is nine characters a
row that no real village was using; that took it from 74 back to 60.

**One of the five breakages for these guards is worth its place**, because it is the number the
owner's screenshot showed: putting the fixed padding back reports *"a bonds row is 74 characters,
over the 66-character ratchet"*.

**And the breakage script itself produced a defect worth recording.** It reverts each breakage with
`git checkout -- common`, which on the second run wiped the uncommitted fixes along with the
breakage — so three breakages reported "did not apply" against a tree that no longer had the guard
in it either. The rule that already existed for this is the one that was skipped: **commit before
breaking things.** The non-empty-diff check added earlier this session is what caught it rather than
letting three false greens through.

#### Carried into session 07

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs.
- **A quiet harness run is now much more likely to be genuinely stuck**, because the commonest cause
  of a quiet one has been fixed. Read `<loader>/run/namesake-harness-result.txt` and the client's CPU
  time as before; both logs still buffer.
- **The 32 is provisional in the same way `Bond.DAILY_CAP = 8` is**, and for the same reason: it is a
  number nobody has watched a real playthrough exhaust. Session 07's harness is the instrument that
  can — a hundred in-game days of a settlement will say how fast a ring actually fills and whether
  anything worth keeping is being evicted. Ask it rather than anyone's eye.
- **Session 07's number is unchanged and is still the one that matters:** the median settlement's
  week-apart is **14** and the ruling wants **25**. Widen the weights, not the base cap.
- The derived deed id is why a ring fills slowly. If session 07's data shows rings full of one deed
  type, that is a signal about the deed types rather than about the ring.

**Ruled at close, by the owner — and it is a direction rather than a correction.**

- **"I still want the NPCs to have in-depth memories."** Recorded as a standing goal, not a defect
  report against this session: the ring works, and it is thin *by design so far*, because nothing
  reads it until session 09. The four ways to make it deeper cost wildly different things and only
  one of them is capacity — see the note below. **Nothing was changed on the strength of this
  ruling**, because changing it now would persist detail no `if` statement reads, which is the
  failure `DESIGN.md` §1 exists to refuse and the one both reference codebases died of.
- **32 is not to be judged against a two-entry fixture.** The owner declined to rule on ring depth
  or on the eviction policy until they have seen **what a villager's ring actually looks like after
  a hundred in-game days**. Both questions are therefore parked, deliberately and with their reasons
  recorded, rather than answered from a harness that has emitted ten deeds.
- **So session 07 gains one output it did not have.** The headless harness already has to dump a
  chronicle and an earn-rate histogram; it must also **print a real ring** — the deepest and the
  median one in the settlement, with the deed-type mix and how many slots are actually in use. That
  is the artefact the two parked rulings get made against. It is a report, not a mechanic, and it
  costs session 07 almost nothing because the run that produces it is the run it was already going
  to make.

#### The four meanings of "in-depth", and what each one costs

Parked here so session 07 has the shape of the question when the data arrives, and so the answer is
not re-derived from scratch. **Only the first is about capacity, and capacity is the least
interesting of the four.**

| | What it means | What it costs |
|---|---|---|
| **More slots** | 32 → 128 or 256 memories each | Linear. 128 slots × 400 personas is 6.3 MB of NBT a save at the current encoding, which is past what a readable codec should carry — it needs the packed fixed-width ring (44 B a deed against 122 B) that `Memories` currently declines. Tractable, and the least felt per byte. |
| **Richer per memory** | *which* item, not just "a gift" | A few bytes. **The largest felt gain per byte available** — it is the difference between "you were kind to me" and "you gave me bread when I was hungry". Blocked only by having no reader. |
| **A repeat count** | "you fed me — nine times, that day" | ~2 bytes on the ring slot, not on `Deed`. Keeps the ring ungrindable *and* restores the magnitude content-addressing collapses. Offered at the close of 06 and left unruled. |
| **Consolidation** | thirty gifts last month become *"you were kind to me, often, around day 40"* | Real design work, and the only one that makes a ring feel deep without growing. It is also how episodic memory actually decays into semantic memory, and it is what would make the eviction question stop mattering — a forgotten memory becomes a summary rather than a hole. Post-slice at the earliest; `DESIGN.md`'s chronicle rows at 21-23 are the nearest existing hook. |

**The constraint that produced 32 was never storage.** It is that a deed ring has *no consumer yet* —
session 09's dialogue pools are the first thing to read one and session 11's board is the first thing
to show one. Making a memory richer before either exists persists detail no `if` statement reads,
which is precisely what `DESIGN.md` §1 forbids and what MCA's 22 traits and LNK's affinity score both
were. **So the sequence is the answer: 07 measures it, 09 reads it, 11 shows it — and the depth
decisions are cheapest to make at 09, when there is finally a line of dialogue whose quality depends
on them.**

**Ledger change.** Session 06 → done, session 07 → NEXT. **No risk changes**: risk 5's five
exemptions are untouched and none fell due this session, so for the first time since session 02 the
forcing function was not the thing keeping rule 5 honest — the discipline was applied while the
record was being written, and it came out as an eighth field deliberately not added. Two decisions
added to `DESIGN.md` §2, the deed id and the deed store, taking the count 47 → 49. A duplicated
paragraph at the end of the session 05 entry was deleted; it was a copy-paste, not a disagreement.
No changes to the 16-session shape.

### Session 07 — 2026-08-14 — the headless simulation harness

**Shipped.** `9169cd7..bdac6e2` plus this ledger commit, pushed to `origin/main`. CI green on all
three jobs — build and test, and the attach-bet harness on each loader.

**Time runs forward now.** A settlement of nine can be advanced a hundred in-game days in **six to
eight milliseconds on a live server thread** — through the shipped record layer rather than a copy of
it — and it dumps a chronicle, a bond-earn-rate report and a real deed ring. Every band threshold
from here is set from that data.

**And the first thing it measured was a threshold nobody could ever have crossed.**

#### The three decisions the session opened with

**1. How headless is headless, and what the number is therefore evidence of.**

A hundred in-game days is 2.4 million ticks. Session 01 established that a long `/tick sprint`
outruns the chunk loader, and `runServer` needs an EULA that is not ours, so a real world cannot be
sprinted through it. The brief framed the choice as *simulate the records and you are testing
arithmetic; simulate the world and you cannot do a hundred days in a minute* — and the answer is
neither, because of a property of the record layer rather than a compromise between them.

**The record layer's only input from the passage of time is an integer.** `Deed.dayOf` divides game
time by 24,000; `Bond.decayedTo` takes a day; `Bond.apply` resets the allowance when the day turns.
Nothing downstream of a deed ever sees a tick. So a hundred days here is **a hundred days exactly**,
with no fidelity lost at all, for everything `DESIGN.md` §8 rules the authority.

What is genuinely modelled is two things and only two: **what a player does** and **who was close
enough to see it**. Everything between a deed and the save file is the shipped code called through
its shipped door — `DeedBus.record` was pulled out of `DeedBus.emit` at exactly the point the level
has finished being asked who was watching, and the simulation calls that. Nothing re-implements
`Deeds.deltaFor`, `Personality`, `Bond.apply` or `Memories`; a report built on a second copy of that
arithmetic would be a report about the copy.

**So the earn rate is evidence of what the record layer does with a stated stream of deeds. It is
not evidence of how many deeds an hour of play produces.** That is the model's *input*, it is an
assumption, and it is handled as one: five player models rather than one, and the witness fraction —
the one number nobody has measured — swept from 0% to 100% rather than asserted. Session 15's
playtest is what measures the input, and `/namesake debug earnrate` computes the same numbers off a
real save so the two can be held against each other rather than compared by eye.

**2. Whether `DialogueStats` is persisted at all. It is not, and the ledger asked for a SavedData.**

Three reasons and one trap, stated because the instruction was explicit.

Every number in it is **derivable from state that is already persisted and already ledgered** — a
bond carries what it is worth and when it was last touched, a ring carries thirty-two deeds each
stamped with a day. Storing them as well is a cache, and session 03 deleted `Settlement.culture` for
being exactly that. **A stored tally survives a change to the thing it counts**: retune a weight, add
a deed type, move the cap, and a persisted histogram still describes a mod that no longer exists,
with nothing available to notice. And **hard rule 1 would charge a schema bump, a datafixer and a
load test** for the ability to regenerate on demand something that is already free to regenerate on
demand.

The tear argument sessions 03, 05 and 06 all made genuinely does not apply, and that is worth saying
rather than assuming: it is about *world state that references itself by id*, and measurement data
does not. So `DialogueStats` **could** have been its own file. That is an argument against one file,
not an argument for persisting it.

**The trap the brief named is real and the build would have been right to refuse it.** A
`DialogueStats` whose named consumer is `/namesake debug stats` is a display, `DISPLAY_PACKAGES`
already contains `net.namesake.command`, and both ways round it are dishonest — an exemption is a
promise that a mechanic will read the field and no mechanic reads a histogram, and a `PRESENTATION`
classification means *ruled in `DESIGN.md` as existing to be drawn*, which is true of
`Persona.appearanceSeed` and would have been a lie here. The honest answer was to have nothing to
classify. `SocialValueLedgerTest.measurementDataIsNotPersisted` is what stops that quietly reverting:
it fails if the class ever declares a codec, and it fails if `NpcRegistry`'s save or load path so
much as mentions it.

**What it costs, plainly.** A deed evicted from a ring leaves no trace, so nothing derived can report
what was forgotten — only how far back the ring still reaches. The simulation *can*, because it keeps
its own chronicle, so the ring dump prints both and the gap between them is one of this session's
artefacts.

**3. What an earn rate is measured over: warmth points per in-game day of contact.**

The mechanism's own clock is the in-game day. `Bond.DAILY_CAP` is per axis per day, the decay is a
point a day, `lastSeenDay` is a day, and `Deed.dayOf` is what turns game time into one. A
**player-hour** needs a conversion nobody will remember and differs between two players with
identical play time. A **per-deed** rate is dominated by how hard somebody is grinding, which is
precisely the number the daily cap exists to make meaningless. Reported beside it: the same warmth
per day *elapsed*, which is what the decay bites into and what somebody who visits rarely actually
experiences. Both are on the report, with the unit printed next to the number rather than living in a
javadoc.

#### What the exit criteria actually showed

**A hundred days in well under a minute.** A hundred days of a nine-resident settlement runs in about
**half a millisecond** in a JVM under test and **6 ms on Fabric / 8 ms on NeoForge** on a live
integrated server thread, against a 50 ms tick. The largest run made this session — forty residents,
two hundred days, a saturating player, ninety percent witnesses — is **570 ms**.

**A readable chronicle.** Digested to a week, with every ladder crossing and every harmful deed
printed in full and in place, because a hundred nearly identical rows is a log rather than a
chronicle — the difference being that somebody reads a chronicle to find out *when things changed*.
`Reports.chronicleInFull` is the every-deed version for when a week is not fine enough.

**A bond-earn-rate report**, per resident and per player model, with days-to-reach at the best, median
and worst observed rate — and `never` printed as `never`, because a rate of zero reported as zero days
would say a threshold is reached immediately by somebody who will never reach it at all.

#### The two numbers this session owed

**The week-apart, closed from 14 to 28, on the same instrument that measured the 14.**

`Personality` now separates **shape** from **magnitude**. `SHAPE` is byte-for-byte the eight-by-six
table session 05 shipped; `SPREAD` is one constant that scales every weight, and it moved 1.0 → 1.6.
Scaling the table scales every villager's deviation from neutral by exactly that factor and the
centring scales with it, so *a typical villager scores exactly one* still holds by construction. The
owner's close-of-05 ruling was **shape now, magnitude at session 07**, and this is what makes both
sentences literally true rather than approximately.

| `PersonalityDistributionTest`, 4,536 personas, seed 20260814 | before | after |
|---|---|---|
| week apart, filling the allowance — **median** | **14** | **28** |
| …widest | 28 | 42 |
| week apart, one gift a day — median | 7 | **14** |
| …widest | 14 | 21 |
| `gift_wanted`, p5 → p95 across the population | 0.662 → 1.338 | 0.459 → 1.540 |
| `gift_wanted`, the median settlement's own spread | 0.361 | **0.562** |
| the clamp | [0.40, 1.60] | [0.30, 1.80] |
| …how often it engages | — | **0.67%** of (persona × deed type) |

**The gap is quantised to multiples of seven, and 25 is not a reachable value.** An allowance is an
integer, because `Bond.gainedToday` counts it in four bits, so seven days of two villagers'
allowances differ by exactly seven times an integer. `SPREAD` 1.2 reaches 21 and 1.6 is the smallest
multiplier that reaches 28. That is why the assertion reads ≥ 25 and the number is 28.

**The clamp stopped being a chosen pair of numbers.** Its stated job is to refuse absurd variation at
the corners of the trait space, so it is now held to a *measured rate* — under 2%, reading 0.67%.
Left at [0.4, 1.6] the same widening would have clamped **2.4%**, flattening exactly the villagers at
either end of a village, which is where the whole mechanic is visible.

**And the cost, stated rather than found later: the between-culture gap widened by the same factor,
because it comes out of the same table.** Karsk's median gift multiplier fell from ×0.74 to ×0.59 and
Meridian's rose from ×1.25 to ×1.40, so a Karsk village now warms at about **half** a Meridian one's
rate where it was three quarters. That is a real change to how a village feels and it is the owner's
to rule. It is also, for what it is worth, the direction standing risk 3 wants.

**The ring, after a hundred in-game days.** The artefact the two rulings parked at the close of
session 06 are made against. One deed a day, nine residents, 35% of the village witnessing:

| | |
|---|---|
| villagers who remember anything | 9 of 9 |
| rings **full** at 32 | **9 of 9** |
| deeds held across the settlement | 288 — **100%** of its total ring capacity |
| occupancy distribution | every villager on exactly 32 |
| deed mix | `FED_HUNGRY` 99 · `GIFT_UNWANTED` 97 · `GIFT_WANTED` 92 |

**The deepest ring** — the villager the player fed most — holds 32 of 32 spanning days 42 to 99,
**58 days of reach**, mixed `FED_HUNGRY` 20 · `GIFT_WANTED` 6 · `GIFT_UNWANTED` 6. Fifty-six deeds
happened in front of them; they hold thirty-two and have **forgotten twenty-four**. **The median
ring** holds 32 of 32 spanning days 2 to 96, **95 days of reach**, mixed almost evenly across the
three kindnesses. Under a *saturating* player at forty residents the deepest ring's reach falls to
**22 days**.

So the plain reading, offered rather than ruled: **32 slots is between three weeks and three months
of memory depending on how hard somebody plays**, it fills completely in a hundred days for
everybody, and what it evicts is the oldest of a set of near-identical kindnesses rather than
anything a player would miss. The eviction question is the one the numbers do not settle: nothing in
this run gave a villager a *killing* to keep, so nothing has yet tested whether thirty-two subsequent
gifts push one out. `MemoriesTest.theRingIsNotGrindable` covers the same-day version of that; the
many-day version is a design question rather than a measurement.

**And the artefact points at one of session 06's four routes to depth rather than at another.** The
deepest ring in the settlement holds thirty-two slots and **three distinct sentences**. It is not
shallow because it ran out of room; it is shallow because twenty of its rows say the same thing.
Session 06 priced *more slots* as linear and "the least felt per byte", and *richer per memory* —
**which** item, not just "a gift" — as "the largest felt gain per byte available, blocked only by
having no reader". This run is the first evidence for that ordering rather than an argument for it,
and session 09 is when there is finally a line of dialogue whose quality depends on the answer.
**Still the owner's to rule, and nothing was changed on the strength of it**: `Deed` gained no field
this session, because persisting detail no `if` statement reads is the failure `DESIGN.md` §1 exists
to refuse and the one both reference codebases died of.

#### What the run found that nobody asked it, and the first one is the important one

**1. Warmth from witnessing is cancelled exactly by the decay.** A witness's share of a gift is one
point; warmth falls one point an in-game day. Five of nine residents met the player and hold **zero**
warmth after a hundred days, and seven of nine never held more than **one**. Trust, which does not
decay, reaches **100**. So warmth accumulates only for the people a player directly gives things to,
and even for them only while a contact is worth more than the gap since the last one.

**Which makes `DESIGN.md` §5's residency threshold unreachable on warmth.** The day the *third*
resident crossed each mark, one deed a day, a hundred days:

| | 20 | 40 | 60 | 80 | 100 |
|---|---|---|---|---|---|
| **warmth** | never | never | never | never | never |
| **trust** | **29** | 60 | 89 | never | never |

**This is LNK's failure caught two sessions before it would have shipped.** LNK set gates at 35–205
against an observed maximum of 32 and nobody noticed for months. Session 09 owns the ruling — read
trust, read *peak* warmth rather than current, or raise the witness share — and session 07 owed it the
table. Nothing was changed on the strength of it, because tuning a decay curve to make a threshold
reachable is tuning the measurement to fit the answer.

**2. `Bond.DAILY_CAP = 8` was flagged provisional at the close of session 05, and here is the
number.** A saturating player pins **the whole village** at 100 warmth and 100 trust: a full standing
band in **3** days of contact and the ceiling in **13**. That is what the cap permits, by
construction — the saturating row *is* the cap measured, so nothing can beat it. Whether it is too
generous is a ruling, and the brief was explicit that the cap is not to be moved this session, so it
is recorded rather than touched. It is the same number session 12's thresholds are set against.

**3. Standing in the room matters more than anything the model assumed.** The witness sweep:

| witnesses | warmth max | median | met you | rings full |
|---|---|---|---|---|
| 0% | 38 | 35 | 3 | 3 |
| 25% | 56 | 0 | 9 | 4 |
| **35%** | **56** | **0** | **9** | **9** |
| 50% | 67 | 1 | 9 | 9 |
| 100% | 70 | 1 | 9 | 9 |

The subject's own warmth **rises with the witness fraction**, from 38 to 70, which is not what a
witness share is supposed to do — and the mechanism is the decay again from the other side. A
resident the player is focused on also *witnesses* the deeds done to the other two, so a higher
witness fraction gives them contact on more days, and more days of contact is what stops the decay
eating what they earned. **Being in the room when your neighbour is fed is what keeps your own warmth
from draining.** Nobody designed that; it falls out of a per-day decay meeting a per-day cap.

**4. The village looks completely different depending on who is playing.** A hundred days:

| model | deeds | warmth max | w p50 | trust max | t p50 | rings full |
|---|---|---|---|---|---|---|
| `ATTENTIVE` | 100 | 56 | 0 | 100 | 23 | 9/9 |
| `SATURATING` | 1,200 | 100 | 100 | 100 | 100 | 9/9 |
| `INTERMITTENT` | 68 | 28 | 2 | 100 | 24 | 4/9 |
| `PASSING_THROUGH` | 15 | 2 | 0 | 22 | 3 | 0/9 |
| `CARELESS` | 200 | 83 | 3 | 100 | 38 | 9/9 |

`PASSING_THROUGH` is the row session 09 should look at hardest: somebody who does one thing a week
never leaves the bottom of any axis and never fills a ring, so the first hours of the mod are the
stranger pool and nothing else. And `CARELESS` — two gifts a day and a blow every eighth visit — ends
**warmer** than `ATTENTIVE`, because goodwill given twice a day outruns a blow given eight times less
often, and nothing goes negative at all. That is the shape session 12's hostile band has to be set
against, and it says violence has to be either rarer in the model or heavier in the table.

#### Hard rule 1 did not fire, and proving that was cheap

**No persisted schema change shipped this session.** `NpcSchema.CURRENT` is still 5, no record gained
or lost a field, and `Deed.id()`'s derivation is untouched — which matters, because session 06 named
changing it as the one drift with no schema break to catch it. `DialogueStats` was the only thing
that would have moved the number, and it is derived.

It was checked rather than asserted anyway. The `setup` phase was run on commit `c8fe745` — the
session 06 head — from a detached checkout, and that world was then loaded by the session 07 build:

```
Loaded 9 persona(s), 9 bound to an entity, 1 settlement(s), 4 bond(s),
8 deed(s) across 4 ring(s) (schema 5)
no migration expected: world is already at schema 5
PASS  SCHEMA registry is writable (not refused as too new)
PASS  RELOAD 3/3 personas survived save -> quit -> reload with the same id and values
PASS  SETTLEMENT RELOAD 6/6 residents came back with the same name, household and settlement
```

One behavioural change does reach existing saves without touching a byte on disk, and it is worth
naming: **`Personality.SPREAD` changes what a stored persona is worth.** A world played before this
session loads identically and its villagers become more different from each other overnight. Nothing
needs a fixer, because nothing on disk means anything different — a save records eight trait axes and
those eight numbers are unchanged.

#### Rule 3: ten deliberate breakages, each watched to fail and removed

| Breakage | Result |
|---|---|
| The clamp left at 0.4/1.6 while `SPREAD` is 1.6 | **4 red.** *"the clamp engages on 2.43% … Widen `Personality.MIN` and `MAX` to match `SPREAD`"* |
| `SPREAD` put back to session 05's 1.0 | **2 red.** *"the median settlement's two extremes end a week … 14.0 points apart"* |
| `DialogueStats` given a codec | **Red.** *"it is on its way into a save file … hard rule 1 then owes a schema bump, a datafixer and a load test"* |
| `NpcRegistry.save` made to reach `DialogueStats` | **Red.** *"measurement data on the save path is measurement data in a save file"* |
| The earnrate name column widened to 48 | **NOTHING FAILED.** The fixture never reached the width. |
| …the same breakage, after the test was given the widest name the generator can make | **Red** |
| A `%n` put into a report line | **Red.** *"Minecraft draws a carriage return as a missing-glyph box"* |
| The simulation's subject chosen from the wall clock | **2 red**, including *"two runs of one plan must produce the same report"* |
| Contact counted in deeds rather than in days | **2 red.** *"one day, whatever happened on it"* |
| Percentiles taken over strangers as well | **Red.** *"ninety-six strangers must not make the median of four friendships zero"* |
| A rate of zero reported as zero days rather than never | **NOTHING FAILED.** The only test covering it returned early on a different branch. |
| …the same breakage, after a fixture that has met the player and earned nothing | **Red** |

**Two of the twelve rows are the pass finding its own gaps, and both are the same shape as session
06's:** a guard that exists and a fixture that never reaches it. The name-column one is exactly the
defect this project keeps shipping — a table measured against a fixture rather than against a village
— and it is the third time the lesson has had to be relearned in a new place.

#### The defect the harness found, on its first run

`/namesake debug simulate` told chat where it had written its report, **as an absolute path**. In a
development worktree that is **130 characters** against a sixty-character chat width, so the last line
of a nine-line message arrived as three rows with a directory split across two of them.

**Every unit test in the repo was green and could not have been otherwise**: the path depends on where
the game is running and nothing in `:common` knows it. The harness step measures what Brigadier
actually emits to a player, and it turned red the first time it ran. Chat now gets the file name and
the log gets the path — and `CommandLayoutTest` caught the *replacement* line too, at 67 characters,
because the line is built by a method a test can hand a path far worse than any real one.

That is the fourth time this project has shipped a string nobody measured against the space it has to
sit in.

#### One harness step, and why it is not a new leg

The ledger's own rule: anything a unit test can prove belongs in a unit test, and this session's
report layout, percentiles, bucketing and arithmetic are all pure. **The harness grew six assertions
inside a phase that was already running** — no new launch, no new CI job, about a millisecond of the
six minutes the witness phase already spends. They make three claims no `:common` test can:

- a hundred days completes on the server thread **inside a single 50 ms tick**;
- the live registry — nine personas, a settlement, four bonds, eight deeds across four rings — is
  **identical afterwards**, queried rather than assumed. The simulation's registry is built with `new`
  and never handed to a `DimensionDataStorage`, so structurally it cannot reach disk; but
  "structurally cannot" is a claim, and this project queries rather than claiming;
- the three commands survive the real dispatcher and reach a player under the chat width with no
  carriage return, which is the assertion that found the defect above.

**Both loaders, every leg green:** 49 in `setup` (43 before this session) and 9 in `verify`, in two
launches each, plus the cross-build load test above. **252 unit tests**, up from 218, real JUnit XML,
`failures=0 errors=0 skipped=0`.

#### The fifth one, and the owner found it in ten seconds

**Every width guard this session shipped measured a populated table, and all three of the commands'
absence branches were over the budget.** The owner ran `/namesake debug stats`, `earnrate` and
`simulate` in a dev client as a fresh username, in a world nobody had done anything in — which is the
state a real player is in for their first hour and the one no fixture here produced. `debug earnrate`
answered with a **68-character** apology:

```
 68  OVER 60  |  nobody has met you, so nobody is earning anything. Go and be seen.|
```

Two more were sitting behind it and had simply never been rendered: the empty-ring line at **71** and
the no-viewer line at **68**. Every one of them is a branch that only fires when there is nothing to
report, which is exactly when a player meets the command for the first time.

**That is the fifth instance of this project's signature defect, and the third time in two sessions
that the guard existed and the fixture never reached it** — after the earnrate name column and the
never-versus-zero-days branch, both caught by the breakage pass. So the guard stopped sampling one
state and now enumerates four: populated, a village that has not met you, an empty world, and the
console with no viewer at all. Reverted, the 68-character line turns it red by name.

**And it is the working agreement paying for itself.** `CLAUDE.md` says the owner playtests and rules
on feel; this session substituted an instrument for that and filed the gap as optional. The
instrument is genuinely better than it was — it measures what Brigadier emits, at scale, and it
caught the 130-character path — but it measures the states somebody thought to write down, and a
person opening the game finds the state nobody thought of.

**The verify count is 9 rather than 10 and that is a property of the save, not a lost leg.**
`checkDataFixer` returns on its first line with a log entry when the world on disk is already at the
current schema, so its assertion only exists on a run that actually migrates something — and session
07 changed no schema, so nothing migrated on any of these four runs. Stated because the number moves
and a bare count that shrinks reads exactly like a regression. Session 06's 44/10 was measured on a
schema 4 → 5 migration; the 43 this session measured on `c8fe745` is the same phase with nothing to
migrate.

#### Carried into session 08 *(all four addressed — see the session 08 entry)*

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs — and **delete
  `<loader>/run/saves/namesake_attachbet`, not `namesake_harness`**, which cost one confusing red run
  this session: `setup` run twice on one world lays a second village on top of the first and fails
  seven legs for a reason that has nothing to do with the code.
- **The warmth-decay finding is session 09's problem and it is the biggest thing on this ledger.** A
  residency threshold on warmth is a threshold no player crosses. Read trust, read peak warmth, or
  raise the witness share — but decide it against the table above rather than by eye.
- **`STILL_UNSETTLED` still fires zero times**, and this session added no evidence either way: the
  simulation never loads a chunk, so it cannot exercise the branch session 04 flagged. The index is
  still session 08's problem at the earliest.
- **`DeedBus.witnessScan` and `DeedBus.emit` still have meters pointed at nothing**, and the brief's
  offer turns out not to apply. The simulation emits thousands of deeds *without the spatial query* —
  that is precisely the seam it replaces — so it cannot measure the half the meters watch. It is not a
  third decline so much as a discovery that the two instruments do not overlap; the phase session 05
  described is still the only thing that would answer it.
- The earn-rate unit is **warmth per in-game day of contact**. Session 12's thresholds are written in
  it; `/namesake debug earnrate` computes the same number off a real save.
- **A ring-derived rate over-states the truth and never under-states it**, because the warmth is
  cumulative and the days it is divided by are capped at what thirty-two slots still cover. At a
  hundred days the error reaches **+109%**. Session 12 must not set a threshold from a live save
  without that correction.

**Ledger change.** Session 07 → done, session 08 → NEXT. **No risk changes and no exemption
movement**; none fell due, and for the second session running the forcing function was not what kept
rule 5 honest. Three decisions added to `DESIGN.md` §2 — the personality magnitude, the earn-rate
unit, and that measurement data is never persisted — taking the count 49 → 52. A fifth verification
instrument recorded above, and it is the first one that is *in* CI, by the same argument that keeps
the profiler out. No changes to the 16-session shape.

### Session 08 — 2026-08-14 — gossip and distortion

**Shipped.** `72ae69e..e2c918a` plus this correction, pushed to `origin/main`. CI green on all three
jobs — build and test, and the attach-bet harness on each loader.

**A deed now reaches people who were not standing there.** Feed a villager in a square and within two
in-game days **78% of the village holds it**, at three descending confidences, and at least one of
them can no longer say who did it. The villager behind a wall records nothing when it happens —
session 05's leg, unchanged — and has heard about it an in-game hour later. **The wall stops them
seeing it. It does not stop the village talking.**

#### The contradiction the session opened on, and why the retention was the only thing that moved

`DESIGN.md` §4 step 7 carried three clauses written before there was any code to run them against:
`confidence × 0.85` per hop, **max 2 hops**, and **identity blurs below 50**. Session 06 did the
arithmetic and wrote the consequence into `Deed.id()`: at 0.85 a two-hop story stands at 72, so the
blur could never fire and one of the three clauses was dead code with a comment on it. This session's
exit criterion asks for a holder who cannot name the actor, and the design as ruled could not produce
one.

**Three of the four available ways out break session 10, which is the whole argument.**

| | What it costs |
|---|---|
| **Raise the hop cap** | 0.85 needs **five** hops to cross 50. A story five settlements deep is a different mod, and §4 rules two. |
| **Raise the blur threshold** | To catch 72 it has to sit above it — which makes a *two-hop* story anonymous. The acceptance script's step 5 is *"someone says they've heard your name, referencing A"*, and that is a two-hop story. **This one fails ship-or-kill directly.** |
| **Change the exit criterion** | Ships a distortion mechanic that never distorts, and a blur branch nothing can reach. |
| **Lower the retention** | Changes what a rumour is worth. |

**The fourth is the only lever that moves the two hops in *opposite* directions relative to the
threshold**, which is why it is the answer rather than the cheapest option. The window is arithmetic
rather than taste: hop one must stay attributed and hop two must not, so `r >= 0.50` and `r^2 < 0.50`
— which is `[0.50, 0.707)`. **Seven tenths is the top of that window and therefore the gentlest
change that works: 100 -> 70 -> 49.**

What it costs is real and smaller than it looks. `Deeds.deltaFor` scales the whole delta by
confidence, so a rumour is worth 0.70 of first-hand where it was 0.85 — but that number is dominated
by the witness share (a third for a gift) and by the outsider weight, so the visible effect on a bond
is usually the same integer. Where it is not, 0.70 is the more defensible figure anyway: hearing about
a murder should not move you 85% as much as watching one.

**And nothing counts hops.** A story is retold while it can still be attributed, so `max 2 hops` falls
out of the retention and the attribution floor rather than needing a counter. That is not a trick to
save a field — a hop count would be an eighth field on a record session 06 deliberately held at seven,
persisted in every ring in every save, deriving something a field already there answers.
`GossipTest.theHopBoundFallsOutOfTheArithmetic` walks the chain and turns red if the derived answer
and `DESIGN.md`'s ruled one ever disagree.

#### The other two decisions the brief named, ruled

**1. Which copy survives when two arrivals disagree: the better-attested one wins, and it does not
move.** Both halves are load-bearing. Better attested wins because a memory should be the best account
of an event a person actually has — somebody told about a killing at 70 who then *watches* a
hundred-confidence copy arrive knows it first-hand from that moment. It does not move because session
06's reason stands unchanged and is the half that protects the ring: refreshing a slot would let a
retelling push first-hand memories out simply by being repeated.

**The cost the brief named is real and is bounded.** It makes `remember` a read-modify-write, and it
does let a retelling touch a ring — but only *upward*. A copy that knows less changes nothing at all,
and the two doors into the method are an emit (first-hand, a hundred) and a drain (strictly less than
whatever it was retold from), so nothing gossip does can degrade a memory. **No path in the mod as it
stands produces the case at all**, because a deed reaches its witnesses at emit and enters the deque
afterwards — first-hand always arrives first. That is stated rather than relied on: the rule is here
so that session 10's second settlement and session 16's NPC actors meet a ring that already behaves
correctly instead of one that behaves correctly by accident.

**2. The deque is persisted, and hard rule 1 was paid first.** The tear argument applies here where
session 07's `DialogueStats` said it did not: **a queued rumour *is* a `Deed`**, which references
personas and settlements by id. The deciding argument is session 10's rather than the tear, though:
§4 step 7's cross-settlement hop carries a **1200-6000 tick delay**, which makes an in-flight story
certain to cross a save — so a volatile deque now means a schema bump during ship-or-kill, which is
the worst available time for one. **Build it so 10 is one more edge, not a rewrite.**

**Schema 6 cost no new persisted record, and that is the part worth recording.** A queued rumour is a
`Deed`: the same seven fields and the same codec that have been on disk since schema 5. **Session 08
added no field to any persisted record** — the confidence a story has left was already there, and the
hop count that would have needed one is derived from it. So `SocialValueLedgerTest` gained no entries
and lost none, for the second session running, and the migration adds a table and nothing else.

**3. The first thing in this mod that polls, bounded by construction rather than measured.**
`DESIGN.md` §8's 250-tick cadence, wired through both loaders' existing server-tick hook. On 249 ticks
in every 250 the hook reads `getTickCount()` and returns; on the 250th it returns on its third line
unless something has happened somewhere recently. **A settlement is in the drain's map only while it
has an unspent story, and a story is spent after two drains** — so the map is sized by recent events
rather than by the world, and with one player it holds one.

Measuring it instead was considered and rejected on session 06's own grounds: a wall-clock number
taken on the owner's machine while they are working on it is the confident-wrong kind.
`GossipTest.theDrainVisitsOnlySettlementsWithSomethingToSay` pins the bound instead, over a
two-hundred-settlement registry, deterministically, in CI — including that a whole in-game day of
drains over a quiet world visits nothing at all.

#### What the exit criteria actually showed

**The propagation half, out of the headless simulation.** One deed, emitted on day 0 in a settlement
of nine, and then nobody visits:

| witnesses / gossip | day 0 | day 1 | day 2 | of village | unnamed |
|---|---|---|---|---|---|
| 0% seen it, told | 7 | 7 | 7 | **78%** | 2 |
| 35% seen it, told | 7 | 7 | 7 | **78%** | 1 |
| 100% seen it, told | 9 | 9 | 9 | 100% | 0 |
| **35% seen it, silent** | **4** | **4** | **4** | **44%** | **0** |

The last row is the control, and it is the sentence this session exists to make false: with step 7
off, a deed reaches the people who were standing there and stops.

**The row that matters most is the first.** The criterion's 60% is partly paid by the witnesses, and
session 07 called the witness fraction the least grounded input in the whole instrument — so a
criterion that only clears because of a guess is one that has to wait for session 15's playtest.
**This one clears at 0% witnesses**, from gossip alone.

**Measured across twelve villages rather than the one it was written against**, because this project's
signature defect is a claim measured against a fixture. At 0% witnesses, coverage by day 2:
`67% 67% 67% 100% 67% 44% 67% 67% 67% 78% 100% 67%` — **eleven of twelve clear 60%**, and eleven of
twelve produce a holder who cannot name the actor. The one that does not is the transfer coin over a
nine-person village, and it is reported rather than tuned away.

**Descending, and every step of the ladder occupied.** The village holds one story at three
confidences and no others: **100 (watched it) x 4, 70 (was told) x 2, 49 (someone from the north)
x 1**. A fourth value would mean the hop bound had moved.

**The owner's half, on screen in a running game.** `/namesake debug deeds` on a villager who was
behind a wall, read out of a real client through the real dispatcher:

```
Krutirgyst Stuksk
  3 of 32 remembered, newest first, day 0
  day  age  deed             how     by
    0       FED_HUNGRY       heard   4e36b848 c70
    0       GIFT_WANTED      heard   4e36b848 c70
```

and, on a run where the blur fired:

```
    0       FED_HUNGRY       rumour  nobody   c49
    0       GIFT_WANTED      heard   af25fa27 c70
```

Four states in one column — `to them`, `saw it`, `heard`, `rumour` — and the `by` column reading
`nobody` when nobody knows. **"Someone from the north" needed no field:** the direction is a function
of where the deed happened, which the deed already carries, and where the holder lives, which their
persona already carries. Session 06 declined an eighth field on `Deed`; session 08 declined a ninth,
for the same reason and after the same look at what it would have bought.

**Both loaders, every leg green:** **60** in `setup` (49 before this session) and 9 in `verify`, in
two launches each, plus the cross-build load test below. **285 unit tests**, up from 252, real JUnit
XML, `failures=0 errors=0 skipped=0`.

#### The answer session 09 is handed instead of the problem

Session 07 found that **no three residents ever reach 20 warmth in a hundred in-game days**, because a
witness's share of a gift is one point and warmth decays one a day — the two cancel exactly. Gossip
was the plausible fix, and testing it cost one column.

**It is not the fix.** The in-game day the third resident crossed each mark, same plan, run both ways:

| axis | gossip | 20 | 40 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|
| warmth | off | never | never | never | never | never |
| trust | off | 29 | 60 | 89 | never | never |
| **warmth** | **on** | **never** | **never** | **never** | **never** | **never** |
| **trust** | **on** | **28** | **56** | **84** | never | never |

Gossip moves the village's median warmth from **0 to 1** and its maximum from 56 to 60, and takes four
days off every trust mark. **It does not come close to making a warmth threshold reachable, and it was
never going to** — a hearer's share of a gift is smaller than a witness's, and the decay is the same
point a day either way. So session 09's ruling stands where session 07 left it, with one option now
closed: **read trust, or read peak warmth, or raise the witness share.** Gossip is not a fourth
option.

Nothing was changed on the strength of this. Tuning a decay curve to make a threshold reachable is
tuning the measurement to fit the answer.

#### What the run found that nobody asked it

**1. Gossip costs memory depth, and the number is worth having before session 09 rules on ring
capacity.** A villager's ring now fills with what happened to their neighbours as well as what
happened in front of them, so it reaches *less far back* in the same number of days. Under a
saturating player over a hundred days the deepest ring reaches back **19 days without propagation and
13 with it**. That is a real consequence rather than a defect — the ring is thirty-two slots and
gossip is competition for them — and it is a printed number rather than a threshold, because it is the
owner's to rule. It also sharpens session 07's finding: the deepest ring was already shallow because
twenty of its rows said the same thing, and now some of those rows belong to other people.

**2. The blur fires on a coin, so it does not fire on every run, and the harness says so rather than
pretending.** Whether one villager takes one telling is a hash of the story and the hearer, over
persona ids a real game mints at random. Across **twelve** in-game runs the whole village held
`[49, 70, 100]` on ten of them and `[70, 100]` on two — those two villages' pair of candidates
happened to take everything at the first telling and left nobody for the second. The leg asserts the
lawful *set* rather than a particular member of it, which is why it is not a 17%-flaky check running
on every push. The deterministic proof lives in `GossipTest` and in the simulation, and a playtest
should feed three or four different villagers rather than concluding from one.

**3. A rumour reaches you on the day you hear it, and nothing before this session could have said
so.** `Bond.apply` stamps `lastSeenDay` with the day it is handed, and a story queued before midnight
can be drained after it — so handing the bond the day the *deed* happened would set that stamp
backwards, and the next read would run the lazy decay over days it had already decayed. Nothing in the
emit path can produce it, which is exactly why it needed looking for. `DeedBus.applyTo` takes the later
of the two, and `GossipTest.aLateTellingDoesNotRewindTheBond` is the fixture.

#### Hard rule 1, and the pre-change half done first

The attach-bet `setup` phase was run on commit `ac01af7` — schema 5, the session 07 head — on **both
loaders before a line of session 08 was written**, and both saves were archived. The schema-6 build
then loaded them:

```
NPC registry datafixer: schema 5 -> 6 (settlement gossip deques added; nothing to
rewrite, an absent table means no rumour was in flight) rewrote 0 record(s)
Loaded 9 persona(s), 9 bound to an entity, 1 settlement(s), 4 bond(s),
8 deed(s) across 4 ring(s), 0 rumour(s) in 0 settlement(s) (schema 6)
```

**This is the additive kind, and saying so out loud is the point — for the third time running.** What
makes it checkable is stronger than it was at 5, and it is the thing session 08 could most easily have
made false: **no persisted record gained a field.** A queued rumour is a `Deed` with the codec it has
had since schema 5, so there is no older shape of anything on disk to reconcile.

The assertion is on what would actually break rather than on the rewrite count — zero is also what a
fixer that does nothing at all returns, which session 03 broke the 2 -> 3 fix into on purpose and
turned the build red for. Read as damage, the registry goes read-only and a world that has been played
for a week silently stops saving its personas, settlements, bonds *and* rings, because there is one
file:

```
PASS  DATAFIXER 5->6 the 8 deed(s) across 4 ring(s) the schema-5 build wrote came through intact
PASS  DATAFIXER 5->6 a world written before schema 6 has no rumours in flight, and an absent
      gossip table reads as that rather than as damage (0)
PASS  DATAFIXER 5->6 the registry is writable, so the migrated file will be written back at
      schema 6 rather than migrating again on every load
```

**Then each world was loaded a second time**, which is the only way session 01's defect 1 is ever
caught: `no migration expected: world is already at schema 6`, on both loaders. The fix reached disk.

#### Rule 3: twenty deliberate breakages, each watched to fail and removed

| Breakage | Result |
|---|---|
| The retention put back to 0.85 | **4+ red**, including the contradiction by name: *"nobody lost the actor's name … session 08 lowered `Deed.RETOLD` to 0.70 precisely so that two hops lands at 49 rather than at 72"* |
| The blur threshold raised to 80, catching a one-hop story | **6+ red**, across the layout, the simulation and the hop bound |
| An unattributed rumour allowed to move a bond | **NOTHING FAILED.** `putBond` refuses it anyway — see below |
| …the same breakage, after the fixture that reaches it | **Red.** *"a delivery that reports a bond it did not write is a count that lies"* |
| The witness re-tell guard removed | **Red.** *"a witness is not told a rumour about the thing they watched"* |
| Step 6 removed: a deed never enters the deque | **6 red**, including both simulation criteria |
| The deque dedupe removed | **2 red.** *"repeating yourself is one rumour, not thirty-two"* |
| The drain requeuing whatever it just told, attributed or not | **3 red** — the hop bound, by three routes |
| An absent gossip table read as damage | **2 red**, one of them the schema ladder |
| `setDirty` removed from the drain | **NOTHING FAILED.** A hearer's ring marked it dirty instead |
| …the same breakage, after the fixture where nobody is left to tell | **Red.** *"a drain marks the file dirty even when there is nobody left to tell"* |
| A duplicate rumour allowed to mark the registry dirty | **Red** |
| The ring keeping the first copy however badly attested | **2 red** — the session 08 ruling, both halves |
| An upgrade refreshing the slot instead of replacing it in place | **Red.** The half that protects the ring |
| A bond stamped with the day the deed happened rather than the day it was heard | **Red** |
| The registry accepting a bond about nobody | **Red** |
| Everybody takes every telling | **4 red**, including the transfer rate |
| The schema bumped to 7 with no matching fix | **6+ red.** *"the fix chain has a hole"* |
| A blurred deed row printing the actor it blurred | **Red.** *"a blurred row must not leak the actor it blurred"* |
| The deed header back to one line with the name on it | **Red.** *"a row of 'deeds, a full ring' is 64 characters"* |

**Two of the twenty rows reported NOTHING FAILED, and both are the same shape as session 05's fifth
row and session 07's two: a guard that exists and a fixture that never reaches it.** Both are recorded
because the reasons are different and both are instructive.

The first is **two doors hiding each other.** Removing the blur guard from `DeedBus.deliver` changed
nothing observable, because `NpcRegistry.putBond` refuses a bond about nobody anyway — so the bond
table looked identical either way. Two things were still wrong and neither had a fixture: the delivery
*reported* a bond it had not written, and every rumour in the world would spend a `Deeds.deltaFor` and
an ERROR line on the way to being refused. Two doors is the right design and is why the guard is not
redundant; it is also why the obvious fixture proves nothing.

The second is **a fixture that was too generous.** Removing `setDirty` from the drain changed nothing,
because somebody heard the story and `NpcRegistry.remember` marks the file dirty on its own. The case
the drain's own flag protects is the one where *nobody* is left to tell: the village's copy degrades
and not one ring changes, and without the flag that degradation never reaches the disk — so a story
reloads with the confidence it had an hour ago and travels further than the design permits.

#### The width defect this session shipped and caught, and where it was caught

**The deed ring's header was 64 characters against a sixty-character chat width**, in the *populated*
state, and it had been there since session 06. It was invisible until this session made `debug deeds`
measurable at all: session 06 measured `describeDeed` and nothing else, so the header — a name plus
five counts on one line — was never rendered by a test. The name is on a line of its own now.

**That is the sixth instance of this project's signature defect and the first one caught by a guard
rather than by the owner**, which is what `CommandLayoutTest.everyStateOfBothCommands` was extended
for: it enumerates twelve states across three commands now rather than eight across two, and session
08's rows went into it rather than into a fifth guard sampling one state.

**And the same lesson arrived a second time, in the harness, where the first attempt walked into it.**
The empty-ring branch was originally rendered *after* the drain, and on the first real run there was
nobody left in the village who had seen nothing — so the branch had nobody to render. The guard
reported that honestly rather than passing, which is better than a false green and is still not a
measurement. It is rendered before the drain now, when the villager behind the wall is empty by
assertion one line above.

#### The defect CI found after the push, and the ruling it forced

**`origin/main` went red on the first push of this session**, on both loaders, on one line:
`FAIL SIMULATE the run cost 51 ms, against a 50 ms tick`. Every gossip leg passed. The assertion was
session 07's, written when a hundred simulated days cost 6–8 ms and the margin made the question look
free; session 08 took it to 21 ms on this machine, and a shared runner is slower than this machine.

**Session 04 had already ruled the general case and this leg predates it mattering.** The profiler
gets no CI job because *a wall-clock number from a shared runner whose neighbours we cannot see is not
evidence*, and this was that exact thing wearing a harness leg's clothes. So the ruling is applied
rather than the number nudged: **the millisecond figure is recorded, and the CI gate is a ceiling
loose enough that only an order-of-magnitude regression trips it on any machine** — 500 ms, ten ticks,
against 21 measured here. The sub-tick claim is a property of a known machine and belongs in this
ledger with its conditions, which is where it now is.

**And it was a real cost as well as a mismeasured one, so the cost was paid too.** `countHolders` —
the simulation's own instrumentation — walked every ring every day asking each *deed* for its id,
which is a sixty-four-bit mix of eight fields, when the ring had been carrying those ids since
earlier the same session. `Memories.idsOf` hands them over. A hundred days warm went **1.24 ms →
1.02 ms** and the in-game run **21 ms → 19 ms** — which is itself worth reading honestly: most of the
increase from session 07's 8 ms is the drains doing real work, not the instrumentation, and the
instrumentation was simply the part that could be given back.

**And the runner is three times slower than this machine, which is the number to keep.** The green
run measured **61 ms on Fabric and 71 on NeoForge** for the same hundred days this machine runs in 19.
Any future wall-clock assertion in CI has to survive a 3–4× factor it cannot see coming.

**Worth recording as a process note rather than only as a fix:** the number was visible on this
machine before the push — 8 ms became 27 and I optimised it to 21 and moved on, without asking what a
2.5× margin means on a machine three times slower. CI asked. The instrument that catches this class is
not a faster machine; it is noticing when an assertion's margin has quietly become the thing under
test.

#### One harness leg, and why it is one rather than none

`WORKPLAN.md` rules that the propagation curve — 60% of a village within two in-game days — is a claim
about *time*, which is session 07's instrument, and it is measured there. What only a running game can
show is everything on the other side of the seam: that a loader's server-tick hook is wired to
`Gossip.onServerTick`, that the 250-tick cadence fires against a real server's tick count, and that a
deque written by an emit is spent by a *tick* rather than by a test calling a method.

**And the assertion is the thesis in one line.** Session 05 proved the villager behind a wall records
nothing, five blocks away and well inside the box. This proves that an in-game hour later they have
heard about it anyway, at a confidence that says they were told rather than that they saw it.

Three more feedings are emitted first, and that is not padding: whether one villager takes one telling
is a coin over persona ids a real game mints at random, and a leg that runs on every push cannot be a
3% coin. Five stories against two hearers is twenty independent flips, which puts "nobody heard
anything" at two in ten thousand.

#### Two things the brief offered and this session declined

**`Simulation.Plan` was not extended to more than one settlement.** The brief said to do it *if
propagation needs it*, and session 08's propagation is same-settlement by definition — a second
settlement with no cross-settlement mechanic would add a report column reading zero for two sessions,
which is the noise session 06 removed from the deed row. What session 10 actually needs is that the
machinery be shared rather than the fixture be pre-built, and it is: the simulation drives the shipped
`Gossip.drain` at the shipped cadence, so session 10 adds an edge and a second settlement rather than
a mechanism.

**`Personas.STILL_UNSETTLED` is parked again, and this session adds evidence rather than none.** The
branch that scans the whole settlement table on every chunk load has now fired **zero times in three
real worlds on both loaders**, and session 08's propagation does not touch it — gossip runs over
residents of a settlement, which is the case the branch does not cover. Session 04 recorded the index
as session 08's problem *at the earliest*; it is not this session's, and the honest note is that
nothing has yet produced a world where it fires.

#### One cost this session found in its own code and paid

**A ring slot now carries the id it is addressed by.** `Memories`' own note claimed a lookup was "a
linear walk of at most 32 long comparisons" — true of the comparison and false of what it took to get
there, because `Deed.id()` is a sixty-four-bit mix of eight fields and was being run once per slot per
walk. That cost nothing while the only caller was an emit, at most thirteen times in the tick
something happened. **The drain asks the same question of every resident on every drain**, which
turned it into about ninety microseconds of one tick in every two hundred and fifty.

Found by effect rather than by reading: the headless simulation's hundred-day run went from 8 ms to
27 ms on a live server thread. With the id carried it is **21 ms**, and with the report reading those
ids too — the fix CI forced, above — **19 ms**. Warm in a JVM under test it went 2.06 ms to 1.02 ms.
Derived and never persisted, so it is not the kind of cache session 03 deleted `Settlement.culture`
for: it cannot disagree with the deed beside it, because both are filled in from the same record at
the same moment and neither is ever mutated.

#### Carried into session 09

- `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client between runs, and delete
  `<loader>/run/saves/namesake_attachbet` before a `setup`.
- **The warmth-decay finding is still session 09's biggest problem, and gossip is no longer one of the
  answers.** The table above is the one to rule against: warmth is `never` at every mark with
  propagation on, and trust reaches 20 on day 28.
- **A deed ring is now competition for slots rather than only a record of what a player did to one
  person.** Session 06's four routes to depth are unchanged, but the *pressure* has moved: the deepest
  ring loses six days of reach to propagation. Session 09 is where a line of dialogue first depends on
  what a ring holds, which is where that becomes a decision rather than a number.
- **`Deed.RETOLD` is the number session 10 will be tempted to move**, because a cross-settlement hop
  at 0.15 arriving at 70 confidence is a strong rumour. It is held by `GossipTest` to a window rather
  than to a value: hop one attributed, hop two not. Move it inside `[0.50, 0.707)` freely; moving it
  outside changes what the mod is, and the build turns red saying so.
- **The blur fires on a coin.** A playtest may see it on the first villager or on the fourth. Feed
  three or four different villagers and wait an in-game hour rather than concluding from one.
- `DeedBus.witnessScan` and `DeedBus.emit` **still have meters pointed at nothing**, unchanged from
  session 07 and for the same reason: the simulation replaces the spatial query. `Gossip.drain` has
  one now and it has the same problem in reverse — it is bounded by construction, so measuring it
  would confirm a number the construction already gives.

**Ledger change.** Session 08 → done, session 09 → NEXT. **No risk changes and no exemption movement**
— none fell due, and for the third session running the forcing function was not what kept rule 5
honest. Three decisions added to `DESIGN.md` §2 — the gossip retention, gossip storage, and which copy
of an event wins a ring slot — taking the count 52 → 55, plus §4 step 7 rewritten around the retention
and the blur's `if` statement, and §8's drain row given its bound. No changes to the 16-session shape.
