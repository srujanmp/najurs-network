package dev.najurs.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

/** %najurs_world% = friendly world name, %najurs_mode% = survival world mode line. Used by TAB. */
final class Expansion extends PlaceholderExpansion {
    private final NajursCore plugin;
    Expansion(NajursCore plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "najurs"; }
    @Override public String getAuthor() { return "najurs"; }
    @Override public String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player p, String params) {
        if (p == null) return "";
        return switch (params) {
            case "world" -> plugin.prettyName(p.getWorld());
            case "mode" -> { String id = plugin.managedId(p.getWorld()); yield id == null ? "" : plugin.modeLine(id); }
            case "total_kills" -> plugin.pvp == null ? "0" : String.valueOf(plugin.pvp.total(p.getUniqueId(), "kills"));
            case "total_wins" -> plugin.pvp == null ? "0" : String.valueOf(plugin.pvp.total(p.getUniqueId(), "wins"));
            case "parkour_best" -> { long b = plugin.data.getLong("parkour.best." + p.getUniqueId(), -1); yield b < 0 ? "none" : Parkour.fmt(b); }
            case "pvp_mode" -> plugin.pvp == null ? "" : plugin.pvp.modeOf(p);
            case "pvp_kills" -> plugin.pvp == null ? "0" : String.valueOf(plugin.pvp.current(p, "kills"));
            case "pvp_deaths" -> plugin.pvp == null ? "0" : String.valueOf(plugin.pvp.current(p, "deaths"));
            case "pvp_streak" -> plugin.pvp == null ? "0" : String.valueOf(plugin.pvp.current(p, "streak"));
            default -> null;
        };
    }
}
