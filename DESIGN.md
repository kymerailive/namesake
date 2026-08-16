# DESIGN — Namesake

What we are building and why. `WORKPLAN.md` owns *what happens next*; this owns *what it is*.
85 decisions ruled, 0 open.

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
| Settlement name | **Derived from the bell and the culture at it, never stored.** A place name and a family name are the same shape in every one of the six grammars — Vale's family suffixes are literally *wood*, *field*, *ford*, *combe*; Karsk's are *grad*, *vor*, *din* — so `Names.family` already **was** the place grammar and needed no second one. Seeded from the bell rather than from the settlement id, because an id comes from a counter that increments in the order a player happens to visit, and would name one world's villages differently on a second playthrough. Derived for §1's reason and for session 03's: a stored name is a cache that can drift from the bell it describes, and a persisted field whose only reader is a display is the shape this document forbids — where a derived one has nothing to classify, which is the standing `Names` has held since session 03. It costs zero bytes and zero schema, which is what made it available at all in a session that must not bump one. |
| Player base | Registers only if villagers actually live there — same rule as everywhere |
| Era scope | **Social institutions only.** No tech tree, no machines. |
| Currency | Emeralds only |
| Treasury | Accrues a share of trades made in the settlement; spends on public works |

### Social
| | |
|---|---|
| Bonds | **3 signed axes: trust, warmth, fear. Plus a debt scalar.** It was four until session 12, and `respect` is gone rather than unread — see the standing band rows below. The cost is named rather than argued away: §6's force-resolution wants *deference up*, so session 16 adds an axis back and pays hard rule 1 for it. That is the price of §1, and §1 is what both reference codebases died without. |
| Standing bands | **Five, named, reading `trust` and `warmth` and nothing else.** `RESENTED` (trust ≤ −20) · `WARY` (trust < 0) · `NEUTRAL` · `TRUSTED` (trust ≥ 20) · `WARM` (warmth ≥ 20). Exactly three consumers, and the brief's instruction to resist a fourth is held by there being one enum and one file: the trade price multiplier, whether one recipe is taught, and which dialogue pool is selected. Pool selection is *behaviourally identical* to session 09's four comparisons, which is what let session 11's Notice Board follow for free — the bands add distinctions the price and the board can see and the pools cannot. |
| Band thresholds | **Measured, not chosen, and each of the four boundaries has a different provenance.** `RESENTED` at −20 is where one witnessed killing puts a person, halved: a villager who watches you kill a neighbour lands at −40 and the person you killed at −54, while no single blow at any severity reaches −20 and twenty bare-handed punches to one person reach −19. `TRUSTED` at 20 **is** `Residency.TRUST_THRESHOLD` rather than a copy of its value, so §5's *"residency also grants the trusted price band"* is one constant instead of two that agree. `WARM` at 20 is session 09's `WARM_WARMTH`, moved unchanged. `WARY` at 0 is session 09's hostile-pool boundary. |
| Why not respect | **Because the instrument said so, and the ledger had promised it since session 05.** Observed maximum respect over a hundred in-game days: zero, zero, zero, zero and **four** across the five player models — the four on one villager of nine — and a witness sweep from nobody watching to everybody moves it by not one point. `DialogueStats.LADDER`'s lowest mark is 20. That is LNK's failure with worse numbers, in the session whose own instrument exists to prevent it. And it could never have been decisive: `DEFENDED_RAID` wrote +12 respect and +8 trust against one shared per-axis allowance, so no raid raised respect without raising trust beside it, and `STRUCK_RESIDENT`'s +1 rounds to nothing under a personality weight below neutral. Raising the column was available and is refused on **design** grounds rather than on measurement — it perturbs nothing sessions 07–09 measured, because the axes are computed independently, but **no deed in the table honestly earns deference except the raid defence that already did.** Deference is not bought with bread. |
| Why not fear | §10 step 6 rules that violence makes prices **rise**, and fear is written only by violence. A band that read it would make hitting people a route to cheaper goods. Fear's consumer is §6's force resolution and its exemption stands at session 16, untouched. |
| Trade price | **1.35 / 1.15 / 1.00 / 0.90 / 0.75, and it *adds to* vanilla's own reputation adjustment rather than replacing it.** `Villager#updateSpecialPrices` runs on every trade-window open and *adds* two contributions into one accumulator — the gossip reputation and Hero of the Village — so `specialPriceDiff` is an accumulator with two contributors in it already and we are the third. Replacing it would suppress vanilla behaviour a player knows, which is replacing the entity by the back door. The dividend: **vanilla already draws a struck-through price** whenever the diff is non-zero, in both directions, so this session's most visible consumer ships no art, no screen and no packet. What a player sees when both fire is both — a cured zombie villager and a warm standing are two discounts, and a murder is two markups. |
| Trade price scope | **Recomputed on every open, reset before it is applied, stored nowhere.** A price is a pure function of a bond already on disk, which is session 11's argument at a second surface: nothing to persist, nothing to migrate, nothing that can drift. It is also what makes §10 step 7 structural — there is nowhere for one player's price to be kept, so there is nowhere for another to read it from. Two guards make that true rather than likely: the diff is reset before ours is added, because the hook fires on interactions that open no window; and a counter somebody else already has open is not repriced, because their client has already drawn the numbers. |
| Taught recipe | **A villager who trusts you shows you how to build their own workstation** — a librarian a lectern, a shepherd a loom, a mason a stonecutter. One vanilla crafting recipe per profession, read off `villager.getVillagerData().getProfession()`, awarded into the player's own recipe book. Zero art, zero data pack, zero persisted state of ours, and idempotent by vanilla's construction. A nitwit and a villager with no job teach nothing, which is an absence branch with a sentence rather than a silent nothing. **The librarian's recipe is a lectern, which is §9's Notice Board** — nothing arranged that; it falls out of the profession table. |
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
| Bond UI | Bands + the deed ring. **Never raw integers.** The bands are the five above, and the Notice Board has **six phrases** for them — session 11's four ruled ones kept word for word, plus *trusts you* and *will not forget*, which are the two distinctions the bands added and the pools could not express. **Five of the six can appear on a board row, and four in an ordinary playthrough.** A resident in the stranger pool is *counted* rather than named — `Board.of` buckets them into "and N who have never met you." — so *has not met you* is reachable only from a unit test; and *will not forget* needs trust ≤ −20, which is one witnessed killing rather than any amount of shoving. Both are correct behaviour and neither was noticed until session 12 wrote a playtest script against the rendered board. |
| Gossip | Distorts, never lies. Confidence degrades; identity blurs below 50. Nothing in the pipeline can add a detail to a story — every field of a retold deed but two is carried through untouched, and those two only move one way. |
| Gossip retention | **`confidence × 0.70` per hop, not 0.85.** The three clauses this section carried — 0.85, max 2 hops, blur below 50 — were written before there was code, and session 06 proved they were inconsistent: at 0.85 two hops lands at 72, so the blur could never fire. Of the four ways out, three break session 10 — five hops is a different mod, a higher blur threshold makes the two-hop story in the acceptance script's step 5 anonymous, and leaving it ships a distortion mechanic that never distorts. Lowering the retention is the only lever that moves the two hops in *opposite* directions relative to the threshold, and the window is arithmetic: hop one must stay attributed and hop two must not, so `r ∈ [0.50, 0.707)`. Seven tenths is the top of it and therefore the gentlest change that works. |
| Gossip storage | **Inside `namesake_npcs.dat`, under one `NpcSchema` version** — settlements' argument for the fourth time, and it applies here where session 07's `DialogueStats` said it did not: a queued rumour *is* a `Deed`, which references personas and settlements by id. Persisted rather than volatile because session 10's cross-settlement hop makes an in-flight story certain to cross a save — and a schema bump during a ship-or-kill session is the worst available time for one. A queued rumour needs no new record, so the migration adds a table and nothing else. **Session 10 spent this exactly as intended and changed no schema:** a story on the road is a queued rumour in the destination's deque, and the two questions an in-flight table would have answered — where is it going, and when does it get there — are the deque's own key and `Deed.gameDay`. |
| Cross-settlement reach | **A story crosses a border only from the place it happened, carrying the copy its own village's source holds.** Both halves are one comparison over `Deed.settlementId`, and between them they keep `max 2 hops` arithmetic across a border rather than a counter. What crosses is the deque's entry untouched — the carrier's copy, a witness leaving town with a hundred — so the far village's first telling lands at **70**: attributed, and therefore the sentence §10 step 5 is made of. Crossing at the *telling* instead would put 49 next door and step 5 would be anonymous; crossing undegraded and setting out again from every village reached would put your name at the horizon. The home rule is what stops the second, and it says something worth saying out loud: **the person who carries your name out of a village is somebody who was there.** |
| Cross-settlement delay | **One in-game day, derived from `Deed.gameDay`, not a scheduled tick.** The ruled 1200–6000 ticks does not compose with the rest: a story is out of its village's deque about 500 ticks after it is queued, so there is nothing left to send by the time such a delay elapses, and honouring it needs a departure timestamp — persisted state, and a schema bump during ship-or-kill. The deciding argument is not the bump, though. **Nothing downstream of a deed has ever seen a tick** — `Deed.dayOf` divides game time by 24,000, `Bond.decayedTo` takes a day, `Bond.apply` resets on the day turn — and session 07's whole claim that a hundred simulated days *are* a hundred days rests on it. A tick-precise delay would be the first sub-day clock in the record layer, and the instrument that has to measure propagation could no longer run it exactly. A delay nobody can simulate is a delay nobody can put a number on. |
| Cross-settlement rate | **0.15 is the chance one hearer in the far settlement takes one telling** — the twin of the same-settlement 0.35, read the way session 08 ruled that one. The road edge decides whether there is a route at all; this decides how well the news takes when it arrives, which is what makes the graph load-bearing rather than decorative. The alternative reading — a coin on the edge — puts a named story into a *specific* neighbouring village 15% of the time, and §10's acceptance script is one gift. Per hearer, one gift reaches a namer in a nine-person village **77%** of the time and three gifts reach one **99%** of the time. |
| Road network | **A Delaunay over settlement bells pruned to the relative-neighbourhood graph, derived and never persisted.** A complete graph looks like nothing and a minimum spanning tree looks like a diagram; between them sits the graph that keeps the spanning tree and adds a second route wherever two villages are genuinely near each other rather than merely connected through a third. The lune test is exact integer arithmetic over block coordinates; the triangulation is doubles and is allowed to be, because it only supplies candidates and losing one is caught two ways. **Nothing is stored:** the graph is a pure function of a table that has been on disk since schema 3, and storing it would be the cache session 03 deleted `Settlement.culture` for. Dimensions never join. |
| Road materialiser | **`dirt_path` over seven natural ground blocks, in loaded chunks only, and nothing else.** The surface is whatever `MOTION_BLOCKING_NO_LEAVES` finds, so a floor, a bridge, a farm or a path already laid answers instead — and none of those are in the allowlist, which means **the natural ground under a player's build is never even looked at**. Nothing is broken, nothing is placed above ground level, and no chunk is ever loaded to build a road: it appears as a player travels it. Not reversible, and that is why there is a switch — `-Dnamesake.roads=off` stops the blocks and changes nothing about the thesis, because gossip crosses the graph rather than the blocks. |
| Residency axis | **The threshold reads `trust`, not warmth.** Ruled by the owner at the close of session 08 against session 07's table and session 08's: a witness's share of a gift is one point and warmth decays one a day, so no three residents ever reach 20 warmth in a hundred in-game days — and gossip, measured both ways, does not change that at any mark. Trust does not decay, so it only ever climbs: the third resident crosses 20 on **day 28**. Residency is therefore earned by consistency rather than by intensity, which is the right reading of a player who has decided they live here. |
| Memory depth | **Two of the owner's three routes, and the third was taken back out at session 12.** Ruled at the close of session 08, the third time they had asked for in-depth memories; session 09 built all three. What survives is `Memories.Slot.repeats` — *how many times*, on the slot rather than on the deed because two villagers who watched the same afternoon can have seen different amounts of it, consumed by the eviction policy on the day it landed — and the ring at **128 slots**. What is gone is `Deed.item`, *which* object: it took a dated exemption rather than a consumer at session 09, three non-display readers were built for it and all three rejected, and no fourth appeared by its expiry. **A board row now reads *gave them something* where it read *gave them bread*, and that is a real loss** rather than a tidy-up. It cost nothing to remove because session 09 had already kept the object **outside `Deed.id()`** — putting it in was the obvious reading of "richer per memory" and would have handed the ring back its grindability — so no deed id in any save moved and the rings re-partition into exactly the sets they were already in. |
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
| Notice Board | **A vanilla lectern standing in a registered settlement. No block entity, no registered block, nothing persisted.** §9 said *a lectern with a block entity*, and a block entity is a **second persisted store** — chunk data, with a schema of its own, outside `NpcSchema`, that hard rule 1's fixer ladder cannot see and a load test cannot cover. This project has ruled *one file, one version number that cannot disagree with itself* four times. What it would have held is the single fact that this lectern is a board, and that is derivable: a lectern inside a settlement's membership radius **is** the board, which is the road graph's argument at session 10 and the residency threshold's at 09. A block state or a data component is the same bytes needing a writer, where deriving needs none; a registered block costs a registry entry, an item, a recipe and two model files, is no longer zero art, and makes every lectern already standing in every existing world not a board. |
| Board gesture | **The one vanilla spends on nothing: an empty lectern, right-clicked with an empty hand.** `LecternBlock.useWithoutItem` returns `CONSUME` and does nothing when there is no book; putting a book on one goes through `useItemOn`, and a lectern that has a book opens the book. So reading, placing and taking a book are untouched, and a lectern **outside** a village is left entirely alone — which is session 10's ruling that a player's build is invisible to the road materialiser, applied to a gesture instead of to a block. Only the server ever opens it, per this section's interactions row, and there is no serverbound packet at all: a reader of a notice board cannot ask it to do anything, so hard rule 6 costs the session nothing and no interaction token is minted. |
| Board standing | **The dialogue pool's own answer, never a naming scheme of the board's.** The bond UI row rules bands and never raw integers, and the five bands are session 12's. The board calls `Dialogue.poolFor` — the same call that selects the villager's line — so the two agree **by construction** rather than by two implementations that happen to match. Session 12 replaces one method: its third ruled consumer *is* pool selection, and when that becomes a band lookup the board moves with it, because nothing on the board names a threshold or prints a number. The cost is stated rather than hidden: **two bands that share a pool look identical on this board**, so a player whose price has moved may see the board stand still. That is strictly better than the board and the villager giving different answers to *what is my standing*. |
| Board provenance | **A hearsay row names the village a story came from and which way it is.** Both halves were already on disk — the deed carries where it happened, the settlement table carries where that is, the holder's persona carries where they live — which is why `Deed.UNKNOWN_ACTOR` has said since session 08 that *"from the north" needs no field*. The name and the bearing are both shown and the second is not a fallback for the first: a player who has never been to Skovadn cannot turn a noun into a direction. A place that has fallen out of the settlement table keeps the bearing and loses the name, which is this mod's one rule about detail arriving at a third site — **it degrades, it is never invented**. Session 11 is also where that rule bit a *layout*: sharing one line with the object let the clip meant for a modded registry id take the `-east` off a bearing, which is a different direction rather than a shorter fact. |
| Board width | **Scaled pixels against 320×240, never characters.** `Window.calculateScale` raises the GUI scale only while the framebuffer divided by the next scale is still at least 320 by 240, so that is the smallest effective GUI the game will ever present — at any window size, at any scale setting, forced or automatic — and therefore the one budget that is a property of Minecraft rather than of somebody's monitor. The sixty-character chat width this repo already guards is wrong twice over here: a lectern is not chat, and a character is not a unit of width — *Illinois* and *wwwwwwww* are eight characters and 32 against 48 pixels. The ASCII advance table that makes the measurement pure is held to the engine's own `Font` by a harness leg, which found **five** of its numbers wrong on the first run it reached. |
| Culture voice | Shared pools + per-culture tics and register tuning. Session 09 ships **160 lines** — four pools × five registers × eight — with six cultures' openers, tag questions, stranger address terms and formality biases over them. A formal culture opens and rarely tags; an informal one does the reverse, so the *rate* is a per-culture number rather than a per-culture string, and a test measures the realised rate rather than the table. |
| Standing split | 80% personal / 20% shared, server-configurable. **Ruled at session 12 to land at session 15, with the config, and the reasoning is in that session's log.** It is an *input* to the standing bands rather than a consumer of them, so it does not spend session 12's three-consumer budget — but it changes `Standing.of`'s signature and would move every threshold that session measured, which makes it a re-measurement rather than an addition. It is also a **tuning knob on a mechanism that already works**: what a village collectively feels about you already reaches a villager who was not there, through the gossip pipeline, arriving *inside* the personal bond rather than beside it. A blended shared term is a second route to the same place, and this document has ruled against two answers to one question five times. Session 15 owns it because it is ruled server-configurable and because the number cannot be set without a playtest to set it against. |
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
    long   birthTick,
    // `int professionId` was here from session 01 and is gone at schema 8. NOTHING EVER WROTE IT:
    // every persona in every save held zero for eleven sessions. The recipe mechanic reads
    // vanilla's own `getVillagerData().getProfession()` off the entity that is standing there.
    int    appearanceSeed,  // derived from id; nothing else persists
    byte   eraOfMajority)

