package dev.najurs.core;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Supplier;

/**
 * Hub leaderboards: a Top 3 board beside every game station, plus the Hall of Fame wall by spawn.
 * Each source is read defensively - a game with no data yet shows "be the first!".
 */
final class Boards {
    record Row(String name, String value) {}
    record Board(String tag, double x, double y, double z, String title, ChatColor colour, Supplier<List<Row>> rows) {}

    private final NajursCore plugin;
    private final List<Board> boards = new ArrayList<>();

    Boards(NajursCore plugin) {
        this.plugin = plugin;
        boards.add(new Board("lb_survival", 4.5, 67.2, -19.5, "TOP PLAYTIME", ChatColor.GREEN, this::playtime));
        boards.add(new Board("lb_skyblock", 19.1, 67.2, -5.8, "TOP ISLANDS", ChatColor.AQUA, () -> level("bskyblock")));
        boards.add(new Board("lb_oneblock", 14.6, 67.2, 13.6, "TOP ONEBLOCKS", ChatColor.YELLOW, () -> level("aoneblock")));
        boards.add(new Board("lb_bedwars", -4.5, 67.2, 19.5, "TOP BEDWARS", ChatColor.RED, this::bedwars));
        boards.add(new Board("lb_hideseek", -19.1, 67.2, 5.8, "TOP HIDE & SEEK", ChatColor.LIGHT_PURPLE, this::hideSeek));
        boards.add(new Board("lb_pvp", -14.6, 67.2, -13.6, "TOP PVP", ChatColor.GOLD, this::pvp));
    }

