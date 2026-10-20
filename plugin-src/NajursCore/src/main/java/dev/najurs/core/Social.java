package dev.najurs.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.function.Consumer;

/** Friends: /join <player>, "who's playing what", join/leave notices, welcome-back and first-join greetings. */
final class Social implements Listener {
    /** What a player is doing; join == null means it can't be joined directly. */
    record Act(String label, Consumer<Player> join) {}

    private final NajursCore plugin;
    /** Players who finished AuthMe login; everyone else is still at the login prompt and invisible to friends. */
    private final Set<UUID> authed = new HashSet<>();

    Social(NajursCore plugin) { this.plugin = plugin; }

    // ---------- activity ----------

    Act activity(Player t) {
        Match.Arena a = plugin.match.arenaOf(t);
        if (a != null) return new Act(a.title, p -> plugin.match.join(p, a.id)); // mid-round = the waiting area
        String w = t.getWorld().getName();
        if (w.equals(PvP.WORLD)) {
            PvP.Mode m = plugin.pvp.modeAt(t.getLocation());
            String id = m == null ? "arena" : m.id().equals("colosseum") ? "arena" : m.id();
            return new Act(m == null ? "the PvP Arena" : m.title(), p -> p.performCommand("go " + id));
        }
        if (w.equals(Nightfall.WORLD)) return new Act("Nightfall", p -> plugin.nightfall.join(p));
        if (w.equals("bedwars")) return new Act("BedWars", p -> p.performCommand("bw join"));
        if (w.equals("hideseek") || w.equals("hs_horror") || w.startsWith("hs2_")) return new Act("Hide & Seek", p -> plugin.dialogs.hideSeek(p));
        if (w.equals(Hub.LOBBY)) return Hub.inParkour(t) ? new Act("Hub Parkour", p -> p.performCommand("go parkour")) : new Act("the Hub", p -> p.performCommand("hub"));
        String id = plugin.managedId(t.getWorld());
        if (id != null) return new Act(plugin.worlds().getString(id + ".name", id), p -> plugin.go(p, id));
        if (NajursCore.REMEMBERED.contains(w)) return new Act(plugin.prettyName(t.getWorld()), p -> p.performCommand("go " + w));
        return new Act(plugin.prettyName(t.getWorld()), null);
    }

    List<Player> others(Player me) {
        return Bukkit.getOnlinePlayers().stream().filter(o -> o != me && authed.contains(o.getUniqueId())).map(o -> (Player) o).toList();
    }

    /** Legacy-coloured lines for the main menu: "Arena Rounds: Alice, Bob". */
    List<String> who() {
        Map<String, List<String>> by = new LinkedHashMap<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (authed.contains(o.getUniqueId())) by.computeIfAbsent(activity(o).label(), k -> new ArrayList<>()).add(o.getName());
        List<String> out = new ArrayList<>();
        for (var e : by.entrySet()) if (out.size() < 6) out.add("&a" + e.getKey() + "&7: &f" + String.join("&7, &f", e.getValue()));
        return out;
    }

    // ---------- /join ----------

