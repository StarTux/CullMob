package com.cavetale.cullmob;

import java.util.List;
import lombok.Value;

/**
 * Configuration deserialized from config.yml.
 */
@Value
final class BreedingConfig {
    protected List<Check> checks;
    protected final double warnRadius;
    protected final long warnTimer;

    @Value
    protected static final class Check {
        protected final double radius;
        protected final long limit;
    }

    protected double maxRadius() {
        return checks.stream().mapToDouble(c -> c.radius).max().orElse(0);
    }
}

