package electricitybillgenerator.analytics.forecast;

import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Chooses the forecaster that drives the battery controller by what it is for, the bill, not
 * by forecast error. Every candidate is fitted on data before the validation year, scored by
 * bill savings over that year, and the winner is refitted on all data before the test year.
 *
 * <p>Peak shaving needs to know how high load can go, so quantile (pinball-loss) GBT models are
 * candidates alongside the mean model and the seasonal baselines.
 */
public final class ForecastSelection {

    public static final double[] GBT_QUANTILES = {0, 0.75, 0.9, 0.95, 0.98};

    /** A forecaster built from all history before {@code origin} (an index into the full series). */
    record Candidate(String name, BiFunction<HourlySeries, Integer, ForecastDispatcher.DayAheadForecast> build) {
    }

    public record Choice(String name, Map<String, Double> validationSavings, ForecastDispatcher.DayAheadForecast forecast,
                         Map<String, ForecastDispatcher.DayAheadForecast> testForecasts) {
    }

    private ForecastSelection() {
    }

    public static Map<String, ForecastDispatcher.DayAheadForecast> candidates(HourlySeries all, int origin) {
        Map<String, ForecastDispatcher.DayAheadForecast> m = new LinkedHashMap<>();
        for (Candidate c : list()) {
            m.put(c.name(), c.build().apply(all, origin));
        }
        return m;
    }

    private static java.util.List<Candidate> list() {
        java.util.List<Candidate> list = new java.util.ArrayList<>();
        list.add(new Candidate("Seasonal naive", LoadForecaster::seasonalNaive));
        list.add(new Candidate("7-day average", LoadForecaster::weekAverage));
        for (double q : GBT_QUANTILES) {
            String name = q == 0 ? "GBT mean" : String.format(java.util.Locale.US, "GBT q=%.2f", q);
            list.add(new Candidate(name, (all, origin) -> LoadForecaster.gbt(all, origin,
                    LoadForecaster.train(all, 0, origin, GradientBoostedTrees.Params.defaults().withQuantile(q)))));
        }
        return list;
    }

    public static Choice select(HourlySeries all, LocalDateTime validationFrom, LocalDateTime testFrom,
                                BatterySpec battery, TariffSpec tariff) {
        int valOrigin = all.indexOf(validationFrom);
        HourlySeries val = all.slice(validationFrom, testFrom);
        double base = BillCalculator.total(BillCalculator.bill(tariff, val));
        Map<String, Double> savings = new LinkedHashMap<>();
        String best = null;
        for (Map.Entry<String, ForecastDispatcher.DayAheadForecast> e : candidates(all, valOrigin).entrySet()) {
            var r = new ForecastDispatcher(e.getKey(), e.getValue()).dispatch(val, battery, tariff);
            double s = base - BillCalculator.total(BillCalculator.bill(tariff, r.grid()));
            savings.put(e.getKey(), s);
            if (best == null || s > savings.get(best)) {
                best = e.getKey();
            }
        }
        Map<String, ForecastDispatcher.DayAheadForecast> test = candidates(all, all.indexOf(testFrom));
        return new Choice(best, savings, test.get(best), test);
    }
}