// axes: warmth, industry, boldness, curiosity, tradition, acquisitiveness, temper, sociability

record Bond(
    byte  trust,     // -64..100   will they rely on you    — Residency, and the standing bands
    byte  warmth,    //   0..100   do they like you         — GiftPolicy, and the top band
    byte  fear,      //   0..100   do they avoid crossing you
    short debt,
    int   lastSeenDay,
    short gainedToday,   // 3 nibbles, cap 8/axis/day, reset lazily on write
    byte  peakWarmth)    // decay target = peak * 0.4
    // `respect` was here until session 12 and is gone at schema 8: measured max 4 across five
    // player models, and no deed raised it without raising trust at least as fast.

record Deed(                        // 21 B packed; 128-entry ring per NPC ≈ 2.7 kB
    short typeId, UUID actor, UUID subject,
    int settlementId, int gameDay,
    byte severity, byte confidence)  // 100 = witnessed first-hand
    // `String item` was here from session 09 and is gone at schema 8. It was never part of
    // Deed.id(), which is why removing it re-partitions no ring in any save.

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

**Three fields left this model at session 12 and the count is stated rather than buried, because it
is the largest deletion this project has made.** Each carried a rule 5 exemption falling due at that
session's close and none of the three was paid: `Persona.professionId` (never written at all),
`Bond.respect` (measured maximum 4, and never decisive where trust was not), `Deed.item` (three
non-display readers built and rejected at session 09, and no fourth found). The mechanism worked
exactly as designed and it took something the design wanted — see the `Bonds` row in §2 for what
session 16 has to buy back.

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
   `confidence × 0.70` per hop, cross-settlement along a road edge at **0.15 per hearer**, arriving
   the in-game day after the deed.

   **Max 2 hops, and nothing counts them.** A story is retold while it can still be attributed, and
   0.70 applied twice to first-hand lands at 49 — below the blur threshold. So the bound is
   arithmetic rather than bookkeeping, which is what keeps `Deed` at eight fields and every ring in
   every save at its current shape.

   **The border is the same two hops, spread over two villages.** What crosses is the deque's own
   entry rather than the telling — the carrier's copy — so the far village's first telling lands at
   70 and can still name you, and its second blurs to 49. A story crosses **only from the place it
   happened**, which is one comparison over `Deed.settlementId` and is what stops an undegraded copy
   setting out again from every village it reaches. Nothing counts borders either.

   **And the delay is a day rather than a tick, which is the one ruled number session 10 moved.**
   A story is out of its own village's deque about 500 ticks after it is queued, so a 1200–6000 tick
   delay has nothing left to send by the time it elapses; and a tick-precise arrival needs a
   departure timestamp, which would be the first sub-day clock in a record layer whose only input
   from the passage of time has always been an integer day. See §2's cross-settlement rows.

   **The blur is an `if` statement, not a caption.** Below confidence 50 the actor is *replaced* by
   `Deed.UNKNOWN_ACTOR`, and an unattributed deed moves no bond: a villager who cannot say who
   killed the smith has no reason to think worse of *you*. They still remember it, because it
   happened — session 06's asymmetry from the other side. "From the north" needs no field either:
   the direction is a function of where the deed happened, which the deed carries, and where the
   holder lives, which their persona carries. **Session 11 built it**, on the Notice Board's hearsay
   rows, and found that the settlement table turns the same two fields into a *name* as well — so
   what a board says is *from Skovadn, west*, and falls back to the bearing alone for a place that is
   no longer in the table.

