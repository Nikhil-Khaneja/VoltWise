package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.data.HourlySeries;

/**
 * Hour-by-hour battery operation. All arrays are aligned with {@code load}.
 *
 * @param load      site demand (kW)
 * @param charge    grid-side power into the battery (kW)
 * @param discharge power delivered from the battery to the load (kW)
 * @param soc       state of charge at the end of each hour (kWh)
 */
public record DispatchResult(String strategy, HourlySeries load, double[] charge, double[] discharge, double[] soc) {

    /** Net grid import: load + charging - discharging. Never negative (no export). */
    public HourlySeries grid() {
        double[] g = new double[load.size()];
        for (int i = 0; i < g.length; i++) {
            g[i] = load.get(i) + charge[i] - discharge[i];
        }
        return load.withValues(g);
    }

    /** A no-battery result, for baseline bills. */
    public static DispatchResult none(HourlySeries load) {
        int n = load.size();
        return new DispatchResult("no battery", load, new double[n], new double[n], new double[n]);
    }
}