    void join(Player p, String[] a) {
        if (a.length == 0) { friends(p); return; }
        Player t = Bukkit.getPlayer(a[0]);
        if (t == null || !authed.contains(t.getUniqueId())) {
            p.sendMessage(ChatColor.RED + a[0] + " isn't online." + (others(p).isEmpty() ? "" : ChatColor.GRAY + " Online: " + ChatColor.WHITE + String.join(", ", others(p).stream().map(Player::getName).toList())));
            return;
        }
        if (t == p) { p.sendMessage(ChatColor.YELLOW + "That's you! Pick a friend: /join <name>"); return; }
        Act act = activity(t);
        if (act.join() == null) { p.sendMessage(ChatColor.YELLOW + t.getName() + " is in " + act.label() + ", which can't be joined directly. Try " + ChatColor.AQUA + "/tpa " + t.getName()); return; }
        if (activity(p).label().equals(act.label())) { p.sendMessage(ChatColor.YELLOW + "You're already in " + act.label() + " with " + t.getName() + ". Try " + ChatColor.AQUA + "/tpa " + t.getName()); return; }
        p.sendMessage(ChatColor.GOLD + "» " + ChatColor.YELLOW + "Joining " + ChatColor.WHITE + t.getName() + ChatColor.YELLOW + " in " + ChatColor.WHITE + act.label());
        t.sendActionBar(Component.text(p.getName() + " is joining you!", NamedTextColor.GREEN));
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.5f);
        act.join().accept(p);
    }

    void friends(Player p) {
        if (Dialogs.supported(p)) { plugin.dialogs.friends(p); return; }
        List<Player> o = others(p);
        if (o.isEmpty()) { p.sendMessage(ChatColor.YELLOW + "No friends online right now."); return; }
        p.sendMessage(ChatColor.GOLD + "Friends online " + ChatColor.GRAY + "(click one to join)");
        for (Player t : o) p.sendMessage(Component.text(" » " + t.getName(), NamedTextColor.GREEN).append(Component.text(" - " + activity(t).label(), NamedTextColor.GRAY))
                .clickEvent(ClickEvent.runCommand("/join " + t.getName())));
    }

    // ---------- join / leave ----------

    /** AuthMe freezes new arrivals at the login prompt; wait for the login so greetings land at the right moment. */
    private void whenAuthed(Player p, int tries, Runnable r) {
        if (!p.isOnline()) return;
        if (loggedIn(p)) { r.run(); return; }
        if (tries > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> whenAuthed(p, tries - 1, r), 20L);
    }

    private boolean loggedIn(Player p) {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) return true;
        try {
            Class<?> c = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            return (boolean) c.getMethod("isAuthenticated", Player.class).invoke(c.getMethod("getInstance").invoke(null), p);
        } catch (Throwable t) { return true; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        whenAuthed(p, 300, () -> welcome(p, first));
    }

    private void welcome(Player p, boolean first) {
        UUID id = p.getUniqueId();
        authed.add(id);
        // rejoined while standing in the PvP world with no round/queue (quit mid-round): nothing to rejoin, go home
        if (p.getWorld().getName().equals(PvP.WORLD) && !plugin.match.inMatch(p)) p.performCommand("hub");
        if (Hub.inHub(p)) p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "NAJURS " + ChatColor.AQUA + ChatColor.BOLD + "NETWORK", ChatColor.GRAY + "Right-click the compass to play", 10, 60, 15);
        else p.sendActionBar(Component.text("Welcome back! Type /hub to go to the hub", NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f);
        if (first) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Welcome " + ChatColor.WHITE + p.getName() + ChatColor.YELLOW + " to NAJURS NETWORK for the first time!");
            p.sendMessage(ChatColor.GRAY + "Tips: " + ChatColor.AQUA + "/play" + ChatColor.GRAY + " opens the game menu, " + ChatColor.AQUA + "/join <friend>" + ChatColor.GRAY + " jumps into their game, " + ChatColor.AQUA + "/hub" + ChatColor.GRAY + " brings you home.");
        } else {
            String at = plugin.data.getString("seen." + id + ".where");
            long ms = System.currentTimeMillis() - plugin.data.getLong("seen." + id + ".at", 0);
            if (at != null) p.sendMessage(ChatColor.GREEN + "Welcome back, " + p.getName() + "! " + ChatColor.GRAY + "Last time you were in " + ChatColor.WHITE + at + ChatColor.GRAY + " (" + ago(ms) + ").");
            List<String> names = others(p).stream().map(Player::getName).toList();
            if (!names.isEmpty()) p.sendMessage(ChatColor.GRAY + "Online now: " + ChatColor.WHITE + String.join(", ", names) + ChatColor.GRAY + " - " + ChatColor.AQUA + "/join <name>");
            for (Player o : others(p)) {
                o.sendActionBar(Component.text("+ " + p.getName() + " joined", NamedTextColor.GREEN));
                o.playSound(o.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.5f);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!authed.remove(p.getUniqueId())) return;
        plugin.data.set("seen." + p.getUniqueId() + ".at", System.currentTimeMillis());
        plugin.data.set("seen." + p.getUniqueId() + ".where", activity(p).label());
        for (Player o : others(p)) {
            o.sendActionBar(Component.text("- " + p.getName() + " left", NamedTextColor.GRAY));
            o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 1.2f);
        }
    }

    static String ago(long ms) {
        long m = ms / 60000;
        return m < 2 ? "just now" : m < 120 ? m + " minutes ago" : m < 2880 ? m / 60 + " hours ago" : m / 1440 + " days ago";
    }
}