Reputation travelling to the next settlement is not bolted onto this pipeline — **it is the pipeline,
run one hop further.**

---

## 5. Residency — the first threshold

Arrive as an outsider: every villager speaks from the **stranger pool**, which must explicitly say it
does not know you. Prices 1.00. Board shows "No history." — **in those words, from session 11**, and
under a heading that says what the absence is an absence of. It is also where a player is told what
the two routes below actually are: the board prints *residents who trust you enough* and *hungry
people you have fed* against their thresholds, which is the only place in the mod either number is
ever explained.

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

**Residency also grants the trusted price band, and at session 12 that stopped being a sentence.**
`Standing.TRUSTED_TRUST` *is* `Residency.TRUST_THRESHOLD` rather than a copy of its value, so the
band a resident's neighbours put you in and the band this section grants are one constant: three
residents at twenty trust is residency, one resident at twenty trust is the price. Move this
threshold and the price moves with it, which is what makes the claim a property of the code instead
of a coincidence somebody has to maintain. It also grants office eligibility at era ≥ 3, and —
critically — makes
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

**We never call `brain.setSchedule`.** Vanilla decides what a villager is *doing*; the day plan
steers inside that decision and never replaces it. A plan stores WHAT, never WHEN — and session 13
read that one turn further: **if the plan is derived from a persona, a settlement and a day then it
persists nothing at all**, so §1 has nothing to classify and there is no schema to move. That is the
answer sessions 03, 09, 10 and 11 each reached about a different field, arriving for a fifth time.

