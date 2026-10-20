package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Shared co-op void worlds managed like any survival world (type `skyblock` or `oneblock` in config.yml):
 * everyone plays on one island together. Skyblock gets the classic starting island; OneBlock gets a single
 * magic block at 0,64,0 that turns into the next block of its phase every time it's broken.
 */
final class VoidWorlds implements Listener {
    static final Set<String> TYPES = Set.of("skyblock", "oneblock");
    static final String VOID = "{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:plains\"}";

    record Phase(String name, ChatColor colour, Material[] blocks, EntityType[] mobs, Material[] loot) {}

    static final List<Phase> PHASES = List.of(
            new Phase("Plains", ChatColor.GREEN, m("GRASS_BLOCK", "DIRT", "OAK_LOG", "OAK_LOG", "STONE", "COBBLESTONE", "OAK_LEAVES", "GRAVEL", "SAND", "BIRCH_LOG", "MELON", "PUMPKIN"),
                    e("COW", "SHEEP", "CHICKEN", "PIG"), m("OAK_SAPLING", "BIRCH_SAPLING", "WHEAT_SEEDS", "BREAD", "BONE_MEAL", "WATER_BUCKET", "TORCH")),
            new Phase("Underground", ChatColor.GRAY, m("STONE", "STONE", "COAL_ORE", "IRON_ORE", "COPPER_ORE", "ANDESITE", "DIORITE", "GRANITE", "GRAVEL", "LAPIS_ORE", "REDSTONE_ORE", "GOLD_ORE"),
                    e("ZOMBIE", "SKELETON", "SPIDER", "BAT"), m("IRON_INGOT", "COAL", "TORCH", "RAIL", "LAVA_BUCKET", "BUCKET")),
            new Phase("Ocean", ChatColor.AQUA, m("SAND", "CLAY", "GRAVEL", "PRISMARINE", "SEA_LANTERN", "SANDSTONE", "DARK_PRISMARINE", "SPONGE", "TUBE_CORAL_BLOCK"),
                    e("SQUID", "TURTLE", "DROWNED", "AXOLOTL"), m("WATER_BUCKET", "KELP", "TRIDENT", "NAUTILUS_SHELL", "PRISMARINE_SHARD", "HEART_OF_THE_SEA")),
            new Phase("Snowy Peaks", ChatColor.WHITE, m("SNOW_BLOCK", "ICE", "PACKED_ICE", "SPRUCE_LOG", "STONE", "EMERALD_ORE", "POWDER_SNOW", "BLUE_ICE", "CALCITE"),
                    e("GOAT", "FOX", "POLAR_BEAR", "STRAY"), m("SPRUCE_SAPLING", "SNOWBALL", "LEATHER_BOOTS", "EMERALD", "SWEET_BERRIES")),
            new Phase("Jungle", ChatColor.DARK_GREEN, m("JUNGLE_LOG", "JUNGLE_LEAVES", "MOSS_BLOCK", "BAMBOO_BLOCK", "MELON", "MUD", "DIAMOND_ORE"),
                    e("PARROT", "OCELOT", "PANDA", "CREEPER"), m("JUNGLE_SAPLING", "COCOA_BEANS", "BAMBOO", "MELON_SEEDS", "DIAMOND")),
            new Phase("Desert", ChatColor.YELLOW, m("SAND", "SANDSTONE", "RED_SAND", "TERRACOTTA", "GOLD_ORE", "SMOOTH_SANDSTONE", "SUSPICIOUS_SAND"),
                    e("HUSK", "RABBIT", "CAMEL"), m("CACTUS", "GOLD_INGOT", "TNT", "BRUSH", "SUGAR_CANE")),
            new Phase("The Nether", ChatColor.RED, m("NETHERRACK", "NETHERRACK", "SOUL_SAND", "GLOWSTONE", "NETHER_QUARTZ_ORE", "MAGMA_BLOCK", "NETHER_GOLD_ORE", "BLACKSTONE", "CRIMSON_STEM", "ANCIENT_DEBRIS", "OBSIDIAN"),
                    e("PIGLIN", "BLAZE", "MAGMA_CUBE", "STRIDER"), m("BLAZE_ROD", "NETHER_WART", "OBSIDIAN", "FLINT_AND_STEEL", "GOLD_INGOT", "ENDER_PEARL")),
            new Phase("Deep Dark", ChatColor.DARK_AQUA, m("DEEPSLATE", "SCULK", "SCULK_CATALYST", "DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_IRON_ORE", "REINFORCED_DEEPSLATE", "SCULK_SENSOR", "AMETHYST_BLOCK"),
                    e("ENDERMAN", "SKELETON", "ZOMBIE"), m("ECHO_SHARD", "DIAMOND", "AMETHYST_SHARD", "ENDER_PEARL", "EXPERIENCE_BOTTLE")),
            new Phase("The End", ChatColor.LIGHT_PURPLE, m("END_STONE", "END_STONE", "OBSIDIAN", "PURPUR_BLOCK", "END_STONE_BRICKS", "SHULKER_BOX", "CRYING_OBSIDIAN"),
                    e("ENDERMAN", "SHULKER", "ENDERMITE"), m("ENDER_EYE", "ENDER_PEARL", "CHORUS_FRUIT", "SHULKER_SHELL", "ELYTRA", "DRAGON_BREATH")));
    static final int PER_PHASE = 100;

