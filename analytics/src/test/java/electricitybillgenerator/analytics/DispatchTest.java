package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.DispatchResult;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;
import electricitybillgenerator.analytics.dispatch.OptimalDispatcher;
import electricitybillgenerator.analytics.dispatch.ThresholdDispatcher;
import electricitybillgenerator.analytics.forecast.LoadForecaster;
import electricitybillgenerator.analytics.report.StrategyResult;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchTest {

    private static final LocalDateTime JAN = LocalDateTime.of(2009, 1, 1, 0, 0);
    private final TariffSpec tariff = Fixtures.simpleTou();
    private final BatterySpec spec = BatterySpec.of(5, 2);

    @Test
    void everyStrategyRespectsBatteryAndGridLimits() {
        HourlySeries history = Fixtures.household(JAN.minusDays(14), 14 + 59, 3);
        HourlySeries load = history.slice(JAN, JAN.plusDays(59));
        int offset = history.indexOf(JAN);
        List<DispatchResult> results = List.of(
                ThresholdDispatcher.fixed(2.0).dispatch(load, spec, tariff),
                new OptimalDispatcher().dispatch(load, spec, tariff),
                ForecastDispatcher.perfect(load).dispatch(load, spec, tariff),
                new ForecastDispatcher("naive", LoadForecaster.seasonalNaive(history, offset)).dispatch(load, spec, tariff));
        for (DispatchResult r : results) {
            double prevSoc = spec.initialSocKwh();
            for (int t = 0; t < load.size(); t++) {
                String at = r.strategy() + " hour " + t;
                assertTrue(r.charge()[t] >= 0 && r.charge()[t] <= spec.powerKw() + 1e-9, at + " charge power");
                assertTrue(r.discharge()[t] >= 0 && r.discharge()[t] <= spec.powerKw() + 1e-9, at + " discharge power");
                assertTrue(r.soc()[t] >= spec.minSocKwh() - 1e-9 && r.soc()[t] <= spec.maxSocKwh() + 1e-9, at + " SoC");
                assertTrue(r.grid().get(t) >= -1e-9, at + " no export");
                double expected = prevSoc + r.charge()[t] * spec.chargeEfficiency() - r.discharge()[t] / spec.dischargeEfficiency();
                assertEquals(expected, r.soc()[t], 1e-6, at + " energy balance");
                prevSoc = r.soc()[t];
            }
        }
    }

    @Test
    void thresholdRuleHoldsGridAtThresholdWhenBatteryCan() {
        double[] v = new double[48];
        Arrays.fill(v, 1.0);
        v[18] = 2.5; // one evening spike the battery can cover
        HourlySeries load = new HourlySeries(JAN, v);
        DispatchResult r = ThresholdDispatcher.fixed(1.5).dispatch(load, spec, tariff);
        assertEquals(1.5, r.grid().get(18), 1e-9);
        assertEquals(1.0, r.discharge()[18], 1e-9);
    }

    @Test
    void optimalLpShavesASingleSpikeExactly() {
        double[] v = new double[24 * 31];
        Arrays.fill(v, 1.0);
        v[24 * 10 + 18] = 3.0;
        HourlySeries load = new HourlySeries(JAN, v);
        // Flat energy price, so the only value is demand reduction (with a TOU spread the LP
        // rightly trades some peak for arbitrage).
        TariffSpec flat = new TariffSpec("flat", 0, 10, 0.15, List.of());
        DispatchResult r = new OptimalDispatcher().dispatch(load, BatterySpec.of(10, 5), flat);
        double peak = Arrays.stream(r.grid().values()).max().orElseThrow();
        // The spike is shaved; recharging the ~2.3 kWh used is spread thinly over the month.
        assertEquals(1.0, peak, 0.01);
    }

    @Test
    void optimalLpIsNeverBeatenByTheOtherStrategies() {
        HourlySeries history = Fixtures.household(JAN.minusDays(14), 14 + 90, 11);
        HourlySeries load = history.slice(JAN, JAN.plusDays(90));
        var base = BillCalculator.bill(tariff, load);
        double lp = StrategyResult.of(new OptimalDispatcher().dispatch(load, spec, tariff), tariff, base).savings();
        double rule = StrategyResult.of(ThresholdDispatcher.fixed(2.2).dispatch(load, spec, tariff), tariff, base).savings();
        double mpc = StrategyResult.of(ForecastDispatcher.perfect(load).dispatch(load, spec, tariff), tariff, base).savings();
        assertTrue(lp > 0);
        assertTrue(lp >= rule - 1e-6, "LP " + lp + " vs rule " + rule);
        assertTrue(lp >= mpc - 1e-6, "LP " + lp + " vs MPC " + mpc);
    }

    @Test
    void savingsEqualBaselineMinusBatteryBill() {
        HourlySeries load = Fixtures.household(JAN, 31, 5);
        var base = BillCalculator.bill(tariff, load);
        DispatchResult r = ThresholdDispatcher.fixed(2.0).dispatch(load, spec, tariff);
        StrategyResult s = StrategyResult.of(r, tariff, base);
        double expected = BillCalculator.total(base) - BillCalculator.total(BillCalculator.bill(tariff, r.grid()));
        assertEquals(expected, s.savings(), 1e-9);
        assertEquals(100 * expected / BillCalculator.total(base), s.savingsPct(), 1e-9);
    }

    @Test
    void tunedThresholdsUseOnlyTrainingData() {
        HourlySeries train = Fixtures.household(JAN.minusYears(1), 365, 9);
        ThresholdDispatcher d = ThresholdDispatcher.tune(train, spec, tariff, 20);
        assertEquals(12, d.thresholds().size());
        d.thresholds().values().forEach(x -> assertTrue(x > 0 && x < 10));
    }
}
