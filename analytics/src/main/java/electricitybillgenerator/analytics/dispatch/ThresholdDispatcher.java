package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.battery.Battery;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Rule-based peak shaving: discharge whenever load is above the month's threshold, and
 * recharge during the day's cheapest tariff hours without pushing grid import over it.
 */
public final class ThresholdDispatcher {

    private final Map<Month, Double> thresholdKw;

    public ThresholdDispatcher(Map<Month, Double> thresholdKw) {
        this.thresholdKw = thresholdKw;
    }

    public static ThresholdDispatcher fixed(double kw) {
        Map<Month, Double> m = new EnumMap<>(Month.class);
        for (Month month : Month.values()) {
            m.put(month, kw);
        }
        return new ThresholdDispatcher(m);
    }

    public Map<Month, Double> thresholds() {
        return thresholdKw;
    }

    public DispatchResult dispatch(HourlySeries load, BatterySpec spec, TariffSpec tariff) {
        Battery battery = new Battery(spec);
        int n = load.size();
        double[] c = new double[n];
        double[] d = new double[n];
        double[] soc = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDateTime t = load.time(i);
            double x = thresholdKw.get(t.getMonth());
            double l = load.get(i);
            if (l > x) {
                d[i] = battery.discharge(l - x);
            } else if (tariff.isCheapestHour(t)) {
                c[i] = battery.charge(x - l);
            }
            soc[i] = battery.socKwh();
        }
        return new DispatchResult("threshold rule", load, c, d, soc);
    }

    /**
     * Picks, for each calendar month, the threshold that minimised that month's bill in the
     * training data (the most recent year wins when several cover the same month). Applying
     * the result to a later year uses no future information.
     */
    public static ThresholdDispatcher tune(HourlySeries train, BatterySpec spec, TariffSpec tariff, int steps) {
        Map<Month, Double> best = new EnumMap<>(Month.class);
        YearMonth last = YearMonth.from(train.time(train.size() - 1));
        for (YearMonth ym = YearMonth.from(train.time(0)); !ym.isAfter(last); ym = ym.plusMonths(1)) {
            HourlySeries month = train.slice(ym);
            if (month.size() < 24 * 20) {
                continue; // skip partial months
            }
            double[] sorted = month.values().clone();
            Arrays.sort(sorted);
            double lo = sorted[sorted.length / 2];
            double hi = sorted[sorted.length - 1];
            double bestX = hi;
            double bestCost = Double.MAX_VALUE;
            for (int s = 0; s <= steps; s++) {
                double x = lo + (hi - lo) * s / steps;
                DispatchResult r = fixed(x).dispatch(month, spec, tariff);
                double cost = BillCalculator.total(BillCalculator.bill(tariff, r.grid()));
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = x;
                }
            }
            best.put(ym.getMonth(), bestX);
        }
        double fallback = best.values().stream().mapToDouble(Double::doubleValue).average().orElse(3.0);
        for (Month m : Month.values()) {
            best.putIfAbsent(m, fallback);
        }
        return new ThresholdDispatcher(best);
    }
}
