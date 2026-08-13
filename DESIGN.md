# DESIGN — Namesake

What we are building and why. `WORKPLAN.md` owns *what happens next*; this owns *what it is*.
47 decisions ruled, 0 open.

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
| Daily allowance | **8 for a typical villager, scaled by the same personality weight.** Personality controls the **ceiling, not the step**: scaling only what one deed is worth is erased the moment a player gives enough to fill the cap — everybody converges on the same number and personality decides nothing but how many gifts it took. Read off the benign columns only; the cap limits positives, so a short temper must not raise anybody's capacity for warmth. |
| Bond UI | Bands + the deed ring. **Never raw integers.** |
| Gossip | Distorts, never lies. Confidence degrades; identity blurs below 50. |
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
| Culture voice | Shared ~400 templates + per-culture tics and register tuning |
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

record Deed(                        // ~24 B; 32-entry ring per NPC ≈ 768 B
    short typeId, UUID actor, UUID subject,
    int settlementId, int gameDay,
    byte severity, byte confidence)  // 100 = witnessed first-hand

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
6. **Gossip queue** — enters the settlement deque, cap 32.
7. **Drain & distort** — 4/in-game hour, `confidence × 0.85` per hop, cross-settlement along a road
   edge at 0.15 with a 1200–6000 tick delay, max 2 hops.

Reputation travelling to the next settlement is not bolted onto this pipeline — **it is the pipeline,
run one hop further.**

---

## 5. Residency — the first threshold

Arrive as an outsider: every villager speaks from the **stranger pool**, which must explicitly say it
does not know you. Prices 1.00. Board shows "no history."

Granted by the Elder at era ≥ 1, requiring either *known* band with ≥3 residents or one significant
deed (`DEFENDED_RAID`, or `FED_HUNGRY` ×3).

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
| Gossip drain | every 250 ticks |
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
