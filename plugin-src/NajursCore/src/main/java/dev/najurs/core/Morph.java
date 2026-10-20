package dev.najurs.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Mob Morph: every minute each player in a mobmorph world becomes a random mob, with that mob's size and a power. */
final class Morph implements Listener {
    record Form(String mob, double scale, String power, PotionEffectType effect, int amp) {}

    static final List<Form> FORMS = List.of(
            new Form("zombie", 1.0, "Strength", PotionEffectType.STRENGTH, 0),
            new Form("skeleton", 1.0, "Speed", PotionEffectType.SPEED, 0),
            new Form("creeper", 0.9, "Explosive personality (Resistance)", PotionEffectType.RESISTANCE, 0),
            new Form("spider", 0.6, "Night vision + Speed", PotionEffectType.NIGHT_VISION, 0),
            new Form("enderman", 1.4, "Long reach (Jump Boost)", PotionEffectType.JUMP_BOOST, 1),
            new Form("cow", 1.0, "Regeneration", PotionEffectType.REGENERATION, 0),
            new Form("pig", 0.7, "Saturation", PotionEffectType.SATURATION, 0),
            new Form("sheep", 0.8, "Fluffy (Resistance)", PotionEffectType.RESISTANCE, 0),
            new Form("chicken", 0.4, "Slow Falling", PotionEffectType.SLOW_FALLING, 0),
            new Form("wolf", 0.6, "Speed II", PotionEffectType.SPEED, 1),
            new Form("fox", 0.5, "Speed + Jump", PotionEffectType.JUMP_BOOST, 0),
            new Form("cat", 0.4, "Nine lives (Regeneration)", PotionEffectType.REGENERATION, 0),
            new Form("rabbit", 0.35, "Jump Boost III", PotionEffectType.JUMP_BOOST, 2),
            new Form("iron_golem", 1.6, "Resistance II, but slow", PotionEffectType.RESISTANCE, 1),
            new Form("snow_golem", 1.0, "Fire Resistance", PotionEffectType.FIRE_RESISTANCE, 0),
            new Form("villager", 1.0, "Hero of the Village", PotionEffectType.HERO_OF_THE_VILLAGE, 0),
            new Form("witch", 1.0, "Potion master (Regeneration)", PotionEffectType.REGENERATION, 0),
            new Form("blaze", 1.0, "Fire Resistance", PotionEffectType.FIRE_RESISTANCE, 0),
            new Form("slime", 0.8, "Bouncy (Jump Boost II)", PotionEffectType.JUMP_BOOST, 1),
            new Form("magma_cube", 0.8, "Fire Resistance + Jump", PotionEffectType.FIRE_RESISTANCE, 0),
            new Form("piglin", 1.0, "Haste", PotionEffectType.HASTE, 1),
            new Form("hoglin", 1.3, "Strength", PotionEffectType.STRENGTH, 0),
            new Form("axolotl", 0.4, "Water Breathing", PotionEffectType.WATER_BREATHING, 0),
            new Form("frog", 0.35, "Jump Boost III", PotionEffectType.JUMP_BOOST, 2),
            new Form("goat", 0.8, "Jump Boost II", PotionEffectType.JUMP_BOOST, 1),
            new Form("panda", 1.1, "Resistance", PotionEffectType.RESISTANCE, 0),
            new Form("polar_bear", 1.3, "Strength", PotionEffectType.STRENGTH, 0),
            new Form("horse", 1.2, "Speed II", PotionEffectType.SPEED, 1),
            new Form("camel", 1.3, "Speed", PotionEffectType.SPEED, 0),
            new Form("sniffer", 1.4, "Haste II", PotionEffectType.HASTE, 1),
            new Form("armadillo", 0.4, "Resistance II", PotionEffectType.RESISTANCE, 1),
            new Form("bee", 0.3, "Slow Falling + Speed", PotionEffectType.SLOW_FALLING, 0),
            new Form("parrot", 0.3, "Slow Falling", PotionEffectType.SLOW_FALLING, 0),
            new Form("turtle", 0.5, "Water Breathing + Resistance", PotionEffectType.WATER_BREATHING, 0),
            new Form("squid", 0.6, "Dolphin's Grace", PotionEffectType.DOLPHINS_GRACE, 0),
            new Form("dolphin", 0.7, "Dolphin's Grace", PotionEffectType.DOLPHINS_GRACE, 0),
            new Form("warden", 1.7, "Strength II, but slow", PotionEffectType.STRENGTH, 1),
            new Form("ghast", 2.0, "Slow Falling", PotionEffectType.SLOW_FALLING, 0),
            new Form("allay", 0.3, "Slow Falling + Haste", PotionEffectType.SLOW_FALLING, 0));

