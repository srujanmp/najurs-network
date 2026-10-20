package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Owner dashboard (/dashboard or the nether-star item): live players, worlds and one-click game control. */
final class Dashboard implements Listener {
    record Game(String name, Material icon, String command) {}

    static final List<Game> GAMES = List.of(
            new Game("&a&lHub", Material.BEACON, "spawn"),
            new Game("&c&lBedWars (quick join)", Material.RED_BED, "bw join"),
            new Game("&a&lH&S: Village", Material.HAY_BLOCK, "hs join village"),
            new Game("&5&lH&S: Haunted Mansion", Material.DARK_OAK_LOG, "hs join mansion"),
            new Game("&3&lH&S: Deep Dark", Material.SCULK, "hs join deepdark"),
            new Game("&6&lArena Rounds", Material.DIAMOND_SWORD, "go arena"),
            new Game("&f&lSumo", Material.SLIME_BALL, "go sumo"),
            new Game("&b&lSpleef", Material.DIAMOND_SHOVEL, "go spleef"),
            new Game("&e&lKnockback FFA", Material.STICK, "go kbffa"),
            new Game("&2&lInfection", Material.ZOMBIE_HEAD, "infection join mansion"),
            new Game("&f&lParkour", Material.FEATHER, "go parkour"),
            new Game("&e&lCreative", Material.BRICKS, "go creative"));

