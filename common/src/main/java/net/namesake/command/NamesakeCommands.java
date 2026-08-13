package net.namesake.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.namesake.Namesake;
import net.namesake.culture.Culture;
import net.namesake.culture.Cultures;
import net.namesake.culture.Names;
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.NpcSchema;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.platform.Platform;
import net.namesake.platform.PersonaLink;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.Personality;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /namesake debug ...} — the instruments for reading persona state out of a running game.
 *
 * <p>These exist because "verify by effect" needs something to read the effect with. A log line at
 * mint time proves a persona was created; only a query after a reload proves it is the same one.
 */
public final class NamesakeCommands {

    private static final SimpleCommandExceptionType NO_TARGET = new SimpleCommandExceptionType(
            Component.literal("No villager or zombie villager within 16 blocks."));

    private static final SimpleCommandExceptionType NOT_A_CARRIER = new SimpleCommandExceptionType(
            Component.literal("That entity cannot carry a persona."));

    private static final SimpleCommandExceptionType NO_PERSONA = new SimpleCommandExceptionType(
            Component.literal("That entity has no persona attached."));

    private static final SimpleCommandExceptionType DEV_ONLY = new SimpleCommandExceptionType(
            Component.literal("Refused: this rewrites persisted persona state. "
                    + "Development environments only."));

    private static final SimpleCommandExceptionType UNKNOWN_AXIS = new SimpleCommandExceptionType(
            Component.literal("Unknown trait axis."));

    private static final double SEARCH_RADIUS = 16.0;

    /** {@code WORKPLAN.md}'s exit criterion for session 03 is written in terms of twenty. */
    private static final int DEFAULT_DUMP = 20;