### The table is global. The moment a villager crosses it is not.

This section said both of those in one sentence for twelve sessions and they are not the same claim.
They are about different objects and both are true:

- **The slot table below is global** — one static table, identical for every villager in every
  settlement in every world. That is not tidiness; it is the entire mechanism behind the
  at-a-glance test. *Four facts and the player can predict any NPC in the world* is only reachable
  if there are four facts to learn. A per-villager table would be four hundred different days
  happening at once, and nothing about it could be learned by watching.
- **The boundary offset is per-villager** — a villager reaches a boundary `offsetOf(persona, slot)`
  ticks after it, so for up to a spread after every boundary the village is split between the old
  slot and the new one. That is the transition wave, and it is what stops four hundred villagers
  path-finding in the same tick.

Same eight slots for everyone, at slightly different moments. `DaySlot` is the first half and
`DayPlan.offsetOf` is the second, and `DayPlanTest.theTableIsGlobalAndTheCrossingIsNot` holds both
in one assertion so neither can drift into being the other.

| # | Slot | dayTime | Vanilla activity | Steering | Ships at |
|---|---|---|---|---|---|
| 0 | DAWN | 0–1999 | IDLE | **none — vanilla's `IDLE` package already strolls and looks** | — |
| 1 | LABOUR_I | 2000–4999 | WORK | **the industry standoff** | 13 |
| 2 | HAUL | 5000–5999 | WORK | **`ERRAND` to the bell — everybody with a workstation** | 14 |
| 3 | NOON | 6000–6999 | WORK | **`ERRAND` to their own bed — everybody** | 14 |
| 4 | LABOUR_II | 7000–8999 | WORK | **the industry standoff** | 13 |
| 5 | COMMONS | 9000–10999 | MEET | **none — vanilla already walks them to r≤6 of the bell** | — |
| 6 | HEARTH | 11000–13999 | IDLE→REST | **none until 12000, then `ERRAND` for the watch** | 14 |
| 7 | NIGHT | 14000–23999 | REST | **none** for sleepers, `ERRAND` for the watch | 14 |

**"Four are exact keyframes" is three, and the fourth is inside a slot.** `VILLAGER_DEFAULT` has five
— **10, 2000, 9000, 11000, 12000** — against eight slot starts. The exact matches are **2000, 9000
and 11000**. The fourth that matters, **12000**, is not a start at all: it is the arrow in the
*IDLE→REST* row, in the middle of `HEARTH`, and it is the line session 14 splits into 6a and 6b. The
fifth, **10**, is where vanilla actually leaves `REST` — `Timeline#getValueAt` wraps to its last
keyframe before its first, so `getActivityAt(0)` answers `REST` and a villager is still asleep for
the first ten ticks of the day. The row above is true of 1,990 of `DAWN`'s 2,000 ticks.

### What session 13 ships, and it is not five slots

Three of the five slots this session owns have steering **none**, and a slot with no steering is not
code. Two more turn out to need none either, which is a result rather than an omission:

