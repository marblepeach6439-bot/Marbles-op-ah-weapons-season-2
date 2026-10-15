package me.marbles.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class MarblesOPWeapons2 extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey weaponKey, healthyKey, lifestealKey, bonusKey;
    private final Map<UUID, Long> healthyCooldown = new HashMap<>();
    private final Set<UUID> healthyActive = new HashSet<>();

    private static final long HEALTHY_DURATION_TICKS = 30 * 20L;
    private static final long HEALTHY_COOLDOWN_MS = 60_000L;
    private static final double KILL_BONUS = 1.0; // half a heart = 1 HP
    private static final double LEECH_PERCENT = 0.5;

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "weapon");
        healthyKey = new NamespacedKey(this, "healthy_boi");
        lifestealKey = new NamespacedKey(this, "lifesteal");
        bonusKey = new NamespacedKey(this, "lifesteal_bonus");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("marbleweapons").setExecutor(this);
        getCommand("marbleweapons").setTabCompleter(this);

        // Every second: lose the blade -> lose lifesteal hearts; show cooldown
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                if (!hasBlade(p) && getBonus(p) > 0) {
                    setBonus(p, 0);
                    p.sendActionBar(Component.text("You lost the Lifestealer - bonus hearts gone!", NamedTextColor.RED));
                }
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) removeModifier(p, healthyKey);
    }

    // ---------------- Item ----------------

    public ItemStack createLifestealer() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Lifestealer", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.empty(),
                line("Healthy Boi (Right Click)", NamedTextColor.RED),
                line("  Double your current health for 30s", NamedTextColor.GRAY),
                line("  Cooldown: 60s", NamedTextColor.DARK_GRAY),
                line("Lifesteal (Passive)", NamedTextColor.RED),
                line("  Each kill: +half a heart. Resets on death/losing blade", NamedTextColor.GRAY),
                line("Life Leach (Passive)", NamedTextColor.RED),
                line("  Heal 50% of damage you deal", NamedTextColor.GRAY)));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.setUnbreakable(true);
        meta.setItemModel(new NamespacedKey("marbles", "lifestealer")); // resource pack model
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, "lifestealer");
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isLifestealer(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return "lifestealer".equals(item.getItemMeta().getPersistentDataContainer()
                .get(weaponKey, PersistentDataType.STRING));
    }

    private boolean hasBlade(Player p) {
        for (ItemStack i : p.getInventory().getContents()) if (isLifestealer(i)) return true;
        return isLifestealer(p.getItemOnCursor());
    }

    // ---------------- Attribute helpers ----------------

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

    // ---------------- Ability 1: Healthy Boi ----------------

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isLifestealer(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        long readyAt = healthyCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now < readyAt) {
            long left = (readyAt - now + 999) / 1000;
            p.sendActionBar(Component.text("Healthy Boi on cooldown: " + left + "s", NamedTextColor.RED));
            return;
        }
        if (healthyActive.contains(p.getUniqueId())) return;

        healthyCooldown.put(p.getUniqueId(), now + HEALTHY_COOLDOWN_MS);
        healthyActive.add(p.getUniqueId());

        double current = p.getHealth();
        setModifier(p, healthyKey, current);          // max health doubles (+current)
        p.setHealth(Math.min(current * 2, p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        p.sendActionBar(Component.text("HEALTHY BOI! Health doubled for 30s", NamedTextColor.GREEN));

        UUID id = p.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> endHealthy(id), HEALTHY_DURATION_TICKS);
    }

    private void endHealthy(UUID id) {
        if (!healthyActive.remove(id)) return;
        Player p = getServer().getPlayer(id);
        if (p == null) return;
        removeModifier(p, healthyKey);
        p.sendActionBar(Component.text("Healthy Boi ended", NamedTextColor.GRAY));
    }

    // ---------------- Ability 2: Lifesteal on kill ----------------

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) {
            // handled in onDeath for the dying player's reset; still allow PvP kills to count below
        }
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.equals(e.getEntity()) || !hasBlade(killer)) return;
        setBonus(killer, getBonus(killer) + KILL_BONUS);
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 0.6f);
        killer.sendActionBar(Component.text("+½ heart (" + (getBonus(killer) / 2) + " bonus hearts)", NamedTextColor.RED));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        setBonus(p, 0);
        endHealthy(p.getUniqueId());
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
        removeModifier(p, healthyKey); // timed ability never survives a relog
        if (hasBlade(p)) setModifier(p, lifestealKey, getBonus(p));
        else setBonus(p, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        healthyActive.remove(e.getPlayer().getUniqueId());
        removeModifier(e.getPlayer(), healthyKey);
    }

    // ---------------- Ability 3: Life Leach ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isLifestealer(p.getInventory().getItemInMainHand())) return;
        heal(p, e.getFinalDamage() * LEECH_PERCENT);
    }

    // ---------------- Command ----------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length < 2 || !a[0].equalsIgnoreCase("give")) {
            s.sendMessage("Usage: /marbleweapons give <lifestealer> [player]");
            return true;
        }
        Player target = a.length >= 3 ? getServer().getPlayer(a[2]) : (s instanceof Player pl ? pl : null);
        if (target == null) { s.sendMessage("Player not found."); return true; }
        if (a[1].equalsIgnoreCase("lifestealer")) {
            target.getInventory().addItem(createLifestealer());
            s.sendMessage("Gave Lifestealer to " + target.getName());
        } else s.sendMessage("Unknown weapon.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("give");
        if (a.length == 2) return List.of("lifestealer");
        return null;
    }
}