    private NamesakeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Namesake.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("persona")
                                .executes(context -> dumpPersona(context, nearestCarrier(context.getSource())))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(context -> dumpPersona(context,
                                                EntityArgument.getEntity(context, "target")))))
                        .then(Commands.literal("registry")
                                .executes(NamesakeCommands::dumpRegistry))
                        // The instrument session 03's exit criterion is read with. Read-only, so
                        // it stays available outside a development environment.
                        .then(Commands.literal("dump")
                                .executes(context -> dump(context, DEFAULT_DUMP))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                        .executes(context -> dump(context,
                                                IntegerArgumentType.getInteger(context, "count")))))
                        .then(Commands.literal("settlements")
                                .executes(NamesakeCommands::dumpSettlements))
                        // The instrument session 05's exit criterion is read with: what the
                        // villagers around you feel about *you*, next to what a gift is worth to
                        // each of them. Read-only.
                        .then(Commands.literal("bonds")
                                .executes(context -> dumpBonds(context, DEFAULT_DUMP))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                        .executes(context -> dumpBonds(context,
                                                IntegerArgumentType.getInteger(context, "count")))))
                        .then(Commands.literal("bond")
                                .executes(context -> dumpBond(context, nearestCarrier(context.getSource())))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(context -> dumpBond(context,
                                                EntityArgument.getEntity(context, "target")))))
                        // settrait and prune both write. An op on a live server could rewrite any
                        // villager's personality, or delete personas whose entities are merely
                        // unloaded — permission level 2 is not a meaningful gate on either. They
                        // are development instruments, so they are hidden outside a development
                        // environment and refuse if reached anyway.
                        .then(Commands.literal("settrait")
                                .requires(NamesakeCommands::isDevelopment)
                                .then(Commands.argument("axis", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(Persona.TRAIT_NAMES, builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
                                                .executes(context -> setTrait(context, nearestCarrier(context.getSource())))
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(context -> setTrait(context,
                                                                EntityArgument.getEntity(context, "target")))))))
                        .then(Commands.literal("prune")
                                .requires(NamesakeCommands::isDevelopment)
                                .executes(NamesakeCommands::prune))));
    }

    /**
     * Hides a writing command outside a development environment.
     *
     * <p>{@code requires} keeps it out of the command tree the server sends the client, so it does
     * not tab-complete and cannot be run. The executors still check as well: {@code requires} is
     * evaluated when the tree is built, and a gate that exists in exactly one place is a gate that
     * moves when someone restructures the builder.
     */
    private static boolean isDevelopment(CommandSourceStack source) {
        return Platform.get().isDevelopmentEnvironment();
    }

    // --- persona -------------------------------------------------------------------------------

    private static int dumpPersona(CommandContext<CommandSourceStack> context, Entity target)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!PersonaService.isPersonaCarrier(target)) {
            throw NOT_A_CARRIER.create();
        }
        UUID personaId = PersonaLink.get().personaId(target).orElseThrow(NO_PERSONA::create);
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        Persona persona = registry.persona(personaId).orElseThrow(NO_PERSONA::create);

        StringBuilder traits = new StringBuilder();
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            traits.append(axis == 0 ? "" : ", ")
                    .append(Persona.TRAIT_NAMES[axis]).append('=').append(persona.trait(axis));
        }

        UUID boundEntity = registry.boundEntity(personaId).orElse(null);
        String header = (persona.isGenerated() ? Names.of(persona).full() + " — " : "")
                + "persona " + persona.id();
        String culture = persona.isGenerated()
                ? Culture.byId(persona.cultureId()).displayName() + " (" + persona.cultureId() + ")"
                : "none yet — not generated";
        String body = "  entity     " + target.getUUID()
                + " (" + EntityType.getKey(target.getType()) + ")"
                + "\n  bound to   " + boundEntity + (target.getUUID().equals(boundEntity) ? " (match)" : " (MISMATCH)")
                + "\n  settlement " + persona.settlementId() + "  household " + persona.householdId()
                + "\n  culture    " + culture + "  profession " + persona.professionId()
                + "\n  birthTick  " + persona.birthTick() + "  appearanceSeed " + persona.appearanceSeed()
                + "\n  era        " + persona.eraOfMajority()
                + "\n  traits     " + traits;

        source.sendSuccess(() -> Component.literal(header + "\n" + body), false);
        Namesake.LOGGER.info("[debug persona] {}\n{}", header, body);
        return 1;
    }

    private static int setTrait(CommandContext<CommandSourceStack> context, Entity target)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!Platform.get().isDevelopmentEnvironment()) {
            throw DEV_ONLY.create();
        }
        String axisName = StringArgumentType.getString(context, "axis");
        int value = IntegerArgumentType.getInteger(context, "value");
        int axis = axisIndex(axisName);

        UUID personaId = PersonaLink.get().personaId(target).orElseThrow(NO_PERSONA::create);
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        Persona persona = registry.persona(personaId).orElseThrow(NO_PERSONA::create);

        Persona updated = persona.withTrait(axis, (byte) value);
        registry.put(updated);
        source.sendSuccess(() -> Component.literal(
                "persona " + personaId + " " + axisName + " = " + value), false);
        return 1;
    }

    private static int axisIndex(String name) throws CommandSyntaxException {
        for (int axis = 0; axis < Persona.TRAIT_NAMES.length; axis++) {
            if (Persona.TRAIT_NAMES[axis].equalsIgnoreCase(name)) {
                return axis;
            }
        }
        throw UNKNOWN_AXIS.create();
    }

    // --- registry ------------------------------------------------------------------------------

    private static int dumpRegistry(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        String report = "npc registry: " + registry.size() + " persona(s), "
                + registry.bindingCount() + " bound"
                + "\n  schema on disk " + registry.loadedSchemaVersion()
                + ", this build writes " + NpcSchema.CURRENT
                + (registry.isReadOnly() ? "\n  READ-ONLY: this file will not be written" : "");
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug registry] {}", report.replace("\n", " |"));
        return registry.size();
    }

    private static int prune(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!Platform.get().isDevelopmentEnvironment()) {
            throw DEV_ONLY.create();
        }
        MinecraftServer server = source.getServer();
        Set<UUID> alive = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            level.getAllEntities().forEach(entity -> alive.add(entity.getUUID()));
        }
        int removed = NpcRegistry.get(server).pruneOrphans(alive::contains);
        source.sendSuccess(() -> Component.literal(
                "pruned " + removed + " orphan persona(s) against " + alive.size() + " loaded entities"), true);
        return removed;
    }

    // --- dump ----------------------------------------------------------------------------------

    /**
     * The nearest {@code limit} loaded NPCs, grouped by settlement and household.
     *
     * <p><b>This is the instrument this session's exit criterion is read with</b>, so it is laid
     * out for the question being asked rather than for the data structure underneath. Grouping by
     * household is the point: "households are recognisably related" is a claim about a shelf of
     * names and eight columns of numbers sitting next to each other, and a flat list sorted by
     * distance would hide exactly the thing the owner is being asked to judge.
     *
     * <p>Only NPCs with a loaded entity appear, because those are the ones you can walk over to and
     * look at. The total is printed alongside so the difference is never mistaken for a loss.
     */
    private static int dump(CommandContext<CommandSourceStack> context, int limit) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        NpcRegistry registry = NpcRegistry.get(source.getServer());

        List<Persona> nearest = registry.all().stream()
                .map(persona -> Map.entry(persona, distanceTo(level, registry, persona, origin)))
                .filter(entry -> entry.getValue() < Double.MAX_VALUE)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        StringBuilder out = new StringBuilder();
        out.append(nearest.size()).append(" of ").append(registry.size())
                .append(" persona(s) — loaded, nearest first")
                .append("\n  axes: ");
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            out.append(axis == 0 ? "" : " ").append(Persona.TRAIT_NAMES[axis], 0, 3);
        }

        // Grouped by settlement, then household, keeping the nearest-first order within each.
        Map<Integer, List<Persona>> bySettlement = nearest.stream()
                .collect(Collectors.groupingBy(Persona::settlementId, LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<Persona>> group : bySettlement.entrySet()) {
            out.append('\n').append(settlementHeader(level, registry, group.getKey()));
            Map<Integer, List<Persona>> byHousehold = group.getValue().stream()
                    .collect(Collectors.groupingBy(Persona::householdId, LinkedHashMap::new,
                            Collectors.toList()));
            for (Map.Entry<Integer, List<Persona>> household : byHousehold.entrySet()) {
                out.append("\n  household ").append(household.getKey());
                for (Persona persona : household.getValue()) {
                    out.append("\n    ").append(describe(persona));
                }
            }
        }

        String report = out.toString();
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug dump] {}", report);
        return nearest.size();
    }

    /** One NPC: name, culture, and eight signed axes. */
    private static String describe(Persona persona) {
        StringBuilder line = new StringBuilder();
        if (persona.isGenerated()) {
            Culture culture = Culture.byId(persona.cultureId());
            line.append(pad(Names.of(persona).full(), 28)).append(pad(culture.displayName(), 9));
        } else {
            line.append(pad("(ungenerated) " + persona.id().toString().substring(0, 8), 28))
                    .append(pad("-", 9));
        }
        for (int axis = 0; axis < Persona.TRAIT_COUNT; axis++) {
            line.append(String.format(" %+04d", persona.trait(axis)));
        }
        return line.toString();
    }

    private static String settlementHeader(ServerLevel level, NpcRegistry registry, int settlementId) {
        if (settlementId == Persona.UNASSIGNED) {
            return "unsettled";
        }
        return registry.settlements().byId(settlementId)
                .map(settlement -> "settlement " + settlement.id()
                        + "  " + Cultures.at(level, settlement.centre()).displayName()
                        + "  " + settlement.specialtyValue()
                        + "  defensibility " + settlement.defensibility()
                        + "  " + needsOf(settlement)
                        + "  centre " + settlement.centre().toShortString())
                .orElse("settlement " + settlementId + "  (MISSING from the registry)");
    }

    private static int dumpSettlements(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        Collection<Settlement> settlements = registry.settlements().all();

        StringBuilder out = new StringBuilder(settlements.size() + " settlement(s)");
        if (settlements.isEmpty()) {
            // Every section prints its own absence — DESIGN.md §11's rule, applied early. An empty
            // report that says nothing reads as a broken command.
            out.append("\n  none detected yet. A settlement needs a bell; the survey runs when a "
                    + "villager loads near one.");
        }
        for (Settlement settlement : settlements) {
            long residents = registry.all().stream()
                    .filter(persona -> persona.settlementId() == settlement.id())
                    .count();
            out.append("\n  ").append(settlementHeader(level, registry, settlement.id()))
                    .append("  residents ").append(residents);
        }

        String report = out.toString();
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug settlements] {}", report.replace("\n", " |"));
        return settlements.size();
    }

    // --- bonds ---------------------------------------------------------------------------------

    /**
     * What the nearest loaded NPCs feel about <i>you</i>, and what a gift is worth to each of them.
     *
     * <p><b>This is what session 05's exit criterion is read with.</b> Feed one villager in front of
     * three others and this prints the shape the criterion describes: the subject moved by three,
     * the witnesses by one, and whoever could not see it not at all. The last column is the reason
     * the same gift lands differently on two people — {@link Personality#scale} for a wanted gift,
     * as a multiplier of nominal, which is the number the weight table exists to produce.
     *
     * <p>Bonds are printed raw here on purpose. {@code DESIGN.md} rules the <i>player-facing</i> bond
     * UI as bands and a deed ring, never integers; this is a debug command and the whole point of it
     * is the integers.
     */
    private static int dumpBonds(CommandContext<CommandSourceStack> context, int limit) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        UUID viewer = source.getEntity() == null ? null : source.getEntity().getUUID();
        int day = Deed.dayOf(level);

        List<Persona> nearest = registry.all().stream()
                .map(persona -> Map.entry(persona, distanceTo(level, registry, persona, origin)))
                .filter(entry -> entry.getValue() < Double.MAX_VALUE)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        StringBuilder out = new StringBuilder("day ").append(day).append(" — ")
                .append(nearest.size()).append(" loaded NPC(s), nearest first; ")
                .append(registry.bonds().size()).append(" bond(s) in the world");
        if (viewer == null) {
            // Every section prints its own absence. Run from the console there is no "you".
            out.append("\n  (no viewer — run this as a player to see what they feel about you)");
        } else {
            out.append("\n  ").append(pad("who", 28)).append("trust warmth respect fear   cap")
                    .append("   gift×");
            for (Persona persona : nearest) {
                Bond bond = registry.bonds().at(persona.id(), viewer, day);
                out.append("\n  ").append(pad(nameOf(persona), 28))
                        .append(String.format(Locale.ROOT, "%+5d %+6d %+7d %+4d", bond.trust(),
                                bond.warmth(), bond.respect(), bond.fear()))
                        .append(String.format(Locale.ROOT, "   %d/%d/%d/%d",
                                bond.gainedToday(Bond.TRUST), bond.gainedToday(Bond.WARMTH),
                                bond.gainedToday(Bond.RESPECT), bond.gainedToday(Bond.FEAR)))
                        .append(String.format(Locale.ROOT, "  %.2f",
                                Personality.scale(persona, DeedType.GIFT_WANTED)));
            }
            if (nearest.isEmpty()) {
                out.append("\n  no NPC is loaded near you.");
            }
        }

        String report = out.toString();
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug bonds] {}", report);
        return nearest.size();
    }

    /** One NPC's whole bond table — everyone they have feelings about, and how strong. */
    private static int dumpBond(CommandContext<CommandSourceStack> context, Entity target)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        UUID personaId = PersonaLink.get().personaId(target).orElseThrow(NO_PERSONA::create);
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        Persona persona = registry.persona(personaId).orElseThrow(NO_PERSONA::create);
        int day = Deed.dayOf(source.getLevel());

        Map<UUID, Bond> held = registry.bonds().of(personaId);
        StringBuilder out = new StringBuilder(nameOf(persona) + " — " + held.size() + " bond(s), day " + day);
        if (held.isEmpty()) {
            out.append("\n  nobody has done anything to them. That is a real answer, not an empty table.");
        }
        for (Map.Entry<UUID, Bond> entry : held.entrySet()) {
            out.append("\n  about ").append(entry.getKey())
                    .append("\n    stored  ").append(entry.getValue())
                    .append("\n    today   ").append(entry.getValue().decayedTo(day));
        }
        out.append("\n  a wanted gift is worth ×")
                .append(String.format(Locale.ROOT, "%.2f", Personality.scale(persona, DeedType.GIFT_WANTED)))
                .append(" to them, a blow ×")
                .append(String.format(Locale.ROOT, "%.2f",
                        Personality.scale(persona, DeedType.STRUCK_RESIDENT)));

        String report = out.toString();
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug bond] {}", report);
        return held.size();
    }

    private static String nameOf(Persona persona) {
        return persona.isGenerated()
                ? Names.of(persona).full()
                : "(ungenerated) " + persona.id().toString().substring(0, 8);
    }

    private static String needsOf(Settlement settlement) {
        StringBuilder needs = new StringBuilder("needs");
        for (Need need : Need.values()) {
            needs.append(' ').append(need.name().toLowerCase(Locale.ROOT)).append('=')
                    .append(settlement.need(need));
        }
        return needs.toString();
    }

    private static double distanceTo(ServerLevel level, NpcRegistry registry, Persona persona,
                                     Vec3 origin) {
        return registry.boundEntity(persona.id())
                .map(level::getEntity)
                .filter(entity -> entity != null && !entity.isRemoved())
                .map(entity -> entity.distanceToSqr(origin))
                .orElse(Double.MAX_VALUE);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    // --- targeting -----------------------------------------------------------------------------

    private static Entity nearestCarrier(CommandSourceStack source) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
        List<Entity> candidates = level.getEntities((Entity) null, box, PersonaService::isPersonaCarrier);
        return candidates.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(origin)))
                .orElseThrow(NO_TARGET::create);
    }
}
