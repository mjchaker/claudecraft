package dev.claudecraft.bot;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import dev.claudecraft.ClaudeCraftPlugin;
import dev.claudecraft.util.Sync;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bot's skills: everything the agent can do in the world. Each tool is
 * described to the model with a JSON schema; execution happens on the main
 * server thread via {@link Sync}.
 */
public final class BotTools {

    private final ClaudeCraftPlugin plugin;
    private final BotManager manager;
    private final AtomicInteger blockBudgetUsed = new AtomicInteger();

    public BotTools(ClaudeCraftPlugin plugin, BotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // Tool definitions (sent to the model)
    // ------------------------------------------------------------------

    public List<Tool> definitions() {
        List<Tool> tools = new ArrayList<>();

        tools.add(tool("observe",
                "Look around: returns your position, nearby players, a terrain heightmap of the "
                        + "surrounding columns (top solid block per column, run-length encoded), and a list "
                        + "of notable non-terrain blocks (existing builds). Call this before planning a build "
                        + "and again after major building steps to verify your work.",
                Map.of(),
                List.of()));

        tools.add(tool("move_to",
                "Walk/float to a position in the world. Use this to get within build range of the "
                        + "target area before placing blocks. Maximum 128 blocks per move.",
                Map.of("to", Map.of(
                        "type", "string",
                        "description", "Absolute target block coordinates as \"x,y,z\", e.g. \"120,64,-35\". "
                                + "Y should be the surface Y + 1 so you stand on the ground.")),
                List.of("to")));

        tools.add(tool("place_blocks",
                "Place a batch of blocks in the world. Use large batches (whole walls, floors, or "
                        + "layers in one call). Blocks must be within your build radius of "
                        + manager.maxBuildRadius() + " blocks; move_to first if the site is far away.",
                Map.of("blocks", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Each entry is \"x,y,z,MATERIAL\" with absolute block coordinates and a "
                                + "Bukkit material name, e.g. \"100,64,-20,OAK_PLANKS\".")),
                List.of("blocks")));

        tools.add(tool("break_blocks",
                "Remove blocks (set them to air). Use for clearing vegetation or terrain in the way, "
                        + "or fixing mistakes. Bedrock cannot be broken.",
                Map.of("blocks", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Each entry is \"x,y,z\" absolute block coordinates, e.g. \"100,65,-20\".")),
                List.of("blocks")));

        tools.add(tool("say",
                "Say something in the server chat as the bot. Use this to announce plans, progress "
                        + "on long builds, and the final result. Keep it to one or two short plain-text sentences.",
                Map.of("message", Map.of("type", "string", "description", "The chat message to send.")),
                List.of("message")));

