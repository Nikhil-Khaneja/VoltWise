package electricitybillgenerator.analytics.battery;

/**
 * Battery parameters. Round-trip losses are split evenly between charging and discharging.
 *
 * @param capacityKwh         usable nameplate energy
 * @param powerKw             max charge and discharge power
 * @param roundTripEfficiency e.g. 0.90
 * @param minSocFraction      lowest allowed state of charge, as a fraction of capacity
 * @param maxSocFraction      highest allowed state of charge
 * @param initialSocFraction  state of charge at the start of a simulation
 */
public record BatterySpec(double capacityKwh, double powerKw, double roundTripEfficiency,
                          double minSocFraction, double maxSocFraction, double initialSocFraction) {

    public BatterySpec {
        if (capacityKwh <= 0 || powerKw <= 0) {
            throw new IllegalArgumentException("capacity and power must be positive");
        }
        if (roundTripEfficiency <= 0 || roundTripEfficiency > 1) {
            throw new IllegalArgumentException("round-trip efficiency must be in (0, 1]");
        }
        if (!(0 <= minSocFraction && minSocFraction < maxSocFraction && maxSocFraction <= 1)) {
            throw new IllegalArgumentException("need 0 <= minSoc < maxSoc <= 1");
        }
        if (initialSocFraction < minSocFraction || initialSocFraction > maxSocFraction) {
            throw new IllegalArgumentException("initial SoC outside limits");
        }
    }

    /** A home-scale default: 13.5 kWh / 5 kW, 90 % round trip, 10-100 % SoC window. */
    public static BatterySpec homeDefault() {
        return of(13.5, 5.0);
    }

    public static BatterySpec of(double capacityKwh, double powerKw) {
        return new BatterySpec(capacityKwh, powerKw, 0.90, 0.10, 1.00, 0.50);
    }

    public double chargeEfficiency() {
        return Math.sqrt(roundTripEfficiency);
    }

    public double dischargeEfficiency() {
        return Math.sqrt(roundTripEfficiency);
    }

    public double minSocKwh() {
        return capacityKwh * minSocFraction;
    }

    public double maxSocKwh() {
        return capacityKwh * maxSocFraction;
    }

    public double initialSocKwh() {
        return capacityKwh * initialSocFraction;
    }
}
