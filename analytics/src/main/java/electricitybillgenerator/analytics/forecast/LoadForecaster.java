package electricitybillgenerator.analytics.forecast;

import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Day-ahead hourly load forecasts. A forecast for the day starting at index {@code dayStart}
 * may only use load before {@code dayStart}: everything is issued at midnight.
 */
public final class LoadForecaster {

    public static final String[] FEATURES = {
            "hour", "dow", "weekend", "month_sin", "month_cos", "doy_sin", "doy_cos",
            "lag_24h_same_hour", "lag_48h_same_hour", "lag_168h_same_hour", "mean_same_hour_7d",
            "prev_day_mean", "prev_day_max", "prev_day_last_hour", "prev_week_mean"
    };

    private LoadForecaster() {
    }

    /** Features for target hour {@code target}, forecast issued at {@code origin} (day start). */
    static double[] features(HourlySeries s, int origin, int target) {
        LocalDateTime t = s.time(target);
        double[] f = new double[FEATURES.length];
        int k = 0;
        f[k++] = t.getHour();
        f[k++] = t.getDayOfWeek().getValue();
        f[k++] = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY ? 1 : 0;
        f[k++] = Math.sin(2 * Math.PI * t.getMonthValue() / 12.0);
        f[k++] = Math.cos(2 * Math.PI * t.getMonthValue() / 12.0);
        f[k++] = Math.sin(2 * Math.PI * t.getDayOfYear() / 365.25);
        f[k++] = Math.cos(2 * Math.PI * t.getDayOfYear() / 365.25);
        f[k++] = s.get(target - 24);
        f[k++] = s.get(target - 48);
        f[k++] = s.get(target - 168);
        double sameHour = 0;
        for (int d = 1; d <= 7; d++) {
            sameHour += s.get(target - 24 * d);
        }
        f[k++] = sameHour / 7;
        double sum = 0, max = 0;
        for (int i = origin - 24; i < origin; i++) {
            sum += s.get(i);
            max = Math.max(max, s.get(i));
        }
        f[k++] = sum / 24;
        f[k++] = max;
        f[k++] = s.get(origin - 1);
        double week = 0;
        for (int i = origin - 168; i < origin; i++) {
            week += s.get(i);
        }
        f[k++] = week / 168;
        return f;
    }

    /** First index at which a full feature row is available: a week of history, aligned to midnight. */
    public static int firstOrigin(HourlySeries s) {
        int i = 168;
        while (s.time(i).getHour() != 0) {
            i++;
        }
        return i;
    }

    /** Trains on every complete day whose start lies in [fromOrigin, toOrigin). */
    public static GradientBoostedTrees train(HourlySeries s, int fromOrigin, int toOrigin,
                                             GradientBoostedTrees.Params params) {
        List<double[]> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (int origin = Math.max(fromOrigin, firstOrigin(s)); origin + 24 <= toOrigin; origin += 24) {
            for (int h = 0; h < 24; h++) {
                x.add(features(s, origin, origin + h));
                y.add(Math.log1p(s.get(origin + h)));
            }
        }
        return new GradientBoostedTrees(params).fit(x.toArray(double[][]::new),
                y.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** Gradient-boosted forecast. The model is trained on log1p(kW). */
    public static ForecastDispatcher.DayAheadForecast gbt(HourlySeries full, int offset, GradientBoostedTrees model) {
        return (dayStart, hours) -> {
            int origin = offset + dayStart;
            double[] out = new double[hours];
            for (int h = 0; h < hours; h++) {
                out[h] = Math.max(0, Math.expm1(model.predict(features(full, origin, origin + h))));
            }
            return out;
        };
    }

    /** Seasonal naive: same hour one week earlier. */
    public static ForecastDispatcher.DayAheadForecast seasonalNaive(HourlySeries full, int offset) {
        return (dayStart, hours) -> {
            int origin = offset + dayStart;
            double[] out = new double[hours];
            for (int h = 0; h < hours; h++) {
                out[h] = full.get(origin + h - 168);
            }
            return out;
        };
    }

    /** Average of the same hour over the previous 7 days. */
    public static ForecastDispatcher.DayAheadForecast weekAverage(HourlySeries full, int offset) {
        return (dayStart, hours) -> {
            int origin = offset + dayStart;
            double[] out = new double[hours];
            for (int h = 0; h < hours; h++) {
                double sum = 0;
                for (int d = 1; d <= 7; d++) {
                    sum += full.get(origin + h - 24 * d);
                }
                out[h] = sum / 7;
            }
            return out;
        };
    }
}