    void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 20L * 30); }

    void refresh() {
        World hub = Bukkit.getWorld(Hub.LOBBY);
        if (hub == null) return;
        for (Board b : boards) {
            List<Row> rows;
            try { rows = b.rows().get(); } catch (Throwable t) { rows = List.of(); }
            StringBuilder sb = new StringBuilder(b.colour() + "" + ChatColor.BOLD + b.title());
            for (int i = 0; i < 3; i++) {
                sb.append('\n');
                if (i < rows.size()) sb.append(i == 0 ? ChatColor.GOLD : i == 1 ? ChatColor.GRAY : ChatColor.RED).append(i + 1).append(". ")
                        .append(ChatColor.WHITE).append(rows.get(i).name()).append(ChatColor.DARK_GRAY).append(" - ").append(ChatColor.YELLOW).append(rows.get(i).value());
                else sb.append(ChatColor.DARK_GRAY).append(i + 1).append(". be the first!");
            }
            place(hub, b.tag(), new Location(hub, b.x(), b.y(), b.z()), sb.toString(), 0.8f);
        }
        hallOfFame(hub);
    }

    static void place(World w, String tag, Location at, String text, float scale) {
        TextDisplay td = w.getNearbyEntitiesByType(TextDisplay.class, at, 2).stream().filter(t -> t.getScoreboardTags().contains(tag)).findFirst()
                .orElseGet(() -> w.spawn(at, TextDisplay.class, t -> {
                    t.addScoreboardTag(tag);
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                    t.setShadowed(true);
                    var tr = t.getTransformation();
                    tr.getScale().set(scale);
                    t.setTransformation(tr);
                }));
        td.setText(text);
    }

    // ---------- sources ----------

    List<Row> playtime() {
        List<Map.Entry<String, Integer>> all = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() == null) continue;
            try { all.add(Map.entry(p.getName(), p.getStatistic(Statistic.PLAY_ONE_MINUTE))); } catch (Exception ignored) {}
        }
        all.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return all.stream().limit(3).map(e -> new Row(e.getKey(), e.getValue() / 72000 + "h " + (e.getValue() / 1200) % 60 + "m")).toList();
    }

    List<Row> level(String game) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return List.of();
        List<Row> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String name = PlaceholderAPI.setPlaceholders(null, "%Level_" + game + "_top_name_" + i + "%");
            String value = PlaceholderAPI.setPlaceholders(null, "%Level_" + game + "_top_value_" + i + "%");
            if (name == null || name.isBlank() || name.contains("%")) break;
            out.add(new Row(name, "level " + value));
        }
        return out;
    }

    List<Row> bedwars() {
        if (Bukkit.getPluginManager().getPlugin("BedWars") == null) return List.of();
        return BedwarsHook.top(3).stream().map(e -> new Row(e[0], e[1] + " score")).toList();
    }

    List<Row> hideSeek() {
        File db = new File(plugin.getDataFolder().getParentFile(), "KenshinsHideAndSeek/khs.db");
        if (!db.exists()) return List.of();
        List<Row> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             ResultSet r = c.createStatement().executeQuery(
                     "SELECT name, seeker_wins + hider_wins AS w FROM hs_players WHERE seeker_wins + hider_wins > 0 ORDER BY w DESC LIMIT 3")) {
            while (r.next()) out.add(new Row(r.getString(1), r.getInt(2) + " wins"));
        } catch (Exception e) { plugin.getLogger().fine("hide & seek stats unavailable: " + e.getMessage()); }
        return out;
    }

    List<Row> pvp() {
        Map<String, int[]> totals = new HashMap<>();
        ConfigurationSection all = plugin.data.getConfigurationSection("pvp");
        if (all != null) for (String mode : all.getKeys(false)) {
            ConfigurationSection sec = all.getConfigurationSection(mode);
            for (String id : sec.getKeys(false)) {
                int[] t = totals.computeIfAbsent(id, k -> new int[2]);
                t[0] += sec.getInt(id + ".kills"); t[1] += sec.getInt(id + ".wins");
            }
        }
        return totals.entrySet().stream().filter(e -> e.getValue()[0] + e.getValue()[1] > 0)
                .sorted((a, b) -> Integer.compare(b.getValue()[0] + 3 * b.getValue()[1], a.getValue()[0] + 3 * a.getValue()[1])).limit(3)
                .map(e -> new Row(plugin.data.getString("names." + e.getKey(), "?"), e.getValue()[0] + "K " + e.getValue()[1] + "W")).toList();
    }

    /** Big board beside spawn mixing every game's best. */
    void hallOfFame(World hub) {
        StringBuilder sb = new StringBuilder(ChatColor.GREEN + "" + ChatColor.BOLD + "✦ HALL OF FAME ✦");
        section(sb, "⚔ PvP (kills / wins)", ChatColor.GOLD, pvp());
        section(sb, "♦ BedWars", ChatColor.RED, bedwars());
        List<Map.Entry<String, Long>> pk = new ArrayList<>();
        ConfigurationSection best = plugin.data.getConfigurationSection("parkour.best");
        if (best != null) for (String id : best.getKeys(false)) pk.add(Map.entry(id, best.getLong(id)));
        pk.sort(Map.Entry.comparingByValue());
        section(sb, "⏱ Parkour", ChatColor.AQUA, pk.stream().limit(3).map(e -> new Row(plugin.data.getString("parkour.names." + e.getKey(), "?"), Parkour.fmt(e.getValue()))).toList());
        List<Map.Entry<String, Integer>> goose = new ArrayList<>();
        ConfigurationSection g = plugin.data.getConfigurationSection("goose.best");
        if (g != null) for (String id : g.getKeys(false)) goose.add(Map.entry(id, g.getInt(id)));
        goose.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        section(sb, "⚡ Goose Chase (best wave)", ChatColor.YELLOW, goose.stream().limit(3).map(e -> new Row(plugin.data.getString("names." + e.getKey(), "?"), "wave " + e.getValue())).toList());
        place(hub, "hubboard", new Location(hub, -7.5, 68.0, 12.5), sb.toString(), 0.9f);
    }

    private static void section(StringBuilder sb, String title, ChatColor c, List<Row> rows) {
        sb.append("\n\n").append(c).append(ChatColor.BOLD).append(title);
        if (rows.isEmpty()) sb.append('\n').append(ChatColor.DARK_GRAY).append("no scores yet - be first!");
        for (int i = 0; i < rows.size(); i++)
            sb.append('\n').append(ChatColor.GRAY).append(i + 1).append(". ").append(ChatColor.WHITE).append(rows.get(i).name())
                    .append(ChatColor.DARK_GRAY).append(" - ").append(ChatColor.YELLOW).append(rows.get(i).value());
    }
}
