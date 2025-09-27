package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.battery.Battery;

/** Shared helper that applies a planned net battery power to the physical battery model. */
final class Execution {

    private Execution() {
    }

    /**
     * @param netKw positive to charge (grid side), negative to discharge (load side)
     */
    static void apply(Battery battery, double netKw, double load, int t, double[] c, double[] d) {
        if (netKw > 1e-9) {
            c[t] = battery.charge(netKw);
        } else if (netKw < -1e-9) {
            d[t] = battery.discharge(Math.min(-netKw, load));
        }
    }
}