        return tools;
    }

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            props.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .build())
                .build();
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Executes a tool call; always returns a human-readable result for the model. */
    public String execute(String name, JsonValue input) {
        try {
            return switch (name) {
                case "observe" -> observe();
                case "move_to" -> moveTo(input);
                case "place_blocks" -> placeBlocks(input);
                case "break_blocks" -> breakBlocks(input);
                case "say" -> say(input);
                default -> "ERROR: unknown tool " + name;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Bot tool '" + name + "' failed: " + e);
            return "ERROR: tool execution failed: " + e.getMessage();
        }
    }

    public void resetBudget() {
        blockBudgetUsed.set(0);
    }

    // ------------------------------------------------------------------
    // observe
    // ------------------------------------------------------------------

    private String observe() throws Exception {
        return Sync.call(plugin, () -> {
            LivingEntity bot = manager.getBot();
            if (bot == null) {
                return "ERROR: the bot entity no longer exists.";
            }
            Location loc = bot.getLocation();
            World w = loc.getWorld();
            int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();

            StringBuilder sb = new StringBuilder();
            sb.append("You are at x=").append(bx).append(" y=").append(by).append(" z=").append(bz)
                    .append(" in world '").append(w.getName()).append("'.");
            sb.append("\nTime: ").append(w.getTime() < 12300 ? "day" : "night")
                    .append(w.hasStorm() ? ", raining" : ", clear");

            sb.append("\nNearby players:");
            boolean anyPlayer = false;
            for (Player p : w.getPlayers()) {
                double d = p.getLocation().distance(loc);
                if (d <= 96) {
                    anyPlayer = true;
                    Location pl = p.getLocation();
                    sb.append("\n- ").append(p.getName())
                            .append(" at x=").append(pl.getBlockX())
                            .append(" y=").append(pl.getBlockY())
                            .append(" z=").append(pl.getBlockZ())
                            .append(" (").append((int) d).append(" blocks away)");
                }
            }
            if (!anyPlayer) {
                sb.append(" none within 96 blocks");
            }

            int r = 10;
            sb.append("\n\nTerrain heightmap, columns x=").append(bx - r).append("..").append(bx + r)
                    .append(", each row lists MATERIAL@surfaceY xN for N consecutive columns (west to east):");
            for (int dz = -r; dz <= r; dz++) {
                sb.append("\nz=").append(bz + dz).append(": ");
                String prev = null;
                int run = 0;
                StringBuilder row = new StringBuilder();
                for (int dx = -r; dx <= r; dx++) {
                    Block top = w.getHighestBlockAt(bx + dx, bz + dz);
                    String key = top.getType().name() + "@" + top.getY();
                    if (key.equals(prev)) {
                        run++;
                    } else {
                        appendRun(row, prev, run);
                        prev = key;
                        run = 1;
                    }
                }
                appendRun(row, prev, run);
                sb.append(row);
            }

            sb.append("\n\nNotable non-terrain blocks nearby (existing builds, within ")
                    .append(r).append(" blocks horizontally):");
            int listed = 0, skipped = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -6; dy <= 12; dy++) {
                        int y = by + dy;
                        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
                            continue;
                        }
                        Block b = w.getBlockAt(bx + dx, y, bz + dz);
                        Material m = b.getType();
                        if (m.isAir() || isNaturalTerrain(m)) {
                            continue;
                        }
                        if (listed < 150) {
                            sb.append("\n- ").append(m.name())
                                    .append(" at ").append(b.getX()).append(",").append(b.getY()).append(",").append(b.getZ());
                            listed++;
                        } else {
                            skipped++;
                        }
                    }
                }
            }
            if (listed == 0) {
                sb.append(" none");
            }
            if (skipped > 0) {
                sb.append("\n(").append(skipped).append(" more not listed)");
            }

            sb.append("\n\nBlock budget remaining for this task: ")
                    .append(Math.max(0, manager.maxBlocksPerTask() - blockBudgetUsed.get()));
            return sb.toString();
        });
    }

    private static void appendRun(StringBuilder row, String key, int run) {
        if (key == null || run == 0) {
            return;
        }
        if (row.length() > 0) {
            row.append(", ");
        }
        row.append(key).append(" x").append(run);
    }

    private static final Set<String> NATURAL_EXACT = Set.of(
            "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM", "MUD",
            "STONE", "DEEPSLATE", "TUFF", "ANDESITE", "DIORITE", "GRANITE", "CALCITE",
            "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE", "SAND", "RED_SAND", "SANDSTONE", "RED_SANDSTONE",
            "GRAVEL", "CLAY", "WATER", "LAVA", "SNOW", "SNOW_BLOCK", "POWDER_SNOW", "ICE",
            "PACKED_ICE", "BLUE_ICE", "BEDROCK", "OBSIDIAN", "NETHERRACK", "END_STONE",
            "MOSS_BLOCK", "MOSS_CARPET", "VINE", "GLOW_LICHEN", "LILY_PAD", "SUGAR_CANE",
            "CACTUS", "BAMBOO", "BAMBOO_SAPLING", "PUMPKIN", "MELON", "KELP", "KELP_PLANT",
            "SEAGRASS", "TALL_SEAGRASS", "DEAD_BUSH", "FERN", "LARGE_FERN",
            "DANDELION", "POPPY", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "SUNFLOWER",
            "LILAC", "PEONY", "ROSE_BUSH", "LILY_OF_THE_VALLEY", "BLUE_ORCHID", "ALLIUM",
            "BROWN_MUSHROOM", "RED_MUSHROOM", "SWEET_BERRY_BUSH", "COCOA", "SPORE_BLOSSOM",
            "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "HANGING_ROOTS", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS");

    private static boolean isNaturalTerrain(Material m) {
        String n = m.name();
        return NATURAL_EXACT.contains(n)
                || n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_SAPLING")
                || n.endsWith("_ORE") || n.endsWith("_TULIP") || n.contains("GRASS")
                || n.contains("AZALEA") || n.contains("CORAL") || n.endsWith("_ROOTS")
                || n.startsWith("CAVE_") || n.contains("AMETHYST");
    }

    // ------------------------------------------------------------------
    // move_to
    // ------------------------------------------------------------------

    private String moveTo(JsonValue input) throws Exception {
        int[] target = parseCoords(field(input, "to"));
        if (target == null) {
            return "ERROR: 'to' must be \"x,y,z\" with integer coordinates.";
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        Sync.run(plugin, () -> {
            LivingEntity bot = manager.getBot();
            if (bot == null) {
                done.complete("ERROR: the bot entity no longer exists.");
                return;
            }
            Location cur = bot.getLocation();
            World w = cur.getWorld();
            Location dest = new Location(w, target[0] + 0.5, target[1], target[2] + 0.5);
            if (cur.distance(dest) > 128) {
                done.complete("ERROR: target is " + (int) cur.distance(dest)
                        + " blocks away; maximum move distance is 128. Move in shorter hops.");
                return;
            }
            new BukkitRunnable() {
                int ticks = 0;

                @Override
                public void run() {
                    LivingEntity b = manager.getBot();
                    if (b == null) {
                        done.complete("ERROR: the bot entity disappeared while moving.");
                        cancel();
                        return;
                    }
                    Location now = b.getLocation();
                    Vector delta = dest.toVector().subtract(now.toVector());
                    double dist = delta.length();
                    if (dist < 0.7 || ticks++ > 400) {
                        b.teleport(dest);
                        done.complete("Arrived at x=" + target[0] + " y=" + target[1] + " z=" + target[2] + ".");
                        cancel();
                        return;
                    }
                    Vector step = delta.normalize().multiply(Math.min(0.6, dist));
                    Location next = now.clone().add(step);
                    next.setDirection(delta);
                    b.teleport(next);
                }
            }.runTaskTimer(plugin, 0L, 1L);
        });
        return done.get(30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // place_blocks / break_blocks
    // ------------------------------------------------------------------

    private record BlockSpec(int x, int y, int z, Material material) {}

    private String placeBlocks(JsonValue input) throws Exception {
        List<Object> entries = arrayField(input, "blocks");
        if (entries.isEmpty()) {
            return "ERROR: 'blocks' must be a non-empty array of \"x,y,z,MATERIAL\" strings.";
        }
        List<BlockSpec> specs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Object v : entries) {
            if (!(v instanceof String s)) {
                addError(errors, "non-string entry");
                continue;
            }
            String[] parts = s.split("[,\\s]+");
            if (parts.length != 4) {
                addError(errors, "bad format: " + s);
                continue;
            }
            Material mat = Material.matchMaterial(parts[3]);
            if (mat == null || !mat.isBlock()) {
                addError(errors, "unknown/unplaceable material: " + parts[3]);
                continue;
            }
            try {
                specs.add(new BlockSpec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), mat));
            } catch (NumberFormatException e) {
                addError(errors, "bad coordinates: " + s);
            }
        }

        int placed = Sync.call(plugin, () -> {
            LivingEntity bot = manager.getBot();
            if (bot == null) {
                return -1;
            }
            Location loc = bot.getLocation();
            World w = loc.getWorld();
            double radiusSq = (double) manager.maxBuildRadius() * manager.maxBuildRadius();
            int count = 0;
            for (BlockSpec spec : specs) {
                if (blockBudgetUsed.get() >= manager.maxBlocksPerTask()) {
                    addError(errors, "block budget for this task exhausted");
                    break;
                }
                if (spec.y() < w.getMinHeight() || spec.y() >= w.getMaxHeight()) {
                    addError(errors, "y out of world bounds: " + spec.y());
                    continue;
                }
                if (loc.distanceSquared(new Location(w, spec.x(), spec.y(), spec.z())) > radiusSq) {
                    addError(errors, "out of build radius: " + spec.x() + "," + spec.y() + "," + spec.z());
                    continue;
                }
                w.getBlockAt(spec.x(), spec.y(), spec.z()).setType(spec.material(), false);
                blockBudgetUsed.incrementAndGet();
                count++;
            }
            if (count > 0) {
                w.playSound(loc, Sound.BLOCK_STONE_PLACE, 1f, 1f);
            }
            return count;
        });

        if (placed < 0) {
            return "ERROR: the bot entity no longer exists.";
        }
        return summary("Placed", placed, errors);
    }

    private String breakBlocks(JsonValue input) throws Exception {
        if (!manager.allowBreaking()) {
            return "ERROR: breaking blocks is disabled by the server configuration.";
        }
        List<Object> entries = arrayField(input, "blocks");
        if (entries.isEmpty()) {
            return "ERROR: 'blocks' must be a non-empty array of \"x,y,z\" strings.";
        }
        List<int[]> coords = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Object v : entries) {
            int[] c = parseCoords(v instanceof String s ? s : null);
            if (c == null) {
                addError(errors, "bad entry (expected \"x,y,z\")");
            } else {
                coords.add(c);
            }
        }

        int broken = Sync.call(plugin, () -> {
            LivingEntity bot = manager.getBot();
            if (bot == null) {
                return -1;
            }
            Location loc = bot.getLocation();
            World w = loc.getWorld();
            double radiusSq = (double) manager.maxBuildRadius() * manager.maxBuildRadius();
            int count = 0;
            for (int[] c : coords) {
                if (blockBudgetUsed.get() >= manager.maxBlocksPerTask()) {
                    addError(errors, "block budget for this task exhausted");
                    break;
                }
                if (c[1] < w.getMinHeight() || c[1] >= w.getMaxHeight()) {
                    continue;
                }
                if (loc.distanceSquared(new Location(w, c[0], c[1], c[2])) > radiusSq) {
                    addError(errors, "out of build radius: " + c[0] + "," + c[1] + "," + c[2]);
                    continue;
                }
                Block b = w.getBlockAt(c[0], c[1], c[2]);
                if (b.getType() == Material.BEDROCK || b.getType().isAir()) {
                    continue;
                }
                b.setType(Material.AIR, false);
                blockBudgetUsed.incrementAndGet();
                count++;
            }
            if (count > 0) {
                w.playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 1f);
            }
            return count;
        });

        if (broken < 0) {
            return "ERROR: the bot entity no longer exists.";
        }
        return summary("Broke", broken, errors);
    }

    // ------------------------------------------------------------------
    // say
    // ------------------------------------------------------------------

    private String say(JsonValue input) {
        String message = field(input, "message");
        if (message == null || message.isBlank()) {
            return "ERROR: 'message' is required.";
        }
        manager.botSay(message);
        return "Said it in chat.";
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String summary(String verb, int count, List<String> errors) {
        StringBuilder sb = new StringBuilder(verb).append(" ").append(count).append(" blocks.");
        if (!errors.isEmpty()) {
            sb.append(" Issues: ").append(String.join("; ", errors)).append(".");
        }
        sb.append(" Block budget remaining: ")
                .append(Math.max(0, manager.maxBlocksPerTask() - blockBudgetUsed.get())).append(".");
        return sb.toString();
    }

    private static void addError(List<String> errors, String error) {
        if (errors.size() < 6) {
            errors.add(error);
        }
    }

    private static Map<String, Object> asMap(JsonValue input) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = input.convert(Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String field(JsonValue input, String name) {
        Object v = asMap(input).get(name);
        return v instanceof String s ? s : null;
    }

    private static List<Object> arrayField(JsonValue input, String name) {
        Object v = asMap(input).get(name);
        return v instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static int[] parseCoords(String s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.split("[,\\s]+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
