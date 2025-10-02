package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.forecast.ForecastMetrics;
import electricitybillgenerator.analytics.forecast.GradientBoostedTrees;
import electricitybillgenerator.analytics.forecast.LoadForecaster;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastTest {

    @Test
    void gbtLearnsANonlinearFunction() {
        Random rnd = new Random(0);
        int n = 4000;
        double[][] x = new double[n][2];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = rnd.nextDouble() * 10;
            x[i][1] = rnd.nextDouble();
            y[i] = Math.sin(x[i][0]) + (x[i][1] > 0.5 ? 2 : 0);
        }
        GradientBoostedTrees m = new GradientBoostedTrees(GradientBoostedTrees.Params.defaults()).fit(x, y);
        double err = 0;
        for (int i = 0; i < 500; i++) {
            double a = rnd.nextDouble() * 10, b = rnd.nextDouble();
            err += Math.abs(m.predict(new double[]{a, b}) - (Math.sin(a) + (b > 0.5 ? 2 : 0)));
        }
        assertTrue(err / 500 < 0.1, "mean abs error " + err / 500);
    }

    @Test
    void quantileLossProducesCalibratedUpperForecasts() {
        Random rnd = new Random(1);
        int n = 6000;
        double[][] x = new double[n][1];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = rnd.nextDouble();
            y[i] = x[i][0] + rnd.nextGaussian() * 0.3;
        }
        GradientBoostedTrees q90 = new GradientBoostedTrees(GradientBoostedTrees.Params.defaults().withQuantile(0.9)).fit(x, y);
        int covered = 0;
        for (int i = 0; i < 2000; i++) {
            double a = rnd.nextDouble();
            if (a + rnd.nextGaussian() * 0.3 <= q90.predict(new double[]{a})) {
                covered++;
            }
        }
        assertEquals(0.9, covered / 2000.0, 0.03);
    }

    @Test
    void forecastsUseOnlyDataBeforeTheDay() {
        LocalDateTime start = LocalDateTime.of(2009, 1, 1, 0, 0);
        HourlySeries s = Fixtures.household(start, 60, 2);
        int origin = 24 * 30;
        double[] before = LoadForecaster.seasonalNaive(s, 0).forecast(origin, 24);
        // Corrupt the target day: a forecast that peeks at it would change.
        double[] v = s.values().clone();
        for (int i = origin; i < origin + 24; i++) {
            v[i] = 1000;
        }
        HourlySeries corrupted = s.withValues(v);
        double[] after = LoadForecaster.seasonalNaive(corrupted, 0).forecast(origin, 24);
        for (int h = 0; h < 24; h++) {
            assertEquals(before[h], after[h]);
        }

        GradientBoostedTrees m = LoadForecaster.train(s, 0, origin, GradientBoostedTrees.Params.defaults());
        double[] g1 = LoadForecaster.gbt(s, 0, m).forecast(origin, 24);
        double[] g2 = LoadForecaster.gbt(corrupted, 0, m).forecast(origin, 24);
        for (int h = 0; h < 24; h++) {
            assertEquals(g1[h], g2[h]);
        }
    }

    @Test
    void metricsOnAPerfectForecast() {
        HourlySeries s = Fixtures.household(LocalDateTime.of(2009, 1, 1, 0, 0), 10, 4);
        ForecastMetrics m = ForecastMetrics.evaluate("perfect", s, (start, h) -> {
            double[] f = new double[h];
            System.arraycopy(s.values(), start, f, 0, h);
            return f;
        });
        assertEquals(0, m.mape(), 1e-12);
        assertEquals(0, m.rmseKw(), 1e-12);
        assertEquals(1.0, m.coverage(), 1e-12);
    }
}
