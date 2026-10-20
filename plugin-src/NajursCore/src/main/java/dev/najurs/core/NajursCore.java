package dev.najurs.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Survival worlds are registered in config.yml under `worlds.<id>`; each one is a Multiverse world
 * plus its _nether and _the_end, with its own Multiverse-Inventories group of the same id.
 * Flags per world: randomdrops, mobmorph, chaos.
 */
public final class NajursCore extends JavaPlugin implements Listener {
    static final Set<String> FLAGS = Set.of("randomdrops", "mobmorph", "chaos");
    static final List<String> TYPES = List.of("normal", "flat", "amplified", "large_biomes", "skyblock", "oneblock");
    /** Unmanaged worlds that /go remembers your position in. */
    static final Set<String> REMEMBERED = Set.of("creative");
    /** Fixed spots /go can send anyone to without Multiverse permissions: world, x, y, z, yaw. */
    static final Map<String, Object[]> SPOTS = Map.of(
            "arena", new Object[]{"arena", 0.5, 65.0, 19.5, 180f},
            "sumo", new Object[]{"arena", 100.5, 65.0, 4.5, 180f},
            "spleef", new Object[]{"arena", 0.5, 71.0, 114.5, 180f},
            "kbffa", new Object[]{"arena", -99.5, 65.0, 0.5, 0f},
            "parkour", new Object[]{"lobby", 0.5, 66.0, -31.0, 180f},
            "hub", new Object[]{"lobby", 0.5, 65.0, 8.5, 180f});
    static final Map<String, String> PRETTY = Map.ofEntries(
            Map.entry("lobby", "Hub"), Map.entry("arena", "PvP Arena"), Map.entry("bedwars", "BedWars"),
            Map.entry("hideseek", "Hide & Seek"), Map.entry("hs_horror", "Horror Hide & Seek"), Map.entry("creative", "Creative"),
            Map.entry("bskyblock_world", "Skyblock"), Map.entry("bskyblock_world_nether", "Skyblock Nether"),
            Map.entry("bskyblock_world_the_end", "Skyblock End"), Map.entry("oneblock_world", "OneBlock"),
            Map.entry("oneblock_world_nether", "OneBlock Nether"), Map.entry("oneblock_world_the_end", "OneBlock End"));

