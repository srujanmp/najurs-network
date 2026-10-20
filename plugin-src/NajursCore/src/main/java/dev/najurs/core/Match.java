package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Round-based PvP in world `arena`: Arena Rounds (free-for-all) and Team Battle (Red vs Blue) share a pool of
 * 10 maps and a random kit theme everyone gets; Sumo and Spleef (fall = out). Players wait in a viewing area,
 * a countdown starts at 2+, the last one (or team) standing wins, then everyone is asked: rematch or leave.
 */
final class Match implements Listener {
    enum State { WAITING, COUNTDOWN, FIGHT, VOTING }

    static final class Arena {
        final String id, title; final ChatColor colour; final Location wait; final List<Location> spawns;
        final boolean deathOut; final double fallY; boolean teams;
        /** Team Battle only: 0 = Red, 1 = Blue. Kept across rounds and rebalanced at each start. */
        final Map<UUID, Integer> team = new HashMap<>();
        State state = State.WAITING;
        final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
        final Set<UUID> alive = new HashSet<>(), players = new HashSet<>(), rematch = new HashSet<>(), watchers = new HashSet<>();
        boolean suddenDone;
        /** kills this round, and how many floor-shrink steps have run (sumo/spleef). */
        final Map<UUID, Integer> kills = new HashMap<>(); int shrink;
        int timer; int round; long fightStart;
        final List<PvpMap> maps = new ArrayList<>(); PvpMap map; Theme theme;
        Arena(String id, String title, ChatColor colour, Location wait, List<Location> spawns, boolean deathOut, double fallY) {
            this.id = id; this.title = title; this.colour = colour; this.wait = wait; this.spawns = spawns; this.deathOut = deathOut; this.fallY = fallY;
        }
    }

    record PvpMap(String name, ChatColor colour, List<Location> spawns, double fallY, Location spectate) {}
    record Theme(String name, ChatColor colour, String[] items, String[] armor, PotionEffectType effect) {}

    /** Round kit themes: everyone in a round gets the same one, so it stays fair. Items use vanilla component syntax. */
    static final List<Theme> THEMES = List.of(
            new Theme("KNIGHTS", ChatColor.WHITE, new String[]{"iron_sword[enchantments={sharpness:1}]", "shield", "golden_apple 3", "cooked_beef 8"},
                    new String[]{"iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"}, null),
            new Theme("ARCHERS", ChatColor.GREEN, new String[]{"bow[enchantments={power:2,punch:1}]", "stone_sword", "golden_apple 2", "arrow 48"},
                    new String[]{"leather_helmet", "chainmail_chestplate", "leather_leggings", "leather_boots"}, null),
            new Theme("PIRATES", ChatColor.AQUA, new String[]{"trident[enchantments={loyalty:3,impaling:1}]", "stone_sword", "golden_apple 2", "cooked_cod 8"},
                    new String[]{"golden_helmet", "leather_chestplate", "chainmail_leggings", "golden_boots"}, null),
            new Theme("MACE & WIND", ChatColor.GRAY, new String[]{"mace[enchantments={density:1}]", "wind_charge 12", "golden_apple 2", "cooked_beef 8"},
                    new String[]{"iron_helmet", "chainmail_chestplate", "iron_leggings", "iron_boots[enchantments={feather_falling:4}]"}, null),
            new Theme("POTION BRAWL", ChatColor.LIGHT_PURPLE, new String[]{"stone_sword",
                    "splash_potion[potion_contents={potion:\"minecraft:harming\"}] 4", "splash_potion[potion_contents={potion:\"minecraft:healing\"}] 3",
                    "splash_potion[potion_contents={potion:\"minecraft:poison\"}] 2", "golden_apple 1"},
                    new String[]{"chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots"}, null),
            new Theme("NINJAS", ChatColor.DARK_PURPLE, new String[]{"iron_sword[enchantments={sharpness:2}]", "ender_pearl 3", "golden_apple 2", "cooked_beef 8"},
                    new String[]{"leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots"}, PotionEffectType.SPEED),
            new Theme("NETHERITE GODS", ChatColor.DARK_RED, new String[]{"netherite_sword", "golden_apple 4", "cooked_beef 8"},
                    new String[]{"netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots"}, null),
            new Theme("COWBOYS", ChatColor.GOLD, new String[]{"crossbow[enchantments={quick_charge:2,multishot:1}]",
                    "firework_rocket[fireworks={explosions:[{shape:\"large_ball\",colors:[I;16711680]}]}] 16", "arrow 32", "golden_apple 2"},
                    new String[]{"leather_helmet", "leather_chestplate", "chainmail_leggings", "leather_boots"}, null),
            new Theme("VIKINGS", ChatColor.BLUE, new String[]{"iron_axe", "shield", "golden_apple 3", "cooked_porkchop 8"},
                    new String[]{"chainmail_helmet", "iron_chestplate", "chainmail_leggings", "iron_boots"}, PotionEffectType.STRENGTH));

    record Vote(String arena) implements InventoryHolder { public Inventory getInventory() { return null; } }
    record KitMenu(String arena) implements InventoryHolder { public Inventory getInventory() { return null; } }

    static final List<String> KITS = List.of("warrior", "archer", "tank", "ninja");

    private final NajursCore plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> kit = new HashMap<>();
    private final Map<String, Integer> winStreak = new HashMap<>();   // "<arena>:<uuid>" -> consecutive round wins
    private final Map<UUID, Long> lastMove = new HashMap<>();   // AFK detection for queued players
    private final Map<UUID, Integer> specIdx = new HashMap<>();
    private final NamespacedKey key;

