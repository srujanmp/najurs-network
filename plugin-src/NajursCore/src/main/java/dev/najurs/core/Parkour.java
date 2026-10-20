package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * Hub Parkour runner. The course layout (start, checkpoints, launch pads, finish) comes from
 * plugins/NajursCore/parkour.yml, written by tools/build/parkour.py. Best times live in data.yml.
 */
final class Parkour implements Listener {
    record Point(String name, double x, double y, double z) {
        Location loc(World w) { return new Location(w, x, y, z, 180, 0); }
        boolean near(Location l, double r) { return Math.abs(l.getX() - x) < r && Math.abs(l.getY() - y) < 1.6 && Math.abs(l.getZ() - z) < r; }
    }
    static final class Run { long start, warn; int cp = -1; final List<Long> splits = new ArrayList<>(); Run(long s) { start = s; } }

    private final NajursCore plugin;
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Long> launched = new HashMap<>();
    private Point start, finish, board;
    private final List<Point> cps = new ArrayList<>();
    private final List<Point> pads = new ArrayList<>();
    private int minX, maxX, minZ, maxZ;

    Parkour(NajursCore plugin) {
        this.plugin = plugin;
        File f = new File(plugin.getDataFolder(), "parkour.yml");
        if (!f.exists()) { plugin.getLogger().warning("parkour.yml missing - run tools/build/parkour.py course"); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        start = point(y.getConfigurationSection("start"), "Start");
        finish = point(y.getConfigurationSection("finish"), "Finish");
        board = point(y.getConfigurationSection("board"), "Board");
        for (Map<?, ?> m : y.getMapList("checkpoints")) cps.add(point(m));
        for (Map<?, ?> m : y.getMapList("launch")) pads.add(point(m));
        minX = y.getInt("lane.x1"); maxX = y.getInt("lane.x2"); minZ = y.getInt("lane.z1"); maxZ = y.getInt("lane.z2");
    }

    static Point point(ConfigurationSection s, String name) { return new Point(name, s.getDouble("x"), s.getDouble("y"), s.getDouble("z")); }
    static Point point(Map<?, ?> m) {
        return new Point(String.valueOf(m.containsKey("name") ? m.get("name") : ""), ((Number) m.get("x")).doubleValue(),
                ((Number) m.get("y")).doubleValue(), ((Number) m.get("z")).doubleValue());
    }

    boolean ready() { return start != null; }

    void start() {
        if (!ready()) return;
        Bukkit.getScheduler().runTaskTimer(plugin, this::hud, 20L, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, this::updateBoard, 60L);
    }

    World lobby() { return Bukkit.getWorld(Hub.LOBBY); }

    boolean inLane(Location l) { return l.getX() >= minX && l.getX() <= maxX && l.getZ() >= minZ && l.getZ() <= maxZ; }

    static String fmt(long ms) { return String.format("%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000); }

    private void hud() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Run> e : runs.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Run r = e.getValue();
            p.sendActionBar(ChatColor.AQUA + "⏱ " + ChatColor.WHITE + fmt(now - r.start) + ChatColor.DARK_GRAY + "  |  "
                    + ChatColor.GOLD + "Checkpoint " + (r.cp + 1) + "/" + cps.size());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!ready() || e.getTo() == null) return;
        Player p = e.getPlayer();
        if (!Hub.inHub(p) || p.getGameMode() == GameMode.SPECTATOR) return;
        Location to = e.getTo();
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        Run r = runs.get(p.getUniqueId());

        if (start.near(to, 1.2) && (r == null || r.cp >= 0 || System.currentTimeMillis() - r.start > 1500)) {
            boolean restart = r != null;
            runs.put(p.getUniqueId(), new Run(System.currentTimeMillis()));
            p.setAllowFlight(false);
            p.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "GO!", ChatColor.GRAY + (restart ? "Run restarted" : "6 zones • " + cps.size() + " checkpoints"), 0, 25, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            plugin.hub.giveParkourItems(p);
            return;
        }
        if (r == null) {
            // not running, but fell off the course: back to the start plate
            if (inLane(to) && to.getY() < 50) { p.setFallDistance(0); p.teleport(new Location(lobby(), start.x(), start.y(), start.z() + 1.5, 180, 0)); }
            return;
        }

        // launch pads: step on the slime block to get flung to the next island
        for (Point pad : pads) {
            if (Math.abs(to.getX() - (pad.x() + 0.5)) < 0.9 && Math.abs(to.getZ() - (pad.z() + 0.5)) < 0.9 && Math.abs(to.getY() - (pad.y() + 1)) < 0.6
                    && System.currentTimeMillis() - launched.getOrDefault(p.getUniqueId(), 0L) > 800) {
                launched.put(p.getUniqueId(), System.currentTimeMillis());
                p.setVelocity(new Vector(0, 1.05, -1.25));
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SLIME_JUMP, 1f, 0.8f);
                p.getWorld().spawnParticle(Particle.ITEM_SLIME, p.getLocation(), 15, 0.3, 0.1, 0.3);
            }
        }
        for (int i = r.cp + 1; i < cps.size(); i++) {
            if (cps.get(i).near(to, 1.6)) {
                if (i > r.cp + 1) { warn(p, r, ChatColor.RED + "You skipped a checkpoint! Go back for #" + (r.cp + 2)); break; }
                r.cp = i;
                long t = System.currentTimeMillis() - r.start;
                r.splits.add(t);
                List<Long> pb = plugin.data.getLongList("parkour.splits." + p.getUniqueId());
                String diff = i < pb.size() ? "  " + (t <= pb.get(i) ? ChatColor.GREEN + "-" : ChatColor.RED + "+") + String.format("%.2fs", Math.abs(t - pb.get(i)) / 1000.0) + ChatColor.GRAY + " vs best" : "";
                p.sendTitle(ChatColor.GOLD + "✔ " + cps.get(i).name(), ChatColor.GRAY + "Checkpoint " + (i + 1) + "/" + cps.size() + "  •  " + fmt(t) + diff, 0, 35, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
                p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 25, 0.5, 0.8, 0.5);
                break;
            }
        }
        if (finish.near(to, 2.5)) {
            if (r.cp == cps.size() - 1) { complete(p, r); return; }
            warn(p, r, ChatColor.RED + "Finish needs every checkpoint - you have " + (r.cp + 1) + "/" + cps.size() + ".");
        }

        // fell: back to the last checkpoint (the run keeps going)
        double floor = (r.cp >= 0 ? cps.get(r.cp).y() : start.y()) - 12;
        if (to.getY() < Math.min(floor, 40) || (inLane(to) && to.getY() < floor)) back(p);
    }

