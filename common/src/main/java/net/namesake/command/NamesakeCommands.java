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
import net.namesake.road.RoadEdge;
import net.namesake.road.RoadGraph;
import net.namesake.road.RoadNetwork;
import net.namesake.road.RoadProgress;
import net.namesake.road.Roads;
import net.namesake.settlement.Need;
import net.namesake.settlement.Settlement;
import net.namesake.sim.PlayerModel;
import net.namesake.sim.Reports;
import net.namesake.sim.Simulation;
import net.namesake.social.Bond;
import net.namesake.social.Deed;
import net.namesake.social.DeedType;
import net.namesake.social.DialogueStats;
import net.namesake.social.Memories;
import net.namesake.social.Personality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private static final SimpleCommandExceptionType UNKNOWN_MODEL = new SimpleCommandExceptionType(
            Component.literal("Unknown player model. See PlayerModel for the five."));

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
                        // Session 10's instrument: which settlements are neighbours, and how much of
                        // the road between them exists as blocks. Read-only — the graph is derived
                        // from the settlement table on demand and nothing here builds anything.
                        .then(Commands.literal("roads")
                                .executes(NamesakeCommands::dumpRoads))
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
                        // The instrument session 06's exit criterion is read with: what one villager
                        // actually remembers, in order, with the day it happened on. Read-only.
                        .then(Commands.literal("deeds")
                                .executes(context -> dumpDeeds(context, nearestCarrier(context.getSource())))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(context -> dumpDeeds(context,
                                                EntityArgument.getEntity(context, "target")))))
                        // Session 07's two instruments over a *live* world. Read-only, derived from
                        // the registry and storing nothing, so they stay available outside a
                        // development environment — which is the point of them: they are how a real
                        // playthrough gets held against the simulation's numbers.
                        .then(Commands.literal("stats")
                                .executes(NamesakeCommands::dumpStats))
                        .then(Commands.literal("earnrate")
                                .executes(NamesakeCommands::dumpEarnRate))
                        // And the simulation itself. Development-only: it writes a report file to
                        // the server's working directory, which is an instrument's behaviour rather
                        // than a command's, and it exists to be run by whoever is tuning rather than
                        // by whoever is playing.
                        .then(Commands.literal("simulate")
                                .requires(NamesakeCommands::isDevelopment)
                                .executes(context -> simulate(context, 100, "ATTENTIVE"))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> simulate(context,
                                                IntegerArgumentType.getInteger(context, "days"),
                                                "ATTENTIVE"))
                                        .then(Commands.argument("model", StringArgumentType.word())
                                                .suggests((context, builder) ->
                                                        SharedSuggestionProvider.suggest(
                                                                java.util.Arrays.stream(PlayerModel.values())
                                                                        .map(Enum::name).toList(), builder))
                                                .executes(context -> simulate(context,
                                                        IntegerArgumentType.getInteger(context, "days"),
                                                        StringArgumentType.getString(context, "model"))))))
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
                + "\n  culture    " + culture
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

    /**
     * <b>Session 10's instrument.</b> Which settlements are neighbours, and how far the road between
     * them has actually been laid.
     *
     * <p>Two questions that look like one and are not, which is why they are two sections. The graph
     * is what a story crosses and it is arithmetic over the settlement table — it exists the moment a
     * second village is registered, whether or not a single block has been placed. The road is what
     * you can see, and it appears only in chunks somebody has loaded. A world where the first section
     * is populated and the second is empty is <b>working correctly</b>, and it is the state the
     * ship-or-kill test can pass in.
     */
    private static int dumpRoads(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        RoadGraph graph = Roads.graphOf(registry.settlements());
        List<String> rows = roadRows(registry.settlements().size(), graph,
                RoadNetwork.materialises(), RoadNetwork.progress(), RoadNetwork.unrouted());

        String report = String.join("\n", rows);
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug roads] {}", report.replace("\n", " |"));
        return graph.size();
    }

    /**
     * The rows {@code /namesake debug roads} prints.
     *
     * <p>Pure over its arguments so {@code CommandLayoutTest} can enumerate the states this command
     * can be in — a world with no settlements, one with a single village and therefore no
     * neighbours, a network with nothing laid, and one half built — rather than measuring whichever
     * one a fixture produces. That is the guard session 07 shipped three over-width absence branches
     * past, and taking {@link RoadProgress} rather than {@code RoadNetwork} is what makes it
     * reachable at all: that class names a block, so touching it outside a running game runs
     * Minecraft's registry bootstrap and throws.
     */
    static List<String> roadRows(int settlements, RoadGraph graph, boolean materialising,
                                 List<RoadProgress> progress, List<RoadEdge> unrouted) {
        List<String> rows = new ArrayList<>();
        rows.add("roads: " + graph.size() + " edge(s) over " + settlements + " settlement(s)");
        if (settlements < 2) {
            // Every section prints its own absence. One village has no neighbours and that is not a
            // failure of anything; saying so is the difference between that and a broken command.
            rows.add("  a road needs two settlements. Find another village.");
            return rows;
        }
        if (graph.edges().isEmpty()) {
            rows.add("  no two are neighbours: a third village sits between them.");
            return rows;
        }
        rows.add("  neighbours, from the relative-neighbourhood graph:");
        for (Map.Entry<Integer, int[]> entry : graph.adjacency().entrySet()) {
            StringBuilder line = new StringBuilder("    s").append(entry.getKey()).append(" ->");
            for (int neighbour : entry.getValue()) {
                line.append(' ').append(neighbour);
            }
            rows.add(clip(line.toString(), Reports.CHAT_WIDTH));
        }

        rows.add("  what has been laid as blocks:");
        if (!materialising) {
            // The distinction the command exists to make: the graph above is what a story crosses,
            // and it is unaffected by whether a single block was ever placed.
            rows.add("    blocks are off; gossip crosses the graph, not them");
            return rows;
        }
        if (progress.isEmpty() && unrouted.isEmpty()) {
            rows.add("    no road has been routed yet");
            return rows;
        }
        for (RoadProgress road : progress) {
            rows.add(road.buildable()
                    ? String.format(Locale.ROOT, "    %-9s %4d/%4d laid, %3d refused, %2dc x%.1f",
                    road.edge(), road.laid(), road.columns(), road.refused(), road.chunks(),
                    road.roughness())
                    : String.format(Locale.ROOT, "    %-9s %s", road.edge(),
                    road.routed() ? "no road: the terrain is too hard" : "no road: none was found"));
        }
        for (RoadEdge edge : unrouted) {
            rows.add(String.format(Locale.ROOT, "    %-9s waiting to be routed", edge));
        }
        return rows;
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
                .append(registry.bonds().size()).append(" bond(s) and ")
                .append(registry.memories().size()).append(" deed(s) across ")
                .append(registry.memories().holders()).append(" ring(s) in the world");
        if (viewer == null) {
            // Every section prints its own absence. Run from the console there is no "you".
            out.append("\n  (no viewer — run this as a player to see what they feel about you)");
        } else {
            int nameColumn = nameColumnFor(nearest);
            out.append("\n  ").append(pad("who", nameColumn)).append("trust warmth  fear   cap")
                    .append("   gift×  mem");
            for (Persona persona : nearest) {
                Bond bond = registry.bonds().at(persona.id(), viewer, day);
                out.append("\n  ").append(pad(nameOf(persona), nameColumn))
                        .append(String.format(Locale.ROOT, "%+5d %+6d %+5d", bond.trust(),
                                bond.warmth(), bond.fear()))
                        .append(String.format(Locale.ROOT, "   %d/%d/%d",
                                bond.gainedToday(Bond.TRUST), bond.gainedToday(Bond.WARMTH),
                                bond.gainedToday(Bond.FEAR)))
                        .append(String.format(Locale.ROOT, "  %.2f",
                                Personality.scale(persona, DeedType.GIFT_WANTED)))
                        // How many of the ring's slots they have filled. The column that makes a
                        // bond of +8 and a memory of one gift distinguishable from +8 and eight.
                        .append(String.format(Locale.ROOT, " %3d",
                                registry.memories().of(persona.id()).size()));
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

    // --- deeds ---------------------------------------------------------------------------------

    /**
     * One NPC's whole deed ring, <b>newest first</b>. What they actually remember.
     *
     * <p><b>This is what session 06's exit criterion is read with</b>, and the criterion that matters
     * is not the arithmetic — a unit test proves the ring holds thirty-two and drops duplicates. It
     * is the other one: <i>a villager who watched you do something last week can still tell you what
     * it was.</i> So the day column is first and it is an absolute in-game day rather than a
     * "3 days ago", because the question being asked is whether the thing is still there at all.
     *
     * <p>The {@code who} column says whether the deed happened <i>to</i> them or in front of them,
     * and it is derived rather than stored: a deed carries its own subject, so the ring needs no
     * second copy of it and no flag that could disagree with the struct it sits next to. That is
     * {@code DESIGN.md} §4 step 3's "the subject records it weighted higher", read the way session 05
     * already implemented it in {@code Deeds.deltaFor}.
     */
    private static int dumpDeeds(CommandContext<CommandSourceStack> context, Entity target)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        UUID personaId = PersonaLink.get().personaId(target).orElseThrow(NO_PERSONA::create);
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        Persona persona = registry.persona(personaId).orElseThrow(NO_PERSONA::create);
        int today = Deed.dayOf(source.getLevel());

        List<Memories.Slot> ring = registry.memories().slotsOf(personaId);
        String report = String.join("\n", deedRows(persona, ring, today));
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug deeds] {}", report);
        return ring.size();
    }

    /**
     * The rows of {@code /namesake debug deeds}, as strings.
     *
     * <p>Package-visible and taking values rather than a command context, for the reason
     * {@link #statRows} and {@link #earnRateRows} are: <b>every state this command can be in has to
     * be measurable, not the one a fixture happens to produce.</b> Session 07 shipped three lines
     * over the chat width because every guard it wrote measured a populated table, and the absence
     * branches — the ones a player meets first — were never rendered. {@code CommandLayoutTest}
     * enumerates the states this command has rather than sampling one.
     */
    static List<String> deedRows(Persona persona, List<Memories.Slot> ring, int today) {
        List<String> lines = new ArrayList<>();
        // The name on a line of its own, which is not a style choice. Session 03's layout budget
        // allows a 27-character full name, and the header that used to carry the name and the
        // counts together came to 64 characters for a real villager — over the chat width, in the
        // populated state, and invisible until session 08 made this method measurable at all.
        lines.add(nameOf(persona));
        lines.add(String.format(Locale.ROOT, "  %d of %d remembered, newest first, day %d",
                ring.size(), Memories.RING_CAPACITY, today));
        if (ring.isEmpty()) {
            // Every section prints its own absence — DESIGN.md §11. A ring that prints nothing at
            // all reads as a broken command rather than as a villager who has seen nothing.
            lines.add("  they have not seen anything happen. That is a real");
            lines.add("  answer, not an empty table.");
            return lines;
        }
        lines.add("  day  " + pad("deed", DEED_COLUMN) + pad("how", 8) + "by");
        for (int slot = ring.size() - 1; slot >= 0; slot--) {
            lines.add(describeDeed(ring.get(slot), persona, today));
        }
        return lines;
    }

    /**
     * The deed cell: the type, how many times, and what it was about — <b>sharing one budget.</b>
     *
     * <p>Session 09 put two new things on this row and the row had no room for either. The age
     * column paid for them: it was {@code today - day} printed next to {@code day}, which is a
     * subtraction the reader can do and six characters on every row to save it. What replaced it is
     * information the ring genuinely holds and nothing else can derive — <i>nine times</i>, and
     * <i>bread</i>.
     *
     * <p><b>One column rather than three is what makes the row's width unconditional.</b> Laid out
     * separately, the type, the count and the object each need their own worst case, and the worst
     * cases do not co-occur in anything the mod can currently emit — a repeat is first-hand by
     * construction, and an item only comes from a gift, which is always nominal severity. Budgeting
     * on that would be budgeting on an invariant a future deed type could break silently. Sharing one
     * clipped cell means the row is the same width whatever is in it.
     */
    static final int DEED_COLUMN = 24;

    /** Narrowest a name column may be, so a header of "who" still has a space after it. */
    private static final int MIN_NAME_COLUMN = 4;

    /**
     * One remembered deed, as a row under a header rather than as labelled prose.
     *
     * <p><b>Laid out against a width budget, because the first version was not.</b> It repeated
     * {@code day}, {@code settlement}, {@code severity} and {@code confidence} on every row, came to
     * ninety characters, and wrapped in the middle of a table — which reads as two rows rather than
     * one. Session 02 lost its placeholder greeting to the same thing and session 03 lost two name
     * grammars to it: <b>a string nobody has measured against the space it has to sit in.</b>
     * {@code CommandLayoutTest} now measures this one.
     *
     * <p>What paid for the width is that <b>three of those columns were noise</b>. Severity and
     * confidence are nominal on every deed anything currently emits, and a column reading
     * {@code 100} on every row for the whole of sessions 06 and 07 is not information. They appear
     * only when they are carrying some — which is also the moment they become the most interesting
     * thing on the row, so it is the right way round rather than a saving.
     */
    private static String describeDeed(Memories.Slot slot, Persona holder, int today) {
        Deed deed = slot.deed();
        // Four ways to hold a deed, and they are exclusive rather than four columns: a thing that
        // happened *to* you is never something you heard about, and a story you cannot attribute is
        // not one you watched. Session 08 turns the last two on, and this column plus the `by` one
        // next to it is what the owner's exit criterion asks to see on a screen — the difference
        // between a villager who saw it and one who was told, in a running game.
        String how = holder.id().equals(deed.subject()) ? "to them"
                : deed.confidence() == Deed.FIRST_HAND ? "saw it"
                : deed.isAttributed() ? "heard" : "rumour";

        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %3d  %s%s%s",
                deed.gameDay(),
                pad(deedCell(slot), DEED_COLUMN),
                pad(how, 8),
                // "Someone from the north" needs no field: the direction is a function of where the
                // deed happened, which the deed carries, and where the holder lives, which their
                // persona carries. Session 09's pool is what turns that into a sentence; here it is
                // enough to say plainly that nobody knows.
                deed.isAttributed() ? deed.actor().toString().substring(0, 8) : "nobody  "));

        if (deed.severity() != Deed.NOMINAL) {
            row.append(" s").append(deed.severity());
        }
        if (deed.confidence() != Deed.FIRST_HAND) {
            row.append(" c").append(deed.confidence());
        }
        if (deed.settlementId() != holder.settlementId()) {
            row.append(" @s").append(deed.settlementId());
        }
        return row.toString();
    }

    /**
     * What kind of deed, how many times, and what it was about — clipped to {@link #DEED_COLUMN}.
     *
     * <p>The order is the priority order, and it is stated because the clip is real. The type is
     * always shown; the count is at most five characters and is the difference between <i>you were
     * kind to me</i> and <i>you fed me, nine times, that day</i>; the object gets whatever is left,
     * and its namespace is dropped because {@code minecraft:} on every row is ten characters of
     * nothing. A count of one is not printed: {@code x1} on almost every row is the column reading
     * the same thing over and over, which is what session 06 stripped three columns off this table
     * for.
     */
    static String deedCell(Memories.Slot slot) {
        StringBuilder cell = new StringBuilder(slot.deed().type().name());
        if (!slot.happenedOnce()) {
            cell.append(" x").append(slot.repeats());
        }
        return cell.length() <= DEED_COLUMN ? cell.toString() : cell.substring(0, DEED_COLUMN);
    }

    // --- session 07: what this world holds, and how fast it got there -----------------------------

    /**
     * <b>What this world's social state actually looks like.</b>
     *
     * <p>The instrument LNK never had. It set skill gates at 35–205 against an observed maximum
     * affinity of 32, zero players ever reached the lowest gate, and nobody noticed for months —
     * because nothing in that codebase could answer "what do players actually earn". The
     * {@code max} column here is that question, and every threshold session 12 sets has to be read
     * against it before it is written down.
     *
     * <p>Derived from the registry and storing nothing. See {@link DialogueStats} for why that is a
     * ruling rather than an omission, and for the one thing it costs.
     */
    private static int dumpStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        UUID viewer = source.getEntity() == null ? null : source.getEntity().getUUID();
        int day = Deed.dayOf(source.getLevel());
        DialogueStats stats = DialogueStats.of(registry, viewer, day);

        List<String> lines = statRows(stats, registry, viewer);
        String report = String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug stats] {}", report);
        return stats.rings().size();
    }

    /**
     * The rows of {@code /namesake debug stats}, as strings.
     *
     * <p>Package-visible and taking values rather than a command context, so
     * {@code CommandLayoutTest} measures the real rows against the chat width directly rather than
     * through reflection or through a formula restated in the test. This project has shipped a table
     * that wrapped mid-row three times and every instrument in the repo reads these commands out of
     * a log file, which has no width.
     */
    static List<String> statRows(DialogueStats stats, NpcRegistry registry, UUID viewer) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "day %d - %d persona(s), %d settlement(s), %d bond(s)",
                stats.today(), stats.personas(), registry.settlements().size(),
                registry.bonds().size()));
        if (viewer == null) {
            // Every section prints its own absence — DESIGN.md §11's rule, applied early.
            lines.add("  (no viewer - run this as a player, not the console)");
        } else if (stats.metTheViewer() == 0) {
            lines.add("  nobody in this world has met you. That is a real answer.");
        } else {
            lines.add(String.format(Locale.ROOT, "  %d of %d have met you",
                    stats.metTheViewer(), stats.personas()));
            for (int axis = 0; axis < Bond.AXIS_COUNT; axis++) {
                lines.add(String.format(Locale.ROOT, "  %-8s max %4d  p50 %4d  min %4d",
                        Bond.AXIS_NAMES[axis], stats.observedMaximum(axis),
                        stats.percentile(axis, 50), stats.observedMinimum(axis)));
            }
        }

        lines.add(String.format(Locale.ROOT, "  rings    %d hold %d deed(s), %d full at %d",
                stats.rings().size(), stats.deedsHeld(), stats.fullRings(), Memories.RING_CAPACITY));
        stats.deepestRing().ifPresent(ring -> lines.add(String.format(Locale.ROOT,
                "  deepest  %s %d/%d back to day %d",
                clip(ring.name(), 20), ring.slots(), Memories.RING_CAPACITY, ring.oldestDay())));
        if (stats.rings().isEmpty()) {
            lines.add("  nobody remembers anything yet. Nothing has happened.");
        }
        for (Map.Entry<DeedType, Integer> entry : stats.deedMix()) {
            lines.add(String.format(Locale.ROOT, "    %-17s %5d", entry.getKey(), entry.getValue()));
        }
        return lines;
    }

    /**
     * <b>How fast this world's bonds are actually moving, in the unit session 12's thresholds are
     * written in.</b>
     *
     * <p>Warmth per in-game day of contact — {@link DialogueStats} rules the unit and says why the
     * two alternatives are worse. The second column is the same warmth per day <i>elapsed</i>, which
     * is what the decay bites into and what somebody who visits rarely actually experiences.
     *
     * <p><b>What it cannot see, said on the report rather than in a comment.</b> A ring holds
     * {@link Memories#RING_CAPACITY} deeds, so on a playthrough longer than that the days this can
     * count are fewer than the days that happened while the warmth is all of it — which makes a rate
     * read off a long save an over-estimate. The simulation knows the truth and
     * {@code Reports.ringTruncation} measures the gap.
     */
    private static int dumpEarnRate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        NpcRegistry registry = NpcRegistry.get(source.getServer());
        UUID viewer = source.getEntity() == null ? null : source.getEntity().getUUID();
        int day = Deed.dayOf(source.getLevel());
        DialogueStats stats = DialogueStats.of(registry, viewer, day);

        List<String> lines = earnRateRows(stats, viewer);
        String report = String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(report), false);
        Namesake.LOGGER.info("[debug earnrate] {}", report);
        return stats.metTheViewer();
    }

    /** The rows of {@code /namesake debug earnrate}. Measured by {@code CommandLayoutTest}. */
    static List<String> earnRateRows(DialogueStats stats, UUID viewer) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "day %d - warmth per in-game day of contact",
                stats.today()));
        if (viewer == null) {
            lines.add("  (no viewer - run this as a player)");
            return lines;
        }

        List<DialogueStats.Standing> met = stats.standings().stream()
                .filter(DialogueStats.Standing::hasMetTheViewer)
                .toList();
        if (met.isEmpty()) {
            lines.add("  nobody has met you, so nobody is earning. Go and be seen.");
            return lines;
        }

        int nameColumn = earnNameColumn(met);
        lines.add("  " + pad("who", nameColumn) + EARN_COLUMNS);
        for (DialogueStats.Standing standing : met) {
            lines.add(earnRateRow(standing, nameColumn));
        }

        float[] rates = stats.ratesPerContactDay();
        float best = rates.length == 0 ? 0F : rates[rates.length - 1];
        lines.add(String.format(Locale.ROOT, "  observed max warmth %d of 100",
                stats.observedMaximum(Bond.WARMTH)));
        lines.add("  a threshold above that is a band nobody is ever in.");
        lines.add("  contact days to reach, at the fastest rate here:");
        StringBuilder ladder = new StringBuilder("   ");
        for (int target : DialogueStats.LADDER) {
            int days = best <= 0F ? -1 : (int) Math.ceil(target / best);
            ladder.append(String.format(Locale.ROOT, " %d:%s", target, days < 0 ? "never" : days));
        }
        lines.add(ladder.toString());
        if (stats.rings().stream().anyMatch(DialogueStats.Ring::isFull)) {
            lines.add("  a full ring hides older contact, so these rates read high.");
        }
        return lines;
    }

    /**
     * How wide a name may be on an earn-rate row before it is clipped.
     *
     * <p>Session 03's layout budget allows a 27-character full name — fourteen given plus twelve
     * family plus a space — and four columns of numbers beside one of those does not fit sixty
     * characters. So the column measures the report (session 06's fix, which bought the bonds table
     * back under its ratchet) <i>and</i> is capped here, because a report is only as narrow as the
     * names that happen to be in it and the next village's names are not this one's.
     */
    private static final int EARN_NAME_MAX = 18;

    /** Everything to the right of the name on an earn-rate row. */
    private static final String EARN_COLUMNS =
            String.format(Locale.ROOT, "%6s %5s %9s %9s", "warm", "days", "/contact", "/elapsed");

    /**
     * How wide the name column is for this report: the widest name present, capped.
     *
     * <p>Package-visible, and taking the standings rather than the whole {@code DialogueStats}, so
     * {@code CommandLayoutTest} can hand it a villager with the longest name the generator can make
     * rather than whichever names a fixture happened to roll. That distinction is not academic — a
     * breakage that widened {@link #EARN_NAME_MAX} to 48 turned nothing red the first time it was
     * run, because no name in the fixture village was longer than eighteen characters.
     */
    static int earnNameColumn(List<DialogueStats.Standing> standings) {
        return Math.max(4, standings.stream()
                .mapToInt(standing -> Math.min(EARN_NAME_MAX, standing.name().length()))
                .max().orElse(4) + 1);
    }

    /** One earn-rate row. Measured by {@code CommandLayoutTest} at the widest name and number. */
    static String earnRateRow(DialogueStats.Standing standing, int nameColumn) {
        return "  " + pad(clip(standing.name(), EARN_NAME_MAX), nameColumn)
                + String.format(Locale.ROOT, "%6d %5d %9.2f %9.2f",
                standing.bond().warmth(), standing.contactDays(),
                standing.perContactDay(), standing.perElapsedDay());
    }

    /**
     * <b>Runs the headless simulation and hands back the report.</b> Session 07's exit criterion.
     *
     * <p>The chat gets a summary; the whole thing goes to a file, because the chronicle, the earn
     * rate table and the ring dump are all wider than chat and a table that wraps mid-row reads as
     * two rows. That is the defect this project has shipped three times.
     *
     * <p><b>It proves it did not touch this world, rather than asserting it.</b> The simulation
     * builds its own {@link NpcRegistry} with {@code new} and never hands it to a
     * {@code DimensionDataStorage}, so it has no path to disk at all — but "structurally cannot" is
     * a claim, and this session's whole discipline is to query rather than to claim. So the live
     * registry's counts are read before and after and printed either way.
     */
    private static int simulate(CommandContext<CommandSourceStack> context, int days, String modelName)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!Platform.get().isDevelopmentEnvironment()) {
            throw DEV_ONLY.create();
        }
        PlayerModel model;
        try {
            model = PlayerModel.valueOf(modelName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw UNKNOWN_MODEL.create();
        }

        NpcRegistry live = NpcRegistry.get(source.getServer());
        String before = live.size() + "/" + live.bonds().size() + "/" + live.memories().size();

        Simulation.Plan plan = Simulation.Plan.standard(
                source.getLevel().getSeed(), days, model);
        Simulation.Outcome outcome = Simulation.run(plan);

        List<String> file = new ArrayList<>(Reports.full(outcome));
        file.add("");
        file.addAll(Reports.propagation(plan));
        file.add("");
        file.addAll(Reports.crossSettlement(plan));
        file.add("");
        file.addAll(Reports.residencyWithAndWithoutGossip(plan));
        file.add("");
        file.addAll(Reports.acrossModels(plan));
        file.add("");
        file.addAll(Reports.witnessSensitivity(plan));
        file.add("");
        file.addAll(Reports.chronicleInFull(outcome));

        String after = live.size() + "/" + live.bonds().size() + "/" + live.memories().size();
        List<String> chat = new ArrayList<>(Reports.summary(outcome));
        chat.add(before.equals(after)
                ? "  this world is untouched: " + before + " personas/bonds/deeds"
                : "  THIS WORLD CHANGED: " + before + " -> " + after);

        try {
            Files.write(SIMULATION_REPORT, file);
            // The file NAME in chat and the absolute path in the log, which is the opposite of the
            // obvious way round and is the fourth time this project has shipped a line nobody
            // measured. An absolute path in a development worktree is 130 characters; chat wraps at
            // about sixty, so it arrives as three rows of a two-row message. The harness caught this
            // one, which is exactly what it is for — every unit test in the repo was green.
            chat.add(reportLine(SIMULATION_REPORT));
            Namesake.LOGGER.info("[debug simulate] report written to {}",
                    SIMULATION_REPORT.toAbsolutePath());
        } catch (IOException e) {
            Namesake.LOGGER.error("[debug simulate] could not write {}",
                    SIMULATION_REPORT.toAbsolutePath(), e);
            chat.add("  could not write the report file, see the log");
        }

        String summary = String.join("\n", chat);
        source.sendSuccess(() -> Component.literal(summary), false);
        Namesake.LOGGER.info("[debug simulate]\n{}", String.join("\n", file));
        return outcome.chronicle().size();
    }

    /** Where the full report lands. The run directory, exactly as the profiler's report does. */
    private static final Path SIMULATION_REPORT = Path.of("namesake-simulation-report.txt");

    /**
     * How chat is told where the report went.
     *
     * <p><b>The file name, never the path, and this is the fourth time this project has shipped the
     * lesson.</b> An absolute path in a development worktree is 130 characters; chat wraps at about
     * sixty, so a two-line message arrives as four rows with a directory split across two of them.
     * Every unit test in the repo was green when it shipped, and the harness caught it in a running
     * game — which is what the harness is for and why the assertion measures what the dispatcher
     * emits rather than what the builder returns.
     */
    static String reportLine(Path report) {
        // Thirty characters of file name leaves thirty for everything else, so the prose is as short
        // as it can be while still saying which of the two report files this is. The absolute path
        // goes to the log, where width costs nothing.
        return "  written to " + report.getFileName();
    }

    /**
     * How wide the name column has to be for <i>this</i> report, rather than for the widest name a
     * generator can produce.
     *
     * <p>Session 03's layout budget allows a full name of 27 characters — fourteen given plus twelve
     * family plus a space — and the bonds table was padding every row to 28 to allow for it. A real
     * village runs about nineteen, so nine characters of every row were being spent on nobody.
     * Nine columns of numbers beside a name is intrinsically wide and will wrap at some GUI scale
     * whatever is done here; this is the part that was free to stop paying for.
     */
    static int nameColumnFor(List<Persona> personas) {
        int widest = personas.stream().mapToInt(persona -> nameOf(persona).length()).max().orElse(0);
        return Math.max(MIN_NAME_COLUMN, widest + 1);
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

    private static String clip(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
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