- **`DAWN`'s "walk+look"** is `VillageBoundRandomStroll`, `SetWalkTargetFromLookTarget` and
  `SetEntityLookTarget`, already in vanilla's `IDLE` package.
- **`COMMONS`'s "walk target r≤6 of bell"** is literally
  `SetWalkTargetFromBlockMemory(MEETING_POINT, speed, closeEnough = 6, …)` at
  `VillagerGoalPackages.java:139`, with `StrollAroundPoi(MEETING_POINT, 0.4F, 40)` beside it. At the
  ruled radius. Writing ours would be a second answer to a question that already has one, which this
  document has ruled against five times.

**So session 13 is the standoff and the transition governor.** The slot table exists because the
standoff is slot-gated and because session 14 hangs `ERRAND` off it. Four of the five slots ship no
code, and the session's job in those four is to prove we did not break what vanilla already does
there.

### Where the day plan runs — outside the record sweep

§8 rules that the persona record is the authority and a rotating sweep visits four hundred records in
twenty buckets. **The day plan is not in it**, and the first reason is sufficient: steering's only
output is a brain memory on a *loaded entity*, and three hundred of the four hundred records have no
entity, so a sweep visit would have nothing to do. Two more: the sweep visits a record about once a
second and the veto below has to act within one tick; and the plan persists nothing, so there is no
record state to advance.

So the sweep's **125–225 ns per record visit** — everything sessions 05–14 put in the payload — is
untouched, and the day plan is a new line item sized by *loaded entities* rather than by records.
Measured maximum in a real generated world: **eleven**. Designed against: sixty to a hundred.

### The industry mechanic

**`WorkAtPoi` is verified in-engine and the caveat is dropped.** `WorkAtPoi.DISTANCE = 1.73`, used by
**both** `checkExtraStartConditions` and `canStillUse`, through `BlockPos#closerToCenterThan` — so it
is measured from the workstation block's **centre** to the villager's position, not corner to feet.
`WorkAtPoi.start` plays the work sound, stamps `LAST_WORKED_AT_POI` and calls `restock()`, so all
three observables are one gate and reaching it is reaching all of them.

**And it is throttled twice.** `CHECK_COOLDOWN = 300`, and then `level.random.nextInt(2) != 0` on top
of it, so a villager standing *on* its workstation starts work about once every **600 ticks**. *"At
09:00 every workstation has someone within arm's reach"* is therefore a claim about a thirty-second
average and not about any given second, and anything that samples one tick is measuring a coin. The
observable to test is `LAST_WORKED_AT_POI`, not a position.

**The 9 m tolerance this section rested on measures something else.**
`SetWalkTargetFromBlockMemory(JOB_SITE, speed, closeEnough = 9, tooFar = 100, 1200)` —
`VillagerGoalPackages.java:78` — compares with **`distManhattan`**, not Euclidean. So *"inside
vanilla's 9 m tolerance"* is a Manhattan nine and *"3–8 m"* was a Euclidean band, and the two do not
compose: six east and six north is Euclidean **8.49**, inside the ruled band, and Manhattan **12**,
outside the tolerance. Vanilla hauls that villager back.

**And it was never the operative guard.** `StrollToPoi(JOB_SITE, 0.4F, closeEnough = 1,
maxDistFromPoi = 10)` sits in the WORK package's `RunOne` at weight 5 of 31 and writes
`WALK_TARGET = the job site, close enough 1` for any villager within **ten metres**, at most once
every 80 ticks. **There is no gap to stand in:** `Manhattan ≤ 9` implies Euclidean ≤ 9.87, which is
inside ten; and any point outside ten is outside the Manhattan tolerance and gets walked back by the
other behaviour. Every position vanilla will leave you at is a position `StrollToPoi` will pull you
off. Vanilla's equilibrium for an employed villager in `WORK` is: **on the workstation.**

**So the standoff is neither stable nor un-contested. It is a veto.** A lazy villager who has arrived
at their standoff point *declines* the walk target vanilla keeps offering, in the window between the
behaviour that writes it and `MoveToTargetSink` that would path on it —
`Brain.availableBehaviorsByPriority` is a `TreeMap` and the sink is priority 1 while the writers are
2 and 5, so a write this tick is not acted on until the next, and our end-of-server-tick hook sits
between them. An erase costs a hashmap remove and never a path.

**A veto is not a tug-of-war.** This section ruled the band out of one and was right about the
outcome and wrong about the mechanism: nothing writes a competing destination, nothing paths twice,
and the villager does not oscillate. It stands still, which is the observable.

| industry | Where they stand | Result |
|---|---|---|
| ≥ 12 | wherever vanilla puts them | Work sound, particles, **trades restock** — we do not steer them at all |
| < 12 | Manhattan ≤ 9 **and** `dx²+dz² ≥ 25` | Outside 1.73 m, so **no work at all**; `StrollToPoi` declined about once per 80 ticks |

The band is an **intersection of two metrics** rather than a range, because the behaviours it
silences measure differently: Manhattan keeps `SetWalkTargetFromBlockMemory` quiet, the horizontal
Euclidean five keeps `StrollAroundPoi(JOB_SITE, 0.4F, 4)` quiet, and being past four is being well
past 1.73. Both are re-checked on the *resolved* ground, because a standoff seven blocks out and
three up a hill is Manhattan ten.

**`industry ≥ 96` was the threshold and it selects nobody.** Rolled through the real `TraitRoll`
across six cultures — 4,536 personas — industry runs **−47 to 85**, with a median of 25. Ninety-six
is unreachable, so every villager in the world would have stood off. That is `Bond.respect`'s failure
exactly, caught before it reached a player rather than after. **The threshold is 12, which is that
population's p25**, so the number is defined by the shape of the population rather than picked to hit
a target: **one villager in four stands off**, and a village of nine has two or three idlers in it and
still reads as a working village.

A lazy villager *audibly* does not work, with no animation and no conflict with vanilla.

### The errand — session 14, and it is the opposite of the standoff in every way that costs

Three errands, and each one is a walk to a **memory vanilla already wrote**. `AcquirePoi` in the CORE
package gives every villager a `HOME` and a `MEETING_POINT`; reading the settlement table instead
would be a registry lookup per villager per errand and would leave an errand impossible for anybody
whose settlement has not been surveyed yet, which is most villagers for the first few thousand ticks
of their lives.

