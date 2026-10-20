package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * Public scores for the PvP modes in world `arena`: kills, deaths, streaks (and Spleef wins).
 * Sumo / Knockback FFA / Spleef falls are handled here so the last player to hit you gets the kill.
 * Each mode has a live Top 5 board beside it, a kill feed, and stats on your action bar.
 */
final class PvP implements Listener {
    record Mode(String id, String title, ChatColor colour, double cx, double cz, double radius, double fallY, Location spawn, double[] board) {}

    static final String WORLD = "arena";
    private final NajursCore plugin;
    private final List<Mode> modes = new ArrayList<>();
    private final Map<UUID, UUID> lastHitter = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private final Map<String, Integer> streak = new HashMap<>();   // "<mode>:<uuid>" -> current streak
    private final Set<String> dirtyBoards = new HashSet<>();
    private final Map<UUID, Long> shielded = new HashMap<>();   // spawn protection: uuid -> expiry millis

    PvP(NajursCore plugin) { this.plugin = plugin; }

    void start() {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        modes.add(new Mode("colosseum", "Colosseum FFA", ChatColor.GOLD, 0, 0, 23, -999, null, new double[]{0.5, 72, 28.5}));
        modes.add(new Mode("sumo", "Sumo", ChatColor.WHITE, 100, 0, 20, 54, new Location(w, 100.5, 65, 4.5, 180, 0), new double[]{100.5, 70.5, -9.5}));
        modes.add(new Mode("kbffa", "Knockback FFA", ChatColor.YELLOW, -100, 0, 20, 49, new Location(w, -99.5, 65, 0.5, 0, 0), new double[]{-100.5, 72, -14.5}));
        // stats only: Team Battle rounds run on the shared map pool, so the area never matches (cz far away)
        modes.add(new Mode("teams", "Team Battle", ChatColor.RED, 0, -99999, 0, -999, null, new double[]{0.5, 72, -28.5}));
        modes.add(new Mode("spleef", "Spleef", ChatColor.AQUA, 0, 100, 20, 66, new Location(w, 0.5, 71, 114.5, 180, 0), new double[]{7.5, 74, 115.5}));
        Bukkit.getScheduler().runTaskTimer(plugin, this::hud, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushBoards, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 20L * 30, 20L * 30);
        modes.forEach(m -> dirtyBoards.add(m.id()));
    }

