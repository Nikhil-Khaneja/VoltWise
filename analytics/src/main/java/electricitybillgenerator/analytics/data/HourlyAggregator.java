package electricitybillgenerator.analytics.data;

import electricitybillgenerator.analytics.anomaly.AnomalyDetector;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Turns flagged minute readings into a clean hourly kW series.
 * An hour needs at least {@code minGoodMinutes} good readings; otherwise it is imputed from
 * the same hour one week earlier (then one day earlier, then one week later).
 */
public final class HourlyAggregator {

    public record Output(HourlySeries series, int imputedHours) {
    }

    private HourlyAggregator() {
    }

    public static Output aggregate(MinuteData d, AnomalyDetector.Result flags, int minGoodMinutes) {
        LocalDateTime firstHour = d.start().truncatedTo(ChronoUnit.HOURS);
        if (!firstHour.equals(d.start())) {
            firstHour = firstHour.plusHours(1);
        }
        int offset = (int) ChronoUnit.MINUTES.between(d.start(), firstHour);
        int hours = (d.size() - offset) / 60;
        double[] kw = new double[hours];
        for (int h = 0; h < hours; h++) {
            double sum = 0;
            int good = 0;
            for (int m = 0; m < 60; m++) {
                int i = offset + h * 60 + m;
                if (flags == null ? !Float.isNaN(d.activeKw()[i]) : !flags.isBad(i)) {
                    sum += d.activeKw()[i];
                    good++;
                }
            }
            kw[h] = good >= minGoodMinutes ? sum / good : Double.NaN;
        }
        int imputed = impute(kw);
        return new Output(new HourlySeries(firstHour, kw), imputed);
    }

    static int impute(double[] kw) {
        int count = 0;
        int[] lags = {-168, -24, 168, 24, -336, 336};
        for (int pass = 0; pass < 10; pass++) {
            boolean any = false;
            for (int h = 0; h < kw.length; h++) {
                if (!Double.isNaN(kw[h])) {
                    continue;
                }
                for (int lag : lags) {
                    int j = h + lag;
                    if (j >= 0 && j < kw.length && !Double.isNaN(kw[j])) {
                        kw[h] = kw[j];
                        count++;
                        break;
                    }
                }
                any |= Double.isNaN(kw[h]);
            }
            if (!any) {
                break;
            }
        }
        return count;
    }
}