| errand | when | who | where | what a player sees |
|---|---|---|---|---|
| `HAUL` | 11:00–12:00 | everybody **with a workstation** | `MEETING_POINT` | the workshops empty and the middle of the village fills |
| `NOON` | 12:00–13:00 | everybody | `HOME` | the hearths fill, and nobody sleeps in one |
| `WATCH` | 18:00–06:00 | `boldness ≥ 20` — one in four | `MEETING_POINT` | the streets empty and two people do not go in |

*You carry what you made*, so `HAUL` needs a job site and the other two do not — a nitwit still eats
at noon and can still stand watch.

**The threshold is measured and its provenance differs from `industry`'s.** Industry's `12` is p25 for
its own sake. Boldness's `20` has a ruled target to hit — §7's *two* — so it is the mark that leaves
two people out of a village of nine: measured over 4,536 personas boldness runs −67 to 78 with p75 at
19, and 20 selects **25.0%**, which is 2.2 of nine. Thirty gives 1.3, which is a village that mostly
has no watch at all.

**A villager on an errand is CHEAPER than one at work, and that is what pays session 13's budget.**

`ERRAND` is a **custom activity**, and the reason is arithmetic rather than architecture. The standoff
holds a villager by a *veto* — erasing the walk target vanilla offers — and that is affordable only
because a standoff point is inside Manhattan nine of the workstation, where
`SetWalkTargetFromBlockMemory` is silent and `StrollToPoi` fires once every eighty ticks. **An errand
goes further than nine.** Out there that behaviour writes the job site back on every tick the walk
target is absent, so a veto becomes a write and an erase per villager per tick — the tug-of-war this
section rules the day plan out of, arriving as a bill. Switching the activity costs one set operation
and silences eleven behaviours at once, and after that **nothing contests, so there is nothing to
decline**: an errand costs the plan *nothing* per tick after the tick it begins, where a standoff pays
a memory read on every one.

The package is **vanilla's `WORK` list with the work taken out** — the minimal look behaviour,
`ShowTradesToPlayer` and `SetLookAndInteract`, all three at vanilla's own priorities. What is missing
is everything that measures from the workstation. `ShowTradesToPlayer` is where the sack comes from:
vanilla already makes a villager hold their goods up when somebody is watching, so *held item is a
noun* is paid by the engine at zero art, in the one slot this section wanted it for.

**Vanilla will not let a mod make an `Activity`, and the finding is worth stating.** The constructor
is private; it reads as public in a NeoForge decompile only because NeoForge ships an access
transformer for it, so `:common` — which compiles against plain NeoForm — cannot construct one at
all. Widening it would mean an access transformer, an access widener and a third module that already
has one: three spellings of one change, and the first bytecode-level modification this project has
made to a class it does not own. **And it would buy nothing.** Measured over all 6,316 source files
of 1.21.1, `BuiltInRegistries.ACTIVITY` is mentioned exactly twice — its own declaration and
`Activity.register` — and `Brain.getActiveActivities()` has **no callers at all**. No packet carries
an activity, no save file holds one, no screen prints one. An `Activity` is a map key in
`Brain.availableBehaviorsByPriority`; what this mod needs is *a key an adult villager's brain does not
use*, and vanilla registers twenty-seven while `Villager.registerBrainGoals` claims ten. `ERRAND`
borrows one of the other seventeen, chosen for being the one nothing that walks on two legs will ever
be given, and `ErrandTest` holds that against `registerBrainGoals`' own bytecode.

### `addActivitySafely`, and what it is actually for

`WORKPLAN.md` has carried it on the never-cut list since session 00 without saying why. Here it is:
**`Villager.refreshBrain` destroys it.** That method does
`this.brain = brain.copyWithoutBehaviors()` and then re-runs vanilla's `registerBrainGoals` —
`copyWithoutBehaviors` builds a **brand new** `Brain` and carries only the memories across, so every
behaviour, every activity registration and every `activityRequirements` entry is gone and what comes
back is vanilla's list. It is called from five places, and the fifth decides the design:

1. `AssignProfessionFromJobSite` — a villager takes a job
2. `ResetProfession` — a villager loses one
3. `ZombieVillager.finishConversion` — a cure
4. `Villager.ageBoundaryReached` — a baby grows up
5. **`Villager.readAdditionalSaveData` — every single load from disk**

So a custom activity is wiped **on every chunk load for every villager**, which is the ordinary path
rather than an edge case, and none of the other four fires an event either loader lets us see. A
one-shot registration at mint is not merely fragile: it is wrong the first time a player walks away
from a village and comes back.

**So it is re-established on demand, and the wipe is detected by effect rather than remembered by a
flag.** `setActiveActivityIfPossible` consults `activityRequirements`, which is exactly the map
`copyWithoutBehaviors` drops — *the activation failing is the wipe*. There is no per-tick check, no
bookkeeping that can disagree with the brain, and **no cost on any tick the helper is not called**; it
is called only at a slot crossing, which is already behind the transition governor at eight villagers
a tick. It also covers all five callers with one mechanism, which no amount of hooking could.

One vanilla behaviour makes the shape necessary rather than merely tidy: `setActiveActivityIfPossible`
does **not** leave a brain alone when the activity is unregistered — it calls `useDefaultActivity()`,
which is `IDLE`. A blind activation against a reloaded brain would silently take a villager out of
`WORK` in the middle of the morning, so the helper registers and retries inside the same call, before
the brain ticks again, and returns a boolean instead of `void`.

### The deactivation watchdog — the second bell lock, and it would have been ours

`ERRAND` has no `UpdateActivityFromSchedule`. It cannot: the schedule says `WORK` at eleven o'clock
and would switch the activity off inside twenty ticks. **That is exactly the shape of the bug this
mod inherited.** `HIDE` is the only vanilla package with no schedule exit, which is why a bedless
villager who hears a bell is stuck in their house for the life of the world — and an `ERRAND` with no
exit is the same deadlock with our name on it. This document did not say so before session 14. It
does now.

So the plan owns the exit, and it owns it in four layers because each catches a failure the one above
cannot:

1. **The window ends.** A slot comparison on the fast path, for every villager on every tick — the
   same comparison the transition governor is already driven by, so it costs nothing new.
2. **Vanilla took them** — a bell, a raid, a panic, a cure. Behind the path gate, and what matters is
   not the activity but **our walk target**: `LocateHidingPlace` requires `WALK_TARGET` to be absent
   and is the only writer of `HIDING_PLACE`, so one of ours left on a villager who has just heard a
   bell *is* the inherited bug, caused by the session that was supposed to be avoiding it.
