package com.cavetale.cullmob;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class CullMobPlugin extends JavaPlugin implements Listener {
    private final ArrayList<IssuedWarning> issuedWarnings = new ArrayList<>();
    private BreedingConfig breedingConfig;

    @Value
    static class IssuedWarning {
        final EntityType entityType;
        final String world;
        final int x, y, z;
        final long time;
    }

    @Value
    static class BreedingConfig {
        final double radius;
        final int limit;
        final double warnRadius;
        final long warnTimer;
    }

    static class CommandException extends Exception {
        CommandException(String message) {
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
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return false;
        }
        try {
            return onCommand(sender, args[0], Arrays.copyOfRange(args, 1, args.length));
        } catch (CommandException ce) {
            sender.sendMessage("[CullMob] " + ChatColor.RED + ce.getMessage());
            return true;
        }
    }

    private boolean onCommand(CommandSender sender, String cmd, String[] args) throws CommandException {
        switch (cmd) {
        case "reload":
            if (args.length != 0) return false;
            loadConf();
            sender.sendMessage("[CullMob] configuration reloaded.");
            return true;
        case "info":
            sender.sendMessage("Breeding: " + new Gson().toJson(this.breedingConfig));
            return true;
        case "list": {
            if (args.length != 0) return false;
            long now = Instant.now().getEpochSecond();
            sender.sendMessage(this.issuedWarnings.size() + " recent warnings:");
            this.issuedWarnings.stream()
                .forEach(is -> {
                        sender.sendMessage("- "
                                           + is.entityType + " at "
                                           + is.world
                                           + " " + is.x
                                           + "," + is.y
                                           + "," + is.z
                                           + " | " + (now - is.time) + " seconds ago.");
                    });
            return true;
        }
        default:
            throw new CommandException("Unknown command: " + cmd);
        }
    }

    // Conf

    <T> T loadConf(String key, Class<T> type) {
        Gson gson = new Gson();
        ConfigurationSection cfg = Objects.requireNonNull(getConfig().getConfigurationSection(key));
        String json = gson.toJson(cfg.getValues(false));
        return gson.fromJson(json, type);
    }

    void loadConf() {
        reloadConfig();
        this.breedingConfig = loadConf("breeding", BreedingConfig.class);
    }

    // Events

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        switch (event.getSpawnReason()) {
        case BREEDING:
        case DISPENSE_EGG:
        case EGG:
            onBreed(event, event.getEntity());
            break;
        default:
            break;
        }
    }

    static boolean compareEntities(Entity a, Entity b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        switch (a.getType()) {
        case SHEEP:
            Sheep sheepa = (Sheep)a;
            Sheep sheepb = (Sheep)b;
            if (sheepa.getColor() != sheepb.getColor()) {
                return false;
            }
            break;
        case RABBIT:
            Rabbit rabbita = (Rabbit)a;
            Rabbit rabbitb = (Rabbit)b;
            if (rabbita.getRabbitType() != rabbitb.getRabbitType()) {
                return false;
            }
            break;
        default:
            break;
        }
        return true;
    }

    boolean nearby(IssuedWarning warning, EntityType entityType, String world, int x, int z) {
        if (entityType != warning.entityType
            || !warning.world.equals(world)) {
            return false;
        }
        double r = this.breedingConfig.warnRadius;
        return (double)Math.abs(warning.x - x) <= r
            && (double)Math.abs(warning.z - z) <= r;
    }

    boolean nearby(Location loc, String world, int x, int z) {
        if (!loc.getWorld().getName().equals(world)) {
            return false;
        }
        double r = this.breedingConfig.warnRadius;
        return loc.getX() - (double)x <= r
            && loc.getZ() - (double)z <= r;
    }

    void onBreed(CreatureSpawnEvent event, Entity spawned) {
        // White-list mob types
        final EntityType entityType = spawned.getType();
        switch (entityType) {
        case CHICKEN:
        case COW:
        case MUSHROOM_COW:
        case PIG:
        case RABBIT:
        case SHEEP:
            break;
        default:
            return;
        }
        // Check nearby mobs
        double r = this.breedingConfig.radius;
        long nearbyCount = spawned.getNearbyEntities(r, r, r)
            .stream()
            .filter(n -> compareEntities(spawned, n))
            .count();
        if (nearbyCount < this.breedingConfig.limit) {
            return;
        }
        // Do the thing.
        event.setCancelled(true);
        // Produce a warning.
        Location loc = spawned.getLocation();
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        long now = Instant.now().getEpochSecond();
        for (Iterator<IssuedWarning> it = this.issuedWarnings.iterator(); it.hasNext();) {
            IssuedWarning warning = it.next();
            if (now - warning.time > this.breedingConfig.warnTimer) {
                it.remove();
            } else if (nearby(warning, entityType, world, x, z)) {
                return;
            }
        }
        this.issuedWarnings.add(new IssuedWarning(entityType, world, x, y, z, now));
        loc.getWorld().getPlayers().stream()
            .filter(p -> nearby(p.getLocation(), world, x, z))
            .forEach(p -> {
                    p.sendMessage("" + ChatColor.RED
                                  + "A nearby "
                                  + Arrays.stream(spawned.getType().name().toLowerCase().split("_"))
                                  .collect(Collectors.joining(" "))
                                  + " farm is getting out of hand. Spawning was denied.");
                    p.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.MASTER, 1.0f, 1.0f);
                });
    }
}
