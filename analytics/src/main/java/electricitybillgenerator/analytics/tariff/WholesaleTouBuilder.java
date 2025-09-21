package electricitybillgenerator.analytics.tariff;

import electricitybillgenerator.analytics.data.PriceSeries;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Derives a retail time-of-use tariff from hourly wholesale prices.
 *
 * <p>For each season, weekday hours are ranked by average wholesale price. The most expensive
 * contiguous {@code onPeakHours} window becomes on-peak, the best {@code midWindowHours} window
 * around it becomes mid-peak, and everything else (including weekends) is off-peak. Each band's
 * retail rate is the mean wholesale price over that band plus a flat delivery adder.
 */
public final class WholesaleTouBuilder {

    public static final List<Integer> SUMMER = List.of(6, 7, 8, 9);
    public static final List<Integer> WINTER = List.of(1, 2, 3, 4, 5, 10, 11, 12);

    private int onPeakHours = 5;
    private int midWindowHours = 11;
    private double deliveryAdderPerKwh = 0.12;
    private double demandChargePerKw = 15.0;
    private double customerChargePerMonth = 10.0;

    public WholesaleTouBuilder onPeakHours(int h) {
        onPeakHours = h;
        return this;
    }

    public WholesaleTouBuilder deliveryAdder(double perKwh) {
        deliveryAdderPerKwh = perKwh;
        return this;
    }

    public WholesaleTouBuilder demandCharge(double perKw) {
        demandChargePerKw = perKw;
        return this;
    }

    public WholesaleTouBuilder customerCharge(double perMonth) {
        customerChargePerMonth = perMonth;
        return this;
    }

    public TariffSpec build(PriceSeries prices) {
        List<TouPeriod> peaks = new ArrayList<>();
        List<TouPeriod> offs = new ArrayList<>();
        double sumAll = 0;
        for (Object[] season : new Object[][]{{"Summer", SUMMER}, {"Winter", WINTER}}) {
            String label = (String) season[0];
            @SuppressWarnings("unchecked")
            List<Integer> months = (List<Integer>) season[1];

            double[] weekdayMean = meanByHour(prices, months, true);
            int onStart = bestWindow(weekdayMean, onPeakHours, 0, 24);
            int midStart = bestWindow(weekdayMean, midWindowHours,
                    Math.max(0, onStart + onPeakHours - midWindowHours), Math.min(24 - midWindowHours, onStart));
            List<Integer> on = range(onStart, onStart + onPeakHours);
            List<Integer> mid = new ArrayList<>(range(midStart, midStart + midWindowHours));
            mid.removeAll(on);

            double onRate = bandMean(prices, months, on, true) / 1000.0 + deliveryAdderPerKwh;
            double midRate = bandMean(prices, months, mid, true) / 1000.0 + deliveryAdderPerKwh;
            double offRate = offPeakMean(prices, months, on, mid) / 1000.0 + deliveryAdderPerKwh;
            peaks.add(new TouPeriod(label + " On-Peak", round4(onRate), months, true, on));
            peaks.add(new TouPeriod(label + " Mid-Peak", round4(midRate), months, true, mid));
            offs.add(new TouPeriod(label + " Off-Peak", round4(offRate), months, false, range(0, 24)));
            sumAll += offRate;
        }
        List<TouPeriod> periods = new ArrayList<>(peaks);
        periods.addAll(offs);
        return new TariffSpec("ISO-NE NEMA/Boston derived TOU + demand (" + prices.location() + ")",
                customerChargePerMonth, demandChargePerKw, round4(sumAll / 2), periods);
    }

    private static double[] meanByHour(PriceSeries p, List<Integer> months, boolean weekdaysOnly) {
        double[] sum = new double[24];
        int[] n = new int[24];
        for (int i = 0; i < p.size(); i++) {
            LocalDateTime t = p.times().get(i);
            if (months.contains(t.getMonthValue()) && (!weekdaysOnly || !isWeekend(t))) {
                sum[t.getHour()] += p.pricesPerMwh().get(i);
                n[t.getHour()]++;
            }
        }
        for (int h = 0; h < 24; h++) {
            sum[h] = n[h] == 0 ? 0 : sum[h] / n[h];
        }
        return sum;
    }

    /** Start of the length-w window in [lo, hi] (inclusive start bounds) with the highest mean. */
    static int bestWindow(double[] byHour, int w, int lo, int hi) {
        int best = lo;
        double bestSum = Double.NEGATIVE_INFINITY;
        for (int s = lo; s <= Math.min(hi, 24 - w); s++) {
            double sum = 0;
            for (int h = s; h < s + w; h++) {
                sum += byHour[h];
            }
            if (sum > bestSum) {
                bestSum = sum;
                best = s;
            }
        }
        return best;
    }

    private static double bandMean(PriceSeries p, List<Integer> months, List<Integer> hours, boolean weekdaysOnly) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < p.size(); i++) {
            LocalDateTime t = p.times().get(i);
            if (months.contains(t.getMonthValue()) && hours.contains(t.getHour()) && (!weekdaysOnly || !isWeekend(t))) {
                sum += p.pricesPerMwh().get(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double offPeakMean(PriceSeries p, List<Integer> months, List<Integer> on, List<Integer> mid) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < p.size(); i++) {
            LocalDateTime t = p.times().get(i);
            if (!months.contains(t.getMonthValue())) {
                continue;
            }
            boolean peakHour = !isWeekend(t) && (on.contains(t.getHour()) || mid.contains(t.getHour()));
            if (!peakHour) {
                sum += p.pricesPerMwh().get(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static boolean isWeekend(LocalDateTime t) {
        return t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static List<Integer> range(int a, int b) {
        return IntStream.range(a, b).boxed().toList();
    }

    private static double round4(double x) {
        return Math.round(x * 10000.0) / 10000.0;
    }
}
