package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.battery.Battery;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Perfect-foresight benchmark: one LP over the whole series with a separate peak variable for
 * each billing month, and the actual load known in advance. No real controller can beat this,
 * so it bounds what any strategy can save. The only boundary condition is that the battery ends
 * the series at least as full as it started.
 */
public final class OptimalDispatcher {

    public DispatchResult dispatch(HourlySeries load, BatterySpec spec, TariffSpec tariff) {
        int n = load.size();
        double[] l = load.values();
        double[] p = new double[n];
        int[] group = new int[n];
        List<YearMonth> months = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            p[t] = tariff.rateAt(load.time(t));
            YearMonth ym = YearMonth.from(load.time(t));
            if (months.isEmpty() || !months.get(months.size() - 1).equals(ym)) {
                months.add(ym);
            }
            group[t] = months.size() - 1;
        }
        double[] demand = new double[months.size()];
        Arrays.fill(demand, tariff.demandChargePerKw());
        Battery battery = new Battery(spec);
        double soc0 = battery.socKwh();
        BatteryLp.Plan plan = BatteryLp.solve(l, p, group, demand, new double[months.size()], soc0, soc0, spec);

        double[] c = new double[n];
        double[] d = new double[n];
        double[] soc = new double[n];
        for (int t = 0; t < n; t++) {
            Execution.apply(battery, plan.charge()[t] - plan.discharge()[t], l[t], t, c, d);
            soc[t] = battery.socKwh();
        }
        return new DispatchResult("optimal LP (perfect foresight)", load, c, d, soc);
    }
}
