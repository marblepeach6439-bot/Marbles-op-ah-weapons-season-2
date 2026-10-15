package me.marbles.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MarblesOPWeapons2 extends JavaPlugin implements Listener, TabExecutor {

    private static final List<String> WEAPONS = List.of("lifestealer", "oathbreaker", "creator", "stormbreaker", "gravity_end", "frostmourne", "nightfall", "grimoire");

    private NamespacedKey weaponKey, healthyKey, lifestealKey, bonusKey, constructKey;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<UUID> healthyActive = new HashSet<>();
    private final Map<UUID, Set<UUID>> trust = new HashMap<>();
    private File trustFile;

    // Oathbreaker
    private final Map<UUID, Integer> contractHits = new HashMap<>();
    private final Set<UUID> shockwaveActive = new HashSet<>();

    // Creator
    private final Map<UUID, List<IronGolem>> constructs = new HashMap<>();
    private final Map<UUID, Integer> constructToken = new HashMap<>();
    private int tokenCounter = 0;
    private final Map<UUID, UUID> ownerTarget = new HashMap<>();
    private final Map<UUID, Long> ownerTargetTime = new HashMap<>();
    private final Map<UUID, UUID> ownerAttacker = new HashMap<>();
    private final Map<UUID, Long> ownerAttackerTime = new HashMap<>();
    private final Map<UUID, UUID> staffHit = new HashMap<>();
    private final Map<UUID, Long> staffHitTime = new HashMap<>();
    private final List<Domain> domains = new ArrayList<>();
    private int domainTick = 0;

    // Stormbreaker
    private NamespacedKey boltKey, volleyKey, gravityKey;
    private final Set<AbstractArrow> bolts = new HashSet<>();
    private final Map<UUID, UUID> lockTarget = new HashMap<>();
    private final Map<UUID, Long> lockUntil = new HashMap<>();
    private final Map<UUID, Integer> hitStreak = new HashMap<>();
    private final Map<UUID, Long> hitStreakTime = new HashMap<>();

    // Frostmourne
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Set<UUID> aiFrozen = new HashSet<>();

    // Grimoire
    private final Map<UUID, Integer> spellIdx = new HashMap<>();
    private final Set<UUID> spellArmed = new HashSet<>();
    private static final String[] SPELLS = {"Wither", "Slowness", "Weakness"};

    private static class Domain {
        World world;
        double cx, cy, cz, half;
        UUID owner, target;
        long start;

        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().equals(world)
                    && Math.abs(l.getX() - cx) <= half
                    && Math.abs(l.getY() - cy) <= half
                    && Math.abs(l.getZ() - cz) <= half;
        }

        boolean isParticipant(UUID u) {
            return u.equals(owner) || u.equals(target);
        }
    }

    // ================= Setup =================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true); // adds any new keys to an old config.yml
        saveConfig();

        weaponKey = new NamespacedKey(this, "weapon");
        healthyKey = new NamespacedKey(this, "healthy_boi");
        lifestealKey = new NamespacedKey(this, "lifesteal");
        bonusKey = new NamespacedKey(this, "lifesteal_bonus");
        constructKey = new NamespacedKey(this, "construct_owner");
        boltKey = new NamespacedKey(this, "bolt_owner");
        volleyKey = new NamespacedKey(this, "volley_bolt");
        gravityKey = new NamespacedKey(this, "gravity_kb");
        loadTrust();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("marbleweapons").setExecutor(this);
        getCommand("marbleweapons").setTabCompleter(this);
        getCommand("mtrust").setExecutor(this);
        getCommand("mtrust").setTabCompleter(this);

        // every second: lose the blade -> lose lifesteal bonus hearts
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                if (!hasWeapon(p, "lifestealer") && getBonus(p) > 0) {
                    setBonus(p, 0);
                    p.sendActionBar(Component.text("You lost the Lifestealer - bonus hearts gone!", NamedTextColor.RED));
                }
            }
        }, 20L, 20L);

        // every 0.5s: Oathbreaker passives + golem AI
        getServer().getScheduler().runTaskTimer(this, () -> {
            oathbreakerPassives();
            constructAI();
            weaponPassives();
        }, 10L, 10L);

        // every 0.2s: isolation domains
        getServer().getScheduler().runTaskTimer(this, this::domainTickAll, 4L, 4L);

        // homing bolts every tick, frozen entities every 5 ticks
        getServer().getScheduler().runTaskTimer(this, this::homingTick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::frozenTick, 5L, 5L);
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) { removeModifier(p, healthyKey); clearAttr(p, Attribute.KNOCKBACK_RESISTANCE, gravityKey); }
        for (UUID id : new ArrayList<>(frozenUntil.keySet())) unfreeze(id);
        for (UUID id : new ArrayList<>(constructs.keySet())) despawnConstructs(id, false);
        for (Domain d : new ArrayList<>(domains)) endDomain(d, "Plugin disabled");
    }

    // ================= Helpers =================

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String pretty(String id) {
        return id.replace('_', ' ');
    }

    private double ab(String weapon, String ability, String key, double def) {
        return getConfig().getDouble("weapons." + weapon + ".abilities." + ability + "." + key, def);
    }

    private boolean keyMatches(String weapon, String ability, String defKey, boolean shift) {
        String k = getConfig().getString("weapons." + weapon + ".abilities." + ability + ".key", defKey);
        return k.equalsIgnoreCase("SHIFT_F") == shift;
    }

    private String keyLabel(String weapon, String ability, String defKey) {
        String k = getConfig().getString("weapons." + weapon + ".abilities." + ability + ".key", defKey);
        return k.equalsIgnoreCase("SHIFT_F") ? "Shift + F" : "F";
    }

    private boolean onCooldown(Player p, String id) {
        Long ready = cooldowns.get(p.getUniqueId() + ":" + id);
        long now = System.currentTimeMillis();
        if (ready != null && now < ready) {
            p.sendActionBar(Component.text(pretty(id) + " on cooldown: " + ((ready - now + 999) / 1000) + "s", NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void startCooldown(Player p, String id, double seconds) {
        cooldowns.put(p.getUniqueId() + ":" + id, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    private long cooldownLeft(Player p, String id) {
        Long ready = cooldowns.get(p.getUniqueId() + ":" + id);
        long now = System.currentTimeMillis();
        return (ready != null && now < ready) ? (ready - now + 999) / 1000 : 0;
    }

    private void effect(Player p, PotionEffectType type, int level, int ticks) {
        if (level <= 0) return;
        p.addPotionEffect(new PotionEffect(type, ticks, level - 1, false, false, true));
    }

    private Location safe(World w, double x, double y, double z, float yaw, float pitch) {
        Location l = new Location(w, x, y, z, yaw, pitch);
        Block b = l.getBlock();
        if (b.isPassable() && b.getRelative(0, 1, 0).isPassable()) return l;
        l.setY(w.getHighestBlockYAt(l) + 1);
        return l;
    }

    // ================= Trust system =================

    private void loadTrust() {
        trust.clear();
        trustFile = new File(getDataFolder(), "trust.yml");
        if (!trustFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(trustFile);
        for (String owner : y.getKeys(false)) {
            try {
                Set<UUID> set = new HashSet<>();
                for (String s : y.getStringList(owner)) set.add(UUID.fromString(s));
                trust.put(UUID.fromString(owner), set);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveTrust() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> en : trust.entrySet()) {
            List<String> list = new ArrayList<>();
            for (UUID u : en.getValue()) list.add(u.toString());
            y.set(en.getKey().toString(), list);
        }
        try {
            getDataFolder().mkdirs();
            y.save(trustFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save trust.yml: " + ex.getMessage());
        }
    }

    private boolean isTrusted(UUID owner, UUID target) {
        boolean a = trust.getOrDefault(owner, Set.of()).contains(target);
        if (!getConfig().getBoolean("trust.mutual", false)) return a;
        return a && trust.getOrDefault(target, Set.of()).contains(owner);
    }

    private String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString();
    }

    private boolean handleTrust(Player p, String[] a) {
        if (a.length == 0) {
            p.sendMessage(Component.text("Usage: /mtrust <add|remove|list> [player]", NamedTextColor.YELLOW));
            return true;
        }
        Set<UUID> mine = trust.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        switch (a[0].toLowerCase()) {
            case "add" -> {
                if (a.length < 2) { p.sendMessage(Component.text("Usage: /mtrust add <player>", NamedTextColor.YELLOW)); return true; }
                Player t = getServer().getPlayerExact(a[1]);
                if (t == null) { p.sendMessage(Component.text("That player must be online.", NamedTextColor.RED)); return true; }
                if (t.equals(p)) { p.sendMessage(Component.text("You can't trust yourself.", NamedTextColor.RED)); return true; }
                mine.add(t.getUniqueId());
                saveTrust();
                p.sendMessage(Component.text("You now trust " + t.getName() + ".", NamedTextColor.GREEN));
                t.sendMessage(Component.text(p.getName() + " now trusts you.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (a.length < 2) { p.sendMessage(Component.text("Usage: /mtrust remove <player>", NamedTextColor.YELLOW)); return true; }
                UUID found = null;
                for (UUID u : mine) if (nameOf(u).equalsIgnoreCase(a[1])) found = u;
                if (found == null) { p.sendMessage(Component.text("You don't trust " + a[1] + ".", NamedTextColor.RED)); return true; }
                mine.remove(found);
                saveTrust();
                p.sendMessage(Component.text("You no longer trust " + a[1] + ".", NamedTextColor.GREEN));
            }
            case "list" -> {
                if (mine.isEmpty()) { p.sendMessage(Component.text("You trust nobody.", NamedTextColor.GRAY)); return true; }
                List<String> names = new ArrayList<>();
                for (UUID u : mine) names.add(nameOf(u));
                p.sendMessage(Component.text("Trusted: " + String.join(", ", names), NamedTextColor.AQUA));
            }
            default -> p.sendMessage(Component.text("Usage: /mtrust <add|remove|list> [player]", NamedTextColor.YELLOW));
        }
        return true;
    }

    // ================= Items =================

    private Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private void addAbility(List<Component> lore, String title, String key, String... desc) {
        lore.add(line(title + " (" + key + ")", NamedTextColor.RED));
        for (String d : desc) lore.add(line("  " + d, NamedTextColor.GRAY));
    }

    private void addPassive(List<Component> lore, String title, String... desc) {
        lore.add(line(title + " (Passive)", NamedTextColor.GOLD));
        for (String d : desc) lore.add(line("  " + d, NamedTextColor.GRAY));
    }

    public ItemStack createWeapon(String id) {
        var cfg = getConfig();
        String root = "weapons." + id + ".";
        Material mat;
        String title;
        NamedTextColor color;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        if (id.equals("lifestealer")) {
            mat = Material.NETHERITE_SWORD; title = "Lifestealer"; color = NamedTextColor.DARK_RED;
            addAbility(lore, "Healthy Boi", keyLabel(id, "healthy_boi", "F"),
                    "Multiply current health x" + fmt(ab(id, "healthy_boi", "multiplier", 2.0)) + " for " + fmt(ab(id, "healthy_boi", "duration", 30)) + "s",
                    "Cooldown: " + fmt(ab(id, "healthy_boi", "cooldown", 60)) + "s");
            if (cfg.getBoolean(root + "abilities.lifesteal.enabled", true))
                addPassive(lore, "Lifesteal", "Each kill: +" + fmt(ab(id, "lifesteal", "hearts-per-kill", 0.5)) + " heart",
                        "Resets on death / losing the blade");
            if (cfg.getBoolean(root + "abilities.life_leach.enabled", true))
                addPassive(lore, "Life Leach", "Heal " + fmt(ab(id, "life_leach", "percent", 50)) + "% of damage you deal");
        } else if (id.equals("oathbreaker")) {
            mat = Material.NETHERITE_SWORD; title = "Oathbreaker"; color = NamedTextColor.DARK_PURPLE;
            addAbility(lore, "Blood Contract", keyLabel(id, "blood_contract", "F"),
                    "Sacrifice " + fmt(ab(id, "blood_contract", "sacrifice-hearts", 3)) + " hearts: next "
                            + fmt(ab(id, "blood_contract", "hits", 3)) + " hits deal +"
                            + fmt(ab(id, "blood_contract", "bonus-hearts", 2)) + " hearts",
                    "Cooldown: " + fmt(ab(id, "blood_contract", "cooldown", 20)) + "s");
            addAbility(lore, "Final Clause", keyLabel(id, "final_clause", "SHIFT_F"),
                    "Consume all absorption hearts for a shockwave",
                    "+" + fmt(ab(id, "final_clause", "damage-per-absorption-heart", 0.5)) + " heart damage per absorption heart (max "
                            + fmt(ab(id, "final_clause", "max-damage-hearts", 5)) + ")",
                    "Cooldown: " + fmt(ab(id, "final_clause", "cooldown", 30)) + "s");
            addPassive(lore, "Oathless", "Strength " + cfg.getInt(root + "passives.strength-level", 2)
                    + ", Speed " + cfg.getInt(root + "passives.speed-level", 2) + " while held");
        } else if (id.equals("creator")) {
            mat = Material.NETHERITE_AXE; title = "The Creator"; color = NamedTextColor.AQUA;
            addAbility(lore, "Created Construct", keyLabel(id, "created_construct", "F"),
                    "Summon " + (int) ab(id, "created_construct", "amount", 10) + " iron golems for "
                            + fmt(ab(id, "created_construct", "duration", 60)) + "s",
                    "They follow and defend you like tamed wolves",
                    "Cooldown: " + fmt(ab(id, "created_construct", "cooldown", 120)) + "s");
            addAbility(lore, "Isolation Barrier", keyLabel(id, "isolation_barrier", "SHIFT_F"),
                    "Hit a player, then trap you both in a "
                            + (int) ab(id, "isolation_barrier", "size", 50) + "^3 domain",
                    "Everyone else is teleported out. Lasts until one of you dies",
                    "Cooldown: " + fmt(ab(id, "isolation_barrier", "cooldown", 180)) + "s");
            addPassive(lore, "Constructs", "Golems get Strength " + cfg.getInt(root + "passives.golem-strength-level", 2)
                    + " + Resistance " + cfg.getInt(root + "passives.golem-resistance-level", 1));
            addPassive(lore, "Domain Guard", "Resistance " + cfg.getInt(root + "passives.domain-resistance-level", 1) + " inside your domain");
        } else if (id.equals("stormbreaker")) {
            mat = Material.CROSSBOW; title = "Stormbreaker's Repeater"; color = NamedTextColor.AQUA;
            addAbility(lore, "Volley", keyLabel(id, "volley", "F"),
                    "Fire " + (int) ab(id, "volley", "bolts", 3) + " spectral bolts, " + fmt(ab(id, "volley", "damage-hearts", 1)) + " heart each",
                    "Cooldown: " + fmt(ab(id, "volley", "cooldown", 20)) + "s");
            addAbility(lore, "Lockdown", keyLabel(id, "lockdown", "SHIFT_F"),
                    "Mark an enemy within " + (int) ab(id, "lockdown", "range", 30) + " blocks for " + fmt(ab(id, "lockdown", "duration", 6)) + "s",
                    "Your bolts home toward the mark", "Cooldown: " + fmt(ab(id, "lockdown", "cooldown", 35)) + "s");
            addPassive(lore, "Storm Sights", "Every 3rd consecutive hit: Slowness I", "Hit from 20+ blocks: Speed I for 3s", "Bolts glow after firing");
        } else if (id.equals("gravity_end")) {
            mat = Material.MACE; title = "Gravity's End"; color = NamedTextColor.DARK_BLUE;
            addAbility(lore, "Singularity", keyLabel(id, "singularity", "F"),
                    "Gravity well within " + (int) ab(id, "singularity", "range", 20) + " blocks pulls enemies in for " + fmt(ab(id, "singularity", "duration", 4)) + "s",
                    "Cooldown: " + fmt(ab(id, "singularity", "cooldown", 35)) + "s");
            addAbility(lore, "Repulsion", keyLabel(id, "repulsion", "SHIFT_F"),
                    "Blast enemies away for " + fmt(ab(id, "repulsion", "damage-hearts", 2)) + " hearts",
                    "Cooldown: " + fmt(ab(id, "repulsion", "cooldown", 25)) + "s");
            addPassive(lore, "Heavy", "-30% knockback received", "+20% damage to airborne enemies");
        } else if (id.equals("frostmourne")) {
            mat = Material.NETHERITE_AXE; title = "Frostmourne's Axe"; color = NamedTextColor.AQUA;
            addAbility(lore, "Glacial Cleave", keyLabel(id, "glacial_cleave", "F"),
                    (int) ab(id, "glacial_cleave", "range", 7) + "-block arc: " + fmt(ab(id, "glacial_cleave", "damage-hearts", 2)) + " hearts + Slowness III "
                            + fmt(ab(id, "glacial_cleave", "slow-seconds", 4)) + "s",
                    "Cooldown: " + fmt(ab(id, "glacial_cleave", "cooldown", 25)) + "s");
            addAbility(lore, "Frozen Prison", keyLabel(id, "frozen_prison", "SHIFT_F"),
                    "Freeze your target (" + (int) ab(id, "frozen_prison", "range", 20) + " blocks) for " + fmt(ab(id, "frozen_prison", "duration", 3)) + "s",
                    "Cooldown: " + fmt(ab(id, "frozen_prison", "cooldown", 45)) + "s");
            addPassive(lore, "Permafrost", "Immune to Slowness I", "+15% damage to slowed enemies", "Resistance I in snow or ice");
        } else if (id.equals("nightfall")) {
            mat = Material.NETHERITE_SWORD; title = "Nightfall"; color = NamedTextColor.DARK_GRAY;
            addAbility(lore, "Shadowstep", keyLabel(id, "shadowstep", "F"),
                    "Teleport behind an enemy within " + (int) ab(id, "shadowstep", "range", 10) + " blocks for " + fmt(ab(id, "shadowstep", "damage-hearts", 2)) + " hearts",
                    "Cooldown: " + fmt(ab(id, "shadowstep", "cooldown", 20)) + "s");
            addAbility(lore, "Thousand Cuts", keyLabel(id, "thousand_cuts", "SHIFT_F"),
                    (int) ab(id, "thousand_cuts", "strikes", 5) + " rapid strikes, " + fmt(ab(id, "thousand_cuts", "damage-hearts", 0.5)) + " heart each",
                    "Cooldown: " + fmt(ab(id, "thousand_cuts", "cooldown", 35)) + "s");
            addPassive(lore, "Dusk", "Critical hit: Speed II for 3s", "+10% damage from behind", "-20% fall damage");
        } else if (id.equals("grimoire")) {
            mat = Material.BOOK; title = "Grimoire of Ruin"; color = NamedTextColor.LIGHT_PURPLE;
            addAbility(lore, "Forbidden Spell", keyLabel(id, "forbidden_spell", "F"),
                    "Cycle Wither / Slowness / Weakness", "Next hit applies it for " + fmt(ab(id, "forbidden_spell", "duration", 5)) + "s",
                    "Cooldown: " + fmt(ab(id, "forbidden_spell", "cooldown", 25)) + "s");
            addAbility(lore, "Arcane Cataclysm", keyLabel(id, "arcane_cataclysm", "SHIFT_F"),
                    "Explosion within " + (int) ab(id, "arcane_cataclysm", "range", 20) + " blocks: "
                            + fmt(ab(id, "arcane_cataclysm", "damage-hearts", 3)) + " hearts, " + (int) ab(id, "arcane_cataclysm", "radius", 5) + "-block radius",
                    "Cooldown: " + fmt(ab(id, "arcane_cataclysm", "cooldown", 45)) + "s");
            addPassive(lore, "Ruin", "Resistance I below half health", "Negative effects you apply last 20% longer");
        } else {
            return null;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        ConfigurationSection ench = cfg.getConfigurationSection(root + "enchantments");
        if (ench != null) {
            for (String k : ench.getKeys(false)) {
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase(Locale.ROOT)));
                int lvl = ench.getInt(k);
                if (en == null) { getLogger().warning("Unknown enchantment in config: " + k); continue; }
                if (lvl > 0) meta.addEnchant(en, lvl, true);
            }
        }
        meta.setUnbreakable(cfg.getBoolean(root + "unbreakable", true));

        meta.setItemModel(new NamespacedKey("marbles", id)); // resource pack model
        if (id.equals("creator")) meta.setEnchantmentGlintOverride(true);
        double bonus = cfg.getDouble(root + "attack-damage-bonus", 0);
        if (bonus > 0) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(this, id + "_damage"), bonus,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String weaponId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    private boolean isWeapon(ItemStack item, String id) {
        return id.equals(weaponId(item));
    }

    private boolean holds(Player p, String id) {
        return isWeapon(p.getInventory().getItemInMainHand(), id);
    }

    private boolean hasWeapon(Player p, String id) {
        for (ItemStack i : p.getInventory().getContents()) if (isWeapon(i, id)) return true;
        return isWeapon(p.getItemOnCursor(), id);
    }

    // ================= Max health helpers =================

    private void setModifier(Player p, NamespacedKey key, double amount) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        removeModifier(p, key);
        if (amount > 0) attr.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    private void removeModifier(Player p, NamespacedKey key) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        for (AttributeModifier m : new ArrayList<>(attr.getModifiers()))
            if (m.getKey().equals(key)) attr.removeModifier(m);
        if (p.getHealth() > attr.getValue()) p.setHealth(attr.getValue());
    }

    private double getBonus(Player p) {
        return p.getPersistentDataContainer().getOrDefault(bonusKey, PersistentDataType.DOUBLE, 0.0);
    }

    private void setBonus(Player p, double v) {
        if (v <= 0) p.getPersistentDataContainer().remove(bonusKey);
        else p.getPersistentDataContainer().set(bonusKey, PersistentDataType.DOUBLE, v);
        setModifier(p, lifestealKey, v);
    }

    private void heal(Player p, double amount) {
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    // ================= Ability keys (F / Shift+F) =================

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        String id = weaponId(p.getInventory().getItemInMainHand());
        if (id == null) return;
        e.setCancelled(true); // F never swaps a weapon into the offhand
        boolean shift = p.isSneaking();

        switch (id) {
            case "lifestealer" -> {
                if (keyMatches(id, "healthy_boi", "F", shift)) healthyBoi(p);
            }
            case "oathbreaker" -> {
                if (keyMatches(id, "blood_contract", "F", shift)) bloodContract(p);
                else if (keyMatches(id, "final_clause", "SHIFT_F", shift)) finalClause(p);
            }
            case "creator" -> {
                if (keyMatches(id, "created_construct", "F", shift)) createdConstruct(p);
                else if (keyMatches(id, "isolation_barrier", "SHIFT_F", shift)) isolationBarrier(p);
            }
            case "stormbreaker" -> {
                if (keyMatches(id, "volley", "F", shift)) volley(p);
                else if (keyMatches(id, "lockdown", "SHIFT_F", shift)) lockdown(p);
            }
            case "gravity_end" -> {
                if (keyMatches(id, "singularity", "F", shift)) singularity(p);
                else if (keyMatches(id, "repulsion", "SHIFT_F", shift)) repulsion(p);
            }
            case "frostmourne" -> {
                if (keyMatches(id, "glacial_cleave", "F", shift)) glacialCleave(p);
                else if (keyMatches(id, "frozen_prison", "SHIFT_F", shift)) frozenPrison(p);
            }
            case "nightfall" -> {
                if (keyMatches(id, "shadowstep", "F", shift)) shadowstep(p);
                else if (keyMatches(id, "thousand_cuts", "SHIFT_F", shift)) thousandCuts(p);
            }
            case "grimoire" -> {
                if (keyMatches(id, "forbidden_spell", "F", shift)) forbiddenSpell(p);
                else if (keyMatches(id, "arcane_cataclysm", "SHIFT_F", shift)) arcaneCataclysm(p);
            }
            default -> { }
        }
    }

    // ================= LIFESTEALER =================

    private void healthyBoi(Player p) {
        String w = "lifestealer";
        if (onCooldown(p, "healthy_boi")) return;
        if (healthyActive.contains(p.getUniqueId())) return;

        double durationSec = ab(w, "healthy_boi", "duration", 30);
        double mult = ab(w, "healthy_boi", "multiplier", 2.0);
        startCooldown(p, "healthy_boi", ab(w, "healthy_boi", "cooldown", 60));
        healthyActive.add(p.getUniqueId());

        double current = p.getHealth();
        setModifier(p, healthyKey, current * (mult - 1));
        p.setHealth(Math.min(current * mult, p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        p.sendActionBar(Component.text("HEALTHY BOI! Health x" + fmt(mult) + " for " + fmt(durationSec) + "s", NamedTextColor.GREEN));

        UUID id = p.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> endHealthy(id), (long) (durationSec * 20));
    }

    private void endHealthy(UUID id) {
        if (!healthyActive.remove(id)) return;
        Player p = getServer().getPlayer(id);
        if (p == null) return;
        removeModifier(p, healthyKey);
        p.sendActionBar(Component.text("Healthy Boi ended", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (!getConfig().getBoolean("weapons.lifestealer.abilities.lifesteal.enabled", true)) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.equals(e.getEntity()) || !hasWeapon(killer, "lifestealer")) return;

        double perKill = ab("lifestealer", "lifesteal", "hearts-per-kill", 0.5) * 2.0;
        double cap = ab("lifestealer", "lifesteal", "max-bonus-hearts", 0) * 2.0;
        double next = getBonus(killer) + perKill;
        if (cap > 0) next = Math.min(next, cap);
        setBonus(killer, next);
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 0.6f);
        killer.sendActionBar(Component.text("Lifesteal: +" + fmt(next / 2) + " bonus hearts", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeach(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("weapons.lifestealer.abilities.life_leach.enabled", true)) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (!holds(p, "lifestealer")) return;
        double pct = ab("lifestealer", "life_leach", "percent", 50) / 100.0;
        heal(p, e.getFinalDamage() * pct);
    }

    // ================= OATHBREAKER =================

    private void oathbreakerPassives() {
        int str = getConfig().getInt("weapons.oathbreaker.passives.strength-level", 2);
        int spd = getConfig().getInt("weapons.oathbreaker.passives.speed-level", 2);
        for (Player p : getServer().getOnlinePlayers()) {
            if (!holds(p, "oathbreaker")) continue;
            effect(p, PotionEffectType.STRENGTH, str, 40);
            effect(p, PotionEffectType.SPEED, spd, 40);
        }
    }

    private void bloodContract(Player p) {
        String w = "oathbreaker";
        if (onCooldown(p, "blood_contract")) return;
        if (contractHits.getOrDefault(p.getUniqueId(), 0) > 0) {
            p.sendActionBar(Component.text("Blood Contract is already active", NamedTextColor.RED));
            return;
        }
        double cost = ab(w, "blood_contract", "sacrifice-hearts", 3) * 2.0;
        if (p.getHealth() <= cost) {
            p.sendActionBar(Component.text("Not enough health to sign the contract", NamedTextColor.RED));
            return;
        }
        p.setHealth(p.getHealth() - cost);
        int hits = (int) ab(w, "blood_contract", "hits", 3);
        contractHits.put(p.getUniqueId(), hits);
        startCooldown(p, "blood_contract", ab(w, "blood_contract", "cooldown", 20));
        p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.1);
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.6f);
        p.sendActionBar(Component.text("BLOOD CONTRACT! Next " + hits + " hits are empowered", NamedTextColor.DARK_RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContractHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (shockwaveActive.contains(p.getUniqueId())) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;
        if (!holds(p, "oathbreaker")) return;
        int left = contractHits.getOrDefault(p.getUniqueId(), 0);
        if (left <= 0) return;

        double bonus = ab("oathbreaker", "blood_contract", "bonus-hearts", 2) * 2.0;
        e.setDamage(e.getDamage() + bonus);
        left--;
        if (left <= 0) {
            contractHits.remove(p.getUniqueId());
            p.sendActionBar(Component.text("Blood Contract fulfilled", NamedTextColor.GRAY));
        } else {
            contractHits.put(p.getUniqueId(), left);
            p.sendActionBar(Component.text("Blood Contract: " + left + " hits left", NamedTextColor.DARK_RED));
        }
        e.getEntity().getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, e.getEntity().getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
    }

    private void finalClause(Player p) {
        String w = "oathbreaker";
        if (onCooldown(p, "final_clause")) return;
        double absorption = p.getAbsorptionAmount(); // HP (2 HP = 1 heart)
        if (absorption <= 0) {
            p.sendActionBar(Component.text("You have no absorption hearts to consume", NamedTextColor.RED));
            return;
        }
        double absorbHearts = absorption / 2.0;
        double damageHearts = Math.min(absorbHearts * ab(w, "final_clause", "damage-per-absorption-heart", 0.5),
                ab(w, "final_clause", "max-damage-hearts", 5));
        double damage = damageHearts * 2.0;
        double radius = ab(w, "final_clause", "radius", 8);
        double kb = ab(w, "final_clause", "knockback", 1.5);

        p.setAbsorptionAmount(0);
        startCooldown(p, "final_clause", ab(w, "final_clause", "cooldown", 30));

        shockwaveActive.add(p.getUniqueId());
        try {
            for (Entity en : p.getNearbyEntities(radius, radius, radius)) {
                if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || le.isDead()) continue;
                if (constructOwner(en) != null && constructOwner(en).equals(p.getUniqueId())) continue;
                if (en instanceof Player t && isTrusted(p.getUniqueId(), t.getUniqueId())) continue;
                le.damage(damage, p);
                Vector push = le.getLocation().toVector().subtract(p.getLocation().toVector());
                if (push.lengthSquared() > 0) {
                    push.normalize().multiply(kb).setY(0.4);
                    le.setVelocity(push);
                }
            }
        } finally {
            shockwaveActive.remove(p.getUniqueId());
        }

        World world = p.getWorld();
        world.spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 3, 0.5, 0.3, 0.5, 0);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            world.spawnParticle(Particle.CLOUD, p.getLocation().add(Math.cos(a) * radius * 0.6, 0.3, Math.sin(a) * radius * 0.6), 1, 0, 0, 0, 0);
        }
        world.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
        p.sendActionBar(Component.text("FINAL CLAUSE! " + fmt(damageHearts) + " hearts of damage", NamedTextColor.DARK_PURPLE));
    }

    // ================= THE CREATOR: Created Construct =================

    private UUID constructOwner(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(constructKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    private void createdConstruct(Player p) {
        String w = "creator";
        if (onCooldown(p, "created_construct")) return;

        UUID owner = p.getUniqueId();
        despawnConstructs(owner, false);

        int amount = (int) ab(w, "created_construct", "amount", 10);
        double durationSec = ab(w, "created_construct", "duration", 60);
        int str = getConfig().getInt("weapons.creator.passives.golem-strength-level", 2);
        int res = getConfig().getInt("weapons.creator.passives.golem-resistance-level", 1);
        int effectTicks = (int) (durationSec * 20) + 200;
        startCooldown(p, "created_construct", ab(w, "created_construct", "cooldown", 120));

        List<IronGolem> list = new ArrayList<>();
        World world = p.getWorld();
        for (int i = 0; i < amount; i++) {
            double a = i * Math.PI * 2 / Math.max(1, amount);
            Location l = safe(world, p.getLocation().getX() + Math.cos(a) * 3.5, p.getLocation().getY(),
                    p.getLocation().getZ() + Math.sin(a) * 3.5, 0f, 0f);
            IronGolem g = world.spawn(l, IronGolem.class, golem -> {
                golem.setPlayerCreated(true);
                golem.setPersistent(false);
                golem.setRemoveWhenFarAway(false);
                golem.getPersistentDataContainer().set(constructKey, PersistentDataType.STRING, owner.toString());
                golem.customName(Component.text(p.getName() + "'s Construct", NamedTextColor.AQUA));
                golem.setCustomNameVisible(false);
                if (str > 0) golem.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, effectTicks, str - 1, false, false, false));
                if (res > 0) golem.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, effectTicks, res - 1, false, false, false));
            });
            list.add(g);
            world.spawnParticle(Particle.POOF, l.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
        }
        constructs.put(owner, list);
        int token = ++tokenCounter;
        constructToken.put(owner, token);
        world.playSound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.8f);
        p.sendActionBar(Component.text("CREATED CONSTRUCT! " + amount + " golems for " + fmt(durationSec) + "s", NamedTextColor.AQUA));

        getServer().getScheduler().runTaskLater(this, () -> {
            Integer cur = constructToken.get(owner);
            if (cur != null && cur == token) despawnConstructs(owner, true);
        }, (long) (durationSec * 20));
    }

    private void despawnConstructs(UUID owner, boolean fx) {
        constructToken.remove(owner);
        List<IronGolem> list = constructs.remove(owner);
        if (list == null) return;
        for (IronGolem g : list) {
            if (!g.isValid()) continue;
            if (fx) g.getWorld().spawnParticle(Particle.POOF, g.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.02);
            g.remove();
        }
    }

    private LivingEntity validTarget(UUID owner, UUID targetId, Long time, long maxAgeMs) {
        if (targetId == null || time == null || System.currentTimeMillis() - time > maxAgeMs) return null;
        Entity t = Bukkit.getEntity(targetId);
        if (!(t instanceof LivingEntity le) || le.isDead() || !le.isValid()) return null;
        if (t.getUniqueId().equals(owner)) return null;
        if (owner.equals(constructOwner(t))) return null;
        if (t instanceof Player tp && isTrusted(owner, tp.getUniqueId())) return null;
        return le;
    }

    private void constructAI() {
        for (UUID ownerId : new ArrayList<>(constructs.keySet())) {
            Player owner = getServer().getPlayer(ownerId);
            List<IronGolem> list = constructs.get(ownerId);
            if (owner == null || owner.isDead() || list == null) { despawnConstructs(ownerId, true); continue; }
            list.removeIf(g -> !g.isValid());

            LivingEntity target = validTarget(ownerId, ownerTarget.get(ownerId), ownerTargetTime.get(ownerId), 15000);
            if (target == null) target = validTarget(ownerId, ownerAttacker.get(ownerId), ownerAttackerTime.get(ownerId), 15000);

            for (IronGolem g : list) {
                if (!g.getWorld().equals(owner.getWorld()) || g.getLocation().distanceSquared(owner.getLocation()) > 35 * 35) {
                    g.teleport(safe(owner.getWorld(), owner.getLocation().getX() + (Math.random() * 4 - 2), owner.getLocation().getY(),
                            owner.getLocation().getZ() + (Math.random() * 4 - 2), 0f, 0f));
                    continue;
                }
                if (target != null && target.getWorld().equals(g.getWorld())) {
                    g.setTarget(target);
                } else {
                    LivingEntity cur = g.getTarget();
                    if (cur != null && (cur.getUniqueId().equals(ownerId) || cur instanceof Player)) g.setTarget(null);
                    if (g.getTarget() == null && g.getLocation().distanceSquared(owner.getLocation()) > 6 * 6) {
                        g.getPathfinder().moveTo(owner.getLocation(), 1.15);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onConstructTarget(EntityTargetLivingEntityEvent e) {
        UUID owner = constructOwner(e.getEntity());
        if (owner == null || e.getTarget() == null) return;
        LivingEntity t = e.getTarget();
        if (t.getUniqueId().equals(owner) || owner.equals(constructOwner(t))
                || (t instanceof Player tp && isTrusted(owner, tp.getUniqueId()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConstructDamage(EntityDamageByEntityEvent e) {
        UUID gOwner = constructOwner(e.getDamager());
        if (gOwner != null) {
            Entity v = e.getEntity();
            if (v.getUniqueId().equals(gOwner) || gOwner.equals(constructOwner(v))
                    || (v instanceof Player vp && isTrusted(gOwner, vp.getUniqueId()))) {
                e.setCancelled(true);
            }
            return;
        }
        UUID vOwner = constructOwner(e.getEntity());
        if (vOwner != null && e.getDamager() instanceof Player a && a.getUniqueId().equals(vOwner)) {
            e.setCancelled(true); // you can't hurt your own constructs
        }
    }

    @EventHandler
    public void onConstructDeath(EntityDeathEvent e) {
        if (constructOwner(e.getEntity()) == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // track who the owner fights (golems help) + staff hits (for the barrier)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackCombat(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh) damager = sh;
        long now = System.currentTimeMillis();

        if (damager instanceof Player p && e.getEntity() instanceof LivingEntity le && constructOwner(le) == null) {
            ownerTarget.put(p.getUniqueId(), le.getUniqueId());
            ownerTargetTime.put(p.getUniqueId(), now);
            if (e.getDamager() instanceof Player && le instanceof Player && holds(p, "creator")) {
                staffHit.put(p.getUniqueId(), le.getUniqueId());
                staffHitTime.put(p.getUniqueId(), now);
            }
        }
        if (e.getEntity() instanceof Player victim && damager instanceof LivingEntity a
                && !a.getUniqueId().equals(victim.getUniqueId()) && !victim.getUniqueId().equals(constructOwner(a))) {
            ownerAttacker.put(victim.getUniqueId(), a.getUniqueId());
            ownerAttackerTime.put(victim.getUniqueId(), now);
        }
    }

    // ================= THE CREATOR: Isolation Barrier =================

    private Domain domainOf(UUID u) {
        for (Domain d : domains) if (d.isParticipant(u)) return d;
        return null;
    }

    private void isolationBarrier(Player p) {
        String w = "creator";
        if (onCooldown(p, "isolation_barrier")) return;
        if (domainOf(p.getUniqueId()) != null) {
            p.sendActionBar(Component.text("You are already in a domain", NamedTextColor.RED));
            return;
        }
        double maxDist = ab(w, "isolation_barrier", "max-distance", 20);
        double windowMs = ab(w, "isolation_barrier", "target-window", 10) * 1000;

        // target = player you just hit with the staff, else the player you're looking at
        Player target = null;
        UUID hit = staffHit.get(p.getUniqueId());
        Long hitTime = staffHitTime.get(p.getUniqueId());
        if (hit != null && hitTime != null && System.currentTimeMillis() - hitTime <= windowMs) target = getServer().getPlayer(hit);
        if (target == null) {
            Entity looked = p.getTargetEntity((int) maxDist);
            if (looked instanceof Player lp) target = lp;
        }
        if (target == null || target.equals(p) || target.isDead()) {
            p.sendActionBar(Component.text("Hit a player with the staff (or look at one) first", NamedTextColor.RED));
            return;
        }
        if (!target.getWorld().equals(p.getWorld()) || target.getLocation().distance(p.getLocation()) > maxDist) {
            p.sendActionBar(Component.text("Target is too far away", NamedTextColor.RED));
            return;
        }
        if (isTrusted(p.getUniqueId(), target.getUniqueId())) {
            p.sendActionBar(Component.text("You trust " + target.getName() + " - can't trap them", NamedTextColor.YELLOW));
            return;
        }
        if (domainOf(target.getUniqueId()) != null) {
            p.sendActionBar(Component.text("Target is already in a domain", NamedTextColor.RED));
            return;
        }

        double size = ab(w, "isolation_barrier", "size", 50);
        Domain d = new Domain();
        d.world = p.getWorld();
        d.half = size / 2.0;
        d.cx = (p.getLocation().getX() + target.getLocation().getX()) / 2.0;
        d.cy = (p.getLocation().getY() + target.getLocation().getY()) / 2.0;
        d.cz = (p.getLocation().getZ() + target.getLocation().getZ()) / 2.0;
        d.owner = p.getUniqueId();
        d.target = target.getUniqueId();
        d.start = System.currentTimeMillis();

        for (Domain o : domains) {
            if (o.world.equals(d.world) && Math.abs(o.cx - d.cx) < o.half + d.half
                    && Math.abs(o.cy - d.cy) < o.half + d.half && Math.abs(o.cz - d.cz) < o.half + d.half) {
                p.sendActionBar(Component.text("Another domain is too close", NamedTextColor.RED));
                return;
            }
        }

        domains.add(d);
        startCooldown(p, "isolation_barrier", ab(w, "isolation_barrier", "cooldown", 180));

        for (Player other : d.world.getPlayers()) {
            if (!d.isParticipant(other.getUniqueId()) && d.contains(other.getLocation())) pushOut(other, d);
        }
        d.world.playSound(new Location(d.world, d.cx, d.cy, d.cz), Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);
        Component msg = Component.text("ISOLATION BARRIER! Last one standing ends it.", NamedTextColor.DARK_AQUA);
        p.sendMessage(msg);
        target.sendMessage(msg);
        p.sendActionBar(msg);
        target.sendActionBar(msg);
    }

    private void pushOut(Player pl, Domain d) {
        Location l = pl.getLocation();
        double dx = l.getX() - d.cx, dz = l.getZ() - d.cz;
        double out = d.half + 3;
        double nx = l.getX(), nz = l.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) nx = d.cx + (dx >= 0 ? out : -out);
        else nz = d.cz + (dz >= 0 ? out : -out);
        pl.teleport(safe(d.world, nx, l.getY(), nz, l.getYaw(), l.getPitch()));
        pl.sendActionBar(Component.text("An isolation barrier blocks your way", NamedTextColor.DARK_AQUA));
    }

    private void pullIn(Player pl, Domain d) {
        Location l = pl.getLocation();
        double m = d.half - 2;
        double nx = Math.max(d.cx - m, Math.min(d.cx + m, l.getX()));
        double ny = Math.max(d.cy - m, Math.min(d.cy + m, l.getY()));
        double nz = Math.max(d.cz - m, Math.min(d.cz + m, l.getZ()));
        pl.teleport(safe(d.world, nx, ny, nz, l.getYaw(), l.getPitch()));
        pl.sendActionBar(Component.text("You can't leave the barrier", NamedTextColor.DARK_AQUA));
    }

    private void endDomain(Domain d, String reason) {
        if (!domains.remove(d)) return;
        for (UUID u : new UUID[]{d.owner, d.target}) {
            Player p = getServer().getPlayer(u);
            if (p != null) p.sendMessage(Component.text("The isolation barrier fell: " + reason, NamedTextColor.GRAY));
        }
        d.world.playSound(new Location(d.world, d.cx, d.cy, d.cz), Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.7f);
    }

    private void domainTickAll() {
        if (domains.isEmpty()) return;
        domainTick++;
        boolean trap = getConfig().getBoolean("weapons.creator.abilities.isolation_barrier.trap-participants", true);
        double maxDur = ab("creator", "isolation_barrier", "max-duration", 0) * 1000;
        int resLevel = getConfig().getInt("weapons.creator.passives.domain-resistance-level", 1);

        for (Domain d : new ArrayList<>(domains)) {
            Player o = getServer().getPlayer(d.owner);
            Player t = getServer().getPlayer(d.target);
            if (o == null || t == null) { endDomain(d, "a participant left"); continue; }
            if (o.isDead() || t.isDead()) { endDomain(d, "a participant died"); continue; }
            if (maxDur > 0 && System.currentTimeMillis() - d.start > maxDur) { endDomain(d, "time ran out"); continue; }

            for (Player pl : d.world.getPlayers()) {
                boolean inside = d.contains(pl.getLocation());
                if (d.isParticipant(pl.getUniqueId())) {
                    if (!inside && trap) pullIn(pl, d);
                } else if (inside) {
                    pushOut(pl, d);
                }
            }
            if (d.contains(o.getLocation())) effect(o, PotionEffectType.RESISTANCE, resLevel, 30);

            if (domainTick % 3 == 0) drawDomain(d);
        }
    }

    private void drawDomain(Domain d) {
        double step = 2.5;
        double h = d.half;
        List<Player> viewers = new ArrayList<>();
        for (Player pl : d.world.getPlayers()) {
            double dx = pl.getLocation().getX() - d.cx, dz = pl.getLocation().getZ() - d.cz;
            if (dx * dx + dz * dz < (h + 48) * (h + 48)) viewers.add(pl);
        }
        if (viewers.isEmpty()) return;
        double[] s = {-h, h};
        for (double a : s) for (double b : s) {
            for (double t = -h; t <= h; t += step) {
                show(viewers, d.cx + t, d.cy + a, d.cz + b, d.world);   // edges along x
                show(viewers, d.cx + a, d.cy + t, d.cz + b, d.world);   // edges along y
                show(viewers, d.cx + a, d.cy + b, d.cz + t, d.world);   // edges along z
            }
        }
    }

    private void show(List<Player> viewers, double x, double y, double z, World w) {
        Location l = new Location(w, x, y, z);
        for (Player v : viewers) v.spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && c != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;
        if (e.getTo() == null) return;
        for (Domain d : domains) {
            boolean fromIn = d.contains(e.getFrom());
            boolean toIn = d.contains(e.getTo());
            boolean participant = d.isParticipant(e.getPlayer().getUniqueId());
            if ((participant && fromIn && !toIn) || (!participant && !fromIn && toIn)) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Component.text("The isolation barrier blocks teleporting", NamedTextColor.DARK_AQUA));
                return;
            }
        }
    }

    // ================= Shared targeting helpers =================

    private boolean isEnemy(Player owner, Entity e) {
        if (!(e instanceof LivingEntity le) || e instanceof ArmorStand || le.isDead() || !le.isValid()) return false;
        if (e.getUniqueId().equals(owner.getUniqueId())) return false;
        if (owner.getUniqueId().equals(constructOwner(e))) return false;
        if (e instanceof Player t && (isTrusted(owner.getUniqueId(), t.getUniqueId()) || t.getGameMode() == GameMode.SPECTATOR)) return false;
        return true;
    }

    private LivingEntity lookTarget(Player p, double range) {
        Entity t = p.getTargetEntity((int) range);
        return isEnemy(p, t) ? (LivingEntity) t : null;
    }

    private LivingEntity nearestEnemy(Player p, double range) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(range, range, range)) {
            if (!isEnemy(p, e)) continue;
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < bd && d <= range * range) { bd = d; best = (LivingEntity) e; }
        }
        return best;
    }

    private LivingEntity pickTarget(Player p, double range) {
        LivingEntity t = lookTarget(p, range);
        return t != null ? t : nearestEnemy(p, range);
    }

    private Location aimPoint(Player p, double range) {
        var res = p.rayTraceBlocks(range);
        if (res != null && res.getHitPosition() != null) return res.getHitPosition().toLocation(p.getWorld());
        Location eye = p.getEyeLocation();
        return eye.add(eye.getDirection().multiply(range));
    }

    /** Damage from an ability: skips passive damage bonuses and the invulnerability frames. */
    private void abilityDamage(Player p, LivingEntity t, double dmg) {
        shockwaveActive.add(p.getUniqueId());
        try {
            t.setNoDamageTicks(0);
            t.damage(dmg, p);
        } finally {
            shockwaveActive.remove(p.getUniqueId());
        }
    }

    private void setAttr(Player p, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) if (m.getKey().equals(key)) return;
        inst.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    private void clearAttr(Player p, Attribute a, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) if (m.getKey().equals(key)) inst.removeModifier(m);
    }

    private boolean snowOrIce(Material m) {
        return m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW || m == Material.ICE
                || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    private void dust(World w, Location l, int count, double spread, Color c, float size) {
        w.spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(c, size));
    }

    // ================= Held-weapon passives (every 0.5s) =================

    private void weaponPassives() {
        for (Player p : getServer().getOnlinePlayers()) {
            String id = weaponId(p.getInventory().getItemInMainHand());

            if ("gravity_end".equals(id)) setAttr(p, Attribute.KNOCKBACK_RESISTANCE, gravityKey, 0.3);
            else clearAttr(p, Attribute.KNOCKBACK_RESISTANCE, gravityKey);

            if ("frostmourne".equals(id)) {
                Location l = p.getLocation();
                if (snowOrIce(l.getBlock().getType()) || snowOrIce(l.clone().subtract(0, 1, 0).getBlock().getType()))
                    effect(p, PotionEffectType.RESISTANCE, 1, 40);
            }
            if ("grimoire".equals(id) && p.getHealth() < p.getAttribute(Attribute.MAX_HEALTH).getValue() / 2.0)
                effect(p, PotionEffectType.RESISTANCE, 1, 40);
        }
    }

    // ================= Melee passives =================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponPassives(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (shockwaveActive.contains(p.getUniqueId())) return;
        if (!(e.getEntity() instanceof LivingEntity v)) return;
        String id = weaponId(p.getInventory().getItemInMainHand());
        if (id == null) return;

        switch (id) {
            case "gravity_end" -> {
                if (!v.isOnGround()) e.setDamage(e.getDamage() * 1.2);
            }
            case "frostmourne" -> {
                if (v.hasPotionEffect(PotionEffectType.SLOWNESS)) e.setDamage(e.getDamage() * 1.15);
            }
            case "nightfall" -> {
                Vector facing = v.getLocation().getDirection().setY(0);
                Vector toAtt = p.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (facing.lengthSquared() > 0 && toAtt.lengthSquared() > 0 && facing.normalize().dot(toAtt.normalize()) < -0.5)
                    e.setDamage(e.getDamage() * 1.10);
                if (e.isCritical()) effect(p, PotionEffectType.SPEED, 2, 60);
            }
            case "grimoire" -> forbiddenHit(p, v);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (holds(p, "nightfall")) e.setDamage(e.getDamage() * 0.8);
    }

    @EventHandler
    public void onSlownessImmunity(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !holds(p, "frostmourne")) return;
        PotionEffect n = e.getNewEffect();
        if (n != null && n.getType().equals(PotionEffectType.SLOWNESS) && n.getAmplifier() == 0) e.setCancelled(true);
    }

    // ================= STORMBREAKER'S REPEATER =================

    private void tagBolt(AbstractArrow a, Player p, boolean volley) {
        a.getPersistentDataContainer().set(boltKey, PersistentDataType.STRING, p.getUniqueId().toString());
        if (volley) a.getPersistentDataContainer().set(volleyKey, PersistentDataType.BYTE, (byte) 1);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setGlowing(true); // passive: bolts glow briefly
        bolts.add(a);
        getServer().getScheduler().runTaskLater(this, () -> a.setGlowing(false), 30L);
        getServer().getScheduler().runTaskLater(this, () -> {
            bolts.remove(a);
            if (volley && a.isValid()) a.remove();
        }, 100L);
    }

    private void volley(Player p) {
        String w = "stormbreaker";
        if (onCooldown(p, "volley")) return;
        startCooldown(p, "volley", ab(w, "volley", "cooldown", 20));
        int count = (int) ab(w, "volley", "bolts", 3);
        for (int i = 0; i < count; i++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || p.isDead()) return;
                SpectralArrow a = p.launchProjectile(SpectralArrow.class, p.getEyeLocation().getDirection().multiply(3.0));
                tagBolt(a, p, true);
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1f, 1.3f);
            }, (long) i * 3);
        }
        p.sendActionBar(Component.text("VOLLEY!", NamedTextColor.AQUA));
    }

    private void lockdown(Player p) {
        String w = "stormbreaker";
        if (onCooldown(p, "lockdown")) return;
        double range = ab(w, "lockdown", "range", 30);
        LivingEntity t = lookTarget(p, range);
        if (t == null) {
            p.sendActionBar(Component.text("Look at an enemy within " + (int) range + " blocks", NamedTextColor.RED));
            return;
        }
        double dur = ab(w, "lockdown", "duration", 6);
        startCooldown(p, "lockdown", ab(w, "lockdown", "cooldown", 35));
        lockTarget.put(p.getUniqueId(), t.getUniqueId());
        lockUntil.put(p.getUniqueId(), System.currentTimeMillis() + (long) (dur * 1000));
        t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) (dur * 20), 0, false, false, false));
        t.getWorld().spawnParticle(Particle.CRIT, t.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
        p.sendActionBar(Component.text("LOCKDOWN: " + t.getName() + " marked for " + fmt(dur) + "s", NamedTextColor.AQUA));
    }

    private void homingTick() {
        if (bolts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<AbstractArrow> it = bolts.iterator(); it.hasNext(); ) {
            AbstractArrow a = it.next();
            if (!a.isValid() || a.isInBlock() || a.isOnGround()) { it.remove(); continue; }
            String s = a.getPersistentDataContainer().get(boltKey, PersistentDataType.STRING);
            if (s == null) { it.remove(); continue; }
            UUID owner = UUID.fromString(s);
            Long until = lockUntil.get(owner);
            UUID tid = lockTarget.get(owner);
            if (until == null || tid == null || now > until) continue;
            Entity t = Bukkit.getEntity(tid);
            if (!(t instanceof LivingEntity le) || le.isDead() || !le.getWorld().equals(a.getWorld())) continue;
            Vector vel = a.getVelocity();
            double speed = vel.length();
            if (speed < 0.1) continue;
            Vector want = le.getEyeLocation().toVector().subtract(a.getLocation().toVector());
            if (want.lengthSquared() == 0) continue;
            Vector nv = vel.clone().normalize().multiply(0.88).add(want.normalize().multiply(0.12)).normalize().multiply(speed);
            a.setVelocity(nv);
        }
    }

    @EventHandler
    public void onRepeaterShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isWeapon(e.getBow(), "stormbreaker")) return;
        if (e.getProjectile() instanceof AbstractArrow a) tagBolt(a, p, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVolleyDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof AbstractArrow a && a.getPersistentDataContainer().has(volleyKey, PersistentDataType.BYTE))
            e.setDamage(ab("stormbreaker", "volley", "damage-hearts", 1) * 2.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoltHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a)) return;
        String s = a.getPersistentDataContainer().get(boltKey, PersistentDataType.STRING);
        if (s == null) return;
        UUID owner = UUID.fromString(s);
        Player p = getServer().getPlayer(owner);
        Entity hit = e.getHitEntity();
        if (hit == null) { hitStreak.remove(owner); return; }          // a miss breaks the streak
        if (hit instanceof Player tp && isTrusted(owner, tp.getUniqueId())) { e.setCancelled(true); return; }
        if (!(hit instanceof LivingEntity le) || hit.getUniqueId().equals(owner)) return;

        long now = System.currentTimeMillis();
        Long last = hitStreakTime.get(owner);
        if (last != null && now - last > 8000) hitStreak.remove(owner);
        hitStreakTime.put(owner, now);
        int n = hitStreak.merge(owner, 1, Integer::sum);
        if (n % 3 == 0) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, true, true));
            le.getWorld().spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        }
        if (p != null && p.getWorld().equals(le.getWorld()) && p.getLocation().distance(le.getLocation()) >= 20)
            effect(p, PotionEffectType.SPEED, 1, 60);
    }

    // ================= GRAVITY'S END =================

    private void singularity(Player p) {
        String w = "gravity_end";
        if (onCooldown(p, "singularity")) return;
        startCooldown(p, "singularity", ab(w, "singularity", "cooldown", 35));
        Location c = aimPoint(p, ab(w, "singularity", "range", 20)).add(0, 1, 0);
        double radius = ab(w, "singularity", "radius", 10);
        int total = (int) (ab(w, "singularity", "duration", 4) * 20);
        World world = c.getWorld();
        world.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 1f, 0.5f);
        p.sendActionBar(Component.text("SINGULARITY!", NamedTextColor.DARK_PURPLE));

        int[] ticks = {0};
        getServer().getScheduler().runTaskTimer(this, task -> {
            ticks[0] += 2;
            if (ticks[0] > total) { task.cancel(); return; }
            for (Entity en : world.getNearbyEntities(c, radius, radius, radius)) {
                if (!isEnemy(p, en)) continue;
                LivingEntity le = (LivingEntity) en;
                Vector pull = c.toVector().subtract(le.getLocation().add(0, 1, 0).toVector());
                double d = pull.length();
                if (d < 0.8) { le.setVelocity(le.getVelocity().multiply(0.3)); continue; }
                pull.normalize().multiply(Math.min(0.9, 0.25 + d * 0.06));
                le.setVelocity(le.getVelocity().multiply(0.4).add(pull));
            }
            world.spawnParticle(Particle.REVERSE_PORTAL, c, 40, 1.5, 1.5, 1.5, 0.3);
            if (ticks[0] % 4 == 0) {
                for (int i = 0; i < 20; i++) {
                    double a = i * Math.PI * 2 / 20 + ticks[0] * 0.2;
                    world.spawnParticle(Particle.SMOKE, c.clone().add(Math.cos(a) * 3, Math.sin(a * 2) * 0.5, Math.sin(a) * 3), 1, 0, 0, 0, 0);
                }
            }
        }, 0L, 2L);
    }

    private void repulsion(Player p) {
        String w = "gravity_end";
        if (onCooldown(p, "repulsion")) return;
        startCooldown(p, "repulsion", ab(w, "repulsion", "cooldown", 25));
        double radius = ab(w, "repulsion", "radius", 7);
        double dmg = ab(w, "repulsion", "damage-hearts", 2) * 2.0;
        for (Entity en : p.getNearbyEntities(radius, radius, radius)) {
            if (!isEnemy(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            abilityDamage(p, le, dmg);
            Vector away = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() == 0) away = p.getLocation().getDirection().setY(0);
            le.setVelocity(away.normalize().multiply(1.8).setY(0.6));
        }
        World world = p.getWorld();
        world.spawnParticle(Particle.SONIC_BOOM, p.getLocation().add(0, 1, 0), 1);
        world.spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 0.5, 0), 3, 1, 0.2, 1, 0);
        world.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        p.sendActionBar(Component.text("REPULSION!", NamedTextColor.DARK_BLUE));
    }

    // ================= FROSTMOURNE'S AXE =================

    private void glacialCleave(Player p) {
        String w = "frostmourne";
        if (onCooldown(p, "glacial_cleave")) return;
        startCooldown(p, "glacial_cleave", ab(w, "glacial_cleave", "cooldown", 25));
        double range = ab(w, "glacial_cleave", "range", 7);
        double dmg = ab(w, "glacial_cleave", "damage-hearts", 2) * 2.0;
        int slowTicks = (int) (ab(w, "glacial_cleave", "slow-seconds", 4) * 20);

        Vector dir = p.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() == 0) dir = new Vector(0, 0, 1);
        dir.normalize();
        for (Entity en : p.getNearbyEntities(range, 3, range)) {
            if (!isEnemy(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            Vector to = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (to.lengthSquared() == 0 || to.length() > range) continue;
            if (dir.dot(to.clone().normalize()) < 0.5) continue; // 120 degree arc
            abilityDamage(p, le, dmg);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 2, false, true, true));
        }
        World world = p.getWorld();
        Location base = p.getLocation().add(0, 1, 0);
        for (int a = -60; a <= 60; a += 10) {
            Vector d = dir.clone().rotateAroundY(Math.toRadians(a));
            for (double r = 1.5; r <= range; r += 1.5)
                world.spawnParticle(Particle.SNOWFLAKE, base.clone().add(d.clone().multiply(r)), 2, 0.1, 0.1, 0.1, 0.01);
            world.spawnParticle(Particle.SWEEP_ATTACK, base.clone().add(d.clone().multiply(range * 0.7)), 1);
        }
        world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
        world.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
        p.sendActionBar(Component.text("GLACIAL CLEAVE!", NamedTextColor.AQUA));
    }

    private void frozenPrison(Player p) {
        String w = "frostmourne";
        if (onCooldown(p, "frozen_prison")) return;
        double range = ab(w, "frozen_prison", "range", 20);
        LivingEntity t = lookTarget(p, range);
        if (t == null) {
            p.sendActionBar(Component.text("Look at an enemy within " + (int) range + " blocks", NamedTextColor.RED));
            return;
        }
        double dur = ab(w, "frozen_prison", "duration", 3);
        startCooldown(p, "frozen_prison", ab(w, "frozen_prison", "cooldown", 45));
        frozenUntil.put(t.getUniqueId(), System.currentTimeMillis() + (long) (dur * 1000));
        int ticks = (int) (dur * 20) + 10;
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, false));
        t.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false, false));
        if (!(t instanceof Player)) { t.setAI(false); aiFrozen.add(t.getUniqueId()); }
        t.getWorld().playSound(t.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.5f);
        p.sendActionBar(Component.text("FROZEN PRISON: " + t.getName() + " frozen for " + fmt(dur) + "s", NamedTextColor.AQUA));
    }

    private void unfreeze(UUID id) {
        frozenUntil.remove(id);
        if (aiFrozen.remove(id)) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le) le.setAI(true);
        }
    }

    private void frozenTick() {
        if (frozenUntil.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(frozenUntil.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity le) || le.isDead() || now > frozenUntil.get(id)) { unfreeze(id); continue; }
            le.setFreezeTicks(le.getMaxFreezeTicks());
            World w = le.getWorld();
            w.spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 8, 0.4, 0.7, 0.4, 0.01);
            w.spawnParticle(Particle.BLOCK, le.getLocation().add(0, 1, 0), 10, 0.4, 0.8, 0.4, Material.ICE.createBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent e) {
        Long until = frozenUntil.get(e.getPlayer().getUniqueId());
        if (until == null || System.currentTimeMillis() > until || e.getTo() == null) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() == t.getX() && f.getZ() == t.getZ() && t.getY() <= f.getY()) return;
        Location nt = f.clone();
        nt.setYaw(t.getYaw());
        nt.setPitch(t.getPitch());
        nt.setY(Math.min(f.getY(), t.getY())); // can still fall, can't walk or jump
        e.setTo(nt);
    }

    // ================= NIGHTFALL =================

    private void shadowstep(Player p) {
        String w = "nightfall";
        if (onCooldown(p, "shadowstep")) return;
        double range = ab(w, "shadowstep", "range", 10);
        LivingEntity t = pickTarget(p, range);
        if (t == null) {
            p.sendActionBar(Component.text("No enemy within " + (int) range + " blocks", NamedTextColor.RED));
            return;
        }
        startCooldown(p, "shadowstep", ab(w, "shadowstep", "cooldown", 20));
        World world = p.getWorld();
        world.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.02);
        world.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);

        Location tl = t.getLocation();
        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() == 0) back = new Vector(0, 0, 1);
        back.normalize().multiply(-1.5);
        Location dest = tl.clone().add(back);
        dest.setDirection(tl.toVector().subtract(dest.toVector()));
        dest = safe(world, dest.getX(), dest.getY(), dest.getZ(), dest.getYaw(), dest.getPitch());
        p.teleport(dest);

        abilityDamage(p, t, ab(w, "shadowstep", "damage-hearts", 2) * 2.0);
        world.spawnParticle(Particle.SMOKE, dest.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.02);
        world.spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 1);
        p.sendActionBar(Component.text("SHADOWSTEP!", NamedTextColor.DARK_GRAY));
    }

    private void thousandCuts(Player p) {
        String w = "nightfall";
        if (onCooldown(p, "thousand_cuts")) return;
        double range = ab(w, "thousand_cuts", "range", 6);
        LivingEntity t = pickTarget(p, range);
        if (t == null) {
            p.sendActionBar(Component.text("No enemy within " + (int) range + " blocks", NamedTextColor.RED));
            return;
        }
        startCooldown(p, "thousand_cuts", ab(w, "thousand_cuts", "cooldown", 35));
        int strikes = (int) ab(w, "thousand_cuts", "strikes", 5);
        double dmg = ab(w, "thousand_cuts", "damage-hearts", 0.5) * 2.0;
        for (int i = 0; i < strikes; i++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || p.isDead() || t.isDead() || !t.isValid()) return;
                if (!t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > range + 4) return;
                abilityDamage(p, t, dmg);
                Location l = t.getLocation().add(0, 1, 0);
                t.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1);
                t.getWorld().spawnParticle(Particle.CRIT, l, 10, 0.3, 0.4, 0.3, 0.3);
                t.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
            }, (long) i * 3);
        }
        p.sendActionBar(Component.text("THOUSAND CUTS!", NamedTextColor.DARK_GRAY));
    }

    // ================= GRIMOIRE OF RUIN =================

    private Color spellColor(int idx) {
        return switch (idx) {
            case 0 -> Color.fromRGB(35, 35, 35);     // Wither - black
            case 1 -> Color.fromRGB(80, 170, 255);   // Slowness - blue
            default -> Color.fromRGB(255, 170, 40);  // Weakness - amber
        };
    }

    private void forbiddenSpell(Player p) {
        UUID id = p.getUniqueId();
        int idx = (spellIdx.getOrDefault(id, -1) + 1) % SPELLS.length;
        spellIdx.put(id, idx);
        spellArmed.add(id);
        long left = cooldownLeft(p, "forbidden_spell");
        String status = left > 0 ? " (ready in " + left + "s)" : " - next hit applies it";
        p.sendActionBar(Component.text("Forbidden Spell: " + SPELLS[idx] + status, NamedTextColor.LIGHT_PURPLE));
        dust(p.getWorld(), p.getLocation().add(0, 1.2, 0), 25, 0.5, spellColor(idx), 1.5f);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private void forbiddenHit(Player p, LivingEntity v) {
        UUID id = p.getUniqueId();
        if (!spellArmed.contains(id) || cooldownLeft(p, "forbidden_spell") > 0) return;
        int idx = spellIdx.getOrDefault(id, 0);
        double dur = ab("grimoire", "forbidden_spell", "duration", 5);
        double bonus = getConfig().getDouble("weapons.grimoire.passives.negative-effect-bonus", 0.2);
        int ticks = (int) (dur * 20 * (1 + bonus));
        PotionEffectType type = idx == 0 ? PotionEffectType.WITHER : idx == 1 ? PotionEffectType.SLOWNESS : PotionEffectType.WEAKNESS;
        v.addPotionEffect(new PotionEffect(type, ticks, 0, false, true, true));
        startCooldown(p, "forbidden_spell", ab("grimoire", "forbidden_spell", "cooldown", 25));
        spellArmed.remove(id);
        dust(v.getWorld(), v.getLocation().add(0, 1, 0), 40, 0.5, spellColor(idx), 1.8f);
        p.getWorld().playSound(v.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.8f);
        p.sendActionBar(Component.text(SPELLS[idx] + " cast!", NamedTextColor.LIGHT_PURPLE));
    }

    private void arcaneCataclysm(Player p) {
        String w = "grimoire";
        if (onCooldown(p, "arcane_cataclysm")) return;
        startCooldown(p, "arcane_cataclysm", ab(w, "arcane_cataclysm", "cooldown", 45));
        Location c = aimPoint(p, ab(w, "arcane_cataclysm", "range", 20));
        double radius = ab(w, "arcane_cataclysm", "radius", 5);
        double dmg = ab(w, "arcane_cataclysm", "damage-hearts", 3) * 2.0;
        World world = c.getWorld();
        Color purple = Color.fromRGB(170, 60, 255);
        p.sendActionBar(Component.text("ARCANE CATACLYSM!", NamedTextColor.DARK_PURPLE));
        world.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);

        int[] t = {0};
        getServer().getScheduler().runTaskTimer(this, task -> {
            t[0] += 2;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                dust(world, c.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, purple, 1.4f);
            }
            if (t[0] < 16) return;
            task.cancel();
            for (Entity en : world.getNearbyEntities(c, radius, radius, radius)) {
                if (!isEnemy(p, en)) continue;
                LivingEntity le = (LivingEntity) en;
                abilityDamage(p, le, dmg);
                le.setVelocity(le.getVelocity().add(new Vector(0, 0.7, 0)));
            }
            world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
            dust(world, c.clone().add(0, 1, 0), 120, radius * 0.5, purple, 2.2f);
            world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
        }, 0L, 2L);
    }

    // ================= Shared lifecycle events =================

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        setBonus(p, 0);
        endHealthy(p.getUniqueId());
        contractHits.remove(p.getUniqueId());
        lockTarget.remove(p.getUniqueId()); hitStreak.remove(p.getUniqueId()); spellArmed.remove(p.getUniqueId());
        unfreeze(p.getUniqueId());
        despawnConstructs(p.getUniqueId(), true);
        Domain d = domainOf(p.getUniqueId());
        if (d != null) endDomain(d, p.getName() + " died");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        getServer().getScheduler().runTask(this, () -> {
            removeModifier(e.getPlayer(), lifestealKey);
            removeModifier(e.getPlayer(), healthyKey);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        removeModifier(p, healthyKey);
        if (hasWeapon(p, "lifestealer")) setModifier(p, lifestealKey, getBonus(p));
        else setBonus(p, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        healthyActive.remove(p.getUniqueId());
        removeModifier(p, healthyKey);
        contractHits.remove(p.getUniqueId());
        lockTarget.remove(p.getUniqueId()); hitStreak.remove(p.getUniqueId()); spellArmed.remove(p.getUniqueId());
        unfreeze(p.getUniqueId());
        clearAttr(p, Attribute.KNOCKBACK_RESISTANCE, gravityKey);
        despawnConstructs(p.getUniqueId(), false);
        Domain d = domainOf(p.getUniqueId());
        if (d != null) endDomain(d, p.getName() + " left");
    }

    // ================= Trust: block weapon hits on trusted players =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrustedHit(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("trust.block-weapon-damage", true)) return;
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof Player victim)) return;
        if (weaponId(attacker.getInventory().getItemInMainHand()) == null) return;
        if (isTrusted(attacker.getUniqueId(), victim.getUniqueId())) {
            e.setCancelled(true);
            attacker.sendActionBar(Component.text("You trust " + victim.getName() + " - weapon hit blocked", NamedTextColor.YELLOW));
        }
    }

    // ================= Commands =================

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("mtrust")) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            return handleTrust(p, a);
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("marbles.weapons.give")) { s.sendMessage("No permission."); return true; }
            reloadConfig();
            loadTrust();
            s.sendMessage("Marbles OP Weapons 2 config reloaded. Give yourself a new weapon to get new enchants.");
            return true;
        }
        if (a.length < 2 || !a[0].equalsIgnoreCase("give")) {
            s.sendMessage("Usage: /marbleweapons give <" + String.join("|", WEAPONS) + "> [player]  |  /marbleweapons reload");
            return true;
        }
        if (!s.hasPermission("marbles.weapons.give")) { s.sendMessage("No permission."); return true; }
        Player target = a.length >= 3 ? getServer().getPlayer(a[2]) : (s instanceof Player pl ? pl : null);
        if (target == null) { s.sendMessage("Player not found."); return true; }
        String id = a[1].toLowerCase();
        ItemStack item = WEAPONS.contains(id) ? createWeapon(id) : null;
        if (item == null) { s.sendMessage("Unknown weapon. Options: " + String.join(", ", WEAPONS)); return true; }
        target.getInventory().addItem(item);
        s.sendMessage("Gave " + id + " to " + target.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("mtrust")) {
            if (a.length == 1) return List.of("add", "remove", "list");
            if (a.length == 2 && a[0].equalsIgnoreCase("add")) {
                List<String> names = new ArrayList<>();
                for (Player p : getServer().getOnlinePlayers()) names.add(p.getName());
                return names;
            }
            if (a.length == 2 && a[0].equalsIgnoreCase("remove") && s instanceof Player p) {
                List<String> names = new ArrayList<>();
                for (UUID u : trust.getOrDefault(p.getUniqueId(), Set.of())) names.add(nameOf(u));
                return names;
            }
            return List.of();
        }
        if (a.length == 1) return List.of("give", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) return WEAPONS;
        return null;
    }
}