    static Material[] m(String... n) { return Arrays.stream(n).map(Material::valueOf).toArray(Material[]::new); }
    static EntityType[] e(String... n) { return Arrays.stream(n).map(EntityType::valueOf).toArray(EntityType[]::new); }

    private final NajursCore plugin;
    private final Map<String, org.bukkit.boss.BossBar> bars = new HashMap<>();   // OneBlock phase bar per world
    VoidWorlds(NajursCore plugin) { this.plugin = plugin; }

    /** Phase name + progress to the next phase / treasure chest. */
    void updateBar(String id, World w) {
        int n = plugin.worlds().getInt(id + ".count");
        Phase p = phase(n);
        boolean last = n / PER_PHASE >= PHASES.size() - 1;
        org.bukkit.boss.BossBar b = bars.computeIfAbsent(id, k -> Bukkit.createBossBar("", org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SEGMENTED_10));
        b.setTitle(p.colour() + "" + ChatColor.BOLD + p.name() + ChatColor.GRAY + "  block " + ChatColor.WHITE + (last ? n : n % PER_PHASE + "/" + PER_PHASE) + ChatColor.GRAY + "  treasure in " + ChatColor.WHITE + (25 - n % 25));
        b.setProgress(last ? 1 : (n % PER_PHASE) / (double) PER_PHASE);
        for (Player x : w.getPlayers()) b.addPlayer(x);
    }

    @EventHandler
    public void onWorld(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        bars.values().forEach(b -> b.removePlayer(p));
        String id = plugin.managedId(p.getWorld());
        if (id == null || !isVoid(id) || !p.getWorld().getName().equals(id)) return;
        boolean one = type(id).equals("oneblock");
        if (one) updateBar(id, p.getWorld());
        String seen = "coop.seen." + p.getUniqueId() + "." + id;
        if (plugin.data.getBoolean(seen)) return;
        plugin.data.set(seen, true);
        p.sendMessage(ChatColor.GOLD + "✦ Co-op island: " + ChatColor.GRAY + "everyone shares this one island, so work together. "
                + (one ? "Break the " + ChatColor.WHITE + "magic block" + ChatColor.GRAY + " in the middle again and again: it turns into the next block, a new phase starts every " + PER_PHASE + " blocks, and every 25th is a treasure chest. Drops go straight to you."
                       : "Use the chest, grow a tree, build out. Falling off sends you back to the island. Don't lose the lava bucket!"));
        p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + (one ? "ONEBLOCK" : "SKYBLOCK"), ChatColor.GRAY + (one ? "Break the block in the middle" : "Build up from your island"), 10, 60, 10);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { bars.values().forEach(b -> b.removePlayer(e.getPlayer())); }

    /** Falling into the void respawns you on the island, not in the hub (unless you have a bed). */
    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        World w = e.getPlayer().getWorld();
        String id = plugin.managedId(w);
        if (id != null && isVoid(id) && w.getName().equals(id) && !e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(w.getSpawnLocation());
    }

    String type(String id) { return plugin.worlds().getString(id + ".type", "normal"); }
    boolean isVoid(String id) { return TYPES.contains(type(id)); }

    /** The `mv create` options for the overworld of a co-op world. */
    static String createArgs(String type) { return "--world-type flat --no-structures --generator-settings " + VOID; }

