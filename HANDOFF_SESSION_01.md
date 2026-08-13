# Handoff prompt — Session 01

Copy everything below the line into a fresh Claude Code session opened in `C:\MCA Reborn Rework`.
Delete this file once session 01 is complete.

---

Start session 01 of the Namesake build.

Read `CLAUDE.md`, then `WORKPLAN.md`, then `DESIGN.md` before doing anything. Session 00 is done —
the repo, multiloader skeleton and CI exist and are green. Do not re-litigate ruled decisions and do
not write a new plan document; `WORKPLAN.md` is the ledger and you update it at the end.

**Your task is session 01 only: Persona, persistence, and the attach bet.** Do not start session 02.

## What this session actually proves

This is the session that can kill the architecture. Everything in `DESIGN.md` assumes a persona can
ride a **vanilla** `Villager` — not a replacement entity — through the entity's whole lifecycle. If
it cannot, the architecture changes and the 16-session estimate roughly doubles. Find that out now,
not at session 08.

## Build

1. **`Persona`** — the record in `DESIGN.md` §3. Start with the fields you can populate today
   (`id`, `settlementId`, `householdId`, `traits[8]`, `cultureId`, `professionId`, `birthTick`,
   `appearanceSeed`, `eraOfMajority`). Generation logic is session 03; a random UUID and zeroed
   traits are fine here.
2. **Attachment** to the vanilla `Villager`, behind one common interface. Fabric has the attachment
   API; NeoForge has data attachments. Neither type may leak into `common` — put the seam next to
   `net.namesake.platform.Platform` and implement it once per loader.
3. **`NpcRegistry extends SavedData`** — UUID-keyed, with an explicit `schemaVersion` int written
   into the NBT from the very first save.
4. **A DataFixer that actually runs.** Not a stub. Bump the schema version and watch an old save
   migrate.

## Exit criteria — verify by effect, not by "it compiles"

A persona must survive, with the same UUID and field values, through **all** of:

- save → quit → reload
- chunk unload → chunk reload
- villager → zombie villager → cured back to villager
- the villager being renamed, having its profession changed, and being transported by a minecart or
  boat if that is quick to check

Then: bump `schemaVersion`, load a world saved before the bump, and **watch the datafixer run** —
confirm from a log line and from the migrated data, not from the absence of a crash.

Unit tests for the registry: round-trip, schema bump, and orphan cleanup when an NPC's entity is
gone.

## What session 00 learned that will save you time

- **`$env:JAVA_HOME` must be set to `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`**
  before any Gradle command. The system default JDK is 26 and the toolchain is pinned to 21.
- **`runServer` requires accepting Mojang's EULA — do not accept it on the owner's behalf.** Use
  `:fabric:runClient` / `:neoforge:runClient`; mod init logs on both sides, so it proves the same
  thing. If you genuinely need a server, ask the owner to accept it.
- **A green build proves almost nothing.** Session 00 shipped five defects past a passing build,
  including a mod that built perfectly and then refused to load because Fabric parses semver
  predicates while NeoForge parses Maven ranges. There is now a guard in `fabric/build.gradle` for
  that specific trap. Assume there are more of its kind and *launch the game*.
- **Rule 3 is not optional.** Every fix gets reverted and watched to fail before you call it done.
  Session 00 did this for the version-range guard and it is why that guard is known to work.

## When you are done

Update `WORKPLAN.md`: set session 01 to done, session 02 to NEXT, and append a session log entry
recording what shipped, what the exit criteria actually showed, and the commit range pushed. Then
push — a change is inert until it is merged and pushed.

If the attach bet fails, **stop**. Do not work around it. Write what you found into the ledger and
hand it back to the owner; that is a design decision, not an implementation problem.
