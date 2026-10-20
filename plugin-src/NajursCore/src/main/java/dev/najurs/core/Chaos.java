package dev.najurs.core;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

/**
 * Goose Chase: in worlds with the `chaos` flag a random event hits a random player (or everyone)
 * wave (chaos-wave-seconds). Waves stack several bad events, including screen-warping ones (inverted colours,
 * red screen, darkness, nausea), plus sometimes a gift.
 */
final class Chaos implements org.bukkit.event.Listener {
    record Event(String name, ChatColor colour, String hint, boolean everyone, Consumer<Player> run) {}

    private final NajursCore plugin;
    private final Random rnd;
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Long> nextAt = new HashMap<>();

    Chaos(NajursCore plugin) {
        this.plugin = plugin;
        this.rnd = plugin.random;
        events.add(new Event("NUKE INCOMING", ChatColor.DARK_RED, "Run! It lands where you are standing in 5s", false, this::nuke));
        events.add(new Event("TNT RAIN", ChatColor.RED, "Look up!", false, this::tntRain));
        events.add(new Event("ANVIL RAIN", ChatColor.GRAY, "Hide under something!", false, this::anvilRain));
        events.add(new Event("LIGHTNING STORM", ChatColor.YELLOW, "Zeus is angry", false, this::lightning));
        events.add(new Event("THE GOOSE IS LOOSE", ChatColor.GOLD, "It's coming for you. Run.", false, this::goose));
        events.add(new Event("MOB AMBUSH", ChatColor.DARK_GREEN, "You're surrounded", false, this::ambush));
        events.add(new Event("SKY DROP", ChatColor.AQUA, "Water bucket MLG time!", false, this::skyDrop));
        events.add(new Event("RANDOM TELEPORT", ChatColor.LIGHT_PURPLE, "Where am I?", false, this::randomTeleport));
        events.add(new Event("PLAYER SWAP", ChatColor.LIGHT_PURPLE, "Everyone swaps places", true, p -> swap(p.getWorld())));
        events.add(new Event("HOTBAR SHUFFLE", ChatColor.BLUE, "Good luck finding your sword", false, this::shuffle));
        events.add(new Event("FLOOR IS LAVA", ChatColor.GOLD, "Don't stand still!", false, this::floorIsLava));
        events.add(new Event("ICE AGE", ChatColor.AQUA, "Everything freezes", false, this::iceAge));
        events.add(new Event("GRAVITY OFF", ChatColor.WHITE, "Up, up and away", true, p -> effect(p, PotionEffectType.LEVITATION, 5, 1)));
        events.add(new Event("MOON JUMP", ChatColor.WHITE, "Low gravity for 30s", true, p -> { effect(p, PotionEffectType.JUMP_BOOST, 30, 4); effect(p, PotionEffectType.SLOW_FALLING, 30, 0); }));
        events.add(new Event("GIANT MODE", ChatColor.GREEN, "You are huge for 30s", false, p -> scaleFor(p, 2.5, 30)));
        events.add(new Event("TINY MODE", ChatColor.GREEN, "You are tiny for 30s", false, p -> scaleFor(p, 0.3, 30)));
        events.add(new Event("STARVING", ChatColor.DARK_GRAY, "Your stomach rumbles", false, p -> { p.setFoodLevel(4); effect(p, PotionEffectType.HUNGER, 20, 1); }));
        events.add(new Event("OP GIFT", ChatColor.GREEN, "Lucky you!", false, this::gift));
        events.add(new Event("FEAST", ChatColor.GREEN, "Golden food for everyone", true, p -> give(p, new ItemStack(Material.GOLDEN_CARROT, 16), new ItemStack(Material.GOLDEN_APPLE, 2))));
        events.add(new Event("SPEED PARTY", ChatColor.YELLOW, "Gotta go fast", true, p -> { effect(p, PotionEffectType.SPEED, 30, 2); effect(p, PotionEffectType.HASTE, 30, 2); }));
        events.add(new Event("XP SHOWER", ChatColor.GREEN, "Levels rain from the sky", false, this::xpShower));
        events.add(new Event("CHICKEN EXPLOSION", ChatColor.WHITE, "Bawk.", false, this::chickens));
        events.add(new Event("METEOR", ChatColor.RED, "Something is falling nearby", false, this::meteor));
        events.add(new Event("SHEEP DISCO", ChatColor.LIGHT_PURPLE, "Party time", false, this::disco));
        events.add(new Event("BLOCK RAIN", ChatColor.GOLD, "Random blocks from the sky", false, this::blockRain));
        events.add(new Event("FLYING MOBS", ChatColor.DARK_GREEN, "Mobs are being thrown at you", false, this::flyingMobs));
        events.add(new Event("CREEPER BOMBERS", ChatColor.GREEN, "Charged creepers incoming", false, this::bombers));
        events.add(new Event("ARROW STORM", ChatColor.GRAY, "Shields up!", false, this::arrowStorm));
        events.add(new Event("ANGRY BEES", ChatColor.YELLOW, "Bzzzz", false, this::bees));
        events.add(new Event("PHANTOM SQUAD", ChatColor.DARK_BLUE, "They come from above", false, this::phantoms));
        events.add(new Event("ITEM STORM", ChatColor.LIGHT_PURPLE, "Random loot explodes around you", false, this::itemStorm));
        // screen & potion chaos: things that mess with what you can see
        events.add(new Event("BLACKOUT", ChatColor.DARK_GRAY, "Who turned off the lights?", false, p -> { effect(p, PotionEffectType.BLINDNESS, 8, 0); p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 0.5f); }));
        events.add(new Event("WARDEN'S DARKNESS", ChatColor.DARK_AQUA, "It can hear your heartbeat...", false, this::wardenDark));
        events.add(new Event("DRUNK", ChatColor.GREEN, "The world is spinning", false, p -> { effect(p, PotionEffectType.NAUSEA, 15, 0); effect(p, PotionEffectType.SLOWNESS, 15, 0); }));
        events.add(new Event("NEGATIVE WORLD", ChatColor.DARK_PURPLE, "Enderman vision: colours inverted", false, p -> vision(p, EntityType.ENDERMAN, 7)));
        events.add(new Event("CREEPER VISION", ChatColor.GREEN, "Everything turns green", false, p -> vision(p, EntityType.CREEPER, 7)));
        events.add(new Event("SPIDER EYES", ChatColor.DARK_RED, "Eight eyes, zero sense", false, p -> vision(p, EntityType.SPIDER, 7)));
        events.add(new Event("BLOOD MOON SCREEN", ChatColor.RED, "Your screen bleeds red", false, p -> redScreen(p, 15)));
        events.add(new Event("FROSTBITE", ChatColor.AQUA, "You're freezing over", false, this::frostbite));
        events.add(new Event("HALLUCINATION", ChatColor.LIGHT_PURPLE, "Is that real fire? Was that a creeper?", false, this::hallucinate));
        events.add(new Event("TORNADO", ChatColor.WHITE, "You spin round and round", false, this::spin));
        events.add(new Event("POTION ROULETTE", ChatColor.LIGHT_PURPLE, "Three random potions. Good luck.", false, this::roulette));
        events.add(new Event("WITHERING", ChatColor.DARK_GRAY, "Your hearts turn black", false, p -> { effect(p, PotionEffectType.WITHER, 8, 0); effect(p, PotionEffectType.WEAKNESS, 20, 0); }));
        events.add(new Event("TOXIC CLOUD", ChatColor.DARK_GREEN, "Hold your breath", false, p -> { effect(p, PotionEffectType.POISON, 7, 1); effect(p, PotionEffectType.MINING_FATIGUE, 20, 1);
            p.getWorld().spawnParticle(Particle.ENTITY_EFFECT, p.getLocation().add(0, 1, 0), 200, 2, 1, 2, 0, Color.GREEN); }));
    }

    static final Set<String> GOOD = Set.of("OP GIFT", "FEAST", "SPEED PARTY", "XP SHOWER", "MOON JUMP", "SHEEP DISCO", "ITEM STORM");
    private final Map<String, Integer> waves = new HashMap<>();

    @org.bukkit.event.EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!plugin.hasFlag(p.getWorld(), "chaos")) return;
        int n = waves.getOrDefault(p.getWorld().getName(), 0);
        for (Player o : p.getWorld().getPlayers()) o.sendMessage(ChatColor.DARK_RED + "☠ " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " was taken by the goose on wave " + ChatColor.YELLOW + n
                + ChatColor.GRAY + " (record " + plugin.data.getInt("goose.best." + p.getUniqueId()) + ")");
    }

    void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10, 20L); }

    /** Runs every second: keeps everyone regenerating, counts down, and fires a wave every chaos-wave-seconds (config.yml). */
    private void tick() {
        long now = System.currentTimeMillis();
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.hasFlag(w, "chaos")) continue;
            List<Player> players = w.getPlayers().stream().filter(p -> p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE).toList();
            if (players.isEmpty() && w.getPlayers().stream().noneMatch(p -> watching.containsKey(p.getUniqueId()))) { nextAt.remove(w.getName()); waves.remove(w.getName()); continue; }   // empty world: next run starts at wave 1
            for (Player p : players) {
                // you can survive a lot, but not forever: everything is designed to wear you down slowly
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 0, false, false, true));
            }
            long due = nextAt.computeIfAbsent(w.getName(), k -> now + 30_000);
            long left = (due - now + 999) / 1000;
            if (left <= 5 && left >= 1)
                for (Player p : players) {
                    p.sendActionBar(ChatColor.RED + "" + ChatColor.BOLD + "⚠ CHAOS WAVE IN " + left + " ⚠");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 0.5f + (5 - left) * 0.2f);
                }
            if (now < due) continue;
            nextAt.put(w.getName(), now + 1000L * Math.max(10, plugin.getConfig().getInt("chaos-wave-seconds", 25)));
            wave(w, players);
        }
    }

    /** A wave stacks several destructive events on everyone at once, getting bigger every 5 waves. */
    void wave(World w, List<Player> players) {
        int n = waves.merge(w.getName(), 1, Integer::sum);
        for (Player p : players) { // best wave reached, for the Hall of Fame
            String path = "goose.best." + p.getUniqueId();
            if (n > plugin.data.getInt(path)) { plugin.data.set(path, n); plugin.data.set("names." + p.getUniqueId(), p.getName()); }
        }
        int count = Math.min(3 + (n - 1) / 5, 6);
        List<Event> bad = new ArrayList<>(events.stream().filter(e -> !GOOD.contains(e.name())).toList());
        List<Event> good = events.stream().filter(e -> GOOD.contains(e.name())).toList();
        Collections.shuffle(bad, rnd);
        List<Event> picked = new ArrayList<>(bad.subList(0, count));
        if (rnd.nextInt(10) < 4) picked.add(good.get(rnd.nextInt(good.size())));
        String names = String.join(ChatColor.GRAY + " + ", picked.stream().map(e -> e.colour() + "" + ChatColor.BOLD + e.name()).toList());
        for (Player p : players) {
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "WAVE " + n, names, 5, 60, 10);
            p.sendMessage(ChatColor.GOLD + "⚡ Wave " + n + " » " + names);
            p.playSound(p.getLocation(), Sound.ENTITY_GOAT_SCREAMING_AMBIENT, 1f, 1.3f);
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.6f, 1f);
        }
        // stagger the events a little so they overlap instead of landing in the same tick
        for (int i = 0; i < picked.size(); i++) {
            Event e = picked.get(i);
            later(i * 30, () -> {
                List<Player> alive = w.getPlayers().stream().filter(p -> p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE).toList();
                if (e.name().equals("PLAYER SWAP")) { if (!alive.isEmpty()) e.run().accept(alive.get(0)); }
                else alive.forEach(e.run());
            });
        }
    }

    // ---------- screen effects ----------

    /** Who is currently looking through a mob's eyes, and where/how to put them back. */
    private final Map<UUID, Object[]> watching = new HashMap<>();

    /** Spectating a mob applies its vanilla shader: enderman = inverted colours, creeper = green, spider = multi-eye. */
    void vision(Player p, EntityType type, int secs) {
        if (watching.containsKey(p.getUniqueId()) || p.isDead()) return;
        Location back = p.getLocation();
        LivingEntity eye = (LivingEntity) p.getWorld().spawnEntity(back, type);
        eye.setAI(false); eye.setInvisible(true); eye.setInvulnerable(true); eye.setSilent(true); eye.setGravity(false);
        eye.setPersistent(false); eye.addScoreboardTag("chaos_vision");
        watching.put(p.getUniqueId(), new Object[]{back, p.getGameMode(), eye});
        p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(eye);
        later(secs * 20, () -> endVision(p));
    }

    void endVision(Player p) {
        Object[] v = watching.remove(p.getUniqueId());
        if (v == null) return;
        ((Entity) v[2]).remove();
        if (!p.isOnline()) return;
        p.setSpectatorTarget(null);
        p.teleport((Location) v[0]);
        p.setGameMode((GameMode) v[1]);
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { endVision(e.getPlayer()); e.getPlayer().setWorldBorder(null); }

    /** Spectators can't leave the mob early (sneaking would). */
    @org.bukkit.event.EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        if (watching.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** A personal world border far away with a huge warning distance tints the whole screen red. */
    void redScreen(Player p, int secs) {
        WorldBorder b = Bukkit.createWorldBorder();
        b.setCenter(p.getLocation()); b.setSize(59_000_000); b.setWarningDistance(Integer.MAX_VALUE);
        p.setWorldBorder(b);
        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f);
        later(secs * 20, () -> { if (p.isOnline()) p.setWorldBorder(null); });
    }

    void wardenDark(Player p) {
        effect(p, PotionEffectType.DARKNESS, 15, 0);
        for (int i = 0; i < 7; i++) later(i * 40, () -> p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1f));
        later(100, () -> p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, 0.8f, 1f));
        later(200, () -> p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1f));
    }

    void frostbite(Player p) {
        effect(p, PotionEffectType.SLOWNESS, 10, 1);
        for (int i = 0; i < 10; i++) later(i * 20, () -> { if (p.isOnline()) p.setFreezeTicks(p.getMaxFreezeTicks() + 20); });
        p.playSound(p.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1f, 0.6f);
    }

    void hallucinate(Player p) {
        p.setVisualFire(true);
        later(160, () -> { if (p.isOnline()) p.setVisualFire(false); });
        for (Sound s : List.of(Sound.ENTITY_CREEPER_PRIMED, Sound.ENTITY_TNT_PRIMED, Sound.ENTITY_ZOMBIE_AMBIENT, Sound.ENTITY_GHAST_SCREAM, Sound.ENTITY_ENDERMAN_STARE))
            later(rnd.nextInt(160), () -> { Location l = p.getLocation(); l.add(l.getDirection().multiply(-2)); p.playSound(l, s, 1f, 1f); });
        effect(p, PotionEffectType.NAUSEA, 8, 0);
    }

    void spin(Player p) {
        for (int i = 0; i < 50; i++) later(i * 2, () -> {
            if (!p.isOnline() || watching.containsKey(p.getUniqueId())) return;
            p.setRotation(p.getLocation().getYaw() + 36, p.getLocation().getPitch());
        });
        p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.6f, 1.5f);
    }

    static final List<PotionEffectType> ROULETTE = List.of(PotionEffectType.BLINDNESS, PotionEffectType.DARKNESS, PotionEffectType.NAUSEA,
            PotionEffectType.LEVITATION, PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON, PotionEffectType.GLOWING,
            PotionEffectType.HUNGER, PotionEffectType.MINING_FATIGUE, PotionEffectType.SPEED, PotionEffectType.JUMP_BOOST, PotionEffectType.INVISIBILITY,
            PotionEffectType.NIGHT_VISION, PotionEffectType.SLOW_FALLING, PotionEffectType.STRENGTH, PotionEffectType.INFESTED, PotionEffectType.OOZING,
            PotionEffectType.WEAVING, PotionEffectType.WIND_CHARGED, PotionEffectType.BAD_OMEN);

    void roulette(Player p) {
        List<PotionEffectType> pool = new ArrayList<>(ROULETTE);
        Collections.shuffle(pool, rnd);
        for (PotionEffectType t : pool.subList(0, 3)) effect(p, t, t == PotionEffectType.LEVITATION ? 4 : 15, t == PotionEffectType.SPEED || t == PotionEffectType.JUMP_BOOST ? 2 : 0);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "Potion roulette: " + ChatColor.WHITE + String.join(", ", pool.subList(0, 3).stream().map(t -> t.getKey().getKey().replace('_', ' ')).toList()));
        p.playSound(p.getLocation(), Sound.ENTITY_WITCH_DRINK, 1f, 1f);
    }

    // ---------- helpers ----------

    void later(int ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); }

    static void effect(Player p, PotionEffectType t, int seconds, int amp) { p.addPotionEffect(new PotionEffect(t, seconds * 20, amp, false, true, true)); }

    static void give(Player p, ItemStack... items) {
        for (ItemStack it : items) p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    Location around(Location c, double radius) {
        double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * radius;
        return c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    /** Highest safe standing spot at x/z. */
    static Location surface(World w, double x, double z) {
        int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(w, x, y + 1, z);
    }

    // ---------- events ----------

    private void nuke(Player p) {
        Location ground = p.getLocation().clone();
        World w = ground.getWorld();
        for (int s = 5; s >= 1; s--) {
            int left = s;
            later((5 - s) * 20, () -> {
                w.spawnParticle(Particle.FLAME, ground.clone().add(0, 1, 0), 60, 0.6, 12, 0.6, 0.01);
                w.playSound(ground, Sound.BLOCK_NOTE_BLOCK_PLING, 3f, 0.5f + left * 0.2f);
                if (p.isOnline()) p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☢ " + left, ChatColor.RED + "Get away from the marker!", 0, 22, 0);
            });
        }
        later(100, () -> {
            w.playSound(ground, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.4f);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, ground, 8, 3, 3, 3);
            w.createExplosion(ground, 7f, true, true);
        });
    }

    private void tntRain(Player p) {
        for (int i = 0; i < 10; i++) {
            Location l = around(p.getLocation(), 9).add(0, 22 + rnd.nextInt(6), 0);
            later(i * 6, () -> { TNTPrimed t = l.getWorld().spawn(l, TNTPrimed.class); t.setFuseTicks(70); });
        }
    }

    private void anvilRain(Player p) {
        for (int i = 0; i < 14; i++) {
            Location l = around(p.getLocation(), 6).add(0, 25, 0);
            later(i * 3, () -> l.getWorld().spawn(l, FallingBlock.class, fb -> fb.setBlockData(Material.ANVIL.createBlockData())));
        }
    }

    private void lightning(Player p) {
        for (int i = 0; i < 8; i++) {
            later(i * 10, () -> { if (p.isOnline()) p.getWorld().strikeLightning(around(p.getLocation(), 8)); });
        }
    }

    private void goose(Player p) {
        Location l = around(p.getLocation(), 12);
        Location spawn = surface(p.getWorld(), l.getX(), l.getZ());
        Chicken goose = p.getWorld().spawn(spawn, Chicken.class, c -> {
            c.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "THE GOOSE");
            c.setCustomNameVisible(true);
            c.setGlowing(true);
            setAttr(c, Attribute.SCALE, 1.6);
            setAttr(c, Attribute.MAX_HEALTH, 30);
            c.setHealth(30);
        });
        Zombie rider = p.getWorld().spawn(spawn, Zombie.class, z -> {
            z.setBaby();
            z.setInvisible(true);
            z.setSilent(true);
            z.setTarget(p);
            setAttr(z, Attribute.MOVEMENT_SPEED, 0.45);
            setAttr(z, Attribute.ATTACK_DAMAGE, 4);
            setAttr(z, Attribute.FOLLOW_RANGE, 64);
            z.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET)); // no burning in daylight
        });
        goose.addPassenger(rider);
        // honk, and give up after 60s so geese don't pile up
        for (int i = 1; i <= 12; i++) later(i * 100, () -> { if (goose.isValid()) goose.getWorld().playSound(goose.getLocation(), Sound.ENTITY_GOAT_SCREAMING_RAM_IMPACT, 1.2f, 1.6f); });
        later(20 * 60, () -> { rider.remove(); if (goose.isValid()) goose.remove(); });
    }

    static void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private static final EntityType[] AMBUSH = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.HUSK, EntityType.STRAY, EntityType.CREEPER};

    private void ambush(Player p) {
        for (int i = 0; i < 6; i++) {
            Location l = around(p.getLocation(), 7);
            Entity e = p.getWorld().spawnEntity(surface(p.getWorld(), l.getX(), l.getZ()), AMBUSH[rnd.nextInt(AMBUSH.length)]);
            if (e instanceof Mob m) { m.setTarget(p); m.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET)); }
        }
    }

    private void skyDrop(Player p) {
        give(p, new ItemStack(Material.WATER_BUCKET));
        p.teleport(p.getLocation().add(0, 60, 0));
        p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1f, 1f);
    }

    private void randomTeleport(Player p) {
        Location l = around(p.getLocation(), 250);
        Location to = surface(p.getWorld(), l.getX(), l.getZ());
        p.teleport(to);
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
    }

    private void swap(World w) {
        List<Player> ps = new ArrayList<>(w.getPlayers());
        if (ps.size() < 2) { ps.forEach(this::randomTeleport); return; }
        List<Location> locs = ps.stream().map(Player::getLocation).toList();
        for (int i = 0; i < ps.size(); i++) ps.get(i).teleport(locs.get((i + 1) % ps.size()));
    }

    private void shuffle(Player p) {
        ItemStack[] hot = new ItemStack[9];
        for (int i = 0; i < 9; i++) hot[i] = p.getInventory().getItem(i);
        List<ItemStack> list = new ArrayList<>(Arrays.asList(hot));
        Collections.shuffle(list, rnd);
        for (int i = 0; i < 9; i++) p.getInventory().setItem(i, list.get(i));
    }

    private void floorIsLava(Player p) {
        // magma under the player for 8 seconds, then the original blocks come back
        Map<Block, Material> saved = new HashMap<>();
        Block base = p.getLocation().getBlock().getRelative(0, -1, 0);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            Block b = base.getRelative(x, 0, z);
            if (b.getType().isSolid() && !(b.getState() instanceof org.bukkit.block.TileState)) { saved.put(b, b.getType()); b.setType(Material.MAGMA_BLOCK); }
        }
        later(160, () -> saved.forEach((b, m) -> { if (b.getType() == Material.MAGMA_BLOCK) b.setType(m); }));
    }

    private void iceAge(Player p) {
        Block base = p.getLocation().getBlock();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            if (x * x + z * z > 64) continue;
            Block top = p.getWorld().getHighestBlockAt(base.getX() + x, base.getZ() + z);
            if (top.getType() == Material.WATER) top.setType(Material.ICE);
            else if (top.getType().isSolid() && top.getRelative(0, 1, 0).getType().isAir()) top.getRelative(0, 1, 0).setType(Material.SNOW);
        }
        effect(p, PotionEffectType.SLOWNESS, 10, 1);
        p.setFreezeTicks(200);
    }

    private void scaleFor(Player p, double scale, int seconds) {
        Morph.setScale(p, scale);
        later(seconds * 20, () -> { if (p.isOnline() && !plugin.hasFlag(p.getWorld(), "mobmorph")) Morph.setScale(p, 1.0); });
    }

    private static final Material[] GIFTS = {Material.DIAMOND, Material.DIAMOND_PICKAXE, Material.TOTEM_OF_UNDYING, Material.ENCHANTED_GOLDEN_APPLE,
            Material.ELYTRA, Material.NETHERITE_INGOT, Material.TRIDENT, Material.ENDER_PEARL, Material.DIAMOND_CHESTPLATE, Material.MACE};

    private void gift(Player p) {
        Material m = GIFTS[rnd.nextInt(GIFTS.length)];
        give(p, new ItemStack(m, m == Material.DIAMOND || m == Material.ENDER_PEARL ? 8 : 1), new ItemStack(plugin.randomItem()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void xpShower(Player p) {
        for (int i = 0; i < 25; i++) {
            Location l = around(p.getLocation(), 4).add(0, 6, 0);
            later(i * 2, () -> l.getWorld().spawn(l, ExperienceOrb.class, o -> o.setExperience(15)));
        }
    }

    private void chickens(Player p) {
        for (int i = 0; i < 20; i++) {
            Chicken c = p.getWorld().spawn(p.getLocation().add(0, 2, 0), Chicken.class);
            c.setVelocity(new Vector(rnd.nextGaussian() * 0.6, 0.8 + rnd.nextDouble() * 0.5, rnd.nextGaussian() * 0.6));
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_CHICKEN_EGG, 2f, 0.5f);
    }

    private void meteor(Player p) {
        Location target = around(p.getLocation(), 25);
        Location ground = surface(p.getWorld(), target.getX(), target.getZ());
        Fireball f = p.getWorld().spawn(ground.clone().add(0, 60, 0), LargeFireball.class, fb -> {
            fb.setDirection(new Vector(0, -1, 0));
            fb.setYield(5f);
            fb.setIsIncendiary(true);
        });
        p.getWorld().playSound(ground, Sound.ITEM_TRIDENT_THUNDER, 3f, 0.5f);
        later(20 * 10, () -> { if (f.isValid()) f.remove(); });
    }

    private void disco(Player p) {
        List<Sheep> sheep = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Location l = around(p.getLocation(), 5);
            sheep.add(p.getWorld().spawn(surface(p.getWorld(), l.getX(), l.getZ()), Sheep.class, s -> s.setCustomName("jeb_")));
        }
        float[] tune = {0.7f, 0.9f, 1.05f, 1.2f, 1.05f, 0.9f, 1.2f, 1.4f};
        for (int i = 0; i < 24; i++) { float pitch = tune[i % tune.length]; later(i * 5, () -> p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, pitch)); }
        later(20 * 30, () -> sheep.forEach(Entity::remove));
    }

    private static final Material[] RAIN = {Material.SAND, Material.GRAVEL, Material.ANVIL, Material.RED_SAND, Material.POINTED_DRIPSTONE,
            Material.MAGMA_BLOCK, Material.TNT, Material.COBWEB, Material.LAVA, Material.OBSIDIAN, Material.SLIME_BLOCK, Material.HONEY_BLOCK};

    private void blockRain(Player p) {
        for (int i = 0; i < 24; i++) {
            Location l = around(p.getLocation(), 7).add(0, 18 + rnd.nextInt(8), 0);
            Material m = RAIN[rnd.nextInt(RAIN.length)];
            later(i * 3, () -> {
                if (m == Material.TNT) { l.getWorld().spawn(l, TNTPrimed.class, t -> t.setFuseTicks(50)); return; }
                FallingBlock fb = l.getWorld().spawn(l, FallingBlock.class, f -> f.setBlockData(m.createBlockData()));
                fb.setHurtEntities(true);
                fb.setDamagePerBlock(2f);
                fb.setMaxDamage(12);
            });
        }
    }

    private static final EntityType[] THROWN = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER,
            EntityType.SILVERFISH, EntityType.SLIME, EntityType.HUSK, EntityType.WITCH};

    private void flyingMobs(Player p) {
        for (int i = 0; i < 6; i++) {
            later(i * 8, () -> {
                if (!p.isOnline()) return;
                Location from = around(p.getLocation(), 14).add(0, 10, 0);
                Entity e = p.getWorld().spawnEntity(from, THROWN[rnd.nextInt(THROWN.length)]);
                Vector toward = p.getLocation().toVector().subtract(from.toVector()).normalize().multiply(1.6).setY(0.5);
                e.setVelocity(toward);
                if (e instanceof Mob m) { m.setTarget(p); m.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET)); }
            });
        }
    }

    private void bombers(Player p) {
        for (int i = 0; i < 3; i++) {
            Location l = around(p.getLocation(), 6).add(0, 20, 0);
            later(i * 15, () -> l.getWorld().spawn(l, Creeper.class, c -> { c.setPowered(true); c.setTarget(p); c.setMaxFuseTicks(25); }));
        }
    }

    private void arrowStorm(Player p) {
        for (int i = 0; i < 40; i++) {
            later(i * 2, () -> {
                if (!p.isOnline()) return;
                Location l = around(p.getLocation(), 5).add(0, 15, 0);
                Arrow a = p.getWorld().spawnArrow(l, new Vector(rnd.nextGaussian() * 0.05, -1, rnd.nextGaussian() * 0.05), 1.6f, 6f);
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                later(100, a::remove);
            });
        }
    }

    private void bees(Player p) {
        for (int i = 0; i < 6; i++) {
            Bee b = p.getWorld().spawn(around(p.getLocation(), 3).add(0, 2, 0), Bee.class);
            b.setAnger(400); b.setTarget(p);
            later(20 * 25, () -> { if (b.isValid()) b.remove(); });
        }
    }

    private void phantoms(Player p) {
        for (int i = 0; i < 3; i++) {
            Phantom ph = p.getWorld().spawn(p.getLocation().add(rnd.nextGaussian() * 4, 12, rnd.nextGaussian() * 4), Phantom.class);
            ph.setTarget(p);
            later(20 * 40, () -> { if (ph.isValid()) ph.remove(); });
        }
    }

    private void itemStorm(Player p) {
        for (int i = 0; i < 16; i++) {
            later(i * 2, () -> {
                if (!p.isOnline()) return;
                Item it = p.getWorld().dropItem(p.getLocation().add(0, 1.5, 0), new ItemStack(plugin.randomItem()));
                it.setVelocity(new Vector(rnd.nextGaussian() * 0.35, 0.5, rnd.nextGaussian() * 0.35));
            });
        }
    }
}
