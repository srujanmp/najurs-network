package dev.najurs.core;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Native Minecraft dialog screens (1.21.6+ clients): the game menu, PvP / BedWars / Hide & Seek pickers,
 * survival world list with a create-world form and manager, the owner dashboard and the rematch vote.
 * Older clients (joining through ViaBackwards) keep the chest menus.
 */
final class Dialogs {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();
    private final NajursCore plugin;
    Dialogs(NajursCore plugin) { this.plugin = plugin; }

    /** Dialogs arrived in protocol 771 (1.21.6). */
    static boolean supported(Player p) {
        if (JavaPlugin.getPlugin(NajursCore.class).getConfig().getBoolean("force-dialogs")) return true; // testing: send dialogs to old clients too
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) return true;
        try { return com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(p.getUniqueId()) >= 771; }
        catch (Throwable t) { return true; }
    }

    // ---------- building blocks ----------

    static Component txt(String legacy) { return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy); }

    ActionButton button(String label, String tooltip, int width, Consumer<Player> run) {
        return button(label, tooltip, width, (view, p) -> run.accept(p));
    }

    /** Callbacks may arrive off the main thread, so hop back before touching the world. */
    ActionButton button(String label, String tooltip, int width, BiConsumer<DialogResponseView, Player> run) {
        var b = ActionButton.builder(txt(label)).width(width).action(DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
                run.accept(view, p);
            });
        }, ONCE));
        if (tooltip != null) b.tooltip(txt(tooltip));
        return b.build();
    }

    static DialogBody line(String legacy) { return DialogBody.plainMessage(txt(legacy), 300); }

    static DialogBody item(Material m, String legacy) {
        return DialogBody.item(new ItemStack(m)).description(DialogBody.plainMessage(txt(legacy), 260)).showTooltip(false).build();
    }

    static Dialog screen(String title, List<DialogBody> body, List<? extends DialogInput> inputs, List<ActionButton> buttons, int columns, ActionButton exit) {
        DialogBase base = DialogBase.builder(txt(title)).canCloseWithEscape(true).pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).inputs(inputs).build();
        var type = DialogType.multiAction(buttons).columns(columns);
        if (exit != null) type.exitAction(exit);
        return Dialog.create(f -> f.empty().base(base).type(type.build()));
    }

    void confirm(Player p, String title, String question, String yes, Runnable onYes, Runnable onNo) {
        DialogBase base = DialogBase.builder(txt(title)).canCloseWithEscape(true).pause(false).body(List.of(line(question))).build();
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(
                button(yes, null, 150, x -> onYes.run()), button("&7Cancel", null, 150, x -> onNo.run())))));
    }

    ActionButton close() { return button("&7Close", null, 120, p -> {}); }
    ActionButton back(Consumer<Player> to) { return button("&7« Back", null, 120, to); }

    int players(String... worlds) {
        int n = 0;
        for (String w : worlds) { World x = Bukkit.getWorld(w); if (x != null) n += x.getPlayers().size(); }
        return n;
    }

    int prefixPlayers(String prefix) {
        return Bukkit.getWorlds().stream().filter(w -> w.getName().startsWith(prefix)).mapToInt(w -> w.getPlayers().size()).sum();
    }

    static String online(int n) { return n == 0 ? "&8no one yet" : "&a" + n + " playing"; }

    /** Run a command as the player; DeluxeMenus menus only open from real chat. */
    static void cmd(Player p, String c) { p.performCommand(c); }

    // ---------- main game menu ----------

    void main(Player p) {
        if (!supported(p)) { p.chat("/games"); return; }
        UUID id = p.getUniqueId();
        int kills = plugin.pvp == null ? 0 : plugin.pvp.total(id, "kills"), wins = plugin.pvp == null ? 0 : plugin.pvp.total(id, "wins");
        long best = plugin.data.getLong("parkour.best." + id, 0);
        int hs = players("hideseek", "hs_horror") + prefixPlayers("hs2_");
        int bw = players("bedwars");
        int pvp = players(PvP.WORLD);
        int surv = plugin.worlds().getKeys(false).stream().mapToInt(w -> plugin.dims(w).stream().mapToInt(this::players).sum()).sum();
        List<ActionButton> b = new ArrayList<>();
        b.add(button("&a&l⛏ Survival Worlds", "&7" + plugin.worlds().getKeys(false).size() + " worlds, each with its own nether,\n&7end and inventory.\n" + online(surv), 150, this::survival));
        b.add(button("&c&l⚔ PvP", "&7Arena Rounds, Team Battle, Sumo,\n&7Spleef, Knockback FFA, Duels\n" + online(pvp), 150, this::pvp));
        b.add(button("&c&l♦ BedWars", "&74 maps. Protect your bed!\n" + online(bw), 150, this::bedwars));
        b.add(button("&d&l◉ Hide & Seek", "&7Village, Haunted Mansion, Deep Dark\n&7and Infection\n" + online(hs), 150, this::hideSeek));
        b.add(button("&b&l☁ Skyblock", "&7Co-op island with everyone, or your own.\n" + online(players("bskyblock_world", "bskyblock_world_nether", "bskyblock_world_the_end") + coop("skyblock")), 150, x -> islands(x, "skyblock")));
        b.add(button("&e&l■ OneBlock", "&7Co-op block with everyone, or your own.\n" + online(players("oneblock_world", "oneblock_world_nether", "oneblock_world_the_end") + coop("oneblock")), 150, x -> islands(x, "oneblock")));
        b.add(button("&4&l☠ Nightfall &7(horror)", "&7Impostor, Blackout Escape, Soul Candles,\n&7Silent Relics - in a pitch-black manor\n&e" + plugin.nightfall.status(), 150, this::horror));
        if (plugin.worlds().isConfigurationSection("sw_goose_chase"))
            b.add(button("&6&l⚡ Goose Chase", "&7Chaos every " + plugin.getConfig().getInt("chaos-wave-seconds", 25) + " seconds.\n" + online(plugin.dims("sw_goose_chase").stream().mapToInt(this::players).sum()), 150, x -> plugin.go(x, "sw_goose_chase")));
        b.add(button("&f&l➹ Parkour", "&76 themed zones with checkpoints.\n&7Your best: &f" + (best == 0 ? "-" : String.format("%.2fs", best / 1000.0)), 150, x -> cmd(x, "go parkour")));
        b.add(button("&e&l✎ Creative", "&7Flat free-build world.\n" + online(players("creative")), 150, x -> cmd(x, "go creative")));
        b.add(button("&b&l★ My Stats", "&7Your numbers in every game", 150, this::stats));
        if (p.hasPermission("najurs.admin")) b.add(button("&4&l⚙ Dashboard", "&7Owner tools: players, worlds,\n&7send everyone anywhere", 150, this::dashboard));
        b.add(button("&b⌂ Hub", "&7Back to the hub spawn", 150, x -> cmd(x, "hub")));
        List<DialogBody> body = List.of(
                line("&7Welcome, &f" + p.getName() + "&7!   &a" + Bukkit.getOnlinePlayers().size() + "&7/" + Bukkit.getMaxPlayers() + " online"),
                line("&6⚔ " + kills + " kills   &e★ " + wins + " wins   &f➹ " + (best == 0 ? "no parkour time" : String.format("%.2fs parkour", best / 1000.0))));
        p.showDialog(screen("&2&lNAJURS &a&lNETWORK", body, List.of(), b, 2, close()));
    }

    void stats(Player p) {
        UUID id = p.getUniqueId();
        List<DialogBody> body = new ArrayList<>();
        body.add(line("&4Nightfall wins: &f" + plugin.data.getInt("nightfall.wins." + id)));
        for (String[] m : new String[][]{{"colosseum", "Arena Rounds"}, {"teams", "Team Battle"}, {"sumo", "Sumo"}, {"spleef", "Spleef"}, {"kbffa", "Knockback FFA"}}) {
            String k = "pvp." + m[0] + "." + id + ".";
            body.add(line("&6" + m[1] + "&7:  &f" + plugin.data.getInt(k + "kills") + "&7 kills  &f" + plugin.data.getInt(k + "deaths")
                    + "&7 deaths  &f" + plugin.data.getInt(k + "wins") + "&7 wins  &f" + plugin.data.getInt(k + "beststreak") + "&7 best streak"));
        }
        long best = plugin.data.getLong("parkour.best." + id, 0);
        body.add(line("&fParkour best: &a" + (best == 0 ? "-" : String.format("%.2fs", best / 1000.0))));
        body.add(line("&6Goose Chase best wave: &f" + plugin.data.getInt("goose.best." + id, 0)));
        p.showDialog(screen("&b&l★ " + p.getName() + "'s Stats", body, List.of(), List.of(back(this::main)), 1, close()));
    }

    // ---------- game pickers ----------

    void pvp(Player p) {
        if (!supported(p)) { p.chat("/pvpmenu"); return; }
        Match m = plugin.match;
        List<ActionButton> b = List.of(
                button("&6&l⚔ Arena Rounds &7(free-for-all)", "&7Last one standing wins. Random map\n&7(10 maps) and the same random kit for all.\n&e" + m.status("colosseum"), 300, x -> cmd(x, "go arena")),
                button("&c&lRed &f&lvs &9&lBlue &7(Team Battle)", "&7Teams auto-balance as people join.\n&7Random map & kit, friendly fire off.\n&e" + m.status("teams"), 300, x -> cmd(x, "go teams")),
                button("&f&lSumo", "&7Fists only, push them off.\n&e" + m.status("sumo"), 145, x -> cmd(x, "go sumo")),
                button("&b&lSpleef", "&7Dig the snow under your foes.\n&e" + m.status("spleef"), 145, x -> cmd(x, "go spleef")),
                button("&e&lKnockback FFA", "&7Drop in any time, Knockback sticks.\n" + online(plugin.pvp.count("kbffa")), 145, x -> cmd(x, "go kbffa")),
                button("&c&l1v1 Duel", "&7Challenge a friend: /duel <name>", 145, x -> x.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.AQUA + "/duel <name>" + ChatColor.YELLOW + " to challenge someone.")));
        p.showDialog(screen("&c&l⚔ PvP", List.of(line("&7Every round picks a random map and kit theme.\n&7Everyone gets the same kit, so it's always fair.")),
                List.of(), b, 2, back(this::main)));
    }

    void bedwars(Player p) {
        if (!supported(p)) { p.chat("/bwmenu"); return; }
        Map<String, String> desc = Map.of("skylands", "&a&lSkylands", "frostbite", "&b&lFrostbite", "inferno", "&c&lInferno &7(2 teams)", "overgrown", "&2&lOvergrown");
        List<ActionButton> b = new ArrayList<>();
        List<String[]> games = Bukkit.getPluginManager().getPlugin("BedWars") != null ? BedwarsHook.games() : List.of();
        for (String[] g : games) {
            String state = switch (g[3]) { case "WAITING" -> "&aWaiting"; case "RUNNING" -> "&cIn game"; case "GAME_END_CELEBRATING" -> "&6Ending"; default -> "&7" + g[3].toLowerCase(); };
            b.add(button(desc.getOrDefault(g[0], "&f&l" + g[0]) + "  &7" + g[1] + "/" + g[2], state + "&7 - click to join", 145, x -> cmd(x, "bw join " + g[0])));
        }
        b.add(button("&e&l✦ Quick join", "&7Joins the map that's filling up", 145, x -> cmd(x, "bw join")));
        b.add(button("&cLeave game", null, 145, x -> cmd(x, "bw leave")));
        p.showDialog(screen("&c&l♦ BedWars", List.of(line("&7Starts at 2 players. Break beds, then finish the team!")), List.of(), b, 2, back(this::main)));
    }

    void hideSeek(Player p) {
        if (!supported(p)) { p.chat("/hsmenu"); return; }
        List<ActionButton> b = List.of(
                button("&a&lVillage", "&7Classic plains village in daylight.", 145, x -> cmd(x, "hs join village")),
                button("&5&lHaunted Mansion", "&7A woodland mansion at midnight.", 145, x -> cmd(x, "hs join mansion")),
                button("&3&lDeep Dark", "&7Ancient city. Don't wake the Warden.", 145, x -> cmd(x, "hs join deepdark")),
                button("&2&lInfection", "&7One zombie hunts everyone;\n&7every kill makes a new zombie.", 145, x -> cmd(x, "infection join mansion")),
                button("&cLeave game", null, 145, x -> cmd(x, "hub")));
        p.showDialog(screen("&d&l◉ Hide & Seek", List.of(line("&7Hiders turn into blocks. Seekers have 4 minutes.")), List.of(), b, 2, back(this::main)));
    }

    void horror(Player p) {
        List<ActionButton> b = new ArrayList<>();
        b.add(button("&4&l☠ NIGHTFALL &7- horror party games", "&7Everyone waits in one lobby; a random game\n&7starts every round: The Impostor, Blackout\n&7Escape, Soul Candles, Silent Relics.\n&7Only your torch lights the dark.\n&e" + plugin.nightfall.status(), 300, x -> plugin.nightfall.join(x)));
        b.add(button("&5&lHaunted Mansion", "&7Hide & Seek at midnight.", 145, x -> cmd(x, "hs join mansion")));
        b.add(button("&3&lDeep Dark", "&7Hide & Seek in an ancient city.", 145, x -> cmd(x, "hs join deepdark")));
        b.add(button("&2&lInfection", "&7Survive the zombie outbreak.", 145, x -> cmd(x, "infection join mansion")));
        p.showDialog(screen("&4&l☠ Horror", List.of(line("&8Turn your volume up...")), List.of(), b, 2, back(this::main)));
    }

    // ---------- skyblock / oneblock ----------

    List<String> coopIds(String type) { return plugin.worlds().getKeys(false).stream().filter(id -> type.equals(plugin.worlds().getString(id + ".type"))).toList(); }
    int coop(String type) { return coopIds(type).stream().mapToInt(id -> plugin.dims(id).stream().mapToInt(this::players).sum()).sum(); }

    /** First the shared co-op worlds, then your own BentoBox island with a reset button. */
    void islands(Player p, String type) {
        boolean sky = type.equals("skyblock");
        String cmd = sky ? "island" : "ob", title = sky ? "&b&l☁ Skyblock" : "&e&l■ OneBlock";
        List<ActionButton> b = new ArrayList<>();
        for (String id : coopIds(type)) {
            int n = plugin.dims(id).stream().mapToInt(this::players).sum();
            String info = sky ? "&7One shared island for everyone" : "&7One shared magic block: " + plugin.voids.progress(id);
            b.add(button("&a&l★ " + plugin.worlds().getString(id + ".name") + " &7(multiplayer)", info + "\n" + online(n) + "\n&eClick to play together", 300, x -> plugin.join(x, id)));
        }
        if (b.isEmpty() && p.hasPermission("najurs.admin"))
            b.add(button("&a&l+ Create a co-op " + (sky ? "Skyblock" : "OneBlock") + " world", "&7A shared world everyone plays in", 300, x -> {
                x.performCommand("worlds create " + (sky ? "Coop_Skyblock skyblock" : "Coop_OneBlock oneblock")); }));
        b.add(button("&f&l⌂ My own " + (sky ? "island" : "OneBlock"), "&7Your personal " + (sky ? "island" : "magic block") + ".\n&7Invite friends: /" + cmd + " team invite <name>", 300, x -> cmd(x, cmd)));
        b.add(button("&c⟳ Reset my " + (sky ? "island" : "OneBlock"), "&7Start your personal one again from scratch", 300,
                x -> confirm(x, "&cReset your " + (sky ? "island" : "OneBlock") + "?", "&7Your personal " + (sky ? "island" : "OneBlock") + " is deleted and you start fresh.\n&cEverything on it is lost.", "&c&lYes, reset",
                        () -> { cmd(x, cmd + " reset"); Bukkit.getScheduler().runTaskLater(plugin, () -> x.performCommand(cmd + " reset"), 10L); }, () -> islands(x, type))));
        if (p.hasPermission("najurs.admin")) for (String id : coopIds(type))
            b.add(button("&c⚙ Manage " + plugin.worlds().getString(id + ".name"), "&7Reset the shared world for everyone, delete...", 300, x -> manage(x, id)));
        p.showDialog(screen(title, List.of(line(sky ? "&7Survive on a tiny island in the void. Co-op = everyone on one island." : "&7Break the magic block, it becomes the next one.\n&7Phases: Plains, Underground, Ocean, Snow, Jungle,\n&7Desert, Nether, Deep Dark, The End.")),
                List.of(), b, 1, back(this::main)));
    }

    // ---------- survival worlds ----------

    void survival(Player p) {
        boolean admin = p.hasPermission("najurs.admin");
        List<ActionButton> b = new ArrayList<>();
        for (String id : plugin.worlds().getKeys(false)) {
            int n = plugin.dims(id).stream().mapToInt(this::players).sum();
            String name = plugin.worlds().getString(id + ".name", id);
            b.add(button("&a&l" + name + "  &7" + (n == 0 ? "" : "(" + n + ")"), "&7" + plugin.modeLine(id) + "\n" + online(n) + "\n&eClick to play", admin ? 230 : 300, x -> plugin.join(x, id)));
            if (admin) b.add(button("&c⚙", "&7Manage " + name, 60, x -> manage(x, id)));
        }
        if (admin) {
            b.add(button("&a&l+ Create a new world", "&7Name, type, modes and seed", 230, this::create));
            b.add(button("&7?", "&7/worlds create <name> [type] [modes] [seed:<n>]", 60, x -> {}));
        }
        List<DialogBody> body = List.of(line("&7Each world keeps its own inventory, nether, end\n&7and the spot you left from."));
        p.showDialog(screen("&2&l⛏ Survival Worlds", body, List.of(), b, admin ? 2 : 1, back(this::main)));
    }

    void create(Player p) {
        if (!p.hasPermission("najurs.admin")) return;
        List<DialogInput> in = List.of(
                DialogInput.text("name", txt("&fWorld name")).maxLength(24).width(280).initial("").build(),
                DialogInput.singleOption("type", txt("&fWorld type"), List.of(
                        SingleOptionDialogInput.OptionEntry.create("normal", txt("Normal"), true),
                        SingleOptionDialogInput.OptionEntry.create("large_biomes", txt("Large biomes"), false),
                        SingleOptionDialogInput.OptionEntry.create("amplified", txt("Amplified"), false),
                        SingleOptionDialogInput.OptionEntry.create("flat", txt("Superflat"), false),
                        SingleOptionDialogInput.OptionEntry.create("skyblock", txt("Co-op Skyblock (void island)"), false),
                        SingleOptionDialogInput.OptionEntry.create("oneblock", txt("Co-op OneBlock (magic block)"), false))).width(280).build(),
                DialogInput.bool("randomdrops", txt("&6Random Drops &7- every block drops a random item")).build(),
                DialogInput.bool("mobmorph", txt("&2Mob Morph &7- turn into a random mob every minute")).build(),
                DialogInput.bool("chaos", txt("&cGoose Chase &7- destructive events every wave")).build(),
                DialogInput.text("seed", txt("&fSeed &7(empty = random)")).maxLength(32).width(280).initial("").build());
        ActionButton go = button("&a&l✔ Create world", "&7Takes a few seconds", 150, (view, x) -> {
            String name = Optional.ofNullable(view.getText("name")).orElse("").replaceAll("[^A-Za-z0-9 '_-]", "").trim();
            if (name.isEmpty()) { x.sendMessage(ChatColor.RED + "Give the world a name."); create(x); return; }
            String id = "sw_" + NajursCore.slug(name);
            if (plugin.worlds().isConfigurationSection(id) || Bukkit.getWorld(id) != null) { x.sendMessage(ChatColor.RED + "A world called " + name + " already exists."); create(x); return; }
            List<String> flags = new ArrayList<>();
            for (String f : List.of("randomdrops", "mobmorph", "chaos")) if (Boolean.TRUE.equals(view.getBoolean(f))) flags.add(f);
            String seed = Optional.ofNullable(view.getText("seed")).orElse("").replaceAll("[^-0-9A-Za-z]", "");
            plugin.createWorld(x, id, name, Optional.ofNullable(view.getText("type")).orElse("normal"), flags, seed.isEmpty() ? null : seed);
        });
        DialogBase base = DialogBase.builder(txt("&a&l+ New Survival World")).canCloseWithEscape(true).pause(false)
                .body(List.of(line("&7Pick a name, a type and any modes. Modes can be\n&7changed later from the ⚙ manager."))).inputs(in).build();
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(go, button("&7« Back", null, 150, this::survival)))));
    }

    void manage(Player p, String id) {
        if (!p.hasPermission("najurs.admin") || !plugin.worlds().isConfigurationSection(id)) return;
        String name = plugin.worlds().getString(id + ".name", id);
        List<String> flags = plugin.worlds().getStringList(id + ".flags");
        World w = Bukkit.getWorld(id);
        int n = plugin.dims(id).stream().mapToInt(this::players).sum();
        List<ActionButton> b = new ArrayList<>();
        b.add(button("&a▶ Teleport there", null, 145, x -> plugin.join(x, id)));
        b.add(button("&e⚡ Pull everyone here", "&7Every online player joins " + name, 145, x -> cmd(x, "worlds sendall " + id)));
        for (String[] f : new String[][]{{"randomdrops", "&6Random Drops"}, {"mobmorph", "&2Mob Morph"}, {"chaos", "&cGoose Chase"}}) {
            boolean on = flags.contains(f[0]);
            b.add(button(f[1] + ": " + (on ? "&a&lON" : "&8OFF"), "&7Click to turn " + (on ? "off" : "on"), 145, x -> { cmd(x, "worlds flag " + id + " " + f[0]); manage(x, id); }));
        }
        b.add(button("&f✎ Rename", null, 145, x -> rename(x, id)));
        b.add(button("&6⟳ Reset (same seed)", "&7Fresh copy of the same map.\n&cWipes blocks, inventories, homes.", 145,
                x -> confirm(x, "&6Reset " + name + "?", "&7Same seed, fresh map. Everyone inside goes to the hub.\n&cBlocks, inventories and homes are wiped.", "&6&lYes, reset", () -> plugin.reset(x, id, false), () -> manage(x, id))));
        b.add(button("&d⟳ Reset (NEW seed)", "&7Brand new map, same modes.\n&cWipes blocks, inventories, homes.", 145,
                x -> confirm(x, "&dReset " + name + " with a new seed?", "&7A brand new random map with the same modes.\n&cBlocks, inventories and homes are wiped.", "&d&lYes, new map", () -> plugin.reset(x, id, true), () -> manage(x, id))));
        b.add(button("&4✖ Delete world", "&cPermanently removes it.\n&7Tip: Reset keeps the world in the list.", 145,
                x -> confirm(x, "&4Delete " + name + " forever?", "&cThis removes the world, its nether, end and inventories.\n&7If you just want a fresh map, use Reset instead.", "&4&lDelete forever", () -> plugin.delete(x, id), () -> manage(x, id))));
        List<DialogBody> body = List.of(line("&7ID &f" + id + "&7  •  " + plugin.modeLine(id) + "  •  " + online(n)
                + (w == null ? "" : "\n&7Seed &f" + w.getSeed())));
        p.showDialog(screen("&c⚙ " + name, body, List.of(), b, 2, back(this::survival)));
    }

    void rename(Player p, String id) {
        String name = plugin.worlds().getString(id + ".name", id);
        ActionButton ok = button("&a✔ Rename", null, 150, (view, x) -> {
            String nn = Optional.ofNullable(view.getText("name")).orElse("").replaceAll("[^A-Za-z0-9 '_-]", "").trim();
            if (!nn.isEmpty()) cmd(x, "worlds rename " + id + " " + nn);
            manage(x, id);
        });
        DialogBase base = DialogBase.builder(txt("&fRename " + name)).canCloseWithEscape(true).pause(false)
                .inputs(List.of(DialogInput.text("name", txt("&fNew name")).maxLength(24).width(280).initial(name).build())).build();
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(ok, button("&7Cancel", null, 150, x -> manage(x, id))))));
    }

    // ---------- owner dashboard ----------

    void dashboard(Player p) {
        if (!p.hasPermission("najurs.admin")) return;
        if (!supported(p)) { plugin.dashboard.open(p); return; }
        Runtime rt = Runtime.getRuntime();
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        long up = ManagementFactory.getRuntimeMXBean().getUptime() / 60000;
        List<DialogBody> body = List.of(line((tps > 18 ? "&a" : tps > 15 ? "&e" : "&c") + "● &7TPS &f" + String.format("%.1f", tps)
                + "   &7RAM &f" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + "/" + rt.maxMemory() / 1048576 + " MB"
                + "   &7Up &f" + up / 60 + "h " + up % 60 + "m   &7Players &f" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()));
        List<ActionButton> b = new ArrayList<>();
        b.add(button("&6&l⚡ Send EVERYONE to…", "&7Pick a game or world", 145, x -> sendPicker(x, null)));
        b.add(button("&f&l☺ Players", "&7Teleport, bring, heal, game mode,\n&7send, kick", 145, this::playerList));
        b.add(button("&a&l⛏ Survival worlds", "&7Create, reset, new seed, delete", 145, this::survival));
        b.add(button("&c&lMore tools", "&7The full admin panel (/admin)", 145, x -> x.chat("/admin")));
        b.add(button("&e☀ Day", null, 145, x -> { cmd(x, "minecraft:time set day"); dashboard(x); }));
        b.add(button("&9☾ Night", null, 145, x -> { cmd(x, "minecraft:time set midnight"); dashboard(x); }));
        b.add(button("&b☂ Clear weather", null, 145, x -> { cmd(x, "minecraft:weather clear"); dashboard(x); }));
        b.add(button("&a✔ Save all worlds", null, 145, x -> { plugin.console("save-all"); x.sendMessage(ChatColor.GREEN + "Worlds saved."); }));
        p.showDialog(screen("&4&l⚙ NAJURS DASHBOARD", body, List.of(), b, 2, back(this::main)));
    }

    void playerList(Player admin) {
        List<ActionButton> b = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers())
            b.add(button("&f" + t.getName(), "&7" + plugin.prettyName(t.getWorld()) + " • " + t.getGameMode().name().toLowerCase() + " • " + Math.round(t.getHealth()) + "❤", 145, x -> player(x, t.getUniqueId())));
        admin.showDialog(screen("&f&l☺ Players online", List.of(), List.of(), b, 2, back(this::dashboard)));
    }

    void player(Player admin, UUID who) {
        Player t = Bukkit.getPlayer(who);
        if (t == null) { admin.sendMessage(ChatColor.RED + "That player left."); playerList(admin); return; }
        Consumer<Consumer<Player>> act = f -> { Player x = Bukkit.getPlayer(who); if (x != null) f.accept(x); };
        List<ActionButton> b = List.of(
                button("&aTeleport to them", null, 145, x -> act.accept(x::teleport)),
                button("&aBring them here", null, 145, x -> act.accept(y -> y.teleport(x))),
                button("&bSend to hub", null, 145, x -> { act.accept(y -> cmd(y, "hub")); player(x, who); }),
                button("&cHeal & feed", null, 145, x -> { act.accept(y -> { y.setHealth(y.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()); y.setFoodLevel(20); y.setSaturation(20); }); player(x, who); }),
                button("&fSurvival", null, 145, x -> { act.accept(y -> y.setGameMode(GameMode.SURVIVAL)); player(x, who); }),
                button("&fCreative", null, 145, x -> { act.accept(y -> y.setGameMode(GameMode.CREATIVE)); player(x, who); }),
                button("&fSpectator", null, 145, x -> { act.accept(y -> y.setGameMode(GameMode.SPECTATOR)); player(x, who); }),
                button("&6Send to a game…", null, 145, x -> sendPicker(x, who)),
                button("&4Kick", "&7They can rejoin", 145, x -> confirm(x, "&4Kick " + t.getName() + "?", "&7They can rejoin straight away.", "&4Kick",
                        () -> act.accept(y -> y.kickPlayer(ChatColor.RED + "Kicked by the owner. You can rejoin.")), () -> player(x, who))));
        admin.showDialog(screen("&f" + t.getName(), List.of(line("&7In &f" + plugin.prettyName(t.getWorld()) + "&7 • " + t.getGameMode().name().toLowerCase()
                + " • &c" + Math.round(t.getHealth()) + "❤")), List.of(), b, 2, back(this::playerList)));
    }

    /** who == null sends everyone. */
    void sendPicker(Player admin, UUID who) {
        List<ActionButton> b = new ArrayList<>();
        List<Dashboard.Game> games = new ArrayList<>(Dashboard.GAMES);
        games.add(5, new Dashboard.Game("&c&lTeam Battle", Material.RED_BANNER, "go teams"));
        games.add(1, new Dashboard.Game("&4&lNightfall", Material.WITHER_SKELETON_SKULL, "go nightfall"));
        for (Dashboard.Game g : games)
            b.add(button(g.name(), null, 145, x -> send(x, who, g.name(), y -> cmd(y, g.command()))));
        for (String id : plugin.worlds().getKeys(false)) {
            String name = plugin.worlds().getString(id + ".name", id);
            b.add(button("&a&l" + name, "&7Survival world", 145, x -> send(x, who, name, y -> plugin.go(y, id))));
        }
        String title = who == null ? "&6&l⚡ Send EVERYONE to…" : "&6Send " + Optional.ofNullable(Bukkit.getPlayer(who)).map(Player::getName).orElse("?") + " to…";
        admin.showDialog(screen(title, List.of(), List.of(), b, 2, back(who == null ? this::dashboard : x -> player(x, who))));
    }

    private void send(Player admin, UUID who, String label, Consumer<Player> action) {
        if (who != null) { Player t = Bukkit.getPlayer(who); if (t != null) action.accept(t); return; }
        Bukkit.getOnlinePlayers().forEach(action);
        Bukkit.broadcastMessage(ChatColor.GOLD + "» " + ChatColor.YELLOW + admin.getName() + " pulled everyone into "
                + ChatColor.stripColor(NajursCore.colour(label)) + ChatColor.GRAY + " (type /hub to leave)");
    }

    // ---------- PvP rematch vote ----------

    void vote(Player p, Match.Arena a) {
        String winner = a.alive.isEmpty() ? null : Optional.ofNullable(Bukkit.getPlayer(a.alive.iterator().next())).map(Player::getName).orElse(null);
        String head = a.teams ? "&7Round " + a.round + " is over." : winner == null ? "&7Round " + a.round + " is over." : "&f" + winner + " &6won round " + a.round + "!";
        DialogBase base = DialogBase.builder(txt(a.colour.toString().replace('§', '&') + "&l" + a.title + " &7- play again?")).canCloseWithEscape(true).pause(false)
                .body(List.of(line(head), line("&7You have " + a.timer + "s. Closing this = stay and watch."))).build();
        p.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(
                button("&a&l⚔ REMATCH", "&7Queue for the next round", 150, x -> plugin.match.vote(x, a, true)),
                button("&c&l✖ LEAVE", "&7Back to the hub", 150, x -> plugin.match.vote(x, a, false))))));
    }
}
