package dev.najurs.core;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;

/**
 * NIGHTFALL: horror party games in Blackwood Manor (world `nightfall`, built by tools/build/nightfall.py).
 * Everyone waits in the lobby above the manor; when 2+ are there a random game (never the same twice) starts:
 *  - The Impostor: crew do tasks at stations, impostors kill, bodies are reported, meetings vote people out.
 *  - Blackout Escape: survivors repair generators and escape through the gates while hunters stalk them.
 *  - Soul Candles: free-for-all candle collecting while ghosts and rivals steal them.
 *  - Silent Relics: the whole group brings relics to the hall while a real Warden hunts by sound.
 * The manor has no light at all: torches carried in either hand light the area around you (moving light blocks).
 */
final class Nightfall implements Listener {
    static final String WORLD = "nightfall";
    enum State { LOBBY, COUNTDOWN, PLAYING, MEETING, ENDING }
    enum Game {
        IMPOSTOR("The Impostor", ChatColor.RED, 3, "Crew: finish your tasks or vote out the impostor. Impostor: kill them all."),
        ESCAPE("Blackout Escape", ChatColor.GOLD, 2, "Survivors: fix 4 generators, then escape through a gate. Hunter: catch them."),
        CANDLES("Soul Candles", ChatColor.AQUA, 2, "Grab the most soul candles in 3 minutes. Ghosts and rivals steal them."),
        RELICS("Silent Relics", ChatColor.DARK_AQUA, 1, "Bring 5 relics to the hall table together. A Warden hunts by sound - sneak!");
        final String title; final ChatColor colour; final int min; final String rules;
        Game(String t, ChatColor c, int m, String r) { title = t; colour = c; min = m; rules = r; }
    }

    private final NajursCore plugin;
    private final NamespacedKey key;
    private final Random rnd;
    private YamlConfiguration layout;
    World world;

    State state = State.LOBBY;
    Game game, last;
    int timer;                       // countdown / ending seconds
    long endsAt;
    final Set<UUID> players = new LinkedHashSet<>(), alive = new HashSet<>();
    final Map<UUID, String> role = new HashMap<>();
    private BossBar bar;
    private final List<Entity> spawned = new ArrayList<>();

    // moving light
    private final Map<UUID, Block> lightAt = new HashMap<>();
    private final Set<UUID> torchOut = new HashSet<>();
    private long lightsOutUntil;
    private static final BlockData LIGHT = Bukkit.createBlockData("minecraft:light[level=13]");

    // station work (hold still for a few seconds)
    record Work(Map<?, ?> station, int need, Runnable done) {}
    private final Map<UUID, Work> working = new HashMap<>();
    private final Map<UUID, Integer> progress = new HashMap<>();

    // impostor
    private final Map<UUID, List<Map<?, ?>>> tasks = new HashMap<>();
    private int tasksTotal, tasksDone;
    private final Map<UUID, Long> killReady = new HashMap<>(), sabotageReady = new HashMap<>();
    private final Set<UUID> calledMeeting = new HashSet<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private long meetingEnds;
    private static final UUID SKIP = new UUID(0, 0);

    // escape
    private final Map<Integer, Integer> genWork = new HashMap<>();
    private final Set<Integer> gensDone = new HashSet<>();
    private final Map<UUID, Integer> hits = new HashMap<>();
    private final Set<UUID> escaped = new HashSet<>();
    private boolean gatesOpen;
    static final int GEN_NEEDED = 4, GEN_WORK = 45;

    // candles / relics
    private final Map<UUID, Integer> score = new HashMap<>();
    private final Map<UUID, Long> stealReady = new HashMap<>();
    private int relicsIn;
    private Warden warden;