    private void warn(Player p, Run r, String msg) {
        if (System.currentTimeMillis() - r.warn < 3000) return;
        r.warn = System.currentTimeMillis();
        p.sendMessage(msg);
    }

    void back(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) return;
        Point at = r.cp >= 0 ? cps.get(r.cp) : start;
        p.setFallDistance(0);
        p.setVelocity(new Vector());
        p.teleport(new Location(lobby(), at.x(), at.y(), at.z() + (r.cp >= 0 ? 0 : -1.5), 180, 0));
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
        p.sendTitle("", ChatColor.RED + "Fell - back to " + (r.cp >= 0 ? "checkpoint " + (r.cp + 1) : "the start"), 0, 20, 5);
    }

    void restart(Player p) {
        boolean was = runs.remove(p.getUniqueId()) != null;
        p.teleport(new Location(lobby(), start.x(), start.y(), start.z() + 1.5, 180, 0));
        p.sendMessage(ChatColor.GRAY + (was ? "Run cancelled. " : "") + "Step on the " + ChatColor.AQUA + "start plate" + ChatColor.GRAY + " to start the timer.");
    }

    void quit(Player p) {
        if (runs.remove(p.getUniqueId()) != null) p.sendActionBar(ChatColor.GRAY + "Parkour run cancelled");
    }

    private void complete(Player p, Run r) {
        runs.remove(p.getUniqueId());
        long t = System.currentTimeMillis() - r.start;
        String path = "parkour.best." + p.getUniqueId();
        long best = plugin.data.getLong(path, Long.MAX_VALUE);
        boolean pb = t < best;
        ConfigurationSection all = plugin.data.getConfigurationSection("parkour.best");
        boolean record = pb && (all == null || all.getKeys(false).stream().allMatch(k -> t < all.getLong(k) || k.equals(p.getUniqueId().toString())));
        if (pb) { plugin.data.set(path, t); plugin.data.set("parkour.names." + p.getUniqueId(), p.getName()); plugin.data.set("parkour.splits." + p.getUniqueId(), r.splits); }
        ConfigurationSection now = plugin.data.getConfigurationSection("parkour.best");
        long myBest = Math.min(t, best);
        int rank = 1 + (int) now.getKeys(false).stream().filter(k -> !k.equals(p.getUniqueId().toString()) && now.getLong(k) < myBest).count();
        String place = ChatColor.YELLOW + "  #" + rank + " of " + now.getKeys(false).size();
        p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "FINISHED!", ChatColor.WHITE + fmt(t) + (pb ? ChatColor.GOLD + "  NEW BEST!" : ChatColor.GRAY + "  best " + fmt(best)) + place, 5, 70, 15);
        Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.WHITE + p.getName() + ChatColor.YELLOW + " finished the Hub Parkour in "
                + ChatColor.AQUA + fmt(t) + (pb ? ChatColor.GOLD + " (personal best!)" : ""));
        if (record) Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "★ NEW SERVER RECORD ★ " + ChatColor.WHITE + p.getName() + ChatColor.YELLOW + " set the fastest Hub Parkour time: " + ChatColor.AQUA + fmt(t));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        for (int i = 0; i < 5; i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> firework(p.getLocation().add((n - 2) * 1.5, 0, 0)), i * 6L);
        }
        plugin.hub.giveHotbar(p);
        if (pb) updateBoard();
    }

    private void firework(Location l) {
        Firework fw = l.getWorld().spawn(l, Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        Color[] cs = {Color.AQUA, Color.LIME, Color.YELLOW, Color.FUCHSIA, Color.ORANGE};
        m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(cs[plugin.random.nextInt(cs.length)]).withFade(Color.WHITE).flicker(true).build());
        m.setPower(1);
        fw.setFireworkMeta(m);
    }

    /** Top 5 best times, shown on a text display beside the start. */
    void updateBoard() {
        World w = lobby();
        if (w == null || board == null) return;
        ConfigurationSection best = plugin.data.getConfigurationSection("parkour.best");
        List<Map.Entry<String, Long>> rows = new ArrayList<>();
        if (best != null) for (String id : best.getKeys(false)) rows.add(Map.entry(id, best.getLong(id)));
        rows.sort(Map.Entry.comparingByValue());
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "" + ChatColor.BOLD + "★ TOP 5 TIMES ★");
        String[] medals = {ChatColor.GOLD + "1.", ChatColor.GRAY + "2.", ChatColor.RED + "3.", ChatColor.WHITE + "4.", ChatColor.WHITE + "5."};
        for (int i = 0; i < 5; i++) {
            sb.append('\n');
            if (i < rows.size()) sb.append(medals[i]).append(' ').append(ChatColor.WHITE)
                    .append(plugin.data.getString("parkour.names." + rows.get(i).getKey(), "?")).append(ChatColor.DARK_GRAY).append(" - ")
                    .append(ChatColor.AQUA).append(fmt(rows.get(i).getValue()));
            else sb.append(ChatColor.DARK_GRAY).append(i + 1).append(". ---");
        }
        Location at = new Location(w, board.x(), board.y(), board.z());
        TextDisplay td = w.getNearbyEntitiesByType(TextDisplay.class, at, 1.5).stream()
                .filter(t -> t.getScoreboardTags().contains("pkboard")).findFirst()
                .orElseGet(() -> w.spawn(at, TextDisplay.class, t -> { t.addScoreboardTag("pkboard"); t.setBillboard(Display.Billboard.CENTER); }));
        td.setText(sb.toString());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { runs.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { runs.remove(e.getPlayer().getUniqueId()); }
}
