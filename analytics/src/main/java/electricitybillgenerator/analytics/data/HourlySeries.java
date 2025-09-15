package electricitybillgenerator.analytics.data;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * A regular hourly time series. Index i covers the hour starting at start + i hours.
 * Values are average kW over the hour, which is also the kWh consumed in that hour.
 * Timestamps are naive local time (the UCI meter does not record DST).
 */
public final class HourlySeries {
    private final LocalDateTime start;
    private final double[] values;

    public HourlySeries(LocalDateTime start, double[] values) {
        this.start = start.truncatedTo(ChronoUnit.HOURS);
        this.values = values;
    }

    public LocalDateTime start() {
        return start;
    }

    public int size() {
        return values.length;
    }

    public double get(int i) {
        return values[i];
    }

    public double[] values() {
        return values;
    }

    public LocalDateTime time(int i) {
        return start.plusHours(i);
    }

    /** Index of the hour starting at t (may be out of range). */
    public int indexOf(LocalDateTime t) {
        return (int) ChronoUnit.HOURS.between(start, t);
    }

    /** Sub-series covering [from, to) clamped to the available range. */
    public HourlySeries slice(LocalDateTime from, LocalDateTime to) {
        int a = Math.max(0, indexOf(from));
        int b = Math.min(values.length, indexOf(to));
        return new HourlySeries(time(a), Arrays.copyOfRange(values, a, Math.max(a, b)));
    }

    public HourlySeries withValues(double[] newValues) {
        if (newValues.length != values.length) {
            throw new IllegalArgumentException("length mismatch");
        }
        return new HourlySeries(start, newValues);
    }

    public HourlySeries slice(YearMonth month) {
        return slice(month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
    }
}
