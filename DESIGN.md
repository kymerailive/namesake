# DESIGN — Namesake

What we are building and why. `WORKPLAN.md` owns *what happens next*; this owns *what it is*.
61 decisions ruled, 0 open.

**The thesis:** a deed witnessed by one villager changes what a different villager, in a different
settlement, says to you later.

---

## 1. The one build rule

> **Every social value must have at least one named consumer that is not a display. If you cannot
> name the `if` statement it feeds, delete the field.**

Both reference codebases died on this. MCA's 12 genes, 22 traits and 15 personalities all terminate
in a renderer — its only mechanical gene effect is a ±10% walk speed, and 4 of 22 traits are dead
code. LNK computes an affinity score for 158 pairs with a second LLM call per reply, and
`grep affinity prompt.py` returns zero hits. Opposite directions, identical failure.

Enforce with a failing test, not intention.

---

## 2. Locked decisions

### Platform
| | |
|---|---|
| Target | Minecraft 1.21.1, Java 21 |
| Loaders | Fabric + NeoForge, hand-rolled multiloader (`common`/`fabric`/`neoforge`) |
| Architecture | **Attach a Persona to the vanilla `Villager`.** Never replace the entity. |
| Mod id / package | `namesake` / `net.namesake` |
| Interactions | **Only the server ever opens one.** The client cannot ask; the server issues a token from the vanilla interact hook it has already reach-checked. That is what lets every serverbound packet require a live token with no bootstrap exemption — hard rule 6 is strict for free. An addon verb inherits this and must not work around it. |
| License | LGPL-3.0 — addons may be any license; forks stay open |
| LLM | Optional enrichment only. Nothing may depend on it. |
| Addon API | Day one, narrow: professions, deed types, need types, gift data |
| Modpack data | Farmer's Delight + Create hand-tuned; tag fallback for everything else |

### Player and world
| | |
|---|---|
| Player role | Outsider who **earns residency** |
| Player progression | External only — no XP, no skills |
| Office | Player-eligible at era 4+, with obligations NPCs notice you failing |
| Fail state | Always recoverable, slowly. Never permanent lockout. |
| Onboarding | The Notice Board teaches everything. No tutorial. |
| Settlements | Detected from vanilla POI clusters (bell + workstations). No player founding in v1. |
| Player base | Registers only if villagers actually live there — same rule as everywhere |
| Era scope | **Social institutions only.** No tech tree, no machines. |
| Currency | Emeralds only |
| Treasury | Accrues a share of trades made in the settlement; spends on public works |

