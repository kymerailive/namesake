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
import net.namesake.npc.NpcRegistry;
import net.namesake.npc.NpcSchema;
import net.namesake.npc.Persona;
import net.namesake.npc.PersonaService;
import net.namesake.platform.Platform;
import net.namesake.platform.PersonaLink;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
            Component.literal("Refused: this deletes personas whose entities are merely unloaded. "
                    + "Development environments only."));

    private static final SimpleCommandExceptionType UNKNOWN_AXIS = new SimpleCommandExceptionType(
            Component.literal("Unknown trait axis."));

    private static final double SEARCH_RADIUS = 16.0;

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
                        .then(Commands.literal("settrait")
                                .then(Commands.argument("axis", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(Persona.TRAIT_NAMES, builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
                                                .executes(context -> setTrait(context, nearestCarrier(context.getSource())))
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(context -> setTrait(context,
                                                                EntityArgument.getEntity(context, "target")))))))
                        .then(Commands.literal("prune")
                                .executes(NamesakeCommands::prune))));
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
        String header = "persona " + persona.id();
        String body = "  entity     " + target.getUUID()
                + " (" + EntityType.getKey(target.getType()) + ")"
                + "\n  bound to   " + boundEntity + (target.getUUID().equals(boundEntity) ? " (match)" : " (MISMATCH)")
                + "\n  settlement " + persona.settlementId() + "  household " + persona.householdId()
                + "\n  culture    " + persona.cultureId() + "  profession " + persona.professionId()
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