    /** Build the starting island once Multiverse has made the world. */
    void setup(String id) {
        World w = Bukkit.getWorld(id);
        if (w == null) return;
        String t = type(id);
        plugin.console("execute in minecraft:" + id + " run gamerule keep_inventory true");
        if (t.equals("oneblock")) {
            plugin.worlds().set(id + ".count", 0); plugin.saveConfig();
            w.getBlockAt(0, 64, 0).setType(Material.GRASS_BLOCK);
            w.getBlockAt(0, 63, 0).setType(Material.BEDROCK);
            plugin.console("mvsetspawn " + id + ":0.5,65,0.5,0,0 --unsafe");
        } else {
            // classic L-shaped island with a tree and a chest
            for (int x = -1; x <= 4; x++) for (int z = -1; z <= 4; z++) {
                if (x > 1 && z > 1) continue;
                for (int y = 61; y <= 63; y++) w.getBlockAt(x, y, z).setType(y == 63 ? Material.GRASS_BLOCK : Material.DIRT);
            }
            w.getBlockAt(0, 60, 0).setType(Material.BEDROCK);
            w.generateTree(new Location(w, 4, 64, -1), org.bukkit.TreeType.TREE);
            Block c = w.getBlockAt(0, 64, 4);
            c.setType(Material.CHEST);
            if (c.getState() instanceof Chest chest) chest.getInventory().addItem(new ItemStack(Material.LAVA_BUCKET), new ItemStack(Material.ICE, 2),
                    new ItemStack(Material.MELON_SLICE), new ItemStack(Material.PUMPKIN_SEEDS), new ItemStack(Material.SUGAR_CANE), new ItemStack(Material.RED_MUSHROOM),
                    new ItemStack(Material.BROWN_MUSHROOM), new ItemStack(Material.BONE, 3), new ItemStack(Material.CACTUS), new ItemStack(Material.OAK_SAPLING, 2));
            plugin.console("mvsetspawn " + id + ":0.5,64,0.5,0,0 --unsafe");
        }
    }

    Phase phase(int count) { return PHASES.get(Math.min(PHASES.size() - 1, count / PER_PHASE)); }

    String progress(String id) {
        int n = plugin.worlds().getInt(id + ".count");
        Phase p = phase(n);
        return p.name() + " (" + n + " blocks)";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        String id = plugin.managedId(b.getWorld());
        if (id == null || !b.getWorld().getName().equals(id) || !type(id).equals("oneblock") || b.getX() != 0 || b.getY() != 64 || b.getZ() != 0) return;
        // drops would roll off the block into the void, so they go straight to the breaker (Random Drops handles its own)
        if (!plugin.hasFlag(b.getWorld(), "randomdrops")) {
            e.setDropItems(false);
            if (b.getState() instanceof Chest chest) {   // treasure chest: loot to the player too
                for (ItemStack it : chest.getInventory().getContents()) if (it != null)
                    e.getPlayer().getInventory().addItem(it).values().forEach(left -> e.getPlayer().getWorld().dropItem(e.getPlayer().getLocation(), left));
                chest.getInventory().clear();
            }
            for (ItemStack it : b.getDrops(e.getPlayer().getInventory().getItemInMainHand(), e.getPlayer()))
                e.getPlayer().getInventory().addItem(it).values().forEach(left -> e.getPlayer().getWorld().dropItem(e.getPlayer().getLocation(), left));
        }
        int n = plugin.worlds().getInt(id + ".count") + 1;
        plugin.worlds().set(id + ".count", n);
        if (n % 20 == 0) plugin.saveConfig();
        Phase before = phase(n - 1), p = phase(n);
        Random r = plugin.random;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (n % 25 == 0) {   // treasure
                b.setType(Material.CHEST);
                if (b.getState() instanceof Chest chest)
                    for (int i = 0; i < 4; i++) chest.getInventory().addItem(new ItemStack(p.loot()[r.nextInt(p.loot().length)], 1 + r.nextInt(3)));
            } else b.setType(p.blocks()[r.nextInt(p.blocks().length)]);
            if (r.nextInt(12) == 0) b.getWorld().spawnEntity(b.getLocation().add(0.5, 1.2, 0.5), p.mobs()[r.nextInt(p.mobs().length)]);
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1, 0.5), 8, 0.4, 0.3, 0.4);
            if (p != before) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.WHITE + plugin.managedId(b.getWorld()) + ChatColor.YELLOW + " reached the " + p.colour() + ChatColor.BOLD + p.name() + ChatColor.YELLOW + " phase (" + n + " blocks)!");
                for (int i = 0; i < 3; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.match.firework(b.getLocation().add(0.5, 1, 0.5)), i * 8L);
            }
            if (n % 25 == 0) for (Player x : b.getWorld().getPlayers()) x.playSound(x.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.2f);   // treasure
            if (p != before) for (Player x : b.getWorld().getPlayers()) {
                x.sendTitle(p.colour() + "" + ChatColor.BOLD + p.name(), ChatColor.GRAY + "New OneBlock phase!", 5, 50, 10);
                x.playSound(x.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
            }
            updateBar(id, b.getWorld());
        });
    }
}