### Social
| | |
|---|---|
| Bonds | 4 signed axes: trust, warmth, respect, fear. Plus a debt scalar. |
| Bond key | **(the NPC who holds it → whoever it is about), both bare UUIDs.** General in shape so session 16's NPC-to-NPC grievances need no migration; restricted in population by one guard, because an NPC-to-NPC bond has no consumer before then and 400 personas is 160,000 rows of §1's forbidden shape. |
| Bond storage | **Inside `namesake_npcs.dat`, under one `NpcSchema` version** — same argument as settlements. Two files can be torn apart by a crash between two writes, and a bond points at a persona by id. |
| Bond decay | **Lazy, warmth only, toward `peak × 0.4` at a point a day.** Computed on read, never ticked; one catch-up applies at most `dayDelta ≤ 64` days. Absence cools a bond; it never resets one. |
| Personality | **One static `float[8][6]`, centred on the population the generator actually produces.** Nominal means *typical*, not "a villager with no personality" — every culture has a baseline, so eight zeroes is not average and never was. The centring offsets are derived from the measured mean, not written down. |
| Personality magnitude | **Shape and magnitude are separate, and the magnitude is one constant.** `Personality.SHAPE` says how much each axis matters *relative to the others*; `Personality.SPREAD` says how far the whole table may move a deed's value. Scaling the table scales every villager's deviation from neutral by exactly the same factor, and the centring scales with it, so a typical villager still scores one by construction. Session 07 moved it 1.0 → 1.6 to close the week-apart the owner ruled at the close of 05, and changed no other number. The clamp `[MIN, MAX]` widens with it and is held to a **measured rate** — under 2% of (persona × deed type) pairs — because a clamp that bites in ordinary play is flattening variation rather than refusing absurdity. |
| Earn rate unit | **Warmth points per in-game day of contact.** Session 12's band thresholds are expressed in it, and both alternatives are worse for one reason: the mechanism's own clock is the in-game day. The daily cap is per axis per day, the decay is a point a day, `lastSeenDay` is a day. A player-hour needs a conversion nobody will remember and differs between two players with identical play time; a per-deed rate is dominated by how hard somebody is grinding, which is the number the cap exists to make meaningless. Reported beside it: the same warmth per in-game day *elapsed*, which is what the decay bites into. |
| Measurement data | **Never persisted.** Every number the instruments report is derived from bonds and rings that are already on disk and already ledgered; storing a tally as well is a cache, and a stored tally survives a change to the thing it counts and then describes a mod that no longer exists. It also puts §1 in an impossible position — a histogram's only consumer is a display, an exemption is a promise a mechanic will read the field, and no mechanic reads a histogram. The cost is real and accepted: a deed evicted from a ring leaves no trace, so nothing can report what was forgotten, only how far back the ring still reaches. |
| Daily allowance | **8 for a typical villager, scaled by the same personality weight.** Personality controls the **ceiling, not the step**: scaling only what one deed is worth is erased the moment a player gives enough to fill the cap — everybody converges on the same number and personality decides nothing but how many gifts it took. Read off the benign columns only; the cap limits positives, so a short temper must not raise anybody's capacity for warmth. |
| Deed id | **Derived from the deed's own six identity fields, never assigned.** Two identical feedings on the same day are therefore one deed. That is what stops a ring being ground out by repetition — the daily cap's job, one level up, through a door the cap cannot see — and it costs zero persisted bytes, because it is a pure function of fields already on disk. **Confidence is deliberately outside it**, so session 08's retelling dedupes against the deed it retells instead of becoming a second row for one murder. |
| Deed storage | **A 128-entry ring per persona, in a side table inside `namesake_npcs.dat`, packed** — beside the bonds, never a field on `Persona`. A persona is durable identity and is rebuilt whole on every write; and one malformed deed inside a persona record would cost that villager their name, culture and traits, where a table of its own costs one memory. Each slot is a fixed 25-byte record behind an actor palette and an item palette, because two UUIDs are two thirds of a readable deed and a village's rings hold the same few people. Worst case measured at session 09: **1,303,029 B of NBT and 153,437 B gzipped** — 25.4 B a deed against session 06's 122.3 — held there by a test. The tag-tree ceiling stayed at 2 MB; the compressed one was re-ruled 100 KB → 200 KB with the measurement in hand, set so the next capacity raise trips it. |
| Ring eviction | **Weakest out, not oldest out.** A harmful deed outranks every kindness absolutely, so no quantity of bread displaces a killing; within a class the memory that happened more often is held harder, up to `Bond.DAILY_CAP`; ties go to the oldest, which is what keeps "the newest N survive" true for a ring of single kindnesses. Session 07 flagged this as the one question its numbers could not settle — *nothing in that run gave a villager a killing to keep* — and until session 09 the answer was that a player could bury what they did under enough of what they did afterwards, one day at a time. The repeat cap is load-bearing: without it five hundred identical gifts would be the strongest benign memory a villager has for ever, which is the grind content addressing closed reopening through a new door. |
| Gift acceptance | **A villager will not take something they have no use for from somebody they do not yet like.** Reads `Bond.warmth` against a threshold of 4 — one feeding is +3, so it costs a second act of kindness and nothing more, against an observed maximum of 56–60 and a village median of 0–1. Feeding the hungry and giving what vanilla says they want are **never** gated, so §10's acceptance script step 2 is untouched and there is no deadlock: the two ungated routes are exactly the two that raise the axis the gate reads. **Warmth rather than trust, and that is the whole point** — trust never decays, so a gate on it opens once and never closes; warmth decays toward four tenths of its high-water mark, so this one closes again when you stop turning up. It is the one thing warmth can express that no other axis can. |
| Bond UI | Bands + the deed ring. **Never raw integers.** |
| Gossip | Distorts, never lies. Confidence degrades; identity blurs below 50. Nothing in the pipeline can add a detail to a story — every field of a retold deed but two is carried through untouched, and those two only move one way. |
| Gossip retention | **`confidence × 0.70` per hop, not 0.85.** The three clauses this section carried — 0.85, max 2 hops, blur below 50 — were written before there was code, and session 06 proved they were inconsistent: at 0.85 two hops lands at 72, so the blur could never fire. Of the four ways out, three break session 10 — five hops is a different mod, a higher blur threshold makes the two-hop story in the acceptance script's step 5 anonymous, and leaving it ships a distortion mechanic that never distorts. Lowering the retention is the only lever that moves the two hops in *opposite* directions relative to the threshold, and the window is arithmetic: hop one must stay attributed and hop two must not, so `r ∈ [0.50, 0.707)`. Seven tenths is the top of it and therefore the gentlest change that works. |
| Gossip storage | **Inside `namesake_npcs.dat`, under one `NpcSchema` version** — settlements' argument for the fourth time, and it applies here where session 07's `DialogueStats` said it did not: a queued rumour *is* a `Deed`, which references personas and settlements by id. Persisted rather than volatile because session 10's cross-settlement hop carries a 1200–6000 tick delay, which makes an in-flight story certain to cross a save — and a schema bump during a ship-or-kill session is the worst available time for one. A queued rumour needs no new record, so the migration adds a table and nothing else. |
| Residency axis | **The threshold reads `trust`, not warmth.** Ruled by the owner at the close of session 08 against session 07's table and session 08's: a witness's share of a gift is one point and warmth decays one a day, so no three residents ever reach 20 warmth in a hundred in-game days — and gossip, measured both ways, does not change that at any mark. Trust does not decay, so it only ever climbs: the third resident crosses 20 on **day 28**. Residency is therefore earned by consistency rather than by intensity, which is the right reading of a player who has decided they live here. |
| Memory depth | **Richer per memory, a repeat count, and 32 → 128 slots — all three, shipped at session 09.** Ruled by the owner at the close of session 08, the third time they had asked for in-depth memories. The evidence pointed at the first two: after a hundred in-game days the deepest ring held thirty-two slots and **three distinct sentences**, so it was shallow because twenty rows said the same thing rather than because it ran out of room. `Deed.item` is *which* object; `Memories.Slot.repeats` is *how many times*, on the slot rather than on the deed because two villagers who watched the same afternoon can have seen different amounts of it. **`item` is deliberately outside `Deed.id()`**: putting it in is the obvious reading of "richer per memory" and hands the ring back its grindability, because an afternoon of eight different junk items would be eight entries where an afternoon of one gift is one. The cost of leaving it out is the honest one — a slot that collects two objects can name neither, and says so. |
| Dialogue | **Selected from authored pools by struct state; a model may only decorate a line that is already complete and shippable, never produce one.** Four pools × five registers × eight lines, plus per-culture openers, tag questions, address terms and a formality bias that decides how often each tic attaches. Every register has a state that selects it, because an authored register nothing can select is dead content — the same failure as a persisted field nothing reads, one layer up. `net.namesake.dialogue` is in the rule 5 ledger's display packages, so **no persisted field can ever name anything in it as a consumer** and the build says so. |
| Ring collisions | **The better-attested copy of an event wins a ring slot, and it does not move.** Confidence is outside `Deed.id()`, so two copies of one event can meet in a ring holding different numbers. Better attested wins because a memory should be the best account a person actually has; it does not move because refreshing a slot would let a retelling push first-hand memories out of a ring simply by being repeated. The door only opens upward, so nothing gossip does can degrade a memory. |
| Grievance notification | None — you must notice. Board is the backstop. |
| Player as grievance subject | Yes, including romantic rivalry over you |
| Factions | Named, generated from the shared cause |
| Theft | Real items, from unlocked containers only |
| Content gating | All on, with a documented "gentle" preset |

