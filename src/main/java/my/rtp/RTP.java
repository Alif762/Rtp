package my.rtp;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RTP extends JavaPlugin implements CommandExecutor, Listener {

    private static class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() { return null; }
    }

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.AIR, Material.CAVE_AIR,
            Material.BEDROCK);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("rtp").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key, String def) {
        return color(getConfig().getString("messages." + key, def));
    }

    // ---------- Command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtp.reload")) return true;
            reloadConfig();
            sender.sendMessage(msg("reloaded", "&aConfig reloaded."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!p.hasPermission("rtp.use")) return true;
        openMenu(p);
        return true;
    }

    // ---------- GUI ----------
    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27,
                color(getConfig().getString("gui-title", "&8RTP - Choose Dimension")));

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        inv.setItem(11, button(Material.GRASS_BLOCK, "&aOverworld", "&7Click to RTP to the Overworld"));
        inv.setItem(13, button(Material.NETHERRACK, "&cNether", "&7Click to RTP to the Nether"));
        inv.setItem(15, button(Material.END_STONE, "&eThe End", "&7Click to RTP to a chorus island"));

        p.openInventory(inv);
    }

    private ItemStack button(Material m, String name, String lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(List.of(color(lore)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        World.Environment env;
        switch (e.getSlot()) {
            case 11 -> env = World.Environment.NORMAL;
            case 13 -> env = World.Environment.NETHER;
            case 15 -> env = World.Environment.THE_END;
            default -> { return; }
        }
        p.closeInventory();
        startRtp(p, env);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ---------- RTP logic ----------
    private World findWorld(World.Environment env) {
        String key = env == World.Environment.NETHER ? "nether"
                : env == World.Environment.THE_END ? "end" : "overworld";
        String def = env == World.Environment.NETHER ? "world_nether"
                : env == World.Environment.THE_END ? "world_the_end" : "world";
        World w = Bukkit.getWorld(getConfig().getString("dimensions." + key, def));
        if (w != null) return w;
        for (World other : Bukkit.getWorlds()) {
            if (other.getEnvironment() == env) return other;
        }
        return null;
    }

    private int[] range(World w) {
        World.Environment env = w.getEnvironment();
        int defMin = env == World.Environment.NETHER ? 100
                : env == World.Environment.THE_END ? 1200
                : getConfig().getInt("min-range", 500);
        int defMax = env == World.Environment.NETHER ? 1000
                : env == World.Environment.THE_END ? 4000
                : getConfig().getInt("max-range", 5000);
        String path = "world-settings." + w.getName();
        int min = getConfig().getInt(path + ".min", defMin);
        int max = getConfig().getInt(path + ".max", defMax);
        if (max <= min) max = min + 1;
        return new int[]{min, max};
    }

    private int attempts(World w) {
        int def = w.getEnvironment() == World.Environment.THE_END ? 200
                : getConfig().getInt("max-attempts", 15);
        return getConfig().getInt("world-settings." + w.getName() + ".attempts", def);
    }

    private void startRtp(Player p, World.Environment env) {
        if (searching.contains(p.getUniqueId())) return;

        World target = findWorld(env);
        if (target == null) {
            p.sendMessage(msg("no-world", "&cThat world doesn't exist."));
            return;
        }

        if (!p.hasPermission("rtp.bypass")) {
            long left = cooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                p.sendMessage(msg("cooldown", "&cPlease wait %s% seconds.")
                        .replace("%s%", String.valueOf(left / 1000 + 1)));
                return;
            }
        }

        searching.add(p.getUniqueId());
        p.sendMessage(msg("searching", "&eSearching for a safe location..."));
        findAndTeleport(p, target, attempts(target));
    }

    private void findAndTeleport(Player p, World world, int attemptsLeft) {
        if (!p.isOnline()) {
            searching.remove(p.getUniqueId());
            return;
        }
        if (attemptsLeft <= 0) {
            searching.remove(p.getUniqueId());
            p.sendMessage(msg("failed", "&cCouldn't find a safe location, try again."));
            return;
        }

        int[] r = range(world);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location c = world.getEnvironment() == World.Environment.NORMAL
                ? world.getSpawnLocation()
                : new Location(world, 0, 0, 0);

        int dist = rnd.nextInt(r[0], r[1] + 1);
        double angle = rnd.nextDouble(0, Math.PI * 2);
        int x = c.getBlockX() + (int) (Math.cos(angle) * dist);
        int z = c.getBlockZ() + (int) (Math.sin(angle) * dist);

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    Location dest = findSafe(world, x, z);
                    if (dest == null) {
                        findAndTeleport(p, world, attemptsLeft - 1);
                        return;
                    }
                    searching.remove(p.getUniqueId());
                    dest.setYaw(p.getLocation().getYaw());
                    dest.setPitch(p.getLocation().getPitch());
                    p.teleport(dest);
                    cooldowns.put(p.getUniqueId(),
                            System.currentTimeMillis() + getConfig().getLong("cooldown-seconds", 60) * 1000);
                    p.sendMessage(msg("success", "&aTeleported to &fX:%x% Y:%y% Z:%z%")
                            .replace("%x%", String.valueOf(dest.getBlockX()))
                            .replace("%y%", String.valueOf(dest.getBlockY()))
                            .replace("%z%", String.valueOf(dest.getBlockZ())));
                }));
    }

    private Location findSafe(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.THE_END) {
            return findEndChorus(world, x, z);
        }
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = 100; y >= 32; y--) {
                Block g = world.getBlockAt(x, y, z);
                if (isSafe(g, world)) return g.getLocation().add(0.5, 1, 0.5);
            }
            return null;
        }
        Block g = world.getHighestBlockAt(x, z);
        return isSafe(g, world) ? g.getLocation().add(0.5, 1, 0.5) : null;
    }

    private Location findEndChorus(World world, int x, int z) {
        int cx = (x >> 4) << 4;
        int cz = (z >> 4) << 4;

        List<Block> chorus = new ArrayList<>();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Block top = world.getHighestBlockAt(cx + dx, cz + dz);
                Material t = top.getType();
                if (t == Material.CHORUS_PLANT || t == Material.CHORUS_FLOWER) chorus.add(top);
            }
        }
        if (chorus.isEmpty()) return null;

        Block pick = chorus.get(ThreadLocalRandom.current().nextInt(chorus.size()));
        Block best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int bx = pick.getX() + dx;
                int bz = pick.getZ() + dz;
                if (bx < cx || bx > cx + 15 || bz < cz || bz > cz + 15) continue;
                Block g = world.getHighestBlockAt(bx, bz);
                if (g.getType() != Material.END_STONE || !isSafe(g, world)) continue;
                double d = dx * dx + dz * dz;
                if (d < bestDist) {
                    bestDist = d;
                    best = g;
                }
            }
        }
        return best == null ? null : best.getLocation().add(0.5, 1, 0.5);
    }

    private boolean isSafe(Block ground, World world) {
        if (UNSAFE.contains(ground.getType())) return false;
        if (!ground.getType().isSolid() || ground.isLiquid()) return false;
        Block b1 = ground.getRelative(0, 1, 0);
        Block b2 = ground.getRelative(0, 2, 0);
        if (!b1.isPassable() || b1.isLiquid()) return false;
        if (!b2.isPassable() || b2.isLiquid()) return false;
        if (ground.getY() <= world.getMinHeight() + 1) return false;
        return world.getWorldBorder().isInside(ground.getLocation());
    }
              }