    Mode modeAt(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(WORLD)) return null;
        for (Mode m : modes) {
            double dx = l.getX() - m.cx(), dz = l.getZ() - m.cz();
            if (m.id().equals("colosseum") ? dx * dx + dz * dz <= m.radius() * m.radius() && l.getY() > 60 && l.getY() < 90
                    : Math.abs(dx) <= m.radius() && Math.abs(dz) <= m.radius()) return m;
        }
        return null;
    }

    int count(String id) {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return 0;
        return (int) w.getPlayers().stream().filter(p -> { Mode m = modeAt(p.getLocation()); return m != null && m.id().equals(id); }).count();
    }

    // ---------- stats ----------

    String key(Mode m, UUID id) { return "pvp." + m.id() + "." + id; }
    int stat(Mode m, UUID id, String s) { return plugin.data.getInt(key(m, id) + "." + s, 0); }
    void add(Mode m, Player p, String s, int n) {
        plugin.data.set(key(m, p.getUniqueId()) + "." + s, stat(m, p.getUniqueId(), s) + n);
        plugin.data.set("names." + p.getUniqueId(), p.getName());
        dirtyBoards.add(m.id());
    }

    String modeOf(Player p) { Mode m = modeAt(p.getLocation()); return m == null ? "" : m.title(); }
    int current(Player p, String s) {
        Mode m = modeAt(p.getLocation());
        if (m == null) return 0;
        return s.equals("streak") ? streak.getOrDefault(m.id() + ":" + p.getUniqueId(), 0) : stat(m, p.getUniqueId(), s);
    }

    void kill(Mode m, Player killer, Player victim, String how) {
        add(m, killer, "kills", 1);
        add(m, victim, "deaths", 1);
        int s = streak.merge(m.id() + ":" + killer.getUniqueId(), 1, Integer::sum);
        streak.remove(m.id() + ":" + victim.getUniqueId());
        if (s > stat(m, killer.getUniqueId(), "beststreak")) plugin.data.set(key(m, killer.getUniqueId()) + ".beststreak", s);
        feed(m.colour() + "⚔ " + ChatColor.WHITE + killer.getName() + ChatColor.GRAY + " " + how + " " + ChatColor.WHITE + victim.getName()
                + ChatColor.DARK_GRAY + " [" + m.title() + "]" + (s > 1 ? ChatColor.GOLD + " " + s + " streak" : ""));
        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        killer.sendActionBar(ChatColor.GREEN + "+1 kill " + ChatColor.GRAY + "(" + victim.getName() + ")" + (s > 1 ? ChatColor.GOLD + "  streak " + s : ""));
        victim.sendMessage(ChatColor.GRAY + killer.getName() + " had " + ChatColor.RED + String.format("%.1f", killer.getHealth() / 2) + "❤" + ChatColor.GRAY + " left.");
        if (s == 3 || s == 5 || s == 10 || s % 15 == 0)
            Bukkit.broadcastMessage(ChatColor.GOLD + "✹ " + ChatColor.WHITE + killer.getName() + ChatColor.YELLOW + " is on a "
                    + ChatColor.RED + ChatColor.BOLD + s + " kill streak" + ChatColor.YELLOW + " in " + m.title() + "!");
    }

    void feed(String msg) {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) for (Player p : w.getPlayers()) p.sendMessage(msg);
    }

    Mode mode(String id) { return modes.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null); }
    void recordKill(String id, Player k, Player v, String how) { Mode m = mode(id); if (m != null) kill(m, k, v, how); }
    void recordDeath(String id, Player v) { Mode m = mode(id); if (m != null) { add(m, v, "deaths", 1); streak.remove(id + ":" + v.getUniqueId()); } }
    void recordWin(String id, Player w) { Mode m = mode(id); if (m != null) add(m, w, "wins", 1); }

    /** Totals across every PvP mode, for the hub sidebar and leaderboard wall. */
    int total(UUID id, String stat) { int t = 0; for (Mode m : modes) t += stat(m, id, stat); return t; }

    /** Spawn protection: no damage taken or dealt for `ms` (void still counts). */
    void protect(Player p, long ms) { shielded.put(p.getUniqueId(), System.currentTimeMillis() + ms); }
    boolean shielded(Player p) { return shielded.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis(); }

    // ---------- events ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProtect(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player v && v.getWorld().getName().equals(WORLD) && e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID) {
            if (shielded(v)) { e.setCancelled(true); return; }
            Entity d = e instanceof EntityDamageByEntityEvent ed ? ed.getDamager() : null;
            Player a = d instanceof Player x ? x : d instanceof Projectile pr && pr.getShooter() instanceof Player x ? x : null;
            if (a != null && shielded(a)) { e.setCancelled(true); a.sendActionBar(ChatColor.YELLOW + "Spawn protection - you can't hit yet"); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !victim.getWorld().getName().equals(WORLD)) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker == null || attacker == victim) return;
        lastHitter.put(victim.getUniqueId(), attacker.getUniqueId());
        lastHitAt.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    Player recentHitter(Player victim) {
        UUID id = lastHitter.get(victim.getUniqueId());
        if (id == null || System.currentTimeMillis() - lastHitAt.getOrDefault(victim.getUniqueId(), 0L) > 8000) return null;
        return Bukkit.getPlayer(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null || !p.getWorld().getName().equals(WORLD) || e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        Mode m = modeAt(e.getTo());
        if (m == null || m.spawn() == null || e.getTo().getY() >= m.fallY() || p.getGameMode() == GameMode.SPECTATOR) return;
        if (plugin.match.handles(m.id())) { if (!plugin.match.fighting(p)) plugin.match.toWait(p, m.id()); return; } // rounds handle their own falls
        Player k = recentHitter(p);
        if (m.id().equals("spleef")) spleefFall(m, p, k);
        else if (k != null) kill(m, k, p, m.id().equals("sumo") ? "pushed" : "knocked");
        else { add(m, p, "deaths", 1); streak.remove(m.id() + ":" + p.getUniqueId());
               feed(m.colour() + "☠ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " fell off" + ChatColor.DARK_GRAY + " [" + m.title() + "]"); }
        lastHitter.remove(p.getUniqueId());
        p.setFallDistance(0);
        p.setFireTicks(0);
        p.setVelocity(new org.bukkit.util.Vector());
        p.teleport(m.spawn());
        protect(p, 3000);
    }

    private void spleefFall(Mode m, Player p, Player k) {
        if (k != null) kill(m, k, p, "spleefed"); else { add(m, p, "deaths", 1); feed(ChatColor.AQUA + "☠ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " fell in [Spleef]"); }
        // last one standing on the snow wins the round; the floor then resets itself
        List<Player> standing = Bukkit.getWorld(WORLD).getPlayers().stream().filter(o -> o != p && o.getGameMode() != GameMode.SPECTATOR
                && Math.abs(o.getLocation().getX() - m.cx()) <= 10.5 && Math.abs(o.getLocation().getZ() - m.cz()) <= 10.5 && o.getLocation().getY() >= 69).toList();
        if (standing.size() == 1) {
            Player w = standing.get(0);
            add(m, w, "wins", 1);
            Bukkit.broadcastMessage(ChatColor.AQUA + "❄ " + ChatColor.WHITE + w.getName() + ChatColor.YELLOW + " won a round of Spleef! "
                    + ChatColor.GRAY + "(" + stat(m, w.getUniqueId(), "wins") + " wins)");
            w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.console("execute in minecraft:arena run fill -10 70 90 10 70 110 snow_block replace air");
                feed(ChatColor.AQUA + "❄ Spleef floor reset - next round!");
            }, 60L);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Mode m = modeAt(v.getLocation());
        if (m == null || plugin.match.handles(m.id())) return;
        Player k = v.getKiller() != null ? v.getKiller() : recentHitter(v);
        if (k != null && k != v) kill(m, k, v, "slayed");
        else { add(m, v, "deaths", 1); streak.remove(m.id() + ":" + v.getUniqueId()); }
        e.setDeathMessage(null); // our kill feed replaces the vanilla message
        lastHitter.remove(v.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastHitter.remove(e.getPlayer().getUniqueId()); shielded.remove(e.getPlayer().getUniqueId()); }

    // ---------- display ----------

    private void hud() {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            Mode m = modeAt(p.getLocation());
            if (m == null || plugin.match.inMatch(p)) continue; // round players get the round's own action bar
            UUID id = p.getUniqueId();
            String extra = m.id().equals("spleef") ? ChatColor.GRAY + "  Wins " + ChatColor.WHITE + stat(m, id, "wins") : "";
            p.sendActionBar(m.colour() + "" + ChatColor.BOLD + m.title() + ChatColor.DARK_GRAY + "  |  " + ChatColor.GRAY + "Kills " + ChatColor.WHITE + stat(m, id, "kills")
                    + ChatColor.GRAY + "  Deaths " + ChatColor.WHITE + stat(m, id, "deaths") + ChatColor.GRAY + "  Streak " + ChatColor.GOLD
                    + streak.getOrDefault(m.id() + ":" + id, 0) + extra);
        }
    }

    private void flushBoards() {
        if (dirtyBoards.isEmpty()) return;
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        for (Mode m : modes) if (dirtyBoards.remove(m.id())) board(w, m);
    }

    private void board(World w, Mode m) {
        String sortBy = plugin.match.handles(m.id()) ? "wins" : "kills";
        ConfigurationSection sec = plugin.data.getConfigurationSection("pvp." + m.id());
        List<Map.Entry<String, Integer>> rows = new ArrayList<>();
        if (sec != null) for (String id : sec.getKeys(false)) rows.add(Map.entry(id, sec.getInt(id + "." + sortBy)));
        rows.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder sb = new StringBuilder(m.colour() + "" + ChatColor.BOLD + m.title().toUpperCase() + ChatColor.GRAY + " - TOP " + sortBy.toUpperCase());
        for (int i = 0; i < 5; i++) {
            sb.append('\n');
            if (i < rows.size() && rows.get(i).getValue() > 0) {
                String id = rows.get(i).getKey();
                sb.append(i == 0 ? ChatColor.GOLD : i == 1 ? ChatColor.GRAY : i == 2 ? ChatColor.RED : ChatColor.WHITE).append(i + 1).append(". ")
                        .append(ChatColor.WHITE).append(plugin.data.getString("names." + id, "?")).append(ChatColor.DARK_GRAY).append(" - ")
                        .append(ChatColor.YELLOW).append(rows.get(i).getValue()).append(ChatColor.GRAY).append(" ").append(sortBy)
                        .append(ChatColor.DARK_GRAY).append(" (best streak ").append(sec.getInt(id + ".beststreak")).append(")");
            } else sb.append(ChatColor.DARK_GRAY).append(i + 1).append(". ---");
        }
        Location at = new Location(w, m.board()[0], m.board()[1], m.board()[2]);
        String tag = "pvpboard_" + m.id();
        TextDisplay td = w.getNearbyEntitiesByType(TextDisplay.class, at, 2).stream().filter(t -> t.getScoreboardTags().contains(tag)).findFirst()
                .orElseGet(() -> w.spawn(at, TextDisplay.class, t -> { t.addScoreboardTag(tag); t.setBillboard(Display.Billboard.CENTER); }));
        td.setText(sb.toString());
    }

    /** Arrows and dropped items pile up in a PvP world; clear them so the arena stays smooth. */
    private void cleanup() {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        for (Entity e : w.getEntities())
            if (e instanceof Item || (e instanceof AbstractArrow a && (a.isInBlock() || a.getTicksLived() > 200)) || e instanceof FallingBlock) e.remove();
    }
}
