package dev.najurs.core;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.File;
import java.io.IOException;

/**
 * Gamerules and frozen clocks live inside each world's save, which is not in git. This keeps them in
 * plugins/NajursCore/world-state.yml instead: saved on shutdown (and every 5 minutes), re-applied whenever a
 * world loads, so a fresh clone that regenerates its worlds gets the same rules.
 */
final class WorldState implements Listener {
    private final NajursCore plugin;
    private final File file;
    private YamlConfiguration state;

    WorldState(NajursCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "world-state.yml");
        this.state = YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        Bukkit.getWorlds().forEach(this::apply);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 20L * 300, 20L * 300);
    }

    @EventHandler
    public void onLoad(WorldLoadEvent e) { apply(e.getWorld()); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void apply(World w) {
        ConfigurationSection s = state.getConfigurationSection(w.getName());
        if (s == null) return;
        ConfigurationSection rules = s.getConfigurationSection("gamerules");
        if (rules != null) for (GameRule<?> r : Registry.GAME_RULE) {
            String k = r.getKey().getKey();
            if (!rules.contains(k)) continue;
            Object v = r.getType() == Boolean.class ? (Object) rules.getBoolean(k) : r.getType() == Integer.class ? (Object) rules.getInt(k) : null;
            if (v != null) try { w.setGameRule((GameRule) r, v); } catch (IllegalArgumentException experimental) { }
        }
        if (s.contains("time")) w.setTime(s.getLong("time"));
        if (s.getBoolean("clear-weather")) { w.setStorm(false); w.setThundering(false); }
    }

    static GameRule<?> rule(String key) { return Registry.GAME_RULE.get(org.bukkit.NamespacedKey.minecraft(key)); }

    void save() {
        YamlConfiguration out = new YamlConfiguration();
        out.options().setHeader(java.util.List.of("Gamerules that differ from vanilla, and the time of worlds with a frozen clock.",
                "Written by NajursCore on shutdown; re-applied when a world loads. Safe to edit while the server is off."));
        for (World w : Bukkit.getWorlds()) {
            String base = w.getName();
            for (GameRule<?> r : Registry.GAME_RULE) {
                try {
                    Object v = w.getGameRuleValue(r), d = w.getGameRuleDefault(r);
                    if (v != null && !v.equals(d)) out.set(base + ".gamerules." + r.getKey().getKey(), v);
                } catch (IllegalArgumentException experimental) { /* rule not enabled in this world (e.g. minecart_improvements) */ }
            }
            Boolean cycle = (Boolean) w.getGameRuleValue(rule("advance_time"));
            if (Boolean.FALSE.equals(cycle)) out.set(base + ".time", w.getTime());
            if (Boolean.FALSE.equals(w.getGameRuleValue(rule("advance_weather"))) && !w.hasStorm()) out.set(base + ".clear-weather", true);
        }
        // keep worlds that aren't loaded right now (e.g. not created yet on a fresh clone)
        for (String k : state.getKeys(false)) if (Bukkit.getWorld(k) == null && !out.contains(k)) out.set(k, state.get(k));
        state = out;
        try { out.save(file); } catch (IOException e) { plugin.getLogger().warning("Could not save world-state.yml: " + e.getMessage()); }
    }
}