### Life and death
| | |
|---|---|
| Generation length | ~40–60 hours of play |
| Funerals | Real gatherings residents path to and attend |
| Bonds on death | Convert to a ~10-day grief modifier on survivors, then a chronicle row |
| Inheritance | Kin first, then highest trust. **Debts die with the NPC** in v1. |
| Settlement death | Becomes a discoverable ruin. Neighbours hear about it. |
| Migration | Driven by unrest and prosperity, along known road edges |
| Animals | v1 stub — no bonds, but harming one a villager owns emits a deed. Full graph post-v1. |

### Presentation and multiplayer
| | |
|---|---|
| Art | Solo, no artist. ~25 greyscale textures + colormaps. Vanilla humanoid model. |
| Prosperity display | Clothing tier + culture palette saturation |
| Speech | Floating text above head for ambient; GUI for menus |
| Culture voice | Shared pools + per-culture tics and register tuning. Session 09 ships **160 lines** — four pools × five registers × eight — with six cultures' openers, tag questions, stranger address terms and formality biases over them. A formal culture opens and rarely tags; an informal one does the reverse, so the *rate* is a per-culture number rather than a per-culture string, and a test measures the realised rate rather than the table. |
| Standing split | 80% personal / 20% shared, server-configurable |
| Absent players | Footprint persists — stationed guards, offices, banners |
| Party propagation | Top-3 by magnitude; negatives capped 0.15, positives 0.25 |

