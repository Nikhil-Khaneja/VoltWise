package electricitybillgenerator.analytics.forecast;

import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;

/**
 * Error metrics for a day-ahead forecaster over a test series. {@code coverage} is the share of
 * hours where actual load came in at or below the forecast (about q for a well-calibrated q-quantile).
 */
public record ForecastMetrics(String name, double mape, double wape, double maeKw, double rmseKw,
                              double dailyPeakMape, double coverage, double[] predictions) {

    /** MAPE ignores hours below this load, where percentage error is meaningless. */
    public static final double MAPE_FLOOR_KW = 0.1;

    public static ForecastMetrics evaluate(String name, HourlySeries test, ForecastDispatcher.DayAheadForecast f) {
        int n = test.size();
        double[] pred = new double[n];
        for (int day = 0; day < n; day += 24) {
            int h = Math.min(24, n - day);
            double[] p = f.forecast(day, h);
            System.arraycopy(p, 0, pred, day, h);
        }
        double ape = 0, absErr = 0, absAct = 0, sq = 0;
        int mapeN = 0, covered = 0;
        for (int i = 0; i < n; i++) {
            double a = test.get(i);
            double e = pred[i] - a;
            absErr += Math.abs(e);
            absAct += Math.abs(a);
            sq += e * e;
            if (a <= pred[i]) {
                covered++;
            }
            if (a >= MAPE_FLOOR_KW) {
                ape += Math.abs(e) / a;
                mapeN++;
            }
        }
        double peakApe = 0;
        int days = 0;
        for (int day = 0; day + 24 <= n; day += 24) {
            double ap = 0, pp = 0;
            for (int h = 0; h < 24; h++) {
                ap = Math.max(ap, test.get(day + h));
                pp = Math.max(pp, pred[day + h]);
            }
            peakApe += Math.abs(pp - ap) / ap;
            days++;
        }
        return new ForecastMetrics(name, ape / mapeN, absErr / absAct, absErr / n, Math.sqrt(sq / n),
                peakApe / days, (double) covered / n, pred);
    }
}