    Match(NajursCore plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "match_item"); }

    static List<Location> ring(World w, double cx, double y, double cz, double r, int n) {
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location l = new Location(w, cx + 0.5 + Math.cos(a) * r, y, cz + 0.5 + Math.sin(a) * r);
            l.setDirection(new org.bukkit.util.Vector(cx + 0.5 - l.getX(), 0, cz + 0.5 - l.getZ()));
            out.add(l);
        }
        return out;
    }

    void start() {
        World w = Bukkit.getWorld(PvP.WORLD);
        if (w == null) return;
        Arena col = new Arena("colosseum", "Arena Rounds", ChatColor.GOLD, new Location(w, 0.5, 71, 24.5, 180, 10), ring(w, 0, 65, 0, 14, 10), true, -999);
        List<PvpMap> pool = col.maps;
        pool.add(new PvpMap("Colosseum", ChatColor.GOLD, ring(w, 0, 65, 0, 14, 10), -999, new Location(w, 0.5, 71, 24.5, 180, 10)));
        pool.add(new PvpMap("Nether Pit", ChatColor.RED, ring(w, 300, 65, 0, 12, 10), -999, new Location(w, 300.5, 81, 0.5, 0, 60)));
        List<Location> sky = new ArrayList<>();
        for (int d : new int[]{45, 135, 225, 315}) for (double off : new double[]{-1.5, 1.5}) {
            double a = Math.toRadians(d);
            Location l = new Location(w, 600.5 + Math.cos(a) * 15 + off * Math.sin(a), 65, 0.5 + Math.sin(a) * 15 - off * Math.cos(a));
            l.setDirection(new org.bukkit.util.Vector(600.5 - l.getX(), 0, 0.5 - l.getZ())); sky.add(l);
        }
        pool.add(new PvpMap("Sky Ruins", ChatColor.AQUA, sky, 55, new Location(w, 600.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("Frozen Lake", ChatColor.WHITE, ring(w, 900, 65, 0, 13, 10), -999, new Location(w, 900.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("Jungle Temple", ChatColor.GREEN, ring(w, 1200, 65, 0, 14, 10), -999, new Location(w, 1200.5, 81, 0.5, 0, 60)));
        // built by tools/build/pvp_maps.py
        pool.add(new PvpMap("Cherry Grove", ChatColor.LIGHT_PURPLE, ring(w, 1500, 65, 0, 14, 10), -999, new Location(w, 1500.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("End Citadel", ChatColor.DARK_PURPLE, ring(w, 1800, 65, 0, 14, 10), 55, new Location(w, 1800.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("Sculk Depths", ChatColor.DARK_AQUA, ring(w, 2100, 65, 0, 14, 10), -999, new Location(w, 2100.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("Ocean Ruins", ChatColor.BLUE, ring(w, 2400, 65, 0, 13, 10), -999, new Location(w, 2400.5, 81, 0.5, 0, 60)));
        pool.add(new PvpMap("Mushroom Isle", ChatColor.RED, ring(w, 2700, 65, 0, 14, 10), -999, new Location(w, 2700.5, 81, 0.5, 0, 60)));
        arenas.put("colosseum", col);
        // Team Battle waits on the far side of the colosseum stands and draws from the same 10 maps
        Arena teams = new Arena("teams", "Team Battle", ChatColor.RED, new Location(w, 0.5, 71, -23.5, 0, 10), col.spawns, true, -999);
        teams.teams = true;
        teams.maps.addAll(pool);
        arenas.put("teams", teams);
        arenas.put("sumo", new Arena("sumo", "Sumo", ChatColor.WHITE, new Location(w, 100.5, 71, 14.5, 180, 25), ring(w, 100, 65, 0, 3.5, 8), false, 54));
        arenas.put("spleef", new Arena("spleef", "Spleef", ChatColor.AQUA, new Location(w, 0.5, 71, 114.5, 180, 20), ring(w, 0, 71, 100, 6, 10), false, 66));
        // glass viewing box beside the sumo platform (the colosseum uses its stands, spleef its ledge)
        plugin.console("execute in minecraft:arena run fill 97 70 12 103 70 17 white_stained_glass");
        plugin.console("execute in minecraft:arena run fill 97 71 12 103 72 17 barrier hollow");
        plugin.console("execute in minecraft:arena run fill 98 71 13 102 72 16 air");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // invisible rail along the colosseum wall top so watchers can't drop into the pit
        for (int deg = 0; deg < 360; deg++) {
            double r = Math.toRadians(deg);
            int x = (int) Math.floor(0.5 + Math.cos(r) * 23.4), z = (int) Math.floor(0.5 + Math.sin(r) * 23.4);
            for (int y = 71; y <= 72; y++) if (w.getBlockAt(x, y, z).getType().isAir()) w.getBlockAt(x, y, z).setType(Material.BARRIER);
        }
    }

    boolean handles(String modeId) { return arenas.containsKey(modeId); }
    boolean inMatch(Player p) { return arenaOf(p) != null; }
    boolean fighting(Player p) { return arenas.values().stream().anyMatch(a -> a.alive.contains(p.getUniqueId())); }
    Arena arenaOf(Player p) {
        UUID id = p.getUniqueId();
        for (Arena a : arenas.values()) if (a.queue.contains(id) || a.players.contains(id) || a.watchers.contains(id)) return a;
        return null;
    }

    /** Someone outside a round fell near this arena: put them in its viewing area. */
    void toWait(Player p, String id) {
        Arena a = arenas.get(id);
        if (a == null) return;
        p.setFallDistance(0);
        p.teleport(a.wait);
    }

    // ---------- joining / leaving ----------

    void join(Player p, String id) {
        Arena a = arenas.get(id);
        if (a == null) return;
        Arena old = arenaOf(p);
        if (old != null && old != a) leave(p, false);
        a.queue.add(p.getUniqueId());
        lastMove.put(p.getUniqueId(), System.currentTimeMillis());
        if (a.teams && !a.team.containsKey(p.getUniqueId())) a.team.put(p.getUniqueId(), smallerTeam(a));
        p.teleport(a.wait);
        resetPlayer(p);
        giveWaitingItems(p, a);
        if (a.teams) p.sendMessage(ChatColor.GRAY + "You're on " + teamName(a.team.get(p.getUniqueId())) + ChatColor.GRAY + " - teams are rebalanced at every round start.");
        p.sendTitle(a.colour + "" + ChatColor.BOLD + a.title.toUpperCase(), ChatColor.GRAY + (a.state == State.FIGHT ? "Round in progress - you're next" : "Waiting for players..."), 5, 40, 10);
        broadcast(a, ChatColor.GREEN + "+ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " joined " + a.title + " (" + a.queue.size() + " waiting)");
    }

    void leave(Player p, boolean toHub) {
        Arena a = arenaOf(p);
        if (a == null) return;
        a.queue.remove(p.getUniqueId());
        a.rematch.remove(p.getUniqueId());
        a.watchers.remove(p.getUniqueId());
        if (a.alive.remove(p.getUniqueId())) {
            plugin.pvp.recordDeath(a.id, p);
            broadcast(a, ChatColor.RED + "✖ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " left the round");
            checkEnd(a);
        }
        a.players.remove(p.getUniqueId());
        a.team.remove(p.getUniqueId());
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType()); // kit speed/slowness must not follow them out
        p.setFireTicks(0); p.setAllowFlight(false);
        if (toHub) p.performCommand("hub");
    }

    void resetPlayer(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20); p.setSaturation(20); p.setFireTicks(0); p.setFallDistance(0);
        p.setAllowFlight(false); p.setFlying(false);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    }

    // ---------- teams ----------

    static String teamName(int t) { return t == 0 ? ChatColor.RED + "" + ChatColor.BOLD + "RED" : ChatColor.BLUE + "" + ChatColor.BOLD + "BLUE"; }

    /** The team with fewer people among everyone queued or playing (ties go Red). */
    int smallerTeam(Arena a) {
        Set<UUID> in = new HashSet<>(a.queue); in.addAll(a.players);
        long red = in.stream().filter(u -> a.team.get(u) != null && a.team.get(u) == 0).count();
        long blue = in.stream().filter(u -> a.team.get(u) != null && a.team.get(u) == 1).count();
        return red <= blue ? 0 : 1;
    }

    /** Move players from the bigger team to the smaller until the sizes differ by at most one. */
    void balance(Arena a, List<Player> ps) {
        List<Player> red = new ArrayList<>(), blue = new ArrayList<>();
        for (Player p : ps) (a.team.getOrDefault(p.getUniqueId(), 0) == 0 ? red : blue).add(p);
        while (Math.abs(red.size() - blue.size()) > 1) {
            List<Player> from = red.size() > blue.size() ? red : blue, to = from == red ? blue : red;
            Player moved = from.remove(from.size() - 1); to.add(moved);   // the latest joiner moves
            a.team.put(moved.getUniqueId(), to == red ? 0 : 1);
            moved.sendMessage(ChatColor.YELLOW + "Teams balanced: you're now on " + teamName(to == red ? 0 : 1));
        }
    }

    Integer teamOf(Player p) {
        Arena a = arenaOf(p);
        return a != null && a.teams ? a.team.get(p.getUniqueId()) : null;
    }

    Set<Integer> aliveTeams(Arena a) {
        Set<Integer> t = new HashSet<>();
        for (UUID u : a.alive) t.add(a.team.getOrDefault(u, 0));
        return t;
    }

    ItemStack tagged(Material m, String action, String name, String... lore) {
        ItemStack it = NajursCore.icon(m, name, lore);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    void giveWaitingItems(Player p, Arena a) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        if (!a.maps.isEmpty()) inv.setItem(0, tagged(Material.FILLED_MAP, "status", "&6&lRandom map & kit every round", "&7" + a.maps.size() + " maps • " + THEMES.size() + " kit themes", "&7Everyone gets the same kit."));
        if (a.teams) {
            int t = a.team.getOrDefault(p.getUniqueId(), 0);
            inv.setItem(2, tagged(t == 0 ? Material.RED_BANNER : Material.BLUE_BANNER, "status", (t == 0 ? "&c&lYou're on RED" : "&9&lYou're on BLUE"), "&7Teams are balanced every round."));
        }
        if (a.state == State.FIGHT && a.map != null && !a.alive.contains(p.getUniqueId()))
            inv.setItem(1, tagged(Material.COMPASS, "spec", "&b&lWatch a player &7(right-click)", "&7Hops to the next fighter."));
        inv.setItem(4, tagged(Material.CLOCK, "status", "&e&l" + a.title + " &7(right-click for status)"));
        inv.setItem(8, tagged(Material.RED_BED, "leave", "&c&lLeave &7(right-click)"));
    }

    // ---------- round flow ----------

    private void tick() {
        for (Arena a : arenas.values()) {
            a.queue.removeIf(id -> { Player p = Bukkit.getPlayer(id); return p == null || !p.getWorld().getName().equals(PvP.WORLD); });
            a.watchers.removeIf(id -> { Player p = Bukkit.getPlayer(id); return p == null || !p.getWorld().getName().equals(PvP.WORLD); });
            if (a.state == State.WAITING || a.state == State.COUNTDOWN) for (Player p : online(a.queue))
                if (System.currentTimeMillis() - lastMove.getOrDefault(p.getUniqueId(), System.currentTimeMillis()) > 180_000) {
                    p.sendMessage(ChatColor.YELLOW + "Removed from " + a.title + " for being idle 3 minutes. Use /pvp to come back.");
                    leave(p, true);
                }
            switch (a.state) {
                case WAITING -> {
                    if (a.queue.size() >= 2) { a.state = State.COUNTDOWN; a.timer = a.round > 0 ? 8 : 15; }
                    else for (Player p : online(a.queue)) p.sendActionBar(a.colour + a.title + ChatColor.GRAY + " - waiting for players (" + a.queue.size() + "/2)");
                }
                case COUNTDOWN -> {
                    if (a.queue.size() < 2) { a.state = State.WAITING; broadcast(a, ChatColor.RED + "Not enough players - countdown cancelled."); continue; }
                    for (Player p : online(a.queue)) p.sendActionBar(a.colour + "" + ChatColor.BOLD + a.title + ChatColor.GRAY + " starts in " + ChatColor.WHITE + a.timer + "s" + ChatColor.GRAY + " (" + a.queue.size() + " players)");
                    if (a.timer <= 5 && a.timer > 0) for (Player p : online(a.queue)) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f); // the big 3-2-1 happens on the map
                    if (--a.timer < 0) begin(a);
                }
                case FIGHT -> {
                    long secs = Math.max(0, (System.currentTimeMillis() - a.fightStart) / 1000);
                    for (Player p : online(a.players)) p.sendActionBar(a.colour + "" + ChatColor.BOLD + a.title + ChatColor.GRAY + "  Round " + a.round + "  |  "
                            + ChatColor.GREEN + a.alive.size() + " alive" + ChatColor.GRAY + "  |  " + ChatColor.WHITE + secs / 60 + ":" + String.format("%02d", secs % 60));
                    if (secs >= 120 && !a.suddenDone) { a.suddenDone = true; suddenDeath(a); }
                    if ((a.id.equals("sumo") || a.id.equals("spleef")) && secs >= 30) { int want = (int) (secs - 30) / 10 + 1; while (a.shrink < want && a.shrink < 8) shrink(a, ++a.shrink); }
                    if (secs >= 300) { broadcast(a, ChatColor.RED + "Time's up - round ended in a draw."); a.alive.clear(); checkEnd(a); }
                }
                case VOTING -> { if (--a.timer <= 0) closeVote(a); }
            }
        }
    }

    private void begin(Arena a) {
        a.state = State.FIGHT;
        a.round++;
        clearShots(a);
        a.players.clear(); a.alive.clear(); a.rematch.clear(); a.suddenDone = false; a.kills.clear(); a.shrink = 0;
        rebuildFloor(a);
        List<Player> ps = online(a.queue);
        Collections.shuffle(ps, plugin.random);
        List<Location> spawns = a.spawns;
        if (!a.maps.isEmpty()) {
            List<PvpMap> pool = new ArrayList<>(a.maps);
            for (Arena other : arenas.values()) if (other != a && other.state == State.FIGHT) pool.remove(other.map); // two rounds never share a map
            if (pool.size() > 1 && a.map != null) pool.remove(a.map);   // never the same map twice in a row
            a.map = pool.get(plugin.random.nextInt(pool.size()));
            a.theme = THEMES.get(plugin.random.nextInt(THEMES.size()));
            spawns = a.map.spawns();
        }
        if (ps.size() > spawns.size()) ps = ps.subList(0, spawns.size());
        if (a.teams) {
            balance(a, ps);
            ps.sort(Comparator.comparingInt(p -> a.team.getOrDefault(p.getUniqueId(), 0)));  // reds first: they fill one half of the ring
        }
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            a.queue.remove(p.getUniqueId()); a.watchers.remove(p.getUniqueId());
            a.players.add(p.getUniqueId()); a.alive.add(p.getUniqueId());
            resetPlayer(p);
            p.teleport(spawnFor(a, spawns, ps, i));
            giveKit(p, a);
            if (a.teams) {
                int t = a.team.getOrDefault(p.getUniqueId(), 0);
                p.getInventory().setHelmet(new ItemStack(t == 0 ? Material.RED_BANNER : Material.BLUE_BANNER)); // same for everyone, so still fair
                p.sendMessage(ChatColor.GRAY + "You are " + teamName(t) + ChatColor.GRAY + ". Friendly fire is off.");
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 6, false, false));
            plugin.pvp.protect(p, 6000); // 3s countdown + 3s of grace: no spawn-kills, no fall damage, nothing dealt
            p.sendTitle(a.teams ? teamName(a.team.getOrDefault(p.getUniqueId(), 0)) + ChatColor.WHITE + " ROUND " + a.round : a.colour + "" + ChatColor.BOLD + "ROUND " + a.round, a.map == null ? ChatColor.GRAY + "Last one standing wins"
                    : a.map.colour() + a.map.name() + ChatColor.DARK_GRAY + "  •  " + a.theme.colour() + a.theme.name(), 0, 50, 10);
        }
        if (a.teams) { // 2v1 and the like: the smaller side gets extra hearts
            long red = ps.stream().filter(p -> a.team.getOrDefault(p.getUniqueId(), 0) == 0).count(), blue = ps.size() - red;
            if (red != blue) for (Player p : ps) if (a.team.getOrDefault(p.getUniqueId(), 0) == (red < blue ? 0 : 1)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 600, 1, false, false));
                p.sendMessage(ChatColor.GOLD + "Your team is outnumbered - bonus hearts!");
            }
        }
        for (int s = 3; s >= 1; s--) {
            int n = s;
            Bukkit.getScheduler().runTaskLater(plugin, () -> online(a.players).forEach(p -> { p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + n, "", 0, 22, 0); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + (3 - n) * 0.3f); }), (3 - s) * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            a.fightStart = System.currentTimeMillis();
            online(a.players).forEach(p -> { p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "FIGHT!", "", 0, 20, 5); p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1.2f); });
        }, 60L);
        a.fightStart = System.currentTimeMillis() + 3000;
        Bukkit.broadcastMessage(a.colour + "⚔ " + a.title + ChatColor.GRAY + " round " + a.round
                + (a.map == null ? "" : " on " + a.map.colour() + a.map.name() + ChatColor.GRAY + " (" + a.theme.colour() + a.theme.name() + ChatColor.GRAY + ")") + " started with " + ChatColor.WHITE + ps.size() + ChatColor.GRAY + " players. " + ChatColor.YELLOW + "/pvp" + ChatColor.GRAY + " to join the next one!");
    }

    /** Free-for-all spreads players round the ring; teams take opposite halves of it. */
    Location spawnFor(Arena a, List<Location> spawns, List<Player> ps, int i) {
        if (!a.teams) return spawns.get(i * spawns.size() / ps.size());
        int t = a.team.getOrDefault(ps.get(i).getUniqueId(), 0);
        List<Player> mates = ps.stream().filter(p -> a.team.getOrDefault(p.getUniqueId(), 0) == t).toList();
        int half = spawns.size() / 2, k = mates.indexOf(ps.get(i));
        return spawns.get(t * half + k * half / Math.max(1, mates.size()));
    }

    private void suddenDeath(Arena a) {
        for (Player p : online(a.alive)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 600, 0, false, false));
            if (a.id.equals("colosseum")) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 20 * 600, 0, false, false));
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "SUDDEN DEATH", ChatColor.GRAY + "Everyone glows" + (a.id.equals("colosseum") ? " and withers" : ""), 5, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
        }
    }

    void giveKit(Player p, Arena a) {
        PlayerInventory inv = p.getInventory();
        switch (a.id) {
            case "spleef" -> inv.setItem(0, Bukkit.getItemFactory().createItemStack("minecraft:diamond_shovel[can_break={blocks:\"minecraft:snow_block\"},enchantments={efficiency:5},unbreakable={}]"));
            case "sumo" -> {}
            default -> {
                if (a.theme != null) { themedKit(p, a.theme); return; }
                switch (kit.getOrDefault(p.getUniqueId(), "warrior")) {
                    case "archer" -> {
                        armor(inv, Material.LEATHER_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
                        inv.setItem(0, ench(Material.BOW, Enchantment.POWER, 2));
                        inv.setItem(1, new ItemStack(Material.STONE_SWORD));
                        inv.setItem(8, new ItemStack(Material.ARROW, 48));
                    }
                    case "tank" -> {
                        armor(inv, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                        inv.setItem(0, new ItemStack(Material.IRON_AXE));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 600, 0, false, false));
                    }
                    case "ninja" -> {
                        armor(inv, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
                        inv.setItem(0, ench(Material.IRON_SWORD, Enchantment.SHARPNESS, 2));
                        inv.setItem(1, new ItemStack(Material.ENDER_PEARL, 3));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 1, false, false));
                    }
                    default -> {
                        armor(inv, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                        inv.setItem(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 1));
                        inv.setItemInOffHand(new ItemStack(Material.SHIELD));
                    }
                }
                inv.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 2));
                inv.setItem(3, new ItemStack(Material.COOKED_BEEF, 8));
            }
        }
    }

    static void themedKit(Player p, Theme t) {
        PlayerInventory inv = p.getInventory();
        int slot = 0;
        for (String spec : t.items()) {
            String[] parts = spec.split(" (?=\\d+$)");
            ItemStack it = Bukkit.getItemFactory().createItemStack("minecraft:" + parts[0]);
            if (parts.length > 1) it.setAmount(Integer.parseInt(parts[1]));
            if (it.getType() == Material.SHIELD) inv.setItemInOffHand(it); else inv.setItem(slot++, it);
        }
        String[] ar = t.armor();
        inv.setHelmet(Bukkit.getItemFactory().createItemStack("minecraft:" + ar[0]));
        inv.setChestplate(Bukkit.getItemFactory().createItemStack("minecraft:" + ar[1]));
        inv.setLeggings(Bukkit.getItemFactory().createItemStack("minecraft:" + ar[2]));
        inv.setBoots(Bukkit.getItemFactory().createItemStack("minecraft:" + ar[3]));
        if (t.effect() != null) p.addPotionEffect(new PotionEffect(t.effect(), 20 * 600, t.effect() == PotionEffectType.SPEED ? 1 : 0, false, false));
    }

    static ItemStack ench(Material m, Enchantment e, int lvl) {
        ItemStack it = new ItemStack(m);
        it.addUnsafeEnchantment(e, lvl);
        return it;
    }

    static void armor(PlayerInventory inv, Material h, Material c, Material l, Material b) {
        inv.setHelmet(new ItemStack(h)); inv.setChestplate(new ItemStack(c)); inv.setLeggings(new ItemStack(l)); inv.setBoots(new ItemStack(b));
    }

    void eliminate(Player p, Player killer, String how) {
        Arena a = arenaOf(p);
        if (a == null || a.state != State.FIGHT || !a.alive.remove(p.getUniqueId())) return;
        if (killer != null && a.alive.contains(killer.getUniqueId())) {
            plugin.pvp.recordKill(a.id, killer, p, how);
            killReward(a, killer, a.kills.merge(killer.getUniqueId(), 1, Integer::sum));
        }
        else { plugin.pvp.recordDeath(a.id, p); broadcast(a, a.colour + "☠ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " is out"); }
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "ELIMINATED", ChatColor.GRAY + "Spectating until the round ends", 5, 40, 10);
        if (!a.teams && a.alive.size() == 2 && a.players.size() > 2) for (Player f : online(a.alive))
            f.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "FINAL 2", ChatColor.GRAY + "Last one standing wins", 0, 30, 10);
        Location watch = a.map != null ? a.map.spectate() : a.wait;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { resetPlayer(p); p.teleport(watch); giveWaitingItems(p, a); } }, 2L);
        checkEnd(a);
    }

    /** In-round streak rewards (arena rounds / team battle only). */
    private void killReward(Arena a, Player k, int n) {
        if (!a.deathOut || (n != 2 && n != 3)) return;
        if (n == 2) k.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE));
        else k.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 20, 1, false, false));
        k.playSound(k.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.8f);
        broadcast(a, ChatColor.GOLD + "✹ " + ChatColor.WHITE + k.getName() + ChatColor.YELLOW + " has " + n + " kills: " + (n == 2 ? "golden apple!" : "speed boost!"));
    }

    /** Arrows, pearls, tridents and potions from a finished round must not hit anyone in the next one. */
    private void clearShots(Arena a) {
        World w = Bukkit.getWorld(PvP.WORLD);
        if (w != null) for (org.bukkit.entity.Projectile pr : w.getEntitiesByClass(org.bukkit.entity.Projectile.class))
            if (pr.getShooter() instanceof Player s && (a.players.contains(s.getUniqueId()) || a.queue.contains(s.getUniqueId()))) pr.remove();
    }

    void checkEnd(Arena a) {
        if (a.state != State.FIGHT) return;
        if (a.teams) { if (aliveTeams(a).size() > 1) return; endTeams(a); return; }
        if (a.alive.size() > 1) return;
        Player winner = a.alive.isEmpty() ? null : Bukkit.getPlayer(a.alive.iterator().next());
        a.state = State.VOTING;
        a.timer = 15;
        if (winner != null) {
            plugin.pvp.recordWin(a.id, winner);
            Bukkit.broadcastMessage(a.colour + "★ " + ChatColor.WHITE + winner.getName() + ChatColor.YELLOW + " won " + a.title + " round " + a.round + "!");
            for (int i = 0; i < 4; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> firework(winner.getLocation()), i * 8L);
        }
        streaks(a, winner == null ? List.of() : List.of(winner));
        summary(a);
        for (Player p : online(a.players)) {
            p.playSound(p.getLocation(), winner == p ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.6f, 1f);
            p.sendTitle(winner == p ? ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!" : ChatColor.RED + "" + ChatColor.BOLD + "GAME OVER",
                    winner == null ? "" : ChatColor.WHITE + winner.getName() + ChatColor.GRAY + " wins", 5, 50, 10);
        }
        afterRound(a);
    }

    void endTeams(Arena a) {
        a.state = State.VOTING;
        a.timer = 15;
        Integer won = a.alive.isEmpty() ? null : a.team.getOrDefault(a.alive.iterator().next(), 0);
        List<Player> winners = online(a.players).stream().filter(p -> won != null && a.team.getOrDefault(p.getUniqueId(), 0).equals(won)).toList();
        if (won != null) {
            winners.forEach(p -> plugin.pvp.recordWin(a.id, p));
            Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + teamName(won) + ChatColor.YELLOW + " won Team Battle round " + a.round + "! "
                    + ChatColor.GRAY + "(" + String.join(", ", winners.stream().map(Player::getName).toList()) + ")");
            online(a.alive).forEach(p -> { for (int i = 0; i < 3; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> firework(p.getLocation()), i * 8L); });
        }
        streaks(a, winners);
        summary(a);
        for (Player p : online(a.players)) {
            boolean w = winners.contains(p);
            p.sendTitle(w ? ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!" : ChatColor.RED + "" + ChatColor.BOLD + "DEFEAT",
                    won == null ? "" : teamName(won) + ChatColor.GRAY + " wins", 5, 50, 10);
            p.playSound(p.getLocation(), w ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.6f, 1f);
        }
        afterRound(a);
    }

    /** Chat recap for everyone in the round: top killers, MVP first. */
    private void summary(Arena a) {
        if (a.kills.isEmpty()) return;
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(a.kills.entrySet());
        top.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "★ Round " + a.round + " MVP: ");
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            String n = Bukkit.getOfflinePlayer(top.get(i).getKey()).getName();
            sb.append(i == 0 ? ChatColor.WHITE + "" + ChatColor.BOLD : ChatColor.GRAY).append(n).append(ChatColor.YELLOW).append(" ").append(top.get(i).getValue()).append(i == 0 ? " kills" : "").append(i < Math.min(3, top.size()) - 1 ? ChatColor.DARK_GRAY + " | " : "");
        }
        broadcast(a, sb.toString());
    }

    /** Win streak per arena: winners +1 (announced from 2), everyone else in the round resets. */
    private void streaks(Arena a, Collection<Player> winners) {
        for (UUID u : a.players) {
            String k = a.id + ":" + u;
            if (winners.stream().noneMatch(w -> w.getUniqueId().equals(u))) { winStreak.remove(k); continue; }
            int s = winStreak.merge(k, 1, Integer::sum);
            if (s >= 2) Bukkit.broadcastMessage(ChatColor.GOLD + "✹ " + ChatColor.WHITE + Bukkit.getOfflinePlayer(u).getName() + ChatColor.YELLOW + " is on a "
                    + ChatColor.RED + ChatColor.BOLD + s + "-round win streak" + ChatColor.YELLOW + " in " + a.title + "!");
        }
    }

    /** Sumo: the platform tightens every 10s after 30s; Spleef: the snow floor loses an outer ring. Both end rounds before the timeout. */
    private void shrink(Arena a, int step) {
        for (Player p : online(a.alive)) {
            p.sendTitle("", ChatColor.RED + "The floor is shrinking!", 0, 25, 5);
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.6f);
        }
        if (a.id.equals("sumo")) shrinkSumo(Math.max(2, 6.5 - 0.6 * step));
        else spleefFloor(10 - step);
    }

    /** Full floor for a fresh round / idle arena (sumo disc r7, spleef snow disc r10; matches tools/build/arena_modes.py). */
    void rebuildFloor(Arena a) { if (a.id.equals("sumo")) shrinkSumo(7); else if (a.id.equals("spleef")) spleefFloor(10); }
    void rebuildFloors() { rebuildFloor(arenas.get("sumo")); rebuildFloor(arenas.get("spleef")); }

    /** Spleef (centre 0,100, y70): snow disc r10; cells outside the square ring `half` become air, inside are refilled where empty. */
    private void spleefFloor(int half) {
        World w = Bukkit.getWorld(PvP.WORLD);
        if (w == null) return;
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            if (x * x + z * z > 100) continue;
            Block b = w.getBlockAt(x, 70, 100 + z);
            if (Math.max(Math.abs(x), Math.abs(z)) > half) { if (b.getType() == Material.SNOW_BLOCK) b.setType(Material.AIR); }
            else if (b.getType() == Material.AIR) b.setType(Material.SNOW_BLOCK);
        }
    }

    /** Sumo platform (centre 100,0): stone base r7 at y63, concrete top r6 at y64 (red core, sea lanterns); everything beyond r is cut. */
    private void shrinkSumo(double r) {
        World w = Bukkit.getWorld(PvP.WORLD);
        if (w == null) return;
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            int d2 = x * x + z * z; boolean in = d2 <= r * r;
            if (d2 <= 49) w.getBlockAt(100 + x, 63, z).setType(in ? Material.STONE : Material.AIR);
            if (d2 <= 36) w.getBlockAt(100 + x, 64, z).setType(!in ? Material.AIR : d2 <= 2 ? Material.RED_CONCRETE
                    : (Math.abs(x) == 6 && z == 0 || x == 0 && Math.abs(z) == 6 || Math.abs(x) == 4 && Math.abs(z) == 4) ? Material.SEA_LANTERN : Material.WHITE_CONCRETE);
        }
    }

    private void afterRound(Arena a) {
        // winner gets a moment to celebrate, then everyone votes
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            clearShots(a);
            for (Player p : online(a.players)) { resetPlayer(p); p.teleport(a.wait); giveWaitingItems(p, a); } // everyone back to the stands
            a.alive.clear(); rebuildFloor(a);
            for (Player p : online(a.players)) openVote(p, a);
        }, 50L);
    }

    void openVote(Player p, Arena a) {
        if (Dialogs.supported(p)) { plugin.dialogs.vote(p, a); return; }
        Inventory inv = Bukkit.createInventory(new Vote(a.id), 27, ChatColor.DARK_GRAY + a.title + " - play again?");
        inv.setItem(11, NajursCore.icon(Material.LIME_CONCRETE, "&a&l⚔ REMATCH", "&7Queue for the next round", "&7(starts when 2+ players say yes)"));
        inv.setItem(13, NajursCore.icon(Material.CLOCK, "&e" + a.timer + "s to decide", "&7Not choosing = stay and watch"));
        inv.setItem(15, NajursCore.icon(Material.RED_CONCRETE, "&c&l✖ LEAVE", "&7Back to the hub"));
        p.openInventory(inv);
    }

    private void closeVote(Arena a) {
        for (Player p : online(a.players)) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Vote) p.closeInventory();
            if (!a.rematch.contains(p.getUniqueId()) && Dialogs.supported(p)) p.closeDialog();
            if (!a.rematch.contains(p.getUniqueId()) && !a.queue.contains(p.getUniqueId())) {
                a.watchers.add(p.getUniqueId());
                p.sendMessage(ChatColor.GRAY + "You're watching " + a.title + ". Right-click the " + ChatColor.GREEN + "clock" + ChatColor.GRAY + " to join the next round.");
            }
        }
        a.players.clear();
        a.state = State.WAITING;
    }

    // ---------- events ----------

    @EventHandler
    public void onVoteClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof Vote v) {
            e.setCancelled(true);
            Arena a = arenas.get(v.arena());
            if (e.getRawSlot() == 11) { p.closeInventory(); vote(p, a, true); }
            else if (e.getRawSlot() == 15) { p.closeInventory(); vote(p, a, false); }
        } else if (e.getInventory().getHolder() instanceof KitMenu) {
            e.setCancelled(true);
            int i = e.getRawSlot() - 10;
            if (i >= 0 && i < KITS.size() * 2 && i % 2 == 0) {
                kit.put(p.getUniqueId(), KITS.get(i / 2));
                p.closeInventory();
                p.sendMessage(ChatColor.GOLD + "Kit selected: " + ChatColor.WHITE + KITS.get(i / 2));
                Arena a = arenaOf(p);
                if (a != null && !a.alive.contains(p.getUniqueId())) giveWaitingItems(p, a);
            }
        }
    }

    /** Rematch (queue for the next round) or leave to the hub; from the vote dialog or chest. */
    void vote(Player p, Arena a, boolean rematch) {
        if (arenaOf(p) != a) return;
        if (!rematch) { leave(p, true); return; }
        a.rematch.add(p.getUniqueId()); a.queue.add(p.getUniqueId()); a.watchers.remove(p.getUniqueId());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        broadcast(a, ChatColor.GREEN + "⚔ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " wants a rematch (" + a.rematch.size() + ")");
        if (a.state == State.VOTING && a.rematch.containsAll(online(a.players).stream().map(Player::getUniqueId).toList())) a.timer = 1;
    }

    Arena arena(String id) { return arenas.get(id); }

    /** One-line live status for menus, e.g. "Fighting • 3 alive • 2 queued". */
    String status(String id) {
        Arena a = arenas.get(id);
        if (a == null) return "";
        String s = switch (a.state) {
            case WAITING -> a.queue.isEmpty() ? "Empty - be the first!" : a.queue.size() + " waiting";
            case COUNTDOWN -> "Starting in " + a.timer + "s • " + a.queue.size() + " ready";
            case FIGHT -> "Round " + a.round + (a.map != null ? " on " + a.map.name() : "") + " • " + a.alive.size() + " alive" + (a.queue.isEmpty() ? "" : " • " + a.queue.size() + " next");
            case VOTING -> "Rematch vote";
        };
        if (a.teams) {
            Set<UUID> in = new HashSet<>(a.queue); in.addAll(a.players);
            long red = in.stream().filter(u -> a.team.getOrDefault(u, 0) == 0).count();
            s += " • Red " + red + " vs Blue " + (in.size() - red);
        }
        return s;
    }

    int count(String id) {
        Arena a = arenas.get(id);
        if (a == null) return 0;
        Set<UUID> in = new HashSet<>(a.queue); in.addAll(a.players); in.addAll(a.watchers);
        return in.size();
    }

    void openKits(Player p) {
        Inventory inv = Bukkit.createInventory(new KitMenu("colosseum"), 27, ChatColor.GOLD + "Choose your kit");
        inv.setItem(10, NajursCore.icon(Material.DIAMOND_SWORD, "&f&lWarrior", "&7Iron armour, diamond sword, shield"));
        inv.setItem(12, NajursCore.icon(Material.BOW, "&a&lArcher", "&7Power II bow, 48 arrows, stone sword"));
        inv.setItem(14, NajursCore.icon(Material.DIAMOND_CHESTPLATE, "&b&lTank", "&7Full diamond, iron axe, but slower"));
        inv.setItem(16, NajursCore.icon(Material.ENDER_PEARL, "&5&lNinja", "&7Speed II, sharp iron sword, 3 pearls"));
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || e.getItem() == null || !e.getItem().hasItemMeta()) return;
        String act = e.getItem().getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (act == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Arena a = arenaOf(p);
        switch (act) {
            case "kit" -> openKits(p);
            case "leave" -> { leave(p, true); }
            case "spec" -> {
                if (a == null || a.state != State.FIGHT || a.alive.contains(p.getUniqueId())) return;
                List<Player> live = online(a.alive);
                if (live.isEmpty()) return;
                Player t = live.get(specIdx.merge(p.getUniqueId(), 1, Integer::sum) % live.size());
                p.setAllowFlight(true); p.setFlying(true);
                p.teleport(t.getLocation().add(0, 4, 0));
                p.sendActionBar(ChatColor.AQUA + "Watching " + ChatColor.WHITE + t.getName() + ChatColor.GRAY + " - click again for the next player");
            }
            case "status" -> {
                Arena at = a != null ? a : null;
                if (at == null) return;
                if (!at.queue.contains(p.getUniqueId()) && !at.alive.contains(p.getUniqueId())) {
                    at.queue.add(p.getUniqueId()); at.watchers.remove(p.getUniqueId());
                    p.sendMessage(ChatColor.GREEN + "You're in the queue for the next round.");
                }
                p.sendMessage(at.colour + at.title + ChatColor.GRAY + ": " + at.state.name().toLowerCase() + ", " + at.queue.size() + " queued, " + at.alive.size() + " alive");
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (arenaOf(e.getPlayer()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Arena a = arenaOf(p);
        if (a == null || !a.deathOut || !a.alive.contains(p.getUniqueId())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setDeathMessage(null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isDead()) p.spigot().respawn(); }, 1L); // skip the death screen
        Player k = p.getKiller() != null ? p.getKiller() : plugin.pvp.recentHitter(p);
        eliminate(p, k, "slayed");
    }

    /** Only fighters can hurt or be hurt; everyone waiting or watching is safe. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || arenaOf(p) == null) return;
        if (!fighting(p)) { e.setCancelled(true); return; }
        Arena pa = arenaOf(p);
        if (!pa.deathOut) { // sumo/spleef: you're out by falling, never by dying; hits still knock back
            if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) e.setDamage(0); else e.setCancelled(true);
        }
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent ed) {
            Player d = ed.getDamager() instanceof Player x ? x : ed.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player x ? x : null;
            if (d != null && !fighting(d)) e.setCancelled(true);
            else if (d != null && d != p && teamOf(d) != null && teamOf(d).equals(teamOf(p))) e.setCancelled(true); // friendly fire off
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Arena a = arenaOf(e.getPlayer());
        if (a != null) { e.setRespawnLocation(a.wait); Bukkit.getScheduler().runTaskLater(plugin, () -> giveWaitingItems(e.getPlayer(), a), 2L); }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) lastMove.put(p.getUniqueId(), System.currentTimeMillis());
        if (e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        Arena a = arenaOf(p);
        if (a == null) return;
        if (!a.alive.contains(p.getUniqueId())) { // spectators can't take damage, so a fall into the void would never end: bring them back
            if (e.getTo().getY() < 40 && p.getWorld().getName().equals(PvP.WORLD)) { p.setFallDistance(0); p.setVelocity(new org.bukkit.util.Vector()); p.teleport(a.map != null && a.players.contains(p.getUniqueId()) ? a.map.spectate() : a.wait); }
            return;
        }
        double fall = a.map != null ? a.map.fallY() : a.fallY;
        if (e.getTo().getY() >= fall) return;
        eliminate(p, plugin.pvp.recentHitter(p), switch (a.id) { case "sumo" -> "pushed"; case "spleef" -> "spleefed"; default -> "knocked off"; });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Arena a = arenaOf(p);
        leave(p, false);
        if (a != null) { resetPlayer(p); p.teleport(a.wait); }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!p.getWorld().getName().equals(PvP.WORLD)) { leave(p, false); return; }
        // arriving outside a round: drop any leftover round buttons from a previous visit
        if (arenaOf(p) == null) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack it = p.getInventory().getItem(i);
                if (it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING)) p.getInventory().setItem(i, null);
            }
        }, 3L);
    }

    // ---------- helpers ----------

    List<Player> online(Collection<UUID> ids) { return new ArrayList<>(ids.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList()); }

    void broadcast(Arena a, String msg) {
        Set<UUID> all = new HashSet<>(a.queue); all.addAll(a.players);
        for (Player p : online(all)) p.sendMessage(msg);
    }

    void firework(Location l) {
        Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.WHITE).flicker(true).build());
        m.setPower(1);
        fw.setFireworkMeta(m);
    }
}
