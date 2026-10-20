package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.meta.FireworkMeta;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.RunningTeam;
import org.screamingsandals.bedwars.api.events.BedwarsGameEndingEvent;
import org.screamingsandals.bedwars.api.events.BedwarsGameStartedEvent;
import org.screamingsandals.bedwars.api.events.BedwarsTargetBlockDestroyedEvent;
import org.screamingsandals.bedwars.api.statistics.LeaderboardEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Only loaded when ScreamingBedWars is installed: victory/defeat celebration and leaderboard access. */
final class BedwarsHook implements Listener {
    private final NajursCore plugin;
    BedwarsHook(NajursCore plugin) { this.plugin = plugin; }

    /** name, players, max players, status (WAITING / RUNNING / ...) for every arena. */
    static List<String[]> games() {
        List<String[]> out = new ArrayList<>();
        try {
            for (var g : BedwarsAPI.getInstance().getGames())
                out.add(new String[]{g.getName(), String.valueOf(g.countConnectedPlayers()), String.valueOf(g.getMaxPlayers()), g.getStatus().name()});
        } catch (Throwable ignored) {}
        return out;
    }

    static List<String[]> top(int n) {
        List<String[]> out = new ArrayList<>();
        try {
            for (LeaderboardEntry e : BedwarsAPI.getInstance().getStatisticsManager().getLeaderboard(n))
                if (e.getTotalScore() > 0) out.add(new String[]{e.getLatestKnownName(), String.valueOf(e.getTotalScore())});
        } catch (Throwable ignored) {}
        return out;
    }

    @EventHandler
    public void onStart(BedwarsGameStartedEvent e) {
        Bukkit.broadcastMessage(ChatColor.RED + "♦ BedWars " + ChatColor.WHITE + e.getGame().getName() + ChatColor.GRAY
                + " has started with " + e.getGame().getConnectedPlayers().size() + " players. " + ChatColor.YELLOW + "/bwmenu" + ChatColor.GRAY + " to play next!");
    }

    @EventHandler
    public void onBed(BedwarsTargetBlockDestroyedEvent e) {
        for (Player p : e.getTeam().getConnectedPlayers()) {
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "BED DESTROYED!", ChatColor.GRAY + "You will no longer respawn", 5, 50, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.6f, 1.2f);
        }
    }

    @EventHandler
    public void onEnd(BedwarsGameEndingEvent e) {
        RunningTeam win = e.getWinningTeam();
        String map = e.getGame().getName();
        if (win == null) { Bukkit.broadcastMessage(ChatColor.GRAY + "♦ BedWars " + map + " ended in a draw."); return; }
        String names = win.getConnectedPlayers().stream().map(Player::getName).collect(Collectors.joining(", "));
        Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.BOLD + "Team " + win.getName() + ChatColor.YELLOW + " won BedWars "
                + ChatColor.WHITE + map + ChatColor.GRAY + " (" + names + ")");
        for (Player p : e.getGame().getConnectedPlayers()) {
            boolean won = win.isPlayerInTeam(p);
            p.sendTitle(won ? ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!" : ChatColor.RED + "" + ChatColor.BOLD + "DEFEAT",
                    won ? ChatColor.YELLOW + "Team " + win.getName() + " wins " + map : ChatColor.GRAY + "Team " + win.getName() + " won - rematch from /bwmenu", 5, 80, 20);
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            if (won) for (int i = 0; i < 5; i++) {
                int n = i;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) firework(p.getLocation().add((n - 2), 0, 0)); }, i * 7L);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, plugin.boards::refresh, 200L);
    }

    static void firework(Location l) {
        Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class);
        FireworkMeta m = fw.getFireworkMeta();
        m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.RED, Color.ORANGE, Color.YELLOW).withFade(Color.WHITE).flicker(true).trail(true).build());
        m.setPower(1);
        fw.setFireworkMeta(m);
    }
}
