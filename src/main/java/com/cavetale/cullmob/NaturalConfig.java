package com.cavetale.cullmob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
final class NaturalConfig {
    protected List<World> worlds;
    protected double tpsThreshold = 0;
    protected double lowTpsSpawnChance = 0;
    protected transient Map<String, World> worldMap;

    @Data
    protected static final class World {
        protected String name;
        protected boolean enabled;
        protected int chunkRadius;
        protected int mobLimit;
    }

    void unpack() {
        worldMap = new HashMap<>();
        for (World world : worlds) {
            worldMap.put(world.name, world);
        }
    }
}
