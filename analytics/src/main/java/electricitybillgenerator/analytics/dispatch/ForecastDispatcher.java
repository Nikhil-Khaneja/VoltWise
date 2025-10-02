package electricitybillgenerator.analytics.dispatch;

import electricitybillgenerator.analytics.battery.Battery;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.time.YearMonth;

/**
 * Day-ahead model predictive control. At midnight it forecasts the next 24 hours, solves the
 * battery LP on the forecast (with the month's peak so far as a floor), and then runs the day
 * against the real load:
 * <ul>
 *   <li>if real load goes above the planned grid cap, discharge to hold the cap;</li>
 *   <li>in the day's cheapest tariff hours, top the battery up without exceeding the cap;</li>
 *   <li>otherwise follow the planned charging, never charging above the cap.</li>
 * </ul>
 * The forecast's job is to set the daily cap: high enough that the battery can hold it, low
 * enough to cut the bill.
 * With a perfect forecast this matches a day-by-day optimiser; forecast error shows up as
 * missed peaks and lost savings.
 */
public final class ForecastDispatcher {

    /** Supplies a 24-hour load forecast for the day starting at index {@code dayStart}. */
    public interface DayAheadForecast {
        double[] forecast(int dayStart, int hours);
    }

    private final DayAheadForecast forecast;
    private final String name;

    public ForecastDispatcher(String name, DayAheadForecast forecast) {
        this.name = name;
        this.forecast = forecast;
    }

    /** Uses the actual load as the forecast: the perfect-foresight day-ahead upper bound. */
    public static ForecastDispatcher perfect(HourlySeries load) {
        return new ForecastDispatcher("MPC + perfect day-ahead forecast", perfectForecast(load));
    }

    public static DayAheadForecast perfectForecast(HourlySeries load) {
        return (start, h) -> {
            double[] f = new double[h];
            for (int i = 0; i < h; i++) {
                f[i] = load.get(start + i);
            }
            return f;
        };
    }

    public DispatchResult dispatch(HourlySeries load, BatterySpec spec, TariffSpec tariff) {
        int n = load.size();
        double[] c = new double[n];
        double[] d = new double[n];
        double[] soc = new double[n];
        Battery battery = new Battery(spec);
        YearMonth month = YearMonth.from(load.time(0));
        double peakSoFar = 0;

        for (int day = 0; day < n; day += 24) {
            int h = Math.min(24, n - day);
            YearMonth ym = YearMonth.from(load.time(day));
            if (!ym.equals(month)) {
                month = ym;
                peakSoFar = 0;
            }
            double[] f = forecast.forecast(day, h);
            double[] p = new double[h];
            for (int t = 0; t < h; t++) {
                p[t] = tariff.rateAt(load.time(day + t));
            }
            double soc0 = battery.socKwh();
            double terminal = Math.min(soc0, spec.initialSocKwh());
            BatteryLp.Plan plan = BatteryLp.solve(f, p, tariff.demandChargePerKw(), peakSoFar, soc0, terminal, spec);
            double cap = Math.max(plan.plannedPeak(), peakSoFar);

            for (int t = 0; t < h; t++) {
                int i = day + t;
                double l = load.get(i);
                double net = plan.charge()[t] - plan.discharge()[t];
                if (l > cap) {
                    d[i] = battery.discharge(l - cap);
                } else if (tariff.isCheapestHour(load.time(i))) {
                    // Keep the battery full for peaks: top up in the cheapest hours, under the cap.
                    c[i] = battery.charge(cap - l);
                } else if (net > 0) {
                    c[i] = battery.charge(Math.min(net, cap - l));
                }
                // Planned arbitrage discharge (net < 0 while under the cap) is skipped on purpose:
                // on this tariff the evening price peak coincides with the household's load peak,
                // and spending energy on a few cents of arbitrage leaves the battery empty when a
                // forecast miss makes the real peak arrive.
                soc[i] = battery.socKwh();
                peakSoFar = Math.max(peakSoFar, l + c[i] - d[i]);
            }
        }
        return new DispatchResult(name, load, c, d, soc);
    }
}