---

## 3. Core data model

```java
record Persona(
    UUID   id,              // stable across entity reload/despawn
    int    settlementId, householdId,
    byte[] traits,          // 8 signed axes, -100..100
    byte   cultureId,
    int    professionId,
    long   birthTick,
    int    appearanceSeed,  // derived from id; nothing else persists
    byte   eraOfMajority)

// axes: warmth, industry, boldness, curiosity, tradition, acquisitiveness, temper, sociability

record Bond(
    byte  trust,     // -64..100   will they rely on you
    byte  warmth,    //   0..100   do they like you
    byte  respect,   // -64..100   do they defer to you
    byte  fear,      //   0..100   do they avoid crossing you
    short debt,
    int   lastSeenDay,
    short gainedToday,   // 4 nibbles, cap 8/axis/day, reset lazily on write
    byte  peakWarmth)    // decay target = peak * 0.4

record Deed(                        // 25 B packed; 128-entry ring per NPC ≈ 3.2 kB
    short typeId, UUID actor, UUID subject,
    int settlementId, int gameDay,
    byte severity, byte confidence,  // 100 = witnessed first-hand
    String item)                     // registry id, or "" — session 09, and NOT part of Deed.id()

record Slot(long id, Deed deed, int repeats)   // one ring entry; repeats saturates at 255

record Grievance(
    int id, UUID instigator, UUID target,
    byte kind, byte stage, byte severity,
    int openedDay, int lastEscalatedDay,
    IntList sourceDeeds)
```

*Familiarity* is **not** stored — it is derivable from `lastSeenDay` + deed count. *Fear* earns its
slot because it makes force-resolutions representable: intimidation works, and the settlement
remembers that it worked.

---

## 4. The deed pipeline — event-driven, nothing polls

1. **Emit** — `DeedBus.emit(deed)` from an interaction handler, damage hook or raid outcome. Never
   from a tick loop.
