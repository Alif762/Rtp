package my.rtp;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RTP extends JavaPlugin implements CommandExecutor {

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.AIR, Material.CAVE_AIR);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("rtp").setExecutor(this);
    }

    private String msg(String key) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, ""));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtp.reload")) return true;
            reloadConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Hanya pemain boleh guna command ini.");
            return true;
        }
        if (!p.hasPermission("rtp.use")) return true;

        World world = p.getWorld();
        if (!getConfig().getStringList("worlds").contains(world.getName())) {
            p.sendMessage(msg("wrong-world"));
            return true;
        }

        if (!p.hasPermission("rtp.bypass")) {
            long left = cooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                p.sendMessage(msg("cooldown").replace("%s%", String.valueOf(left / 1000 + 1)));
                return true;
            }
        }

        p.sendMessage(msg("searching"));
        findAndTeleport(p, world, getConfig().getInt("max-attempts", 15));
        return true;
    }

    private void findAndTeleport(Player p, World world, int attemptsLeft) {
        if (!p.isOnline()) return;
        if (attemptsLeft <= 0) {
            p.sendMessage(msg("failed"));
            return;
        }

        int min = getConfig().getInt("min-range", 500);
        int max = getConfig().getInt("max-range", 5000);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = world.getSpawnLocation();

        int dist = r.nextInt(min, max + 1);
        double angle = r.nextDouble(0, Math.PI * 2);
        int x = c.getBlockX() + (int) (Math.cos(angle) * dist);
        int z = c.getBlockZ() + (int) (Math.sin(angle) * dist);

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Bukkit.getScheduler().runTask(this, () -> {
                Block ground = world.getHighestBlockAt(x, z);
                if (!isSafe(ground, world)) {
                    findAndTeleport(p, world, attemptsLeft - 1);
                    return;
                }
                Location dest = ground.getLocation().add(0.5, 1, 0.5);
                dest.setYaw(p.getLocation().getYaw());
                dest.setPitch(p.getLocation().getPitch());
                p.teleport(dest);
                cooldowns.put(p.getUniqueId(),
                        System.currentTimeMillis() + getConfig().getLong("cooldown-seconds", 60) * 1000);
                p.sendMessage(msg("success")
                        .replace("%x%", String.valueOf(dest.getBlockX()))
                        .replace("%y%", String.valueOf(dest.getBlockY()))
                        .replace("%z%", String.valueOf(dest.getBlockZ())));
            });
        });
    }

    private boolean isSafe(Block ground, World world) {
        if (UNSAFE.contains(ground.getType())) return false;
        if (!ground.getType().isSolid()) return false;
        if (!ground.getRelative(0, 1, 0).isPassable()) return false;
        if (!ground.getRelative(0, 2, 0).isPassable()) return false;
        if (ground.getY() <= world.getMinHeight() + 1) return false;
        return world.getWorldBorder().isInside(ground.getLocation());
    }
              }