    Nightfall(NajursCore plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "nf"); this.rnd = plugin.random; }

    void start() {
        world = Bukkit.getWorld(WORLD);
        File f = new File(plugin.getDataFolder(), "nightfall.yml");
        if (world == null || !f.exists()) { plugin.getLogger().warning("Nightfall disabled: world or nightfall.yml missing"); return; }
        layout = YamlConfiguration.loadConfiguration(f);
        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::lights, 5L, 3L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::workTick, 5L, 5L);
        cleanupWorld();
    }

    boolean enabled() { return layout != null; }

    // ---------- layout helpers ----------

    Location at(List<?> xyz) { return new Location(world, ((Number) xyz.get(0)).doubleValue(), ((Number) xyz.get(1)).doubleValue(), ((Number) xyz.get(2)).doubleValue()); }
    Location at(Map<?, ?> m) { return new Location(world, num(m, "x"), num(m, "y"), num(m, "z")); }
    static double num(Map<?, ?> m, String k) { return ((Number) m.get(k)).doubleValue(); }
    @SuppressWarnings("unchecked") List<Map<?, ?>> list(String k) { return layout.getMapList(k); }
    Location lobby() { Location l = at(layout.getList("lobby")); l.setYaw(180); return l; }
    Location hall() { return at(layout.getList("hall")); }
    List<Location> spawns() { return layout.getList("spawns").stream().map(o -> at((List<?>) o)).toList(); }
    List<Location> spots() { return layout.getList("candles").stream().map(o -> at((List<?>) o)).toList(); }

    String roomAt(Location l) {
        for (Map<?, ?> r : list("rooms"))
            if (l.getX() >= num(r, "x1") && l.getX() <= num(r, "x2") + 1 && l.getZ() >= num(r, "z1") && l.getZ() <= num(r, "z2") + 1) return (String) r.get("name");
        return "corridor";
    }

    boolean inWorld(Player p) { return p.getWorld().getName().equals(WORLD); }
    List<Player> online(Collection<UUID> ids) { return new ArrayList<>(ids.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList()); }
    List<Player> lobbyPlayers() { return world == null ? List.of() : world.getPlayers().stream().filter(p -> !players.contains(p.getUniqueId()) && p.getGameMode() != GameMode.CREATIVE).toList(); }
    void all(String msg) { if (world != null) world.getPlayers().forEach(p -> p.sendMessage(msg)); }

    // ---------- joining ----------

    void join(Player p) {
        if (!enabled()) { p.sendMessage(ChatColor.RED + "Nightfall isn't set up yet."); return; }
        p.teleport(lobby());
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.8f);
    }

    void toLobby(Player p) {
        reset(p);
        p.teleport(lobby());
        lobbyItems(p);
    }

    void reset(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20); p.setFireTicks(0); p.setFallDistance(0); p.setWalkSpeed(0.2f); p.setGlowing(false);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        bar.removePlayer(p);
    }

    void lobbyItems(Player p) {
        p.getInventory().setItem(0, tagged(Material.WRITTEN_BOOK, "rules", "&c&lHow to play &7(right-click)"));
        p.getInventory().setItem(4, tagged(Material.TORCH, "none", "&6Your torch", "&7In the manor, the only light", "&7is the torch you carry."));
        p.getInventory().setItem(8, tagged(Material.RED_BED, "leave", "&cBack to Hub &7(right-click)"));
    }

    ItemStack tagged(Material m, String action, String name, String... lore) {
        ItemStack it = NajursCore.icon(m, name, lore);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    String action(ItemStack it) {
        return it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    void rules(Player p) {
        List<io.papermc.paper.registry.data.dialog.body.DialogBody> body = new ArrayList<>();
        body.add(Dialogs.line("&7A random game starts whenever 2+ players are in the lobby.\n&7Inside the manor it is pitch black - only torches light the way."));
        for (Game g : Game.values()) body.add(Dialogs.line(g.colour.toString().replace('§', '&') + "&l" + g.title + " &8(" + g.min + "+ players)\n&7" + g.rules));
        body.add(Dialogs.line("&8Tip: tasks are done by right-clicking a station and standing still."));
        DialogBase base = DialogBase.builder(Dialogs.txt("&4&lNIGHTFALL")).canCloseWithEscape(true).pause(false).body(body).build();
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.notice())));
    }

    // ---------- main loop ----------

    private void tick() {
        if (world == null) return;
        players.removeIf(u -> { Player p = Bukkit.getPlayer(u); return p == null || !inWorld(p); });
        alive.retainAll(players);
        switch (state) {
            case LOBBY -> {
                List<Player> lob = lobbyPlayers();
                if (lob.size() >= 2) { state = State.COUNTDOWN; timer = last == null ? 20 : 12; game = pick(lob.size()); announceNext(); }
                else lob.forEach(p -> p.sendActionBar(ChatColor.DARK_RED + "NIGHTFALL " + ChatColor.GRAY + "- waiting for players (" + lob.size() + "/2)"));
                board(ChatColor.GRAY + "Waiting for players... (" + lob.size() + "/2)");
            }
            case COUNTDOWN -> {
                List<Player> lob = lobbyPlayers();
                if (lob.size() < 2) { state = State.LOBBY; all(ChatColor.RED + "Not enough players - countdown cancelled."); return; }
                if (lob.size() < game.min) game = pick(lob.size());
                lob.forEach(p -> p.sendActionBar(game.colour + "" + ChatColor.BOLD + game.title + ChatColor.GRAY + " starts in " + ChatColor.WHITE + timer + "s"));
                board(game.colour + "" + ChatColor.BOLD + game.title + "\n" + ChatColor.GRAY + game.rules + "\n" + ChatColor.WHITE + "Starts in " + timer + "s");
                if (timer <= 5 && timer > 0) lob.forEach(p -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 0.5f));
                if (--timer < 0) begin(lob);
            }
            case PLAYING -> { play(); ambience(); }
            case MEETING -> meetingTick();
            case ENDING -> { if (--timer <= 0) finish(); }
        }
    }

    Game pick(int n) {
        List<Game> pool = new ArrayList<>(Arrays.stream(Game.values()).filter(g -> g.min <= n).toList());
        if (pool.size() > 1) pool.remove(last);
        return pool.get(rnd.nextInt(pool.size()));
    }

    void announceNext() {
        for (Player p : lobbyPlayers()) {
            p.sendTitle(game.colour + "" + ChatColor.BOLD + game.title, ChatColor.GRAY + game.rules, 5, 70, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 0.8f);
        }
    }

    void board(String text) {
        for (Entity e : world.getEntitiesByClass(TextDisplay.class)) if (e.getScoreboardTags().contains("nf_board")) ((TextDisplay) e).setText(text);
    }

    // ---------- starting / ending ----------

    private void begin(List<Player> lob) {
        state = State.PLAYING;
        last = game;
        List<Player> ps = new ArrayList<>(lob.subList(0, Math.min(10, lob.size())));
        Collections.shuffle(ps, rnd);
        players.clear(); alive.clear(); role.clear(); score.clear(); hits.clear(); escaped.clear(); tasks.clear(); votes.clear();
        killReady.clear(); sabotageReady.clear(); calledMeeting.clear(); genWork.clear(); gensDone.clear(); stealReady.clear();
        tasksDone = 0; tasksTotal = 0; relicsIn = 0; gatesOpen = false; lightsOutUntil = 0;
        resetMap();
        bar.setTitle(game.colour + "" + ChatColor.BOLD + game.title); bar.setProgress(1); bar.setColor(BarColor.RED);
        List<Location> sp = spawns();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            players.add(p.getUniqueId()); alive.add(p.getUniqueId());
            reset(p);
            p.teleport(sp.get(i % sp.size()).clone().setDirection(hall().toVector().subtract(sp.get(i % sp.size()).toVector())));
            bar.addPlayer(p);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_EMERGE, 0.6f, 0.7f);
        }
        endsAt = System.currentTimeMillis() + 1000L * switch (game) { case IMPOSTOR -> 480; case ESCAPE -> 480; case CANDLES -> 180; case RELICS -> 360; };
        switch (game) {
            case IMPOSTOR -> startImpostor(ps);
            case ESCAPE -> startEscape(ps);
            case CANDLES -> startCandles(ps);
            case RELICS -> startRelics(ps);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "☠ " + ChatColor.GRAY + "Nightfall: " + game.colour + game.title + ChatColor.GRAY + " started with "
                + ChatColor.WHITE + ps.size() + ChatColor.GRAY + " players. " + ChatColor.YELLOW + "/go nightfall" + ChatColor.GRAY + " to wait for the next one.");
    }

    void torch(Player p) { p.getInventory().setItemInOffHand(tagged(Material.TORCH, "none", "&6Torch", "&7Lights the way. Keep it in a hand.")); }

    /** winners == null: nobody wins. */
    void end(String headline, Collection<UUID> winners) {
        if (state == State.ENDING) return;
        state = State.ENDING; timer = 8;
        working.clear();
        for (UUID u : winners == null ? List.<UUID>of() : winners) {
            String path = "nightfall.wins." + u;
            plugin.data.set(path, plugin.data.getInt(path) + 1);
            Player w = Bukkit.getPlayer(u);
            if (w != null) plugin.data.set("names." + u, w.getName());
        }
        for (Player p : online(players)) {
            boolean won = winners != null && winners.contains(p.getUniqueId());
            p.sendTitle(won ? ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY" : ChatColor.DARK_RED + "" + ChatColor.BOLD + "DEFEAT", headline, 5, 80, 15);
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WARDEN_DEATH, 0.8f, 1f);
            p.setGameMode(GameMode.SPECTATOR);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 10, 0, false, false));
        }
        if (game == Game.IMPOSTOR || game == Game.ESCAPE) {
            String hunters = String.join(", ", online(players).stream().filter(p -> role.get(p.getUniqueId()) != null && !role.get(p.getUniqueId()).equals("crew") && !role.get(p.getUniqueId()).equals("survivor")).map(Player::getName).toList());
            if (!hunters.isEmpty()) all(ChatColor.GRAY + (game == Game.IMPOSTOR ? "The impostor was: " : "The hunter was: ") + ChatColor.RED + hunters);
        }
        all(ChatColor.DARK_RED + "☠ " + game.colour + ChatColor.BOLD + game.title + ChatColor.GRAY + " is over: " + ChatColor.WHITE + headline);
    }

    private void finish() {
        for (Player p : online(players)) toLobby(p);
        players.clear(); alive.clear();
        resetMap();
        state = State.LOBBY;
    }

    /** Remove props, lights and bosses from the last round and close the gates. */
    void resetMap() {
        spawned.forEach(Entity::remove); spawned.clear();
        if (warden != null) { warden.remove(); warden = null; }
        lightAt.values().forEach(b -> { if (b.getType() == Material.LIGHT) b.setType(Material.AIR); }); lightAt.clear();
        torchOut.clear();
        cleanupWorld();
        for (Map<?, ?> g : list("gates")) fillBox(g, Material.IRON_BARS);
        for (Map<?, ?> g : list("generators")) lamps(g, false);
        meetingLights(false);
    }

    /** Entities we may have left after a restart (they are not persistent, but be sure). */
    void cleanupWorld() {
        for (Entity e : world.getEntities())
            if (e.getScoreboardTags().contains("nf_temp") || e instanceof Warden || e instanceof Vex || (e instanceof Item i && action(i.getItemStack()) != null)) e.remove();
    }

    void fillBox(Map<?, ?> g, Material m) {
        for (int x = (int) num(g, "x1"); x <= (int) num(g, "x2"); x++)
            for (int y = (int) num(g, "y1"); y <= (int) num(g, "y2"); y++)
                for (int z = (int) num(g, "z1"); z <= (int) num(g, "z2"); z++) world.getBlockAt(x, y, z).setType(m);
    }

    void lamps(Map<?, ?> g, boolean on) {
        List<?> l = (List<?>) g.get("lamps");
        int x1 = ((Number) l.get(0)).intValue(), z1 = ((Number) l.get(1)).intValue(), x2 = ((Number) l.get(2)).intValue(), z2 = ((Number) l.get(3)).intValue();
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++)
            if (world.getBlockAt(x, 69, z).getType() == Material.REDSTONE_LAMP) world.getBlockAt(x, 70, z).setType(on ? Material.REDSTONE_BLOCK : Material.BLACK_CONCRETE);
    }

    void meetingLights(boolean on) {
        for (int x = -6; x <= 6; x += 4) for (int z = -6; z <= 6; z += 4) {
            Block b = world.getBlockAt(x, 67, z);
            if (on && b.getType().isAir()) b.setBlockData(Bukkit.createBlockData("minecraft:light[level=15]"));
            else if (!on && b.getType() == Material.LIGHT) b.setType(Material.AIR);
        }
    }

    void out(Player p, String why) {
        if (!alive.remove(p.getUniqueId())) return;
        p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "YOU DIED", ChatColor.GRAY + why, 5, 50, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_DEATH, 0.7f, 1.4f);
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 900, 0, false, false));
        p.sendMessage(ChatColor.GRAY + "You're a ghost now: fly around and watch. " + ChatColor.DARK_GRAY + "(/hub to leave)");
        Block b = lightAt.remove(p.getUniqueId());
        if (b != null && b.getType() == Material.LIGHT) b.setType(Material.AIR);
    }

    // ---------- moving light ----------

    private void lights() {
        if (world == null) return;
        boolean blackout = System.currentTimeMillis() < lightsOutUntil;
        for (Player p : online(players)) {
            Block want = null;
            boolean holds = p.getInventory().getItemInMainHand().getType() == Material.TORCH || p.getInventory().getItemInOffHand().getType() == Material.TORCH;
            boolean dark = torchOut.contains(p.getUniqueId()) || (blackout && !"impostor".equals(role.get(p.getUniqueId())));
            if (alive.contains(p.getUniqueId()) && holds && !dark && (state == State.PLAYING || state == State.MEETING)) {
                Block head = p.getEyeLocation().getBlock();
                if (head.getType().isAir() || head.getType() == Material.LIGHT) want = head;
                else if (p.getLocation().getBlock().getType().isAir()) want = p.getLocation().getBlock();
            }
            Block had = lightAt.get(p.getUniqueId());
            if (had != null && !had.equals(want) && had.getType() == Material.LIGHT && lightAt.values().stream().filter(had::equals).count() == 1) had.setType(Material.AIR);
            if (want == null) { lightAt.remove(p.getUniqueId()); continue; }
            if (want.getType().isAir()) want.setBlockData(LIGHT, false);
            lightAt.put(p.getUniqueId(), want);
        }
    }

    /** Random scares: torches sputter out, whispers, distant doors. */
    private void ambience() {
        for (Player p : online(alive)) {
            int r = rnd.nextInt(400);
            if (r < 3 && torchOut.add(p.getUniqueId())) {
                p.sendActionBar(ChatColor.DARK_GRAY + "Your torch sputters...");
                p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.6f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> torchOut.remove(p.getUniqueId()), 40L + rnd.nextInt(40));
            } else if (r < 8) {
                Location l = p.getLocation().add(rnd.nextGaussian() * 6, 0, rnd.nextGaussian() * 6);
                Sound s = List.of(Sound.AMBIENT_CAVE, Sound.BLOCK_WOODEN_DOOR_OPEN, Sound.ENTITY_ENDERMAN_AMBIENT, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT,
                        Sound.BLOCK_CHAIN_STEP, Sound.ENTITY_GHAST_AMBIENT).get(rnd.nextInt(7));
                p.playSound(l, s, 0.6f, 0.5f + rnd.nextFloat() * 0.5f);
            }
        }
    }

    // ---------- stations (right-click and stand still) ----------

    Map<?, ?> stationAt(Block b) {
        for (Map<?, ?> s : list("stations")) if ((int) num(s, "x") == b.getX() && (int) num(s, "y") == b.getY() && (int) num(s, "z") == b.getZ()) return s;
        return null;
    }

    void startWork(Player p, Map<?, ?> st, int seconds, Runnable done) {
        Work w = working.get(p.getUniqueId());
        if (w != null && w.station() == st) return;
        working.put(p.getUniqueId(), new Work(st, seconds * 4, done));
        progress.put(p.getUniqueId(), 0);
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.8f, 0.8f);
    }

    private void workTick() {
        for (var it = working.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Work w = e.getValue();
            if (p == null || !alive.contains(p.getUniqueId()) || state != State.PLAYING || p.getLocation().distanceSquared(at(w.station()).add(0.5, 0, 0.5)) > 9) {
                if (p != null) p.sendActionBar(ChatColor.RED + "Interrupted - stay next to the station");
                it.remove(); continue;
            }
            int done = progress.merge(p.getUniqueId(), 1, Integer::sum);
            int bars = done * 20 / w.need();
            p.sendActionBar(ChatColor.YELLOW + w.station().get("task").toString() + " " + ChatColor.GREEN + "|".repeat(bars) + ChatColor.DARK_GRAY + "|".repeat(20 - bars));
            if (done % 4 == 0) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + done / (float) w.need());
            if (done >= w.need()) { it.remove(); w.done().run(); }
        }
    }

    // ================= THE IMPOSTOR =================

    void startImpostor(List<Player> ps) {
        int imps = ps.size() >= 7 ? 2 : 1;
        List<Map<?, ?>> st = new ArrayList<>(list("stations"));
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            boolean imp = i < imps;
            role.put(p.getUniqueId(), imp ? "impostor" : "crew");
            Collections.shuffle(st, rnd);
            tasks.put(p.getUniqueId(), new ArrayList<>(st.subList(0, 4)));
            if (!imp) tasksTotal += 4;
            torch(p);
            p.getInventory().setItem(8, tagged(Material.COMPASS, "tracker", "&e&lTask tracker", "&7Points at your nearest task."));
            if (imp) {
                p.getInventory().setItem(0, tagged(Material.IRON_SWORD, "knife", "&c&lKnife", "&7Hit a crewmate to kill them.", "&7Recharges in 25s."));
                p.getInventory().setItem(1, tagged(Material.REDSTONE_TORCH, "sabotage", "&4&lCut the lights &7(right-click)", "&7Crew torches die for 25s", "&7unless someone fixes the fuse box."));
                killReady.put(p.getUniqueId(), System.currentTimeMillis() + 20_000);
                sabotageReady.put(p.getUniqueId(), System.currentTimeMillis() + 45_000);
                p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "IMPOSTOR", ChatColor.GRAY + "Kill the crew. Fake tasks to blend in." + (imps > 1 ? " You have a partner." : ""), 10, 80, 10);
                if (imps > 1) p.sendMessage(ChatColor.RED + "Impostors: " + String.join(", ", ps.subList(0, imps).stream().map(Player::getName).toList()));
            } else {
                p.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "CREWMATE", ChatColor.GRAY + "Do your tasks. " + imps + " impostor" + (imps > 1 ? "s are" : " is") + " among you.", 10, 80, 10);
            }
            p.sendMessage(ChatColor.GRAY + "Your tasks: " + ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE,
                    tasks.get(p.getUniqueId()).stream().map(s -> s.get("task") + " (" + s.get("room") + ")").toList()));
            p.sendMessage(ChatColor.GRAY + "Found a body? Right-click it to report. Suspicious? Ring the " + ChatColor.GOLD + "bell" + ChatColor.GRAY + " in the Great Hall.");
        }
    }

    void impostorTick() {
        long now = System.currentTimeMillis();
        bar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "THE IMPOSTOR" + ChatColor.GRAY + "  Tasks " + ChatColor.WHITE + tasksDone + "/" + tasksTotal
                + (now < lightsOutUntil ? ChatColor.DARK_RED + "  ⚠ LIGHTS OUT" : ""));
        bar.setProgress(tasksTotal == 0 ? 1 : Math.min(1, tasksDone / (double) tasksTotal));
        bar.setColor(BarColor.GREEN);
        for (Player p : online(alive)) {
            List<Map<?, ?>> left = tasks.getOrDefault(p.getUniqueId(), List.of());
            Map<?, ?> near = left.stream().min(Comparator.comparingDouble(s -> at(s).distanceSquared(p.getLocation()))).orElse(null);
            if (near != null) p.setCompassTarget(at(near));
            if (working.containsKey(p.getUniqueId())) continue;
            String extra = "";
            if ("impostor".equals(role.get(p.getUniqueId()))) {
                long k = (killReady.getOrDefault(p.getUniqueId(), 0L) - now) / 1000, s = (sabotageReady.getOrDefault(p.getUniqueId(), 0L) - now) / 1000;
                extra = ChatColor.RED + "  Knife " + (k <= 0 ? ChatColor.GREEN + "READY" : ChatColor.GRAY + "" + k + "s") + ChatColor.RED + "  Sabotage " + (s <= 0 ? ChatColor.GREEN + "READY" : ChatColor.GRAY + "" + s + "s");
            }
            p.sendActionBar((near == null ? ChatColor.GREEN + "All your tasks are done!" : ChatColor.YELLOW + "Next: " + ChatColor.WHITE + near.get("task") + ChatColor.GRAY + " in " + near.get("room")
                    + ChatColor.DARK_GRAY + " (" + (4 - left.size()) + "/4)") + extra + ChatColor.DARK_GRAY + "  • you're in " + roomAt(p.getLocation()));
        }
        long crew = alive.stream().filter(u -> "crew".equals(role.get(u))).count(), imps = alive.size() - crew;
        if (imps == 0) end("The crew found every impostor", crewIds());
        else if (imps >= crew) end("The impostors took over the manor", impIds());
        else if (tasksTotal > 0 && tasksDone >= tasksTotal) end("The crew finished every task", crewIds());
        else if (now > endsAt) end("Time ran out - the impostors win", impIds());
    }

    List<UUID> crewIds() { return players.stream().filter(u -> "crew".equals(role.get(u))).toList(); }
    List<UUID> impIds() { return players.stream().filter(u -> "impostor".equals(role.get(u))).toList(); }

    void doTask(Player p, Map<?, ?> st) {
        List<Map<?, ?>> mine = tasks.get(p.getUniqueId());
        if (mine == null || !mine.contains(st)) {
            if ("fuse".equals(st.get("kind")) && System.currentTimeMillis() < lightsOutUntil) { startWork(p, st, 3, this::fixLights); return; }
            p.sendActionBar(ChatColor.GRAY + "That's not one of your tasks.");
            return;
        }
        if ("fuse".equals(st.get("kind")) && System.currentTimeMillis() < lightsOutUntil) { startWork(p, st, 3, this::fixLights); return; }
        startWork(p, st, 4, () -> {
            mine.remove(st);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            p.sendActionBar(ChatColor.GREEN + "✔ " + st.get("task") + " done!");
            if ("crew".equals(role.get(p.getUniqueId()))) tasksDone++;
        });
    }

    void fixLights() {
        lightsOutUntil = 0;
        for (Player p : online(players)) { p.sendTitle("", ChatColor.GREEN + "The lights are back", 0, 30, 10); p.removePotionEffect(PotionEffectType.DARKNESS); }
    }

    void sabotage(Player imp) {
        long now = System.currentTimeMillis();
        if (now < sabotageReady.getOrDefault(imp.getUniqueId(), 0L)) { imp.sendActionBar(ChatColor.RED + "Sabotage is recharging"); return; }
        sabotageReady.put(imp.getUniqueId(), now + 60_000);
        lightsOutUntil = now + 25_000;
        for (Player p : online(alive)) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f);
            if ("impostor".equals(role.get(p.getUniqueId()))) { p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 25, 0, false, false)); continue; }
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 25, 0, false, false));
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "LIGHTS OUT", ChatColor.GRAY + "Fix the wiring in the Fuse Room!", 5, 50, 10);
        }
    }

    void knife(Player imp, Player victim) {
        long now = System.currentTimeMillis();
        if (now < killReady.getOrDefault(imp.getUniqueId(), 0L)) { imp.sendActionBar(ChatColor.RED + "Knife recharging..."); return; }
        if (!"crew".equals(role.get(victim.getUniqueId()))) return;
        killReady.put(imp.getUniqueId(), now + 25_000);
        Location l = victim.getLocation();
        tasksTotal -= tasks.getOrDefault(victim.getUniqueId(), List.of()).size();   // the dead don't hold the crew back
        out(victim, "The impostor got you");
        victim.teleport(l.clone().add(0, 1.5, 0));
        body(victim, l);
        imp.playSound(imp.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.6f);
        l.getWorld().playSound(l, Sound.ENTITY_PLAYER_HURT, 0.8f, 0.6f);
    }

    void body(Player victim, Location l) {
        Mannequin m = world.spawn(l, Mannequin.class, x -> {
            x.setProfile(ResolvableProfile.resolvableProfile(victim.getPlayerProfile()));
            x.setImmovable(true); x.setInvulnerable(true); x.setPersistent(false); x.setAI(false);
            x.addScoreboardTag("nf_temp"); x.addScoreboardTag("nf_body");
            x.setPose(Pose.SLEEPING, true);
            x.customName(Dialogs.txt("&c☠ " + victim.getName() + " &7(right-click to report)"));
            x.setCustomNameVisible(false);
        });
        spawned.add(m);
    }

    void meeting(Player caller, String why) {
        if (state != State.PLAYING) return;
        state = State.MEETING;
        working.clear(); votes.clear();
        lightsOutUntil = 0;
        meetingEnds = System.currentTimeMillis() + 35_000;
        spawned.removeIf(e -> { if (e.getScoreboardTags().contains("nf_body")) { e.remove(); return true; } return false; });
        meetingLights(true);
        List<Location> sp = spawns();
        List<Player> ps = online(alive);
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            p.teleport(sp.get(i % sp.size()).clone().setDirection(hall().toVector().subtract(sp.get(i % sp.size()).toVector())));
            p.setWalkSpeed(0);
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "EMERGENCY MEETING", ChatColor.WHITE + caller.getName() + ChatColor.GRAY + " " + why, 5, 50, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 0.7f);
        }
        all(ChatColor.RED + "☎ Meeting called by " + ChatColor.WHITE + caller.getName() + ChatColor.GRAY + " (" + why + "). Discuss in chat, then vote!");
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (state == State.MEETING) online(alive).forEach(this::voteDialog); }, 60L);
    }

    void voteDialog(Player p) {
        List<ActionButton> b = new ArrayList<>();
        for (Player o : online(alive))
            b.add(plugin.dialogs.button("&f" + o.getName(), null, 140, x -> castVote(x, o.getUniqueId())));
        b.add(plugin.dialogs.button("&7Skip vote", null, 140, x -> castVote(x, SKIP)));
        p.showDialog(Dialogs.screen("&c&lWho is the impostor?", List.of(Dialogs.line("&7Most votes is thrown out of the manor. Ties and skips: nobody.")), List.of(), b, 2, null));
    }

    void castVote(Player p, UUID who) {
        if (state != State.MEETING || !alive.contains(p.getUniqueId()) || votes.containsKey(p.getUniqueId())) return;
        votes.put(p.getUniqueId(), who);
        all(ChatColor.GRAY + "✉ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " has voted (" + votes.size() + "/" + alive.size() + ")");
        if (votes.keySet().containsAll(alive)) meetingEnds = 0;
    }

    void meetingTick() {
        long left = (meetingEnds - System.currentTimeMillis()) / 1000;
        bar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "MEETING" + ChatColor.GRAY + "  vote ends in " + Math.max(0, left) + "s  (" + votes.size() + "/" + alive.size() + " voted)");
        if (left > 0) return;
        Map<UUID, Integer> count = new HashMap<>();
        votes.values().forEach(v -> count.merge(v, 1, Integer::sum));
        int top = count.values().stream().max(Integer::compare).orElse(0);
        List<UUID> leaders = count.entrySet().stream().filter(e -> e.getValue() == top).map(Map.Entry::getKey).toList();
        for (Player p : online(players)) { p.closeDialog(); p.setWalkSpeed(0.2f); }
        meetingLights(false);
        state = State.PLAYING;
        killReady.replaceAll((u, t) -> System.currentTimeMillis() + 15_000);
        if (top == 0 || leaders.size() > 1 || leaders.get(0).equals(SKIP)) { all(ChatColor.GRAY + "Nobody was thrown out" + (leaders.size() > 1 ? " (tie)." : ".")); return; }
        Player ej = Bukkit.getPlayer(leaders.get(0));
        if (ej == null) return;
        boolean imp = "impostor".equals(role.get(ej.getUniqueId()));
        if (!imp) tasksTotal -= tasks.getOrDefault(ej.getUniqueId(), List.of()).size();
        out(ej, "You were thrown out of the manor");
        for (Player p : online(players)) p.sendTitle(ChatColor.WHITE + ej.getName() + " was thrown out", imp ? ChatColor.RED + "They WERE an impostor" : ChatColor.GRAY + "They were not an impostor...", 5, 60, 10);
    }

    // ================= BLACKOUT ESCAPE =================

    void startEscape(List<Player> ps) {
        int hunters = ps.size() >= 7 ? 2 : 1;
        List<Location> far = spots().stream().filter(l -> l.distanceSquared(hall()) > 900).toList();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (i < hunters) {
                role.put(p.getUniqueId(), "hunter");
                p.getInventory().setItem(0, tagged(Material.NETHERITE_AXE, "none", "&4&lCleaver", "&7Two hits and they're done."));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 900, 0, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 900, 0, false, false));
                p.teleport(far.get(rnd.nextInt(far.size())));
                p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "HUNTER", ChatColor.GRAY + "You see in the dark. They can't see you.", 10, 80, 10);
            } else {
                role.put(p.getUniqueId(), "survivor");
                torch(p);
                p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "SURVIVOR", ChatColor.GRAY + "Fix " + GEN_NEEDED + " generators, then escape.", 10, 80, 10);
                p.sendMessage(ChatColor.GRAY + "Generators are in the Generator Rooms. " + ChatColor.WHITE + "Stand next to one and sneak" + ChatColor.GRAY + " to repair it (faster together). You can take 1 hit.");
            }
        }
    }

    void escapeTick() {
        long now = System.currentTimeMillis();
        List<Map<?, ?>> gens = list("generators");
        List<Player> hunters = online(alive).stream().filter(p -> "hunter".equals(role.get(p.getUniqueId()))).toList();
        for (Player p : online(alive)) {
            if (!"survivor".equals(role.get(p.getUniqueId())) || escaped.contains(p.getUniqueId())) continue;
            // the hunter's heartbeat
            double d = hunters.stream().mapToDouble(h -> h.getLocation().distance(p.getLocation())).min().orElse(99);
            if (d < 20) { p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, (float) (1.4 - d / 20), 1f);
                          if (d < 10) Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.1f), 10L); }
            String status = "";
            for (int i = 0; i < gens.size(); i++) {
                if (gensDone.contains(i) || !p.isSneaking() || p.getLocation().distanceSquared(at(gens.get(i)).add(0.5, 0, 0.5)) > 9) continue;
                int w = genWork.merge(i, 1, Integer::sum);
                status = ChatColor.YELLOW + "Repairing " + ChatColor.GREEN + "|".repeat(w * 20 / GEN_WORK) + ChatColor.DARK_GRAY + "|".repeat(Math.max(0, 20 - w * 20 / GEN_WORK));
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 0.6f + rnd.nextFloat() * 0.6f);
                if (rnd.nextInt(12) == 0) { // misfire: loud and gives you away
                    world.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.4f);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false));
                    p.sendActionBar(ChatColor.RED + "⚠ The generator misfired! Everyone heard that.");
                    genWork.put(i, Math.max(0, w - 3));
                    status = null;
                }
                if (w >= GEN_WORK) generatorDone(i);
            }
            if (status != null && !status.isEmpty()) p.sendActionBar(status);
            else if (status != null) p.sendActionBar(ChatColor.GRAY + (gatesOpen ? "The gates are OPEN - run to an exit (north or south wall)!" : "Find a generator (Generator Rooms) and sneak next to it") + ChatColor.DARK_GRAY + "  • " + roomAt(p.getLocation()));
            if (gatesOpen) for (Map<?, ?> g : list("gates")) {
                List<?> z = (List<?>) g.get("zone");
                Location l = p.getLocation();
                if (l.getX() >= ((Number) z.get(0)).doubleValue() && l.getX() <= ((Number) z.get(2)).doubleValue() + 1 && l.getZ() >= ((Number) z.get(1)).doubleValue() && l.getZ() <= ((Number) z.get(3)).doubleValue() + 1) {
                    escaped.add(p.getUniqueId());
                    p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "ESCAPED!", ChatColor.GRAY + "You made it out alive", 5, 50, 10);
                    all(ChatColor.GREEN + "✔ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " escaped the manor!");
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 900, 0, false, false));
                    p.setGlowing(true);
                }
            }
        }
        for (Player h : hunters) h.sendActionBar(ChatColor.DARK_RED + "Hunt them. " + ChatColor.GRAY + "Generators " + gensDone.size() + "/" + GEN_NEEDED + (gatesOpen ? ChatColor.RED + "  GATES OPEN" : ""));
        bar.setTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "BLACKOUT ESCAPE" + ChatColor.GRAY + "  Generators " + ChatColor.WHITE + gensDone.size() + "/" + GEN_NEEDED
                + ChatColor.GRAY + "  Escaped " + ChatColor.WHITE + escaped.size() + ChatColor.GRAY + "  " + clock());
        bar.setProgress(Math.min(1, gensDone.size() / (double) GEN_NEEDED));
        List<UUID> survivors = players.stream().filter(u -> "survivor".equals(role.get(u))).toList();
        long stillIn = survivors.stream().filter(u -> alive.contains(u) && !escaped.contains(u)).count();
        if (hunters.isEmpty()) end("The hunter fled", survivors);
        else if (stillIn == 0 || now > endsAt) {
            boolean win = escaped.size() * 2 >= survivors.size() && !escaped.isEmpty();
            end(escaped.size() + "/" + survivors.size() + " survivors escaped", win ? new ArrayList<>(escaped) : players.stream().filter(u -> "hunter".equals(role.get(u))).toList());
        }
    }

    void generatorDone(int i) {
        if (!gensDone.add(i)) return;
        Map<?, ?> g = list("generators").get(i);
        lamps(g, true);
        world.playSound(at(g), Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.8f);
        all(ChatColor.GOLD + "⚡ The generator in the " + g.get("room") + " roars to life! " + ChatColor.GRAY + "(" + gensDone.size() + "/" + GEN_NEEDED + ")");
        if (gensDone.size() >= GEN_NEEDED && !gatesOpen) {
            gatesOpen = true;
            for (Map<?, ?> gate : list("gates")) fillBox(gate, Material.AIR);
            for (Player p : online(players)) { p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "THE GATES ARE OPEN", ChatColor.GRAY + "North and south walls of the manor", 5, 60, 10); p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f); }
            online(alive).stream().filter(p -> "hunter".equals(role.get(p.getUniqueId()))).forEach(h -> h.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 900, 1, false, false)));
        }
    }

    void hunterHit(Player hunter, Player victim) {
        if (!"survivor".equals(role.get(victim.getUniqueId())) || escaped.contains(victim.getUniqueId())) return;
        int n = hits.merge(victim.getUniqueId(), 1, Integer::sum);
        world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.7f);
        if (n >= 2) { out(victim, hunter.getName() + " caught you"); all(ChatColor.DARK_RED + "☠ " + ChatColor.WHITE + victim.getName() + ChatColor.GRAY + " was caught by the hunter."); return; }
        victim.sendTitle(ChatColor.RED + "WOUNDED", ChatColor.GRAY + "One more hit and you're dead. RUN!", 0, 40, 10);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 2, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, false, false));
        hunter.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, false));   // a moment to wipe the blade
    }

    // ================= SOUL CANDLES =================

    void startCandles(List<Player> ps) {
        for (Player p : ps) {
            role.put(p.getUniqueId(), "player"); score.put(p.getUniqueId(), 0); torch(p);
            p.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "SOUL CANDLES", ChatColor.GRAY + "Most candles in 3 minutes wins", 10, 70, 10);
            p.sendMessage(ChatColor.GRAY + "Walk over candles to grab them. " + ChatColor.WHITE + "Punch a rival to steal one" + ChatColor.GRAY + ". Ghosts knock candles out of your hands!");
        }
        List<Location> s = new ArrayList<>(spots());
        Collections.shuffle(s, rnd);
        s.stream().limit(30).forEach(this::dropCandle);
    }

    void dropCandle(Location l) {
        Item it = world.dropItem(l, tagged(Material.CANDLE, "candle", "&bSoul candle"));
        it.setVelocity(new org.bukkit.util.Vector()); it.setUnlimitedLifetime(true); it.setPersistent(false); it.setGlowing(false);
        it.addScoreboardTag("nf_temp");
        spawned.add(it);
    }

    void candlesTick() {
        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, Integer>> top = score.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).toList();
        String lead = String.join(ChatColor.GRAY + ", ", top.stream().limit(3).map(e -> ChatColor.WHITE + Optional.ofNullable(Bukkit.getPlayer(e.getKey())).map(Player::getName).orElse("?") + " " + ChatColor.AQUA + e.getValue()).toList());
        bar.setTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "SOUL CANDLES  " + lead + ChatColor.GRAY + "  " + clock());
        bar.setProgress(Math.max(0, Math.min(1, (endsAt - now) / 180_000.0)));
        for (Player p : online(alive)) p.sendActionBar(ChatColor.AQUA + "Your candles: " + ChatColor.WHITE + score.getOrDefault(p.getUniqueId(), 0) + ChatColor.DARK_GRAY + "  • " + roomAt(p.getLocation()));
        if (rnd.nextInt(18) == 0) {      // a ghost drifts in
            List<Player> ps = online(alive);
            if (!ps.isEmpty()) {
                Player t = ps.get(rnd.nextInt(ps.size()));
                Vex v = world.spawn(t.getLocation().add(rnd.nextInt(9) - 4, 2, rnd.nextInt(9) - 4), Vex.class, x -> {
                    x.setPersistent(false); x.addScoreboardTag("nf_temp"); x.setTarget(t); x.getEquipment().setItemInMainHand(null); x.setLimitedLifetimeTicks(20 * 20);
                });
                spawned.add(v);
                t.playSound(t.getLocation(), Sound.ENTITY_VEX_CHARGE, 1f, 0.6f);
            }
        }
        if (now > endsAt) {
            int best = top.isEmpty() ? 0 : top.get(0).getValue();
            List<UUID> w = top.stream().filter(e -> e.getValue() == best && best > 0).map(Map.Entry::getKey).toList();
            end(w.isEmpty() ? "Nobody found a candle" : String.join(" & ", w.stream().map(u -> Optional.ofNullable(Bukkit.getPlayer(u)).map(Player::getName).orElse("?")).toList()) + " won with " + best + " candles", w);
        }
    }

    void loseCandles(Player p, int n, Player thief) {
        int have = score.getOrDefault(p.getUniqueId(), 0), lose = Math.min(have, n);
        if (lose == 0) return;
        score.put(p.getUniqueId(), have - lose);
        if (thief != null) { score.merge(thief.getUniqueId(), lose, Integer::sum); thief.playSound(thief.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.5f); thief.sendActionBar(ChatColor.GREEN + "Stole a candle from " + p.getName()); }
        else for (int i = 0; i < lose; i++) dropCandle(p.getLocation().add(rnd.nextGaussian(), 0.5, rnd.nextGaussian()));
        p.sendActionBar(ChatColor.RED + "-" + lose + " candle" + (lose > 1 ? "s" : ""));
    }

    // ================= SILENT RELICS =================

    void startRelics(List<Player> ps) {
        for (Player p : ps) {
            role.put(p.getUniqueId(), "seeker"); torch(p);
            p.sendTitle(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "SILENT RELICS", ChatColor.GRAY + "Bring 5 relics to the hall table. Sneak.", 10, 80, 10);
            p.sendMessage(ChatColor.GRAY + "Relics (echo shards) are hidden around the manor. Carry them to the " + ChatColor.WHITE + "round table" + ChatColor.GRAY + " in the Great Hall and right-click it. "
                    + ChatColor.DARK_AQUA + "The Warden is blind but hears everything - sneak near it!");
        }
        List<Location> s = new ArrayList<>(spots().stream().filter(l -> l.distanceSquared(hall()) > 225).toList());
        Collections.shuffle(s, rnd);
        for (Location l : s.subList(0, 8)) {
            Item it = world.dropItem(l, tagged(Material.ECHO_SHARD, "relic", "&3&lAncient relic", "&7Bring it to the Great Hall table."));
            it.setVelocity(new org.bukkit.util.Vector()); it.setUnlimitedLifetime(true); it.setPersistent(false); it.addScoreboardTag("nf_temp");
            spawned.add(it);
        }
        Location far = s.get(s.size() - 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.PLAYING || game != Game.RELICS) return;
            warden = world.spawn(far, Warden.class, w -> {
                w.setPersistent(false); w.addScoreboardTag("nf_temp"); w.setRemoveWhenFarAway(false);
                w.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(9);   // 4.5 hearts: brutal, but you get a chance
            });
            for (Player p : online(players)) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_EMERGE, 1.2f, 0.8f);
            all(ChatColor.DARK_AQUA + "Something has woken up in the " + roomAt(far) + "...");
        }, 200L);
    }

    void relicsTick() {
        int need = 5;
        bar.setTitle(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "SILENT RELICS" + ChatColor.GRAY + "  Relics " + ChatColor.WHITE + relicsIn + "/" + need + ChatColor.GRAY + "  Alive " + alive.size() + "  " + clock());
        bar.setProgress(Math.min(1, relicsIn / (double) need));
        for (Player p : online(alive)) {
            int carrying = Arrays.stream(p.getInventory().getContents()).filter(i -> "relic".equals(action(i))).mapToInt(ItemStack::getAmount).sum();
            double d = warden == null ? 99 : warden.getLocation().distance(p.getLocation());
            p.sendActionBar((carrying > 0 ? ChatColor.AQUA + "Carrying " + carrying + " relic" + (carrying > 1 ? "s" : "") + " - take it to the hall table!" : ChatColor.GRAY + "Find relics")
                    + (d < 16 ? ChatColor.DARK_RED + "  ◉ It is close" : "") + ChatColor.DARK_GRAY + "  • " + roomAt(p.getLocation()));
        }
        if (relicsIn >= need) end("The relics are returned. The Warden sleeps.", new ArrayList<>(players));
        else if (alive.isEmpty()) end("The Warden got everyone", null);
        else if (System.currentTimeMillis() > endsAt) end("Time ran out", null);
    }

    // ---------- loop dispatch ----------

    private void play() {
        switch (game) {
            case IMPOSTOR -> impostorTick();
            case ESCAPE -> escapeTick();
            case CANDLES -> candlesTick();
            case RELICS -> relicsTick();
        }
    }

    String clock() { long s = Math.max(0, (endsAt - System.currentTimeMillis()) / 1000); return ChatColor.WHITE + "" + s / 60 + ":" + String.format("%02d", s % 60); }

    // ---------- events ----------

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom().getName().equals(WORLD)) { leave(p); return; }
        if (!inWorld(p) || !enabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWorld(p) || players.contains(p.getUniqueId()) || p.getGameMode() == GameMode.CREATIVE) return;
            toLobby(p);
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "NIGHTFALL", ChatColor.GRAY + (state == State.PLAYING || state == State.MEETING ? game.title + " in progress - you're next" : "Horror party games"), 10, 60, 10);
            all(ChatColor.DARK_RED + "+ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " entered Nightfall (" + world.getPlayers().size() + ")");
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (inWorld(e.getPlayer())) { leave(e.getPlayer()); reset(e.getPlayer()); } }

    void leave(Player p) {
        working.remove(p.getUniqueId());
        Block b = lightAt.remove(p.getUniqueId());
        if (b != null && b.getType() == Material.LIGHT) b.setType(Material.AIR);
        if (players.remove(p.getUniqueId())) {
            if (alive.remove(p.getUniqueId()) && "crew".equals(role.get(p.getUniqueId()))) tasksTotal -= tasks.getOrDefault(p.getUniqueId(), List.of()).size();
            p.setWalkSpeed(0.2f); p.setGlowing(false);
            p.getInventory().clear();
            for (PotionEffect x : p.getActivePotionEffects()) p.removePotionEffect(x.getType());
        }
        bar.removePlayer(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inWorld(p) || p.getGameMode() == GameMode.CREATIVE || e.getHand() == EquipmentSlot.OFF_HAND) return;
        String act = action(e.getItem());
        if (act != null && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            switch (act) {
                case "rules" -> { e.setCancelled(true); rules(p); return; }
                case "leave" -> { e.setCancelled(true); p.performCommand("hub"); return; }
                case "sabotage" -> { e.setCancelled(true); if (state == State.PLAYING && alive.contains(p.getUniqueId())) sabotage(p); return; }
            }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        e.setCancelled(true);   // no chests, doors or levers: the manor is a set
        if (state != State.PLAYING || !alive.contains(p.getUniqueId())) return;
        Block b = e.getClickedBlock();
        if (b.getType() == Material.BELL || (game == Game.RELICS && Math.abs(b.getX()) <= 2 && Math.abs(b.getZ()) <= 2 && b.getY() <= 66)) {
            if (game == Game.IMPOSTOR) {
                if (!calledMeeting.add(p.getUniqueId())) { p.sendActionBar(ChatColor.RED + "You already used your emergency meeting."); return; }
                meeting(p, "rang the emergency bell");
            } else if (game == Game.RELICS) depositRelics(p);
            return;
        }
        Map<?, ?> st = stationAt(b);
        if (st != null && game == Game.IMPOSTOR) doTask(p, st);
        else if (st != null && game == Game.ESCAPE && "generator".equals(st.get("kind"))) p.sendActionBar(ChatColor.YELLOW + "Sneak next to the generator to repair it");
    }

    void depositRelics(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if ("relic".equals(action(it))) { n += it.getAmount(); it.setAmount(0); }
        if (n == 0) { p.sendActionBar(ChatColor.GRAY + "Bring relics here."); return; }
        relicsIn += n;
        world.playSound(hall(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.5f, 0.8f);
        all(ChatColor.DARK_AQUA + "✦ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " placed " + n + " relic" + (n > 1 ? "s" : "") + " (" + relicsIn + "/5)");
    }

    @EventHandler
    public void onEntityClick(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!inWorld(p)) return;
        e.setCancelled(true);
        if (e.getRightClicked().getScoreboardTags().contains("nf_body") && state == State.PLAYING && alive.contains(p.getUniqueId())) {
            String name = e.getRightClicked().customName() == null ? "a body" : "a body";
            meeting(p, "found " + name);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inWorld(p)) {
            if (e.getEntity().getWorld().getName().equals(WORLD) && e.getEntity().getScoreboardTags().contains("nf_body")) e.setCancelled(true);
            return;
        }
        boolean fighting = state == State.PLAYING && alive.contains(p.getUniqueId());
        if (fighting && game == Game.RELICS && e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Warden) return;   // real damage
        if (fighting && game == Game.RELICS && e.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM) return;
        e.setCancelled(true);
        if (!fighting || !(e instanceof EntityDamageByEntityEvent ed)) return;
        if (ed.getDamager() instanceof Vex v && game == Game.CANDLES) { loseCandles(p, 2, null); v.remove(); p.playSound(p.getLocation(), Sound.ENTITY_VEX_DEATH, 1f, 0.6f); return; }
        if (!(ed.getDamager() instanceof Player d) || !alive.contains(d.getUniqueId())) return;
        switch (game) {
            case IMPOSTOR -> { if ("impostor".equals(role.get(d.getUniqueId())) && "knife".equals(action(d.getInventory().getItemInMainHand()))) knife(d, p); }
            case ESCAPE -> { if ("hunter".equals(role.get(d.getUniqueId()))) hunterHit(d, p); }
            case CANDLES -> {
                long now = System.currentTimeMillis();
                if (now < stealReady.getOrDefault(d.getUniqueId(), 0L)) return;
                stealReady.put(d.getUniqueId(), now + 3000);
                loseCandles(p, 1, d);
                p.setVelocity(p.getLocation().toVector().subtract(d.getLocation().toVector()).normalize().multiply(0.6).setY(0.3));
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!inWorld(p)) return;
        e.getDrops().clear(); e.setDeathMessage(null); e.setKeepInventory(true);
        if (alive.contains(p.getUniqueId())) {
            // drop any relics where they fell so others can pick them up
            for (ItemStack it : p.getInventory().getContents()) if ("relic".equals(action(it))) {
                Item i = world.dropItem(p.getLocation(), it.clone()); i.setUnlimitedLifetime(true); i.setPersistent(false); i.addScoreboardTag("nf_temp"); spawned.add(i);
            }
            Location l = p.getLocation();
            all(ChatColor.DARK_AQUA + "☠ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " was taken by the Warden.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> { out(p, "The Warden found you"); p.teleport(l.add(0, 1, 0)); }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (inWorld(e.getPlayer())) e.setRespawnLocation(players.contains(e.getPlayer().getUniqueId()) ? hall() : lobby());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inWorld(p)) return;
        String act = action(e.getItem().getItemStack());
        if (act == null) return;
        if (!alive.contains(p.getUniqueId()) || state != State.PLAYING) { e.setCancelled(true); return; }
        if (act.equals("candle")) {
            e.setCancelled(true);
            e.getItem().remove(); spawned.remove(e.getItem());
            score.merge(p.getUniqueId(), 1, Integer::sum);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            List<Location> s = spots();
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (state == State.PLAYING && game == Game.CANDLES) dropCandle(s.get(rnd.nextInt(s.size()))); }, 200L);
        } else if (act.equals("relic")) {
            p.playSound(p.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 1f, 1f);
            p.sendActionBar(ChatColor.AQUA + "You found a relic! Take it to the Great Hall table.");
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (inWorld(e.getPlayer()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true); }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { /* swapping the torch to the main hand is fine */ }

    @EventHandler
    public void onBreak(BlockBreakEvent e) { if (inWorld(e.getPlayer()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true); }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { if (inWorld(e.getPlayer()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true); }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        // mobs ignore people in the lobby, ghosts and those who escaped
        if (e.getTarget() instanceof Player p && inWorld(p) && (!alive.contains(p.getUniqueId()) || escaped.contains(p.getUniqueId()))) e.setCancelled(true);
    }

    /** /nightfall start|stop|skip for the owner. */
    void admin(Player p, String sub) {
        switch (sub) {
            case "start" -> { if (state == State.LOBBY || state == State.COUNTDOWN) { if (game == null) game = pick(Math.max(2, lobbyPlayers().size())); state = State.COUNTDOWN; timer = 3; } }
            case "stop" -> { if (state == State.PLAYING || state == State.MEETING) end("Stopped by the owner", null); }
            default -> {
                for (Game g : Game.values()) if (g.name().equalsIgnoreCase(sub)) { game = g; if (state == State.LOBBY) { state = State.COUNTDOWN; timer = 10; } announceNext(); return; }
                p.sendMessage(ChatColor.RED + "/nightfall start|stop|impostor|escape|candles|relics");
            }
        }
    }

    String status() {
        return switch (state) {
            case LOBBY -> lobbyPlayers().isEmpty() ? "Empty - be the first!" : lobbyPlayers().size() + " in the lobby";
            case COUNTDOWN -> game.title + " in " + timer + "s";
            case PLAYING, MEETING -> game.title + " • " + alive.size() + " alive";
            case ENDING -> "Round ending";
        };
    }
}