3. **We lost track of them.** A villager whose brain is running `ERRAND` that the plan cannot account
   for is put back on vanilla's schedule, unconditionally and without being asked how they got there.
   Layer 1 depends on our bookkeeping being right; **this one does not**, which is what makes `ERRAND`
   a *window* rather than a state.
4. **And one that is free and is recorded rather than built:** all five `refreshBrain` callers end in
   `registerBrainGoals`, which sets `IDLE` and then calls `updateActivityFromSchedule`. A save and a
   reload, a cure, a profession change and growing up are all exits that cost this mod nothing — the
   same reason the bell lock heals on a chunk cycle, working for us for once.

The exit itself is **vanilla's own** — `brain.updateActivityFromSchedule`, the call
`SetHiddenState`'s else-branch makes and the one session 13's bell-lock repair makes. The mod does not
decide what a villager does next. **And the answer is read back**, because that call opens with
`gameTime - lastScheduleUpdate > 20` and silently does nothing inside that window: an errand that
ended within twenty ticks of beginning would leave a villager in `ERRAND` with the one thing that
could take them out already spent. That is the second bell lock as one line of throttle rather than as
a missing behaviour, so if the schedule declines, `setActiveActivityToFirstValid` forces it.

### Legibility, with no custom animation
Six laws: one intent, one place · held item is a noun, never a verb · **silence is a signal** · the
crowd is the message, facing beats posing · particles are punctuation (max 1/NPC/40 ticks, within 32
blocks of a player) · the bell is the clock.

**The at-a-glance test.** 09:00 — everyone at a workstation, except those eight blocks away doing
nothing. 11:00 — roads fill with sacks moving one direction. 12:00 — hearths fill. 15:00 — a ring at
the bell. 20:00 — streets empty but for the watch.
*Four facts and the player can predict any NPC in the world.*

**Two clauses of that line moved at session 14 and both are rulings rather than edits.**

- **17:00 — the tavern lights up — is struck, and it is deferred rather than deleted.** There is no
  tavern in this mod: no building types, no interiors, no institutions. Buildings arrive with the
  **era ladder at sessions 24–27**, which is outside the sixteen-session slice by this document's own
  sequencing, and 17:00 is `HEARTH`'s first hour — slot 6a — whose steering is *none*, so striking it
  costs no mechanic and changes no code. The alternative was to build something and call it a tavern,
  which is the shape both reference codebases died of. **A criterion with an unbuildable clause in it
  is not a criterion**, and the honest thing is to say which clause and when it comes back rather
  than to quietly ship four facts and claim five. `WORKPLAN.md`'s "after the slice" table carries it.
- **20:00's *two torches on the perimeter* is built as two people and not as two torches, and not on
  the perimeter.** The torch is a block this mod does not place — session 10 put every block it lays
  behind a switch and this one would light a village it has no business lighting. The perimeter is
  refused for a stronger reason than that: **the edge of a village after dark is where the mobs are**,
  and standing somebody's villagers out there to satisfy a legibility law is this mod spending a
  player's village on a picture. The watch stands at the meeting point — inside, lit, where the golem
  walks. What carries the line is the count and the contrast: at midnight these are the only two
  people out, which no other hour of the day looks like.

### The transition wave

Boundary offset derives from `industry`, blended by `tradition`, with a **hard `spread ≥ 64` floor
enforced in code, not config**. Without it, 400 simultaneous transitions fire 400 path requests in one
tick; with it the mean is `400 / 64 ≈ 6` a tick, against a transition governor that serves 8.
*Never expose "make everyone punctual" as a config option* — there is no property, no config field
and no setter, and `DayPlanTest.theSpreadFloorIsNotConfigurable` reads the class's bytecode to prove
nothing consults one.

**A blend rather than a mark plus a jitter, and the difference is measured.** Industry says where
diligence would place a villager; tradition says how much of that survives contact with whim, which
is a uniform draw across the whole spread. The obvious `mark + jitter` fails, because the traits the
generator produces span about two thirds of their nominal range — so a mark derived from them only
ever reaches the middle of the spread, and a jitter carved out of the remainder narrows it further.
Measured, that version occupied **43 of 64** offsets. Blending toward a draw over the whole spread
makes the coverage a property of the arithmetic rather than of a distribution the next culture added
would move.

**What the two walls actually produce, simulated at 400.** The spread scatters the crossings; the
`id % 7` path gate then delays each villager to the next tick their own id opens, scattering everyone
who shares an offset across seven more; the governor drains 8 a tick. Measured over 4,536 personas:
52 of 64 offsets used, a peak of **17.5 per 400** at the busiest offset, a mean of **6.25 a tick** —
and **the last villager crosses 63 ticks after the boundary**, which is inside the spread. The
governor absorbs the peak and adds nothing.

Two assertions written before that one were both wrong and the way they were wrong is worth keeping:
the first held the busiest *offset* to 12, the second held *arrivals at the governor* to 8. Both
read ~17.5. The second is wrong in kind rather than in value — **a governor that is never exceeded is
a governor that does nothing.** Its job is to absorb a burst into a queue, so the numbers that decide
whether the wall holds are the mean (which decides whether the queue is stable at all) and the worst
delay (which is the only part a player could see).

### The bell lock — why villagers get stuck in their own houses

Recorded here because it is vanilla's, it is not fixable from inside this mod without changing state
we do not own, and **the day plan is one missing line away from causing it.**

`BellBlockEntity.updateEntities` writes `HEARD_BELL_TIME` into every living entity within 32 blocks of
a rung bell, **with no expiry**. `ReactToBell` sits in the CORE package at priority 0, so it runs in
every activity, and re-asserts `HIDE` on every tick that memory is present. **The `HIDE` package is
the only one of vanilla's seven with no `UpdateActivityFromSchedule`** — WORK, MEET, IDLE, PLAY and
REST all end with `Pair.of(99, …)` and HIDE does not — so the schedule cannot pull a villager out of
it. Its one exit is `SetHiddenState`, which needs `HIDING_PLACE` *and* `HEARD_BELL_TIME` before it
will time out after 300 ticks and erase them both. `HIDING_PLACE` has exactly one writer,
`LocateHidingPlace`, which requires `WALK_TARGET` to be **absent** and then needs a HOME point of
interest within 32 blocks or a HOME memory.