    record Screen(String kind, UUID target) implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }

    private final NajursCore plugin;
    Dashboard(NajursCore plugin) { this.plugin = plugin; }

    static ItemStack icon(Material m, String name, String... lore) { return NajursCore.icon(m, name, lore); }

    ItemStack head(Player p, String... lore) {
        ItemStack it = icon(Material.PLAYER_HEAD, "&f&l" + p.getName(), lore);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(p);
        it.setItemMeta(meta);
        return it;
    }

    void open(Player admin) {
        Inventory inv = Bukkit.createInventory(new Screen("main", null), 54, ChatColor.DARK_RED + "" + ChatColor.BOLD + "NAJURS DASHBOARD");
        ItemStack pane = icon(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, pane);
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576, maxMb = rt.maxMemory() / 1048576;
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        long upMin = ManagementFactory.getRuntimeMXBean().getUptime() / 60000;
        inv.setItem(4, icon(tps > 18 ? Material.LIME_CONCRETE : tps > 15 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE, "&f&lServer",
                "&7TPS: &f" + String.format("%.1f", tps), "&7Memory: &f" + usedMb + " / " + maxMb + " MB",
                "&7Players: &f" + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers(), "&7Uptime: &f" + upMin / 60 + "h " + upMin % 60 + "m",
                "", "&eClick to refresh"));
        inv.setItem(0, icon(Material.COMMAND_BLOCK, "&c&lMore tools", "&7Open the full admin panel", "&7(game modes, time, map teleports)"));
        inv.setItem(8, icon(Material.BARRIER, "&cClose"));

        // Row 1-2: online players
        int slot = 9;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (slot > 26) break;
            inv.setItem(slot++, head(p, "&7World: &f" + plugin.prettyName(p.getWorld()), "&7Mode: &f" + p.getGameMode().name().toLowerCase(),
                    "&7Health: &c" + Math.round(p.getHealth()) + "❤", "", "&eClick to manage"));
        }
        // Row 3: survival worlds
        slot = 27;
        for (String id : plugin.worlds().getKeys(false)) {
            if (slot > 35) break;
            List<String> flags = plugin.worlds().getStringList(id + ".flags");
            int players = plugin.dims(id).stream().map(Bukkit::getWorld).filter(Objects::nonNull).mapToInt(w -> w.getPlayers().size()).sum();
            inv.setItem(slot++, icon(NajursCore.iconFor(flags), "&a&l" + plugin.worlds().getString(id + ".name"),
                    "&8" + id, "&7Mode: &f" + plugin.modeLine(id), "&7Players: &f" + players, "",
                    "&eClick: reset / new seed / pull everyone / delete"));
        }
        // Rows 4-5: send everyone
        slot = 36;
        for (Game g : GAMES) inv.setItem(slot++, icon(g.icon(), "&6Send ALL → " + g.name(), "&7Every online player joins."));
        for (String id : plugin.worlds().getKeys(false)) {
            if (slot > 53) break;
            inv.setItem(slot++, icon(NajursCore.iconFor(plugin.worlds().getStringList(id + ".flags")), "&6Send ALL → &a&l" + plugin.worlds().getString(id + ".name"),
                    "&7Every online player joins."));
        }
        admin.openInventory(inv);
        admin.playSound(admin.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.6f);
    }

    void openPlayer(Player admin, Player target) {
        Inventory inv = Bukkit.createInventory(new Screen("player", target.getUniqueId()), 54, ChatColor.DARK_RED + "Manage " + target.getName());
        inv.setItem(4, head(target, "&7World: &f" + plugin.prettyName(target.getWorld())));
        inv.setItem(10, icon(Material.ENDER_PEARL, "&aTeleport to them"));
        inv.setItem(11, icon(Material.LEAD, "&aBring them to you"));
        inv.setItem(12, icon(Material.BEACON, "&bSend to Hub"));
        inv.setItem(13, icon(Material.GOLDEN_APPLE, "&cHeal & feed"));
        inv.setItem(14, icon(Material.IRON_SWORD, "&fSurvival mode"));
        inv.setItem(15, icon(Material.GRASS_BLOCK, "&fCreative mode"));
        inv.setItem(16, icon(Material.ENDER_EYE, "&fSpectator mode"));
        inv.setItem(22, icon(Material.IRON_DOOR, "&c&lKick", "&7Disconnects them (they can rejoin)."));
        int slot = 27;
        for (Game g : GAMES) inv.setItem(slot++, icon(g.icon(), "&6Send → " + g.name()));
        for (String id : plugin.worlds().getKeys(false)) {
            if (slot > 44) break;
            inv.setItem(slot++, icon(NajursCore.iconFor(plugin.worlds().getStringList(id + ".flags")), "&6Send → &a&l" + plugin.worlds().getString(id + ".name")));
        }
        inv.setItem(49, icon(Material.ARROW, "&7« Back"));
        admin.openInventory(inv);
    }

    /** Send-target entries in slot order after the fixed games: survival world ids. */
    List<String> worldIds() { return new ArrayList<>(plugin.worlds().getKeys(false)); }

    void sendTo(Collection<? extends Player> who, int index) {
        if (index < GAMES.size()) { String cmd = GAMES.get(index).command(); who.forEach(p -> p.performCommand(cmd)); return; }
        List<String> ids = worldIds();
        int w = index - GAMES.size();
        if (w < ids.size()) who.forEach(p -> plugin.go(p, ids.get(w)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Screen screen) || !(e.getWhoClicked() instanceof Player admin)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory() || e.getCurrentItem() == null || e.getCurrentItem().getType().isAir()) return;
        if (!admin.hasPermission("najurs.admin")) { admin.closeInventory(); return; }
        int s = e.getSlot();
        admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        if (screen.kind().equals("main")) {
            if (s == 0) { admin.closeInventory(); admin.chat("/admin"); }
            else if (s == 4) open(admin);
            else if (s == 8) admin.closeInventory();
            else if (s >= 9 && s <= 26) {
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (s - 9 < online.size()) openPlayer(admin, online.get(s - 9));
            } else if (s >= 27 && s <= 35) {
                List<String> ids = worldIds();
                if (s - 27 < ids.size()) plugin.openManage(admin, ids.get(s - 27));
            } else if (s >= 36) {
                admin.closeInventory();
                sendTo(Bukkit.getOnlinePlayers(), s - 36);
                Bukkit.broadcastMessage(ChatColor.GOLD + "» " + ChatColor.YELLOW + "najurs pulled everyone into "
                        + ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()).replace("Send ALL → ", "") + ChatColor.GRAY + " (type /hub to leave)");
            }
            return;
        }
        Player t = Bukkit.getPlayer(screen.target());
        if (t == null) { admin.sendMessage(ChatColor.RED + "That player left."); open(admin); return; }
        switch (s) {
            case 10 -> { admin.closeInventory(); admin.teleport(t); }
            case 11 -> { admin.closeInventory(); t.teleport(admin); }
            case 12 -> t.performCommand("spawn");
            case 13 -> { t.setHealth(t.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()); t.setFoodLevel(20); t.setSaturation(20); }
            case 14 -> t.setGameMode(GameMode.SURVIVAL);
            case 15 -> t.setGameMode(GameMode.CREATIVE);
            case 16 -> t.setGameMode(GameMode.SPECTATOR);
            case 22 -> { admin.closeInventory(); t.kickPlayer(ChatColor.RED + "Kicked by najurs. You can rejoin."); return; }
            case 49 -> { open(admin); return; }
            default -> {
                if (s >= 27 && s <= 44) { admin.closeInventory(); sendTo(List.of(t), s - 27); return; }
            }
        }
        if (s >= 12 && s <= 16) { admin.sendMessage(ChatColor.GREEN + "Done: " + ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()) + " → " + t.getName()); openPlayer(admin, t); }
    }
}
