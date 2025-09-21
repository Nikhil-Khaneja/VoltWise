package electricitybillgenerator.analytics.report;

import electricitybillgenerator.analytics.data.HourlySeries;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Load-shape statistics for one consumer. */
public final class LoadAnalysis {

    public record MonthStats(YearMonth month, double energyKwh, double avgKw, double peakKw,
                             LocalDateTime peakTime, double loadFactor) {
    }

    public record Summary(double totalKwh, double avgKw, double peakKw, LocalDateTime peakTime, double loadFactor,
                          double avgMonthlyLoadFactor, int mostCommonPeakHour, double[] weekdayProfile,
                          double[] weekendProfile, double weekendToWeekdayRatio, List<MonthStats> months) {
    }

    private LoadAnalysis() {
    }

    public static Summary analyse(HourlySeries s) {
        double total = 0, peak = 0;
        LocalDateTime peakTime = null;
        double[] wd = new double[24], we = new double[24];
        int[] wdN = new int[24], weN = new int[24];
        Map<YearMonth, double[]> acc = new TreeMap<>(); // kwh, hours, peak
        Map<YearMonth, LocalDateTime> peakTimes = new TreeMap<>();
        int[] dailyPeakHourCount = new int[24];

        for (int day = 0; day + 24 <= s.size(); day += 24) {
            int bestH = 0;
            for (int h = 1; h < 24; h++) {
                if (s.get(day + h) > s.get(day + bestH)) {
                    bestH = h;
                }
            }
            dailyPeakHourCount[s.time(day + bestH).getHour()]++;
        }
        for (int i = 0; i < s.size(); i++) {
            LocalDateTime t = s.time(i);
            double v = s.get(i);
            total += v;
            if (v > peak) {
                peak = v;
                peakTime = t;
            }
            boolean weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (weekend) {
                we[t.getHour()] += v;
                weN[t.getHour()]++;
            } else {
                wd[t.getHour()] += v;
                wdN[t.getHour()]++;
            }
            YearMonth ym = YearMonth.from(t);
            double[] a = acc.computeIfAbsent(ym, k -> new double[3]);
            a[0] += v;
            a[1]++;
            if (v > a[2]) {
                a[2] = v;
                peakTimes.put(ym, t);
            }
        }
        double wdMean = 0, weMean = 0;
        for (int h = 0; h < 24; h++) {
            wd[h] = wdN[h] == 0 ? 0 : wd[h] / wdN[h];
            we[h] = weN[h] == 0 ? 0 : we[h] / weN[h];
            wdMean += wd[h] / 24;
            weMean += we[h] / 24;
        }
        List<MonthStats> months = new ArrayList<>();
        double lfSum = 0;
        for (Map.Entry<YearMonth, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            double avg = a[0] / a[1];
            double lf = a[2] == 0 ? 0 : avg / a[2];
            months.add(new MonthStats(e.getKey(), a[0], avg, a[2], peakTimes.get(e.getKey()), lf));
            lfSum += lf;
        }
        int commonHour = 0;
        for (int h = 1; h < 24; h++) {
            if (dailyPeakHourCount[h] > dailyPeakHourCount[commonHour]) {
                commonHour = h;
            }
        }
        double avg = total / s.size();
        return new Summary(total, avg, peak, peakTime, avg / peak, lfSum / months.size(), commonHour,
                wd, we, weMean / wdMean, months);
    }
}