So there are two deadlocks with no exit at all: a villager with neither a nearby bed nor a HOME
memory never gets a hiding place; and a villager whose `WALK_TARGET` is never absent on a tick
`LocateHidingPlace` runs never gets one either. Neither memory declares a codec, so neither is
persisted — which is why the bug heals on a chunk unload and reads as intermittent.

**First, a rule.** Anything that starves `WALK_TARGET` while a villager is indoors is this bug; and
vanilla never clears its *own* walk target on an activity change, because `Villager.registerBrainGoals`
uses `addActivity` rather than `addActivityAndRemoveMemoryWhenStopped` throughout. So the day plan
**never writes a walk target to a villager outside `{WORK, IDLE}`**, and **takes its own walk target
back** when the slot ends. The state is named — `Steering.Posture.BELL_LOCKED`, three memory reads —
and reported by `/namesake debug dayplan`.

**And then a repair, ruled at the close of session 13: after 1,200 ticks the plan runs vanilla's own
exit for them.** The objection is recorded rather than buried, because it was raised and overruled:
this changes vanilla state the mod does not own, on a diagnosis with a verified mechanism and no
reproduction, and this document has refused that shape before.

What makes it defensible is that **it invents no recovery.** `SetHiddenState`'s else-branch does
three things — erase `HEARD_BELL_TIME`, erase `HIDING_PLACE`, call `updateActivityFromSchedule`.
`Steering.unstickTheBellLock` does the first and the third. It does not do the second because there
is nothing to erase: **the absent hiding place is the deadlock**, and it is exactly why
`SetHiddenState` never runs. So the mod does not decide what a stuck villager does next — the
schedule does, as it would have. Erasing the memory is what makes it stick, because `ReactToBell` is
CORE priority 0 and would re-assert `HIDE` within a tick otherwise.

**1,200 ticks is argued.** `SetHiddenState.HIDE_TIMEOUT` is **300** — vanilla's own view of when a
villager should be finished hiding — so waiting four times that means the repair only ever fires long
after the engine would have fired it, and 1,200 is the number vanilla itself uses for
`tooLongUnreachableDuration`. The patience also does real work: of the two deadlock shapes only one
is permanent, and a villager whose `WALK_TARGET` merely happened to be occupied rescues itself inside
the window. Neither memory is persisted, so this reaches no save file and moves no schema.

---

## 8. Tick budget

| Work | Cadence |
|---|---|
| Persona record sweep | 20 rotating buckets (~1×/sec/record) |
| Witness scan | on deed emit — **never polled** |
| Gossip drain | every 250 ticks — **the only thing in this mod that polls**, and bounded by construction rather than by measurement: a settlement is in the drain's map only while it has an unspent story, and a story is spent after two drains. On 249 ticks in 250 the hook reads a tick count and returns. |
| Grievance escalation | 1×/in-game day, per settlement |
| Settlement survey | census on the server thread at 16 chunks/tick, scoring off-thread, once per place, ever |
| Road edge A* | 1 edge/tick, off-thread, **one node per chunk**, heights from the generator's own noise rather than from a chunk — the same estimate vanilla places structures with, so it needs neither a chunk in memory nor one on disk. Session 04 measured a cold column at 72 ms; a router that read a hundred would be a freeze rather than a road. |
| Road blocks | ≤48 column checks/tick while a road is being laid, **loaded chunks only**, and a build that finds nothing loaded sleeps for 200 ticks. Transient and one-shot per place, like session 03's census; zero once every road is decided. |

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

**A per-tick budget is a claim about the most expensive HOUR — added at session 14.** A tick happens
at one time of day, and the day plan does entirely different work at nine in the morning (one
villager in four vetoed at a workstation) than at eleven (everybody on an errand). Session 13 froze
the profiler's clock at 09:00 for every cell and would therefore have priced session 14 at zero, with
perfect confidence. The `hours` phase measures one population at three hours and nothing else, and
what it found is that **the peak does not move**: an errand hour costs the same as a labour hour on
our own meter, because an errand costs *nothing* per tick after the tick it begins. The **engine's**
cost falls by about half a millisecond over the same three cells, which is a thousand times the whole
budget — a villager in `ERRAND` runs three behaviours where a villager in `WORK` runs eleven.

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
a **vanilla `lectern` with no block entity at all** — session 11, and the reasoning is in §2's Notice
Board row: a block entity is a second persisted store and what it would hold is derivable. Its panel
is drawn from fills rather than a nine-slice sprite for the same reason one more time, which is that
a sprite name that is wrong is a failure nothing catches until the screen is on somebody's monitor.
Author in **Blockbench**
skin-edit mode. Make skins datapack-loadable from v1 so community contributions cost only a merge.
**Do not use MCA's PNGs** — GPL-3.0 and individually contributed.

---

## 10. The acceptance script

1. Arrive at A. Every villager speaks a stranger line. Prices 1.00.
2. Feed a hungry villager in the square with 3 witnesses. Bond +3 subject, +1 each witness.
3. Walk the road to B (~2 min). Stranger lines. Board shows "no history."
4. Wait ~2 in-game days.
5. **Return to B. Someone says they've heard your name, referencing A.** Hearsay row at reduced
   confidence. **Prices 1.00 — unchanged, and that is the point.**
6. Strike a resident of B before 4 witnesses. Trust drops unclipped. Prices rise *for you* — to
   **1.15**, because one blow puts its subject below zero and no blow at any severity reaches −20.
7. A second player who has done nothing gets stranger lines and 1.00 prices everywhere.

**Step 5 said 0.95 until session 12, and the number gave way rather than the ladder.** It is wrong
twice over. It is not *on* the ladder — the five multipliers are ruled and a band ladder is discrete
by construction, which is what §2's *bands, never raw integers* means arriving at a price tag — and,
measured, it is not what the step produces: **one gift, one hop, one hearer lands at one point of
trust in the far village**, and after a hundred in-game days of daily kindness the neighbouring
village's residents hold six or seven. No threshold above 1 can catch that; only a boolean could, and
a discount for *having been mentioned once* is a discount nothing can take away again.

So the honest reading is the one the mod already delivers: **hearsay changes what they say, not what
they charge.** Reputation reaches a village down the road as a sentence first, and reaches the price
only when somebody there has actually dealt with you. The observable at step 5 is the line and the
hearsay row — which is what session 10's ship-or-kill was about, and what the owner reacted to.

**Ship-or-kill:** step 5 must produce an audible reaction from a playtester who was not told it was
coming. If it does not, the propagation thesis is wrong and the design should be reconsidered before
another line is written.
