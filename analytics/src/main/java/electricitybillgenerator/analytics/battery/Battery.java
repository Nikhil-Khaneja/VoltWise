package electricitybillgenerator.analytics.battery;

/**
 * Stateful battery stepped one hour at a time. Every request is clamped to the power limit
 * and the state-of-charge window, so no dispatcher can push it outside its limits.
 */
public final class Battery {
    private final BatterySpec spec;
    private double socKwh;

    public Battery(BatterySpec spec) {
        this(spec, spec.initialSocKwh());
    }

    public Battery(BatterySpec spec, double socKwh) {
        this.spec = spec;
        this.socKwh = Math.max(spec.minSocKwh(), Math.min(spec.maxSocKwh(), socKwh));
    }

    public double socKwh() {
        return socKwh;
    }

    public BatterySpec spec() {
        return spec;
    }

    /**
     * Charge for one hour at up to {@code kw} measured at the grid side.
     *
     * @return the grid-side kW actually drawn
     */
    public double charge(double kw) {
        if (kw <= 0) {
            return 0;
        }
        double headroomGridKwh = (spec.maxSocKwh() - socKwh) / spec.chargeEfficiency();
        double accepted = Math.max(0, Math.min(Math.min(kw, spec.powerKw()), headroomGridKwh));
        socKwh = Math.min(spec.maxSocKwh(), socKwh + accepted * spec.chargeEfficiency());
        return accepted;
    }

    /**
     * Discharge for one hour delivering up to {@code kw} to the load.
     *
     * @return the load-side kW actually delivered
     */
    public double discharge(double kw) {
        if (kw <= 0) {
            return 0;
        }
        double availableLoadKwh = (socKwh - spec.minSocKwh()) * spec.dischargeEfficiency();
        double delivered = Math.max(0, Math.min(Math.min(kw, spec.powerKw()), availableLoadKwh));
        socKwh = Math.max(spec.minSocKwh(), socKwh - delivered / spec.dischargeEfficiency());
        return delivered;
    }
}
