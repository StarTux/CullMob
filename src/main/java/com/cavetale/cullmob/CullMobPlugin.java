package com.cavetale.cullmob;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import lombok.Value;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
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
import org.bukkit.entity.Fish;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

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
    private double tps = 20.0;
    private Random random = new Random();
    private long now;

    /**
     * Track why, where, and when a warning was issued.
     */
    @Value
    private static class IssuedWarning {
        final EntityType entityType;
        final String world;
        final int x;
        final int y;
        final int z;
        final long time;
    }

    /**
     * Thrown by `onCommand()` and its methods.
     */
    private static class CommandException extends Exception {
        CommandException(final String message) {
            super(message);
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        loadConf();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
                tps = Bukkit.getServer().getTPS()[0];
                now = Instant.now().getEpochSecond();
            }, 20L, 20L);
    }

    @Override
    public void onDisable() {
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
            sender.sendMessage("Breeding: " + Json.serialize(breedingConfig));
            return true;
        case "list": {
            if (args.length != 0) {
                return false;
            }
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
        case "save":
            saveDefaultConfig();
            sender.sendMessage("Default config saved");
            return true;
        default:
            throw new CommandException("Unknown command: " + cmd);
        }
    }

    private <T> T loadConf(final String key, final Class<T> type) {
        Gson gson = new Gson();
        ConfigurationSection cfg
            = Objects.requireNonNull(getConfig().getConfigurationSection(key).getDefaultSection());
        String json = gson.toJson(cfg.getValues(false));
        return gson.fromJson(json, type);
    }

    private void loadConf() {
        reloadConfig();
        breedingConfig = loadConf("breeding", BreedingConfig.class);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    private void onPreCreatureSpawn(final PreCreatureSpawnEvent event) {
        if (tps > 19.0) return;
        switch (event.getReason()) {
        case BREEDING: // Passive breeding, like Villagers
        case VILLAGE_DEFENSE:
        case NETHER_PORTAL:
        case BEEHIVE:
        case PATROL: // Pillagers
        case EGG:
        case METAMORPHOSIS:
            onSpawnFromEnvironment(event, event.getSpawnLocation(), 64.0);
            break;
        default: break;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    private void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (tps > 19.0) return;
        // White-list spawn reasons
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        switch (reason) {
        case BREEDING:
        case DISPENSE_EGG:
        case EGG:
        case BUILD_IRONGOLEM:
        case BUILD_SNOWMAN:
            onBreed(event.getEntity(), event);
            break;
        case DEFAULT:
            if (event.getEntity().getType() == EntityType.TADPOLE) {
                onBreed(event.getEntity(), event);
            }
            break;
        default:
            break;
        }
    }

    /**
     * Special case for Phantoms.
     * Qua PaperMC, `net.minecraft.world.level.levelgen.PhantomSpawner`
     * spawns phantoms with NATURAL as reason.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    private void onPhantomPreSpawn(final PhantomPreSpawnEvent event) {
        if (tps > 19.0) return;
        if (!(event.getSpawningEntity() instanceof Player)) return;
        Player player = (Player) event.getSpawningEntity();
        if (player.getAffectsSpawning()) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    private void onSpawnerSpawn(final SpawnerSpawnEvent event) {
        if (tps > 19.0) return;
        onSpawnFromEnvironment(event, event.getSpawner().getLocation(),
                               (double) event.getSpawner().getRequiredPlayerRange());
    }

    /**
     * Handle PreSpawnerSpawnEvent.
     * Known issue: We assume the default spawner range of 16 blocks.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    private void onPreSpawnerSpawn(final PreSpawnerSpawnEvent event) {
        if (tps > 19.0) return;
        onSpawnFromEnvironment(event, event.getSpawnerLocation(), 16.0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    private void onSheepRegrowWool(SheepRegrowWoolEvent event) {
        if (tps > 19.0) return;
        onSpawnFromEnvironment(event, event.getEntity().getLocation(), 64.0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    private void onPlayerFish(PlayerFishEvent event) {
        if (tps > 19.0) return;
        if (event.getPlayer().getAffectsSpawning()) return;
        switch (event.getState()) {
        case BITE: event.setCancelled(true);
        default: break;
        }
    }

    /**
     * Deny some spawning situations if no nearby player affects mob
     * spawning where Paper does not check `affectsSpawning`. Our AFK
     * plugin manages this value for afk players.
     */
    private void onSpawnFromEnvironment(final Cancellable event, Location loc, double distance) {
        for (Player player : loc.getWorld().getNearbyEntitiesByType(Player.class, loc, distance)) {
            if (player.getGameMode() != GameMode.SPECTATOR && player.getAffectsSpawning()) return;
        }
        event.setCancelled(true);
    }

    private static boolean compareEntities(final Entity a, final Entity b) {
        switch (a.getType()) {
        case FROG:
        case TADPOLE:
            return b.getType() == EntityType.FROG
                || b.getType() == EntityType.TADPOLE;
        default: break;
        }
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

    private boolean nearby(final IssuedWarning warning, final EntityType entityType,
                   final String world, final int x, final int z) {
        if (entityType != warning.entityType
            || !warning.world.equals(world)) {
            return false;
        }
        double r = breedingConfig.warnRadius;
        return (double) Math.abs(warning.x - x) <= r
            && (double) Math.abs(warning.z - z) <= r;
    }

    private boolean nearby(final Location loc, final String world,
                   final int x, final int z) {
        if (!loc.getWorld().getName().equals(world)) {
            return false;
        }
        double r = breedingConfig.warnRadius;
        return Math.abs(loc.getX() - (double) x) <= r
            && Math.abs(loc.getZ() - (double) z) <= r;
    }

    private static String human(Enum e) {
        return e.name().toLowerCase().replace("_", " ");
    }

    private static String toString(Location loc) {
        return loc.getWorld().getName() + ":"
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void onBreed(final LivingEntity entity, Cancellable event) {
        // White-list mob types
        switch (entity.getType()) {
        case AXOLOTL:
        case BEE:
        case CAT:
        case CHICKEN:
        case COW:
        case FOX:
        case FROG:
        case GOAT:
        case IRON_GOLEM:
        case LLAMA:
        case MOOSHROOM:
        case OCELOT:
        case PANDA:
        case PIG:
        case RABBIT:
        case SHEEP:
        case SNOW_GOLEM:
        case TADPOLE:
        case TURTLE:
        case VILLAGER:
        case WOLF:
            break;
        default:
            if (entity instanceof Animals) break;
            if (entity instanceof Ageable) break;
            return;
        }
        checkNearbyMobs(entity, event);
    }

    /**
     * Check if there are too many nearby mobs.
     * @return true if spawning was denied, false otherwise.
     */
    private boolean checkNearbyMobs(LivingEntity entity, Cancellable event) {
        final double r = breedingConfig.maxRadius();
        final Location loc = entity.getLocation();
        final EntityType entityType = entity.getType();
        List<Double> nearbys = loc.getWorld().getNearbyEntities(loc, r, r, r)
            .stream()
            .filter(n -> !n.equals(entity))
            .filter(n -> compareEntities(entity, n))
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
        if (failedCheck == null) return false;
        // Do the thing.
        event.setCancelled(true);
        getLogger().info("[Breeding] Denied " + human(entityType) + " at "
                         + toString(loc) + " due to check:"
                         + " radius=" + (int) Math.ceil(failedCheck.radius)
                         + " limit=" + failedCheck.limit);
        // Produce a warning.
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (Iterator<IssuedWarning> iter = issuedWarnings.iterator(); iter.hasNext();) {
            IssuedWarning warning = iter.next();
            if (now - warning.time > breedingConfig.warnTimer) {
                iter.remove();
            } else if (nearby(warning, entityType, world, x, z)) {
                return false;
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
        return true;
    }

    @EventHandler
    private void onEntityPathfind(EntityPathfindEvent event) {
        if (tps > 17.0) return;
        Entity entity = event.getEntity();
        if (entity instanceof Animals || entity instanceof Villager || entity instanceof Fish) {
            if (random.nextInt(20) == 0) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onPlayerElytraBoost(PlayerElytraBoostEvent event) {
        if (tps > 17.0) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Firework boost is restricted due to heavy server load.");
    }

    @EventHandler
    private void onPlayerRiptide(PlayerRiptideEvent event) {
        if (tps > 17.0) return;
        Player player = event.getPlayer();
        if (!player.isGliding()) return;
        player.sendMessage(ChatColor.RED + "Riptide is restricted due to heavy server load.");
        Bukkit.getScheduler().runTask(this, () -> {
                Vector velo = player.getVelocity();
                velo.setX(0);
                velo.setZ(0);
                player.setVelocity(velo);
                player.setGliding(false);
            });
    }
}