2. **Witness scan** — one `AABB.inflate(24)`, filtered by `canSee()`, capped at 12 nearest. Paid
   once per deed. *(MCA's harvest chore scans ~72,000 blocks per villager per minute. Do not.)*
3. **Record** — each witness appends to their ring at confidence 100; the subject records it weighted
   higher.
4. **Bond update** — deed delta × witness share × place weight × severity × confidence × personality
   weight, then against what is left of the daily cap.

   The order is not decoration. The first four are **structural** — how much of this deed is yours at
   all — and apply whichever way it cuts. The last two are **softeners**, and neither may ever make a
   harmful axis cheaper: a personality weight below neutral is ignored on the way down, the cap
   applies to positives only, and rounding is away from zero so a share cannot quietly shave a
   negative to nothing. *You cannot spend the day's allowance and then hit someone for free, and a
   placid villager does not charge less for a murder.* A weight **above** neutral still bites both
   ways — a hot temper takes a blow harder — which is what gives the table a column for the harmful
   deeds at all.
5. **Settlement effect** — contributes to unrest/prosperity. One-time shocks excluded from the drift
   target, so grief fades instead of pinning the settlement forever.
6. **Gossip queue** — enters the settlement deque, cap 32. One entry per story: a deed already in
   flight is not queued again, so the deque inherits the ring's ungrindability from `Deed.id()`
   rather than needing its own version of it.
7. **Drain & distort** — 4/in-game hour, same-settlement transfer at **0.35** per hearer,
   `confidence × 0.70` per hop, cross-settlement along a road edge at 0.15 with a 1200–6000 tick
   delay.

   **Max 2 hops, and nothing counts them.** A story is retold while it can still be attributed, and
   0.70 applied twice to first-hand lands at 49 — below the blur threshold. So the bound is
   arithmetic rather than bookkeeping, which is what keeps `Deed` at seven fields and every ring in
   every save at its current shape.

   **The blur is an `if` statement, not a caption.** Below confidence 50 the actor is *replaced* by
   `Deed.UNKNOWN_ACTOR`, and an unattributed deed moves no bond: a villager who cannot say who
   killed the smith has no reason to think worse of *you*. They still remember it, because it
   happened — session 06's asymmetry from the other side. "From the north" needs no field either:
   the direction is a function of where the deed happened, which the deed carries, and where the
   holder lives, which their persona carries.

Reputation travelling to the next settlement is not bolted onto this pipeline — **it is the pipeline,
run one hop further.**

---

## 5. Residency — the first threshold

Arrive as an outsider: every villager speaks from the **stranger pool**, which must explicitly say it
does not know you. Prices 1.00. Board shows "no history."

Granted by the Elder at era ≥ 1, requiring either *known* band with ≥3 residents or one significant
deed (`DEFENDED_RAID`, or `FED_HUNGRY` ×3).

**The band reads `trust`** — ruled at the close of session 08, and it is measured rather than chosen.
A witness's share of a gift is one point and warmth decays one a day, so the two cancel exactly and
**no three residents ever reach 20 warmth in a hundred in-game days**; gossip, run both ways, does not
change that at any mark. Trust does not decay, so the third resident crosses 20 on **day 28**.
Residency is therefore earned by consistency rather than by intensity.

**Derived, never persisted.** There is no residency flag and no grant ceremony: three residents at
twenty trust, or one significant deed, computed from bonds and rings that are already on disk. A
stored flag would be a persisted social value whose only consumer today is a line of dialogue, which
§1 forbids and the rule 5 ledger would refuse; deriving it means there is nothing to classify and
nothing that can disagree with the bonds justifying it. It costs one thing, recorded rather than
found later: the deed route reads the rings, and a ring evicts — bounded by trust never decaying, by
the eviction policy holding a `DEFENDED_RAID` above the gifts that would displace it, and by session
09 raising the ring to a hundred and twenty-eight slots.

**And the second route turns out to be much the cheaper of the two, which is a finding rather than a
plan.** Session 09's harness leg reached residency by `FED_HUNGRY` ×3 before the trust band was
anywhere near — three hungry people fed, against twenty-eight in-game days for the band. Vanilla's
own `wantsMoreFood` gates it in real play, so it is not free; but the band the ruling is about is the
slower path, and whether that is the right balance is the owner's.

**`warmth`'s consumer is the gift gate, not this.** §5's second route was one of the two candidates
offered at the close of session 08 and it was the wrong one: the route is specified as a deed count,
so putting warmth on it means *redefining* the route rather than filling a hole. See the gift
acceptance row in §2 for what it reads instead, and why warmth rather than trust.

**The moment:** before residency they call you *stranger*. After, **they use your name.** One string
swap, zero art, and it delivers the whole pitch in a single line.

Residency also grants the trusted price band, office eligibility at era ≥ 3, and — critically — makes
you a valid *subject* of gossip rather than only an actor. Other settlements start hearing about you.

---

## 6. The grievance ladder

Every conflict traces to scarcity: capped job slots, quality-ranked housing, finite partners, and
**your finite attention**.

| Stage | What you'd notice | Cost to resolve |
|---|---|---|
| 1 · Friction | Cold greeting, won't sit near them, small talk stops | One conversation |
| 2 · Grievance | They tell third parties; it enters the gossip queue | A favour or gift |
| 3 · Dispute | Public. Refuse to share a work site. Output drops. | Arbitration — someone loses |
| 4 · Rupture | Theft, sabotage, a fight, an affair surfacing, departure | Damage control only |
| 5 · Consequence | Permanent. Written to the chronicle. | None — it's history |

Intervening early is cheap; late is impossible. Most resolve at stage 1–2 without you. Active dramas
capped at `2 + population/10`.

**Resolution shapes the settlement:** *mediate* (both lose a little warmth, settlement trust rises) ·
*take a side* (one gains a lot, one loses a lot and remembers who) · *force it* (both gain fear,
settlement hardens — deference up, trust down, guards multiply).

No dominant strategy, and with a daily attention budget every side you take is legible to the person
you didn't. **You cannot be everyone's friend** — which is precisely what Stardew structurally forbids.

---

## 7. The day plan

Slot boundaries are **global** and four are exact `Schedule.VILLAGER_DEFAULT` keyframes. **We never
call `brain.setSchedule`.** A plan stores WHAT, never WHEN.

| # | Slot | dayTime | Vanilla activity | Steering |
|---|---|---|---|---|
| 0 | DAWN | 0–1999 | IDLE | walk+look (uncontested) |
| 1 | LABOUR_I | 2000–4999 | WORK | **none** |
| 2 | HAUL | 5000–5999 | WORK | custom `ERRAND` |
| 3 | NOON | 6000–6999 | WORK | `ERRAND` |
| 4 | LABOUR_II | 7000–8999 | WORK | **none** |
| 5 | COMMONS | 9000–10999 | MEET | walk target r≤6 of bell |
| 6 | HEARTH | 11000–13999 | IDLE→REST | none, then `ERRAND` |
| 7 | NIGHT | 14000–23999 | REST | **none** for sleepers |

### The industry mechanic

Vanilla `SetWalkTargetFromBlockMemory(JOB_SITE, …, closeEnough = 9, …)` — **verified**,
`VillagerGoalPackages.java:85` — only re-asserts beyond 9 blocks. `WorkAtPoi` requires **1.73 m**, and
`WorkAtPoi.start` is what plays the work sound, sets `LAST_WORKED_AT_POI` and triggers `restock()`.

| industry | Standoff | Result |
|---|---|---|
| ≥ 96 | 1.0 m | Work sound, particles, **trades restock**, prosperity ticks |
| < 96 | 3–8 m | Inside vanilla's 9 m tolerance (no tug-of-war), outside 1.73 m (**no work at all**) |

A lazy villager *audibly* does not work, with no code, no animation, no conflict with vanilla.
**Verify the 1.73 figure in-engine before building on it** — it was not confirmable from the
decompiled sources on disk.

### Legibility, with no custom animation
Six laws: one intent, one place · held item is a noun, never a verb · **silence is a signal** · the
crowd is the message, facing beats posing · particles are punctuation (max 1/NPC/40 ticks, within 32
blocks of a player) · the bell is the clock.

**The at-a-glance test.** 09:00 — everyone at a workstation, except those eight blocks away doing
nothing. 11:00 — roads fill with sacks moving one direction. 12:00 — hearths fill. 15:00 — a ring at
the bell. 17:00 — the tavern lights up. 20:00 — streets empty but for two torches on the perimeter.
*Four facts and the player can predict any NPC in the world.*

### The transition wave
Boundary offset derives from `industry`, jittered by `tradition`, with a **hard `spread ≥ 64` floor
enforced in code, not config**. Without it, 400 simultaneous transitions fire 400 path requests in one
tick; with it, worst case ~6/tick. *Never expose "make everyone punctual" as a config option.*

---

## 8. Tick budget

| Work | Cadence |
|---|---|
| Persona record sweep | 20 rotating buckets (~1×/sec/record) |
| Witness scan | on deed emit — **never polled** |
| Gossip drain | every 250 ticks — **the only thing in this mod that polls**, and bounded by construction rather than by measurement: a settlement is in the drain's map only while it has an unspent story, and a story is spent after two drains. On 249 ticks in 250 the hook reads a tick count and returns. |
| Grievance escalation | 1×/in-game day, per settlement |
| Settlement survey | census on the server thread at 16 chunks/tick, scoring off-thread, once per place, ever |
| Road edge A* | 1 edge/tick, off-thread, 16×16-chunk heightmap grid |

### What a tick actually costs — measured, session 04

Every figure below was written here as an estimate before there was code to measure. Session 04
measured them. **`WORKPLAN.md`'s session 04 log carries the distributions, the conditions and the
five ways the measurement was wrong first**; these are the conclusions.

| | |
|---|---|
| 400 loaded vanilla villagers | **14.75 ms/tick** mean, p95 19.40, p99 22.02. ~32 µs each, linear to 400. Fabric and NeoForge within 2.1%. |
| …of which `villagerBrain` | **10.41 ms** — two thirds of a villager, and a quarter of that is pathfinding |
| our sweep over 400 records | **1.2 µs/tick warm, 3.3 µs cold** |
| our per-tick cost, 400 villagers loaded, no sweep | **zero calls** — not a small number |

The ~18 ms this section carried is a tail of that distribution rather than its middle. **We still
cannot fix vanilla's cost while attached, and still shouldn't try.**

**Our budget stays ~5.95 µs/tick — re-ruled at the close of session 04 with the measurement in
hand, not merely inherited.** The sweep frame already spends a fifth to a half of it, leaving
**125–225 ns per record visit** for everything sessions 05–14 put in the payload. There is far more
real headroom than that — 14.75 ms of a 50 ms tick leaves ~35 ms — and the budget is deliberately
not raised to meet it. The discipline is the point. Raise it when a payload has been priced and
does not fit, never the first time it pinches.

**The architectural answer: the persona record is the authority.** Only 60–100 NPCs are ever loaded
as entities; the rest exist purely as records the bucket sweep advances. Measured, that is worth
**3.3× of the tick** — 96 loaded plus 304 as records costs **4.51 ms**, against 14.75 ms for the
same four hundred people all loaded. A virtual record is **~10,000× cheaper** than a loaded
villager, not the ~100× this section guessed; that margin is headroom for a payload, not licence to
spend it.

**400 is a record count, not an entity count.** Seven real generated villages produced 52 personas,
a median of nine each, and nowhere were more than **eleven** villagers loaded at once — so 400
records is the state of a save after roughly fifty villages. It stays as the record target and as
the deliberately pessimistic entity figure the architecture is sized against.

---

## 9. Art plan

Three decisions do all the work: **use the vanilla humanoid model verbatim** (swap the renderer, not
the entity) · **one 2D colormap PNG replaces every skin tone** (sample `melanin × hemoglobin` as UV) ·
**tint clothing by culture palette** rather than drawing outfits.

| Asset | Count |
|---|---|
| Base body, greyscale (wide + slim) | 2 |
| Skin colormap · hair colormap | 2 |
| Hair shapes, greyscale | 6 |
| Clothing shapes, greyscale | 8 |
| Face variants · eyes/blink | 7 |
| **Total** | **~25** *(MCA ships 1,312)* |

Everything else is zero art: a Charter is a `written_book` with a data component; the Notice Board is
a `lectern` with a block entity; GUIs use vanilla nine-slice backgrounds. Author in **Blockbench**
skin-edit mode. Make skins datapack-loadable from v1 so community contributions cost only a merge.
**Do not use MCA's PNGs** — GPL-3.0 and individually contributed.

---

## 10. The acceptance script

1. Arrive at A. Every villager speaks a stranger line. Prices 1.00.
2. Feed a hungry villager in the square with 3 witnesses. Bond +3 subject, +1 each witness.
3. Walk the road to B (~2 min). Stranger lines. Board shows "no history."
4. Wait ~2 in-game days.
5. **Return to B. Someone says they've heard your name, referencing A.** Hearsay row at reduced
   confidence. Prices 0.95.
6. Strike a resident of B before 4 witnesses. Trust drops unclipped. Prices rise *for you*.
7. A second player who has done nothing gets stranger lines and 1.00 prices everywhere.

**Ship-or-kill:** step 5 must produce an audible reaction from a playtester who was not told it was
coming. If it does not, the propagation thesis is wrong and the design should be reconsidered before
another line is written.
