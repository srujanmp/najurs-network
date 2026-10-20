package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Hub (world `lobby`) quality of life: hotbar menu items, double jump, no damage or hunger, welcome title. */
final class Hub implements Listener {
    static final String LOBBY = "lobby";
    private final NajursCore plugin;
    private final NamespacedKey key;
    private final Set<UUID> hidingPlayers = new HashSet<>();

    Hub(NajursCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hub_item");
    }

    static boolean inHub(Player p) { return p.getWorld().getName().equals(LOBBY); }

    /** The parkour lane runs north out of the hub wall; double jump would make it trivial there. */
    static boolean inParkour(Player p) { Location l = p.getLocation(); return l.getZ() < -30.5 && Math.abs(l.getX()) <= 13; }

    ItemStack item(Material m, String action, String name, String... lore) {
        ItemStack it = NajursCore.icon(m, name, lore);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    String action(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    void giveHotbar(Player p) {
        if (!inHub(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, item(Material.COMPASS, "menu", "&a&lGame Menu &7(right-click)", "&7Every game on the server."));
        inv.setItem(2, item(Material.GRASS_BLOCK, "worlds", "&2&lSurvival Worlds &7(right-click)", "&7Vanilla, Random Drops, Mob Morph,", "&7Stalker Nights, Goose Chase..."));
        inv.setItem(4, item(Material.TNT, "goose", "&6&lGoose Chase &7(right-click)", "&7Survival where random chaos", "&7happens every minute."));
        inv.setItem(6, item(Material.FEATHER, "parkour", "&f&lHub Parkour &7(right-click)", "&7Sky course with checkpoints."));
        inv.setItem(8, item(hidingPlayers.contains(p.getUniqueId()) ? Material.GRAY_DYE : Material.LIME_DYE, "visibility",
                hidingPlayers.contains(p.getUniqueId()) ? "&7Players: &cHidden" : "&7Players: &aVisible", "&7Right-click to toggle."));
        if (p.hasPermission("najurs.admin"))
            inv.setItem(7, item(Material.NETHER_STAR, "dashboard", "&c&lAdmin Dashboard &7(right-click)", "&7Players, worlds, resets,", "&7send everyone anywhere."));
        inv.setHeldItemSlot(0);
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setAllowFlight(true); // double jump
    }

    /** While running the parkour: checkpoint / restart / quit buttons replace the middle of the hotbar. */
    void giveParkourItems(Player p) {
        var inv = p.getInventory();
        inv.setItem(2, item(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "pkcp", "&6&lBack to checkpoint &7(right-click)"));
        inv.setItem(4, item(Material.OAK_DOOR, "pkrestart", "&e&lRestart run &7(right-click)"));
        inv.setItem(6, item(Material.RED_BED, "pkquit", "&c&lQuit run &7(right-click)"));
    }

    void later(int ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        later(5, () -> {
            if (!p.isOnline()) return;
            giveHotbar(p);
            for (UUID id : hidingPlayers) { Player h = Bukkit.getPlayer(id); if (h != null) h.hidePlayer(plugin, p); }
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "NAJURS " + ChatColor.AQUA + ChatColor.BOLD + "NETWORK",
                    ChatColor.GRAY + "Right-click the compass to play", 10, 60, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f);
            if (first) Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + "Welcome " + ChatColor.WHITE + p.getName()
                    + ChatColor.YELLOW + " to NAJURS NETWORK for the first time!");
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (inHub(p)) later(3, () -> giveHotbar(p)); // after Multiverse-Inventories swaps inventories
        else if (e.getFrom().getName().equals(LOBBY) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.setAllowFlight(false);
            p.setFlying(false);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { later(3, () -> giveHotbar(e.getPlayer())); }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (inHub(e.getPlayer()) && (e.getNewGameMode() == GameMode.ADVENTURE || e.getNewGameMode() == GameMode.SURVIVAL))
            later(1, () -> e.getPlayer().setAllowFlight(true));
    }

    // ---------- hotbar items ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        String a = action(e.getItem());
        if (a == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        switch (a) {
            case "menu" -> p.chat("/menu"); // DeluxeMenus open commands only fire from real chat
            case "pkcp" -> plugin.parkour.back(p);
            case "pkrestart" -> plugin.parkour.restart(p);
            case "pkquit" -> { plugin.parkour.quit(p); giveHotbar(p); p.teleport(p.getWorld().getSpawnLocation().add(0.5, 0, 0.5)); }
            case "dashboard" -> { if (p.hasPermission("najurs.admin")) plugin.dialogs.dashboard(p); }
            case "worlds" -> p.performCommand("worlds");
            case "goose" -> p.performCommand("go sw_goose_chase");
            case "parkour" -> p.performCommand("go parkour");
            case "visibility" -> {
                boolean hide = hidingPlayers.add(p.getUniqueId());
                if (!hide) hidingPlayers.remove(p.getUniqueId());
                for (Player o : Bukkit.getOnlinePlayers()) if (o != p) { if (hide) p.hidePlayer(plugin, o); else p.showPlayer(plugin, o); }
                p.sendActionBar(hide ? ChatColor.GRAY + "Other players hidden" : ChatColor.GREEN + "Other players visible");
                giveHotbar(p);
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (action(e.getItemDrop().getItemStack()) != null) e.setCancelled(true); }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (action(e.getCurrentItem()) != null || action(e.getCursor()) != null) e.setCancelled(true);
        if (e.getHotbarButton() >= 0 && e.getWhoClicked() instanceof Player p && action(p.getInventory().getItem(e.getHotbarButton())) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { if (action(e.getOffHandItem()) != null || action(e.getMainHandItem()) != null) e.setCancelled(true); }

    // ---------- double jump, no damage, no hunger ----------

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!inHub(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        e.setCancelled(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        if (inParkour(p)) return;
        p.setVelocity(p.getLocation().getDirection().multiply(1.25).setY(0.95));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.2f);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (inHub(p) && !p.getAllowFlight() && !inParkour(p) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR
                && p.getLocation().subtract(0, 0.1, 0).getBlock().getType().isSolid())
            p.setAllowFlight(true);
    }

    @EventHandler
    public void onVoid(PlayerMoveEvent e) {
        // safety net: hub damage is off (so the void can't kill you); bring anyone who drops this far back to spawn
        Player p = e.getPlayer();
        if (inHub(p) && e.getTo() != null && e.getTo().getY() < -50) { p.setFallDistance(0); p.teleport(p.getWorld().getSpawnLocation().add(0.5, 0, 0.5)); }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p) && e.getCause() != EntityDamageEvent.DamageCause.KILL) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p)) e.setCancelled(true);
    }
}