    private final NajursCore plugin;
    private final Map<UUID, Form> current = new HashMap<>();

    Morph(NajursCore plugin) { this.plugin = plugin; }

    void tick() {
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.hasFlag(p.getWorld(), "mobmorph")) apply(p);
    }

    void apply(Player p) {
        Form old = current.get(p.getUniqueId());
        Form f;
        do { f = FORMS.get(plugin.random.nextInt(FORMS.size())); } while (old != null && f == old);
        clearEffects(p, old);
        current.put(p.getUniqueId(), f);
        // big mobs would fill your own first-person view, so only small/medium forms are shown to yourself
        plugin.console("disguiseplayer " + p.getName() + " " + f.mob() + " setSelfDisguiseVisible " + (f.scale() <= 1.1));
        setScale(p, f.scale());
        int ticks = 20 * 65;
        p.addPotionEffect(new PotionEffect(f.effect(), ticks, f.amp(), false, false, true));
        if (f.power().contains("Speed") && f.effect() != PotionEffectType.SPEED) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 0, false, false, true));
        if (f.power().contains("slow")) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0, false, false, true));
        String name = f.mob().replace('_', ' ').toUpperCase(Locale.ROOT);
        p.sendTitle(ChatColor.GREEN + "You are a " + ChatColor.YELLOW + ChatColor.BOLD + name, ChatColor.GRAY + "Power: " + ChatColor.WHITE + f.power(), 5, 50, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1f);
        for (Player o : p.getWorld().getPlayers())
            if (o != p) o.sendMessage(ChatColor.DARK_GREEN + "✦ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " turned into a " + ChatColor.YELLOW + name.toLowerCase(Locale.ROOT));
    }

    static void setScale(Player p, double scale) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(scale);
    }

    void clearEffects(Player p, Form f) {
        if (f == null) return;
        p.removePotionEffect(f.effect());
        if (f.power().contains("Speed")) p.removePotionEffect(PotionEffectType.SPEED);
        if (f.power().contains("slow")) p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /** Back to a normal player (leaving the world, quitting, or the mode being switched off). */
    void clear(Player p) {
        clearEffects(p, current.remove(p.getUniqueId()));
        setScale(p, 1.0);
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") != null) plugin.console("undisguiseplayer " + p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (current.containsKey(e.getPlayer().getUniqueId())) clear(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // scale persists in player data, so reset anyone who logged out mid-morph
        if (!plugin.hasFlag(e.getPlayer().getWorld(), "mobmorph")) setScale(e.getPlayer(), 1.0);
        else soon(e.getPlayer());
    }

    /** Morph straight away on arrival instead of waiting for the next minute tick. */
    @EventHandler
    public void onEnter(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        if (plugin.hasFlag(e.getPlayer().getWorld(), "mobmorph") && !plugin.hasFlag(e.getFrom(), "mobmorph")) soon(e.getPlayer());
    }

    void soon(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.hasFlag(p.getWorld(), "mobmorph") && Bukkit.getPluginManager().getPlugin("LibsDisguises") != null) {
                apply(p);
                p.sendMessage(ChatColor.GRAY + "You change into a new mob every minute. Press " + ChatColor.WHITE + "F5" + ChatColor.GRAY + " to see yourself!");
            }
        }, 40L);
    }
}