    final Random random = new Random();
    private final Map<String, List<Material>> dropTables = new HashMap<>();
    private List<Material> droppable;
    private File dataFile;
    YamlConfiguration data;
    Morph morph;
    Dashboard dashboard;
    Hub hub;
    Parkour parkour;
    PvP pvp;
    Match match;
    Boards boards;
    Dialogs dialogs;
    Nightfall nightfall;
    VoidWorlds voids;
    WorldState worldState;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().isConfigurationSection("worlds")) {
            // first run: adopt the existing survival world
            getConfig().set("worlds.survival.name", "Survival");
            getConfig().set("worlds.survival.flags", new ArrayList<String>());
            saveConfig();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        droppable = Arrays.stream(Material.values())
                .filter(m -> m.isItem() && !m.isAir() && !m.isLegacy() && !OPERATOR_ONLY.contains(m.name())
                        && !m.name().endsWith("_SPAWN_EGG") && !m.name().contains("COMMAND_BLOCK"))
                .toList();
        morph = new Morph(this);
        dashboard = new Dashboard(this);
        dialogs = new Dialogs(this);
        getServer().getPluginManager().registerEvents(this, this);
        hub = new Hub(this);
        parkour = new Parkour(this);
        getServer().getPluginManager().registerEvents(hub, this);
        getServer().getPluginManager().registerEvents(parkour, this);
        parkour.start();
        PvP pvp = new PvP(this);
        getServer().getPluginManager().registerEvents(pvp, this);
        pvp.start();
        this.pvp = pvp;
        match = new Match(this);
        getServer().getPluginManager().registerEvents(match, this);
        match.start();
        worldState = new WorldState(this);
        getServer().getPluginManager().registerEvents(worldState, this);
        worldState.start();
        voids = new VoidWorlds(this);
        getServer().getPluginManager().registerEvents(voids, this);
        nightfall = new Nightfall(this);
        getServer().getPluginManager().registerEvents(nightfall, this);
        nightfall.start();
        boards = new Boards(this);
        boards.start();
        if (Bukkit.getPluginManager().getPlugin("BedWars") != null) getServer().getPluginManager().registerEvents(new BedwarsHook(this), this);
        getServer().getPluginManager().registerEvents(morph, this);
        getServer().getPluginManager().registerEvents(dashboard, this);
        Chaos chaos = new Chaos(this); chaos.start(); getServer().getPluginManager().registerEvents(chaos, this);
        getServer().getScheduler().runTaskTimer(this, morph::tick, 20L * 10, 20L * 60);
        getServer().getScheduler().runTaskTimer(this, this::saveData, 20L * 300, 20L * 300);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) new Expansion(this).register();
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) rememberLocation(p, p.getLocation());
        saveData();
        if (worldState != null) worldState.save();
    }

    void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().warning("Could not save data.yml: " + e.getMessage()); }
    }

    static final Set<String> OPERATOR_ONLY = Set.of("BARRIER", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK", "TEST_BLOCK", "TEST_INSTANCE_BLOCK");

    // ---------- registry helpers ----------

    ConfigurationSection worlds() { return getConfig().getConfigurationSection("worlds"); }

    /** Registry id for any dimension of a managed world (sw_x_nether -> sw_x), or null. */
    String managedId(World w) {
        if (w == null) return null;
        String n = w.getName().replaceFirst("_(nether|the_end)$", "");
        return worlds().isConfigurationSection(n) ? n : null;
    }

    boolean hasFlag(World w, String flag) {
        String id = managedId(w);
        return id != null && worlds().getStringList(id + ".flags").contains(flag);
    }

    /** Human name for any world, used by the tab list / scoreboard placeholders. */
    String prettyName(World w) {
        String id = managedId(w);
        if (id != null) {
            String base = worlds().getString(id + ".name", id);
            return w.getName().endsWith("_nether") ? base + " Nether" : w.getName().endsWith("_the_end") ? base + " End" : base;
        }
        return PRETTY.getOrDefault(w.getName(), w.getName());
    }

    void console(String cmd) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "world" : s.substring(0, Math.min(s.length(), 20));
    }

    static String colour(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    // ---------- last location / starter kit ----------

    /** Key under which a location is remembered: the managed id, or the world name for REMEMBERED worlds. */
    String memoryKey(World w) {
        String id = managedId(w);
        return id != null ? id : REMEMBERED.contains(w.getName()) ? w.getName() : null;
    }

    void rememberLocation(Player p, Location loc) {
        String key = memoryKey(loc.getWorld());
        if (key != null) data.set("last." + p.getUniqueId() + "." + key, loc);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null && e.getFrom().getWorld() != e.getTo().getWorld()) rememberLocation(e.getPlayer(), e.getFrom());
    }

    /**
     * Some plugins only accept setup commands from an in-game op (Hide & Seek's `hs map save`). tools/provision.py
     * queues those in pending-op-commands.txt; the first op to join runs them, 20s apart, and the file is removed.
     */
    @EventHandler
    public void onOpJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        File f = new File(getDataFolder(), "pending-op-commands.txt");
        if (!p.isOp() || !f.exists()) return;
        List<String> cmds;
        try { cmds = Files.readAllLines(f.toPath()).stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList(); }
        catch (IOException ex) { return; }
        if (!f.delete()) return;
        p.sendMessage(ChatColor.GOLD + "Finishing first-time setup (" + cmds.size() + " steps, about " + cmds.size() * 20 + "s)...");
        for (int i = 0; i < cmds.size(); i++) {
            String c = cmds.get(i);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { getLogger().info("first-time setup: " + p.getName() + " runs /" + c); p.performCommand(c); } }, 100L + i * 400L);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.sendMessage(ChatColor.GREEN + "First-time setup done."); }, 140L + cmds.size() * 400L);
    }

    @EventHandler
    public void onQuitRemember(PlayerQuitEvent e) { rememberLocation(e.getPlayer(), e.getPlayer().getLocation()); }

    /** Send a player to a world: their last spot there if we remember one, else the world spawn. */
    void go(Player p, String key) {
        World w = Bukkit.getWorld(key);
        if (w == null) { p.sendMessage(ChatColor.RED + "That world is not loaded."); return; }
        Location last = data.getLocation("last." + p.getUniqueId() + "." + key);
        Location to = last != null && last.getWorld() != null ? last : w.getSpawnLocation().add(0.5, 0, 0.5);
        p.teleport(to);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        if (worlds().isConfigurationSection(key)) {
            String name = worlds().getString(key + ".name"), mode = modeLine(key);
            boolean same = name.equalsIgnoreCase(mode);
            p.sendTitle(ChatColor.GREEN + name, ChatColor.GRAY + (same ? "Survival" : mode), 5, 40, 10);
            p.sendMessage(ChatColor.GRAY + "You are in " + ChatColor.GREEN + name + (same ? "" : ChatColor.GRAY + " (" + mode + ")") + ChatColor.GRAY + ". Type " + ChatColor.AQUA + "/hub" + ChatColor.GRAY + " to leave.");
            if (!worlds().getStringList(key + ".flags").contains("randomdrops") && !VoidWorlds.TYPES.contains(worlds().getString(key + ".type", ""))) giveStarter(p, key);
        } else {
            p.sendTitle(ChatColor.YELLOW + prettyName(w), "", 5, 30, 10);
        }
    }

    String modeLine(String id) {
        List<String> f = worlds().getStringList(id + ".flags");
        String type = worlds().getString(id + ".type", "");
        String base = type.equals("skyblock") ? "Co-op Skyblock" : type.equals("oneblock") ? "Co-op OneBlock" : null;
        if (f.isEmpty()) return base != null ? base : "Vanilla survival";
        List<String> names = new ArrayList<>();
        if (base != null) names.add(base);
        for (String x : f) names.add(switch (x) {
            case "randomdrops" -> "Random Drops"; case "mobmorph" -> "Mob Morph";
            case "chaos" -> "Goose Chase"; default -> x; });
        return String.join(" + ", names);
    }

    /** One starter kit per player per survival world (given on their first visit). */
    void giveStarter(Player p, String id) {
        String path = "starter." + id;
        List<String> got = data.getStringList(path);
        if (got.contains(p.getUniqueId().toString())) return;
        got.add(p.getUniqueId().toString()); data.set(path, got);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (ItemStack it : List.of(new ItemStack(Material.STONE_SWORD), new ItemStack(Material.STONE_PICKAXE),
                    new ItemStack(Material.STONE_AXE), new ItemStack(Material.STONE_SHOVEL), new ItemStack(Material.BREAD, 16),
                    new ItemStack(Material.TORCH, 32), new ItemStack(Material.WHITE_BED), new ItemStack(Material.OAK_SAPLING, 4)))
                p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            p.sendMessage(ChatColor.GREEN + "You received a starter kit for " + worlds().getString(id + ".name") + ".");
        }, 20L); // after Multiverse-Inventories has swapped in this world's inventory
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equals("send")) return send(s, a);
        if (cmd.getName().equals("dashboard")) {
            if (s instanceof Player p && p.hasPermission("najurs.admin")) dialogs.dashboard(p);
            return true;
        }
        if (cmd.getName().equals("play")) { if (s instanceof Player p) dialogs.main(p); return true; }
        if (cmd.getName().equals("nightfall")) {
            if (s instanceof Player p) { if (a.length > 0 && p.hasPermission("najurs.admin")) nightfall.admin(p, a[0].toLowerCase(Locale.ROOT)); else nightfall.join(p); }
            return true;
        }
        if (cmd.getName().equals("pvp")) { if (s instanceof Player p) dialogs.pvp(p); return true; }
        if (cmd.getName().equals("hub")) {
            if (!(s instanceof Player p)) return true;
            String w = p.getWorld().getName();
            // leave minigames through their own command so the game state stays consistent
            if (w.equals("hideseek") || w.startsWith("hs2_")) p.performCommand("hs leave");
            else if (w.equals("hs_horror")) { p.performCommand("infection leave"); p.performCommand("hs leave"); }
            if (match.inMatch(p)) match.leave(p, false);
            p.performCommand("spawn");
            return true;
        }
        if (cmd.getName().equals("go")) {
            if (!(s instanceof Player p)) return true;
            if (a.length != 1) { p.sendMessage(ChatColor.RED + "/go <world>"); return true; }
            String dest = a[0].toLowerCase(Locale.ROOT);
            if (dest.equals("arena") || dest.equals("colosseum")) { match.join(p, "colosseum"); return true; }
            if (dest.equals("sumo") || dest.equals("spleef") || dest.equals("teams")) { match.join(p, dest); return true; }
            if (dest.equals("nightfall") || dest.equals("horror")) { nightfall.join(p); return true; }
            Object[] spot = SPOTS.get(dest);
            if (spot != null) {
                World w = Bukkit.getWorld((String) spot[0]);
                if (w == null) { p.sendMessage(ChatColor.RED + "That world is not loaded."); return true; }
                p.teleport(new Location(w, (double) spot[1], (double) spot[2], (double) spot[3], (float) spot[4], 0));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
                return true;
            }
            if (!worlds().isConfigurationSection(a[0]) && !REMEMBERED.contains(a[0])) { p.sendMessage(ChatColor.RED + "Unknown world " + a[0]); return true; }
            go(p, a[0]);
            return true;
        }
        if (a.length == 0) {
            if (s instanceof Player p) { if (Dialogs.supported(p)) dialogs.survival(p); else openMenu(p); } else list(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { list(s); return true; }
        if (sub.equals("join") && a.length >= 2 && s instanceof Player p) { join(p, a[1]); return true; }
        if (!s.hasPermission("najurs.admin")) { s.sendMessage(ChatColor.RED + "Only the owner can manage worlds."); return true; }
        switch (sub) {
            case "create" -> create(s, a);
            case "reload" -> { reloadConfig(); s.sendMessage(ChatColor.GREEN + "NajursCore config reloaded."); }
            case "setupvoid" -> {  // used by tools/najurs.py on a fresh clone: build the co-op island of a regenerated world
                if (a.length < 2 || !worlds().isConfigurationSection(a[1]) || !voids.isVoid(a[1]) || Bukkit.getWorld(a[1]) == null) { s.sendMessage(ChatColor.RED + "/worlds setupvoid <co-op world id>"); return true; }
                voids.setup(a[1]);
                s.sendMessage(ChatColor.GREEN + "Built the starting island of " + a[1]);
            }
            case "wavetime" -> {
                if (a.length < 2 || !a[1].matches("\\d+")) { s.sendMessage(ChatColor.RED + "/worlds wavetime <seconds>"); return true; }
                getConfig().set("chaos-wave-seconds", Math.max(10, Integer.parseInt(a[1]))); saveConfig();
                s.sendMessage(ChatColor.GREEN + "Goose Chase waves now every " + getConfig().getInt("chaos-wave-seconds") + "s.");
            }
            case "manage" -> {
                if (a.length < 2 || !worlds().isConfigurationSection(a[1]) || !(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "/worlds manage <id>"); return true; }
                openManage(p, a[1]);
            }
            case "reset" -> {
                if (a.length < 2 || !worlds().isConfigurationSection(a[1])) { s.sendMessage(ChatColor.RED + "/worlds reset <id> [newseed] confirm"); return true; }
                boolean newSeed = Arrays.asList(a).contains("newseed");
                if (!Arrays.asList(a).contains("confirm")) {
                    s.sendMessage(ChatColor.YELLOW + "This wipes " + a[1] + " (blocks, inventories, homes). Run /worlds reset " + a[1] + (newSeed ? " newseed" : "") + " confirm");
                    return true;
                }
                reset(s, a[1], newSeed);
            }
            case "sendall" -> {
                if (a.length < 2 || !worlds().isConfigurationSection(a[1])) { s.sendMessage(ChatColor.RED + "/worlds sendall <id>"); return true; }
                for (Player p : Bukkit.getOnlinePlayers()) go(p, a[1]);
                Bukkit.broadcastMessage(ChatColor.GOLD + "» " + ChatColor.YELLOW + "Everyone has been pulled into " + worlds().getString(a[1] + ".name") + "! " + ChatColor.GRAY + "Type /hub to leave.");
            }
            case "delete" -> {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "/worlds delete <id> confirm"); return true; }
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
                    s.sendMessage(ChatColor.YELLOW + "This permanently deletes " + a[1] + ". Run /worlds delete " + a[1] + " confirm");
                    return true;
                }
                delete(s, a[1]);
            }
            case "rename" -> {
                if (a.length < 3 || !worlds().isConfigurationSection(a[1])) { s.sendMessage(ChatColor.RED + "/worlds rename <id> <new name...>"); return true; }
                String name = String.join(" ", Arrays.copyOfRange(a, 2, a.length)).replace("\"", "");
                worlds().set(a[1] + ".name", name); saveConfig();
                for (String dim : dims(a[1])) console("mv modify " + dim + " set alias \"" + name + (dim.endsWith("_nether") ? " Nether" : dim.endsWith("_the_end") ? " End" : "") + "\"");
                s.sendMessage(ChatColor.GREEN + "Renamed " + a[1] + " to " + name);
            }
            case "flag" -> {
                if (a.length < 3 || !worlds().isConfigurationSection(a[1]) || !FLAGS.contains(a[2])) {
                    s.sendMessage(ChatColor.RED + "/worlds flag <id> <randomdrops|mobmorph|chaos>"); return true;
                }
                List<String> f = new ArrayList<>(worlds().getStringList(a[1] + ".flags"));
                boolean on = !f.remove(a[2]);
                if (on) f.add(a[2]);
                worlds().set(a[1] + ".flags", f); saveConfig();
                if (a[2].equals("mobmorph") && !on) dims(a[1]).stream().map(Bukkit::getWorld).filter(Objects::nonNull)
                        .flatMap(w -> w.getPlayers().stream()).forEach(morph::clear);
                s.sendMessage(ChatColor.GREEN + a[2] + " is now " + (on ? "ON" : "OFF") + " in " + a[1]);
            }
            default -> s.sendMessage(ChatColor.YELLOW + "/worlds create <name> [normal|flat|amplified|large_biomes] [randomdrops] [mobmorph] [chaos] [seed:<n>]\n"
                    + "/worlds reset <id> [newseed] confirm | /worlds sendall <id> | /worlds delete <id> confirm\n"
                    + "/worlds rename <id> <name> | /worlds flag <id> <flag> | /worlds list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equals("send")) return a.length == 1 ? Stream.concat(Stream.of("all"), Bukkit.getOnlinePlayers().stream().map(Player::getName)).toList() : List.of();
        if (cmd.getName().equals("go")) return a.length == 1 ? Stream.of(worlds().getKeys(false).stream(), REMEMBERED.stream(), SPOTS.keySet().stream(), Stream.of("teams", "nightfall")).flatMap(x -> x).toList() : List.of();
        if (a.length == 1) return List.of("create", "manage", "delete", "reset", "sendall", "rename", "flag", "list", "join");
        if (a[0].equals("reset") && a.length >= 3) return List.of("newseed", "confirm");
        if (a.length == 2 && !a[0].equals("create")) return new ArrayList<>(worlds().getKeys(false));
        if (a[0].equals("create") && a.length >= 3) return Stream.concat(TYPES.stream(), FLAGS.stream()).toList();
        if (a[0].equals("flag") && a.length == 3) return new ArrayList<>(FLAGS);
        return List.of();
    }

    List<String> dims(String id) { return List.of(id, id + "_nether", id + "_the_end"); }

    void list(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "Survival worlds:");
        for (String id : worlds().getKeys(false))
            s.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + id + ChatColor.GRAY + " \"" + worlds().getString(id + ".name") + "\" " + worlds().getStringList(id + ".flags"));
    }

    void join(Player p, String id) {
        if (!worlds().isConfigurationSection(id)) { p.sendMessage(ChatColor.RED + "No world " + id); return; }
        go(p, id);
    }

    void create(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(ChatColor.RED + "/worlds create <name> [type] [flags...] [seed:<n>]"); return; }
        String name = a[1].replace('_', ' ').replace("\"", "");
        String id = "sw_" + slug(a[1]);
        if (worlds().isConfigurationSection(id) || Bukkit.getWorld(id) != null) { s.sendMessage(ChatColor.RED + id + " already exists."); return; }
        String type = "normal"; String seed = null; List<String> flags = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            String t = a[i].toLowerCase(Locale.ROOT);
            if (TYPES.contains(t)) type = t;
            else if (FLAGS.contains(t)) flags.add(t);
            else if (t.startsWith("seed:")) seed = a[i].substring(5);
            else { s.sendMessage(ChatColor.RED + "Unknown option " + a[i]); return; }
        }
        createWorld(s, id, name, type, flags, seed);
    }

    void createWorld(CommandSender s, String id, String name, String type, List<String> flags, String seed) {
        s.sendMessage(ChatColor.YELLOW + "Creating " + name + " (" + id + ")... the server may pause for a few seconds.");
        String seedArg = seed == null ? "" : " --seed " + seed;
        console("mv create " + id + " normal " + (VoidWorlds.TYPES.contains(type) ? VoidWorlds.createArgs(type) : "--world-type " + type) + seedArg);
        console("mv create " + id + "_nether nether" + seedArg);
        console("mv create " + id + "_the_end the_end" + seedArg);
        worlds().set(id + ".name", name);
        worlds().set(id + ".type", type);
        worlds().set(id + ".flags", flags);
        worlds().set(id + ".created", System.currentTimeMillis());
        saveConfig();
        // Multiverse finishes creating worlds on a later tick, so configure them once they exist.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getWorld(id) == null) { s.sendMessage(ChatColor.RED + "Multiverse has not finished creating " + id + ", check the console."); return; }
            console("mvinv create-group " + id);
            console("mvinv add-worlds " + id + " " + String.join(",", dims(id)));
            console("mvinv add-shares " + id + " all");
            for (String dim : dims(id)) {
                console("mv modify " + dim + " set gamemode survival");
                console("mv modify " + dim + " set difficulty normal");
                console("mv modify " + dim + " set respawn-world " + id);
                console("mv modify " + dim + " set alias \"" + name + (dim.endsWith("_nether") ? " Nether" : dim.endsWith("_the_end") ? " End" : "") + "\"");
            }
            if (VoidWorlds.TYPES.contains(type)) voids.setup(id);
            s.sendMessage(ChatColor.GREEN + "Created " + name + "! Join from /worlds or /go " + id);
        }, 60L);
    }

    void delete(CommandSender s, String id) {
        if (!worlds().isConfigurationSection(id)) { s.sendMessage(ChatColor.RED + "No world " + id); return; }
        List<Path> folders = new ArrayList<>();
        for (String dim : dims(id)) {
            World w = Bukkit.getWorld(dim);
            if (w == null) continue;
            for (Player p : w.getPlayers()) p.performCommand("spawn");
            folders.add(w.getWorldFolder().toPath());
            console("mv remove " + dim);
        }
        console("mvinv delete-group " + id);
        worlds().set(id, null); saveConfig();
        data.set("starter." + id, null);
        for (String uuid : Optional.ofNullable(data.getConfigurationSection("last")).map(c -> c.getKeys(false)).orElse(Set.of()))
            data.set("last." + uuid + "." + id, null);   // a remembered spot in a missing world breaks data.yml loading
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            for (Path f : folders) {
                if (Bukkit.getWorld(f.getFileName().toString()) != null) continue; // still loaded: never delete a live world
                try (Stream<Path> walk = Files.walk(f)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException e) { getLogger().warning("Could not delete " + f + ": " + e.getMessage()); }
            }
        }, 40L);
        s.sendMessage(ChatColor.GREEN + "Deleted " + id + ".");
    }

    /**
     * Wipe a survival world (all three dimensions, inventories, homes-in-memory) and regenerate it with the
     * same id, name and modes. newSeed=false keeps the current seed.
     */
    void reset(CommandSender s, String id, boolean newSeed) {
        if (!worlds().isConfigurationSection(id) || Bukkit.getWorld(id) == null) { s.sendMessage(ChatColor.RED + "No loaded world " + id); return; }
        String name = worlds().getString(id + ".name", id), type = worlds().getString(id + ".type", "normal");
        List<String> flags = new ArrayList<>(worlds().getStringList(id + ".flags"));
        String seed = String.valueOf(newSeed ? random.nextLong() : Bukkit.getWorld(id).getSeed());
        World hub = Bukkit.getWorld(Hub.LOBBY);
        List<Path> folders = new ArrayList<>();
        for (String dim : dims(id)) {
            World w = Bukkit.getWorld(dim);
            if (w == null) continue;
            for (Player p : w.getPlayers()) {
                p.teleport(hub.getSpawnLocation().add(0.5, 0, 0.5));
                p.sendTitle(ChatColor.RED + name + " is resetting", ChatColor.GRAY + "Join again in a few seconds", 5, 60, 10);
            }
            folders.add(w.getWorldFolder().toPath());
            console("mv remove " + dim);
        }
        console("mvinv delete-group " + id);
        data.set("starter." + id, null);
        for (String uuid : Optional.ofNullable(data.getConfigurationSection("last")).map(c -> c.getKeys(false)).orElse(Set.of()))
            data.set("last." + uuid + "." + id, null);
        Bukkit.broadcastMessage(ChatColor.GOLD + "⟳ " + ChatColor.YELLOW + name + ChatColor.GRAY + " is being reset" + (newSeed ? " with a brand new seed" : "") + "...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            folders.add(getDataFolder().toPath().getParent().resolve("Multiverse-Inventories/groups/" + id));
            for (Path f : folders) {
                if (!Files.exists(f)) continue;
                try (Stream<Path> walk = Files.walk(f)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                catch (IOException e) { getLogger().warning("Could not delete " + f + ": " + e.getMessage()); }
            }
            Bukkit.getScheduler().runTask(this, () -> {
                console("mvinv reload");
                createWorld(s, id, name, type, flags, seed);
                Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.broadcastMessage(ChatColor.GREEN + "✔ " + name
                        + " has been reset! " + ChatColor.GRAY + "Join with /go " + id), 80L);
            });
        });
    }

    boolean send(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(ChatColor.RED + "/send <all|player> <command...>"); return true; }
        String command = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).replaceFirst("^/", "");
        Collection<? extends Player> targets = a[0].equalsIgnoreCase("all") ? Bukkit.getOnlinePlayers()
                : Optional.ofNullable(Bukkit.getPlayerExact(a[0])).map(List::of).orElse(List.of());
        if (targets.isEmpty()) { s.sendMessage(ChatColor.RED + "No such player online."); return true; }
        for (Player p : targets) p.performCommand(command);
        s.sendMessage(ChatColor.GREEN + "Sent " + targets.size() + " player(s): /" + command);
        return true;
    }

    // ---------- GUI ----------

    record Menu(String kind, String target) implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }

    static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(colour(name));
        meta.setLore(Arrays.stream(lore).map(NajursCore::colour).toList());
        it.setItemMeta(meta);
        return it;
    }

    static Material iconFor(List<String> flags) {
        return flags.contains("chaos") ? Material.TNT
                : flags.contains("mobmorph") ? Material.CREEPER_HEAD : flags.contains("randomdrops") ? Material.CHEST : Material.GRASS_BLOCK;
    }

    void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new Menu("list", null), 54, ChatColor.DARK_GREEN + "» Survival Worlds");
        boolean admin = p.hasPermission("najurs.admin");
        ItemStack pane = icon(Material.GREEN_STAINED_GLASS_PANE, " ");
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 48, 50, 51, 52, 53}) inv.setItem(i, pane);
        inv.setItem(4, icon(Material.FILLED_MAP, "&a&lPick a world", "&7Each world has its own nether,", "&7end, inventory and your last spot."));
        int slot = 10;
        for (String id : worlds().getKeys(false)) {
            if (slot > 43) break;
            List<String> flags = worlds().getStringList(id + ".flags");
            int players = dims(id).stream().map(Bukkit::getWorld).filter(Objects::nonNull).mapToInt(x -> x.getPlayers().size()).sum();
            List<String> lore = new ArrayList<>();
            lore.add("&8ID: " + id);
            lore.add("&7Mode: &f" + modeLine(id));
            lore.add("&7Players: &f" + players);
            lore.add("");
            lore.add("&e▶ Click to play");
            if (admin) lore.add("&c⚙ Shift-click: reset / new seed / pull everyone / delete");
            ItemStack it = icon(iconFor(flags), "&a&l" + worlds().getString(id + ".name"), lore.toArray(String[]::new));
            if (players > 0) it.setAmount(Math.min(players, 64));
            inv.setItem(slot, it);
            slot += (slot % 9 == 7) ? 3 : 1;
        }
        if (admin) inv.setItem(49, icon(Material.EMERALD, "&a&l+ New world",
                "&7Type in chat:", "&f/worlds create <name> [type] [modes] [seed:<n>]",
                "&7Types: &fnormal flat amplified large_biomes", "&7Modes: &frandomdrops mobmorph chaos"));
        inv.setItem(45, icon(Material.COMPASS, "&bBack to Hub"));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    void openManage(Player p, String id) {
        Inventory inv = Bukkit.createInventory(new Menu("manage", id), 27, ChatColor.DARK_RED + "Manage: " + worlds().getString(id + ".name"));
        List<String> flags = worlds().getStringList(id + ".flags");
        inv.setItem(10, icon(Material.ENDER_PEARL, "&aTeleport there"));
        inv.setItem(11, icon(Material.NAME_TAG, "&eRename", "&7Type: &f/worlds rename " + id + " <new name>"));
        inv.setItem(12, icon(Material.CHEST, "&6Random Drops: " + (flags.contains("randomdrops") ? "&aON" : "&cOFF"), "&7Click to toggle"));
        inv.setItem(13, icon(Material.CREEPER_HEAD, "&2Mob Morph: " + (flags.contains("mobmorph") ? "&aON" : "&cOFF"), "&7Click to toggle"));
        inv.setItem(15, icon(Material.TNT, "&cGoose Chase: " + (flags.contains("chaos") ? "&aON" : "&cOFF"), "&7Random events every minute", "&7Click to toggle"));
        inv.setItem(16, icon(Material.LAVA_BUCKET, "&c&lDelete world", "&7Permanently deletes the world,", "&7its nether, end and inventories.", "&cClick, then confirm"));
        inv.setItem(20, icon(Material.LIGHTNING_ROD, "&e&lPull everyone here", "&7Sends every online player", "&7into this world now."));
        inv.setItem(22, icon(Material.RECOVERY_COMPASS, "&6&lReset (same seed)", "&7Fresh copy of the same map.", "&7Wipes blocks, inventories, homes.", "&cClick, then confirm"));
        inv.setItem(23, icon(Material.END_CRYSTAL, "&d&lReset (NEW random seed)", "&7Brand new map, same modes.", "&7Wipes blocks, inventories, homes.", "&cClick, then confirm"));
        inv.setItem(18, icon(Material.ARROW, "&7Back"));
        p.openInventory(inv);
    }

    void openResetConfirm(Player p, String id, boolean newSeed) {
        Inventory inv = Bukkit.createInventory(new Menu(newSeed ? "resetnew" : "resetsame", id), 27, ChatColor.DARK_RED + "Reset " + id + (newSeed ? " (new seed)?" : "?"));
        inv.setItem(11, icon(Material.LIME_CONCRETE, "&a&lYES, reset it", "&7Everyone inside goes to the hub."));
        inv.setItem(15, icon(Material.RED_CONCRETE, "&c&lNo, keep it"));
        p.openInventory(inv);
    }

    void openConfirm(Player p, String id) {
        Inventory inv = Bukkit.createInventory(new Menu("confirm", id), 27, ChatColor.DARK_RED + "Delete " + id + "?");
        inv.setItem(11, icon(Material.LIME_CONCRETE, "&a&lYES, delete forever"));
        inv.setItem(15, icon(Material.RED_CONCRETE, "&c&lNo, keep it"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType().isAir() || e.getClickedInventory() != e.getView().getTopInventory()) return;
        boolean admin = p.hasPermission("najurs.admin");
        switch (menu.kind()) {
            case "list" -> {
                if (e.getSlot() == 45) { p.closeInventory(); p.performCommand("spawn"); return; }
                List<String> lore = it.getItemMeta().getLore();
                if (lore == null || lore.isEmpty() || !ChatColor.stripColor(lore.get(0)).startsWith("ID: ")) return;
                String id = ChatColor.stripColor(lore.get(0)).substring(4);
                if (e.isShiftClick() && admin) openManage(p, id);
                else { p.closeInventory(); join(p, id); }
            }
            case "manage" -> {
                if (!admin) return;
                String id = menu.target();
                switch (e.getSlot()) {
                    case 10 -> { p.closeInventory(); join(p, id); }
                    case 11 -> { p.closeInventory(); p.sendMessage(ChatColor.YELLOW + "Type: /worlds rename " + id + " <new name>"); }
                    case 12 -> { p.performCommand("worlds flag " + id + " randomdrops"); openManage(p, id); }
                    case 13 -> { p.performCommand("worlds flag " + id + " mobmorph"); openManage(p, id); }
                    case 15 -> { p.performCommand("worlds flag " + id + " chaos"); openManage(p, id); }
                    case 16 -> openConfirm(p, id);
                    case 20 -> { p.closeInventory(); p.performCommand("worlds sendall " + id); }
                    case 22 -> openResetConfirm(p, id, false);
                    case 23 -> openResetConfirm(p, id, true);
                    case 18 -> openMenu(p);
                }
            }
            case "confirm" -> {
                if (!admin) return;
                p.closeInventory();
                if (e.getSlot() == 11) delete(p, menu.target());
            }
            case "resetsame", "resetnew" -> {
                if (!admin) return;
                p.closeInventory();
                if (e.getSlot() == 11) reset(p, menu.target(), menu.kind().equals("resetnew"));
            }
        }
    }

    // ---------- Random Drops ----------

    /** Each block type maps to one random item, fixed per world seed so players can learn the table. */
    Material randomDrop(World w, Material block) {
        List<Material> table = dropTables.computeIfAbsent(w.getName(), k -> {
            List<Material> shuffled = new ArrayList<>(droppable);
            Collections.shuffle(shuffled, new Random(w.getSeed()));
            return shuffled;
        });
        return table.get(Math.floorMod(block.ordinal() * 31 + 7, table.size()));
    }

    Material randomItem() { return droppable.get(random.nextInt(droppable.size())); }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        World w = e.getBlock().getWorld();
        if (!hasFlag(w, "randomdrops") || e.getBlock().getType().isAir()) return;
        e.setDropItems(false);
        Material m = randomDrop(w, e.getBlock().getType());
        w.dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), new ItemStack(m, 1 + random.nextInt(Math.min(m.getMaxStackSize(), 3))));
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        World w = e.getEntity().getWorld();
        if (e.getEntity() instanceof Player || !hasFlag(w, "randomdrops")) return;
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(randomItem()));
    }

    // ---------- world change housekeeping ----------

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (hasFlag(e.getFrom(), "mobmorph") && !hasFlag(e.getPlayer().getWorld(), "mobmorph")) morph.clear(e.getPlayer());
    }

}
