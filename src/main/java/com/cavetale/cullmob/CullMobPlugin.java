package com.cavetale.cullmob;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Value;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class.
 */
public final class CullMobPlugin extends JavaPlugin implements Listener {
    /**
     * List of all recently issued warnings. This serves two purposes:
     * - Check against potential new warnings to prevent spam.
     * - Inform users of the `/cullmob list` command.
     * Outdated warnings are occasionally removed by the iterator.
     */
    private final ArrayList<IssuedWarning> issuedWarnings = new ArrayList<>();
    /** Currently loaded configuration. */
    private BreedingConfig breedingConfig;

    /**
     * Track why, where, and when a warning was issued.
     */
    @Value
    static class IssuedWarning {
        final EntityType entityType;
        final String world;
        final int x;
        final int y;
        final int z;
        final long time;
    }

    /**
     * Configuration deserialized from config.yml.
     */
    @Value
    static final class BreedingConfig {
        @Value
        static final class Check {
            final double radius;
            final long limit;
        }
        List<Check> checks;
        final double warnRadius;
        final long warnTimer;
        double maxRadius() {
            return checks.stream().mapToDouble(c -> c.radius).max().orElse(0);
        }
    }

    /**
     * Thrown by `onCommand()` and its methods.
     */
    static class CommandException extends Exception {
        CommandException(final String message) {
            super(message);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        loadConf();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String alias, final String[] args) {
        if (args.length == 0) {
            return false;
        }
        try {
            return onCommand(sender, args[0],
                             Arrays.copyOfRange(args, 1, args.length));
        } catch (CommandException ce) {
            sender.sendMessage("[CullMob] " + ChatColor.RED + ce.getMessage());
            return true;
        }
    }

    private boolean onCommand(final CommandSender sender, final String cmd,
                              final String[] args)throws CommandException {
        switch (cmd) {
        case "reload":
            if (args.length != 0) {
                return false;
            }
            loadConf();
            sender.sendMessage("[CullMob] configuration reloaded.");
            return true;
        case "info":
            sender.sendMessage("Breeding: "
                               + new Gson().toJson(breedingConfig));
            return true;
        case "list": {
            if (args.length != 0) {
                return false;
            }
            long now = Instant.now().getEpochSecond();
            sender.sendMessage(issuedWarnings.size()
                               + " recent warnings:");
            issuedWarnings.stream()
                .forEach(is -> {
                        sender.sendMessage("- "
                                           + is.entityType + " at "
                                           + is.world
                                           + " " + is.x
                                           + "," + is.y
                                           + "," + is.z
                                           + " | " + (now - is.time)
                                           + " seconds ago.");
                    });
            return true;
        }
        default:
            throw new CommandException("Unknown command: " + cmd);
        }
    }

    // Conf

    <T> T loadConf(final String key, final Class<T> type) {
        Gson gson = new Gson();
        ConfigurationSection cfg
            = Objects.requireNonNull(getConfig().getConfigurationSection(key));
        String json = gson.toJson(cfg.getValues(false));
        return gson.fromJson(json, type);
    }

    void loadConf() {
        reloadConfig();
        breedingConfig = loadConf("breeding", BreedingConfig.class);
    }

    // Events

    @EventHandler
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        // White-list spawn reasons
        switch (event.getSpawnReason()) {
        case BREEDING:
        case DISPENSE_EGG:
        case EGG:
        case VILLAGE_DEFENSE: // sketchy
            break;
        default:
            return;
        }
        // White-list mob types
        final LivingEntity spawned = event.getEntity();
        final EntityType entityType = spawned.getType();
        switch (entityType) {
        case CHICKEN:
        case COW:
        case MUSHROOM_COW:
        case PIG:
        case RABBIT:
        case SHEEP:
        case VILLAGER:
        case BEE:
        case TURTLE:
        case LLAMA:
        case WOLF:
        case OCELOT:
        case CAT:
        case PANDA:
        case IRON_GOLEM: // sketchy
            break;
        default:
            if (spawned instanceof Animals) break;
            if (spawned instanceof Ageable) break;
            return;
        }
        onBreed(event, spawned);
    }

    static boolean compareEntities(final Entity a, final Entity b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        switch (a.getType()) {
        case SHEEP:
            Sheep sheepa = (Sheep) a;
            Sheep sheepb = (Sheep) b;
            if (sheepa.getColor() != sheepb.getColor()) {
                return false;
            }
            break;
        case RABBIT:
            Rabbit rabbita = (Rabbit) a;
            Rabbit rabbitb = (Rabbit) b;
            if (rabbita.getRabbitType() != rabbitb.getRabbitType()) {
                return false;
            }
            break;
        default:
            break;
        }
        return true;
    }

    boolean nearby(final IssuedWarning warning, final EntityType entityType,
                   final String world, final int x, final int z) {
        if (entityType != warning.entityType
            || !warning.world.equals(world)) {
            return false;
        }
        double r = breedingConfig.warnRadius;
        return (double) Math.abs(warning.x - x) <= r
            && (double) Math.abs(warning.z - z) <= r;
    }

    boolean nearby(final Location loc, final String world,
                   final int x, final int z) {
        if (!loc.getWorld().getName().equals(world)) {
            return false;
        }
        double r = breedingConfig.warnRadius;
        return Math.abs(loc.getX() - (double) x) <= r
            && Math.abs(loc.getZ() - (double) z) <= r;
    }

    static String human(Enum e) {
        return e.name().toLowerCase().replace("_", " ");
    }

    static String toString(Location loc) {
        return loc.getWorld().getName() + ":"
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    void onBreed(final CreatureSpawnEvent event, final LivingEntity spawned) {
        // Check nearby mobs
        final double r = breedingConfig.maxRadius();
        final Location loc = spawned.getLocation();
        EntityType entityType = spawned.getType();
        List<Double> nearbys = spawned.getNearbyEntities(r, r, r).stream()
            .filter(n -> compareEntities(spawned, n))
            .map(n -> loc.distance(n.getLocation()))
            .collect(Collectors.toList());
        BreedingConfig.Check failedCheck = null;
        for (BreedingConfig.Check check : breedingConfig.checks) {
            long nearbyCount = nearbys.stream()
                .filter(i -> i <= check.radius)
                .count();
            if (nearbyCount >= check.limit) {
                failedCheck = check;
                break;
            }
        }
        if (failedCheck == null) return;
        // Do the thing.
        event.setCancelled(true);
        getLogger().info("[Breeding] Denied " + human(entityType) + " at "
                         + toString(loc) + " due to check:"
                         + "radius=" + (int) Math.ceil(failedCheck.radius)
                         + "limit=" + failedCheck.limit);
        // Produce a warning.
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        long now = Instant.now().getEpochSecond();
        for (Iterator<IssuedWarning> it = issuedWarnings.iterator();
             it.hasNext();) {
            IssuedWarning warning = it.next();
            if (now - warning.time > breedingConfig.warnTimer) {
                it.remove();
            } else if (nearby(warning, entityType, world, x, z)) {
                return;
            }
        }
        issuedWarnings.add(new IssuedWarning(entityType,
                                             world, x, y, z, now));
        loc.getWorld().getPlayers().stream()
            .filter(p -> nearby(p.getLocation(), world, x, z))
            .forEach(p -> {
                    p.sendMessage("" + ChatColor.RED
                                  + "A nearby "
                                  + human(entityType)
                                  + " farm is getting out of hand."
                                  + " Spawning was denied.");
                    p.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH,
                                SoundCategory.MASTER, 1.0f, 1.0f);
                });
    }
}
