package electricitybillgenerator.analytics.tariff;

import electricitybillgenerator.analytics.data.HourlySeries;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Applies a {@link TariffSpec} to an hourly grid-import series, one bill per calendar month. */
public final class BillCalculator {

    public record MonthlyBill(YearMonth month, double energyKwh, Map<String, Double> kwhByPeriod,
                              double energyCost, double peakKw, LocalDateTime peakTime,
                              double demandCost, double customerCharge) {
        public double total() {
            return energyCost + demandCost + customerCharge;
        }
    }

    private BillCalculator() {
    }

    public static List<MonthlyBill> bill(TariffSpec tariff, HourlySeries gridKw) {
        Map<YearMonth, Acc> byMonth = new TreeMap<>();
        for (int i = 0; i < gridKw.size(); i++) {
            LocalDateTime t = gridKw.time(i);
            double kwh = gridKw.get(i);
            Acc a = byMonth.computeIfAbsent(YearMonth.from(t), m -> new Acc());
            a.kwh += kwh;
            a.cost += kwh * tariff.rateAt(t);
            a.byPeriod.merge(tariff.periodNameAt(t), kwh, Double::sum);
            if (kwh > a.peak) {
                a.peak = kwh;
                a.peakTime = t;
            }
        }
        List<MonthlyBill> bills = new ArrayList<>();
        byMonth.forEach((m, a) -> bills.add(new MonthlyBill(m, a.kwh, a.byPeriod, a.cost, a.peak, a.peakTime,
                a.peak * tariff.demandChargePerKw(), tariff.customerChargePerMonth())));
        return bills;
    }

    public static double total(List<MonthlyBill> bills) {
        return bills.stream().mapToDouble(MonthlyBill::total).sum();
    }

    private static final class Acc {
        double kwh;
        double cost;
        double peak = -1;
        LocalDateTime peakTime;
        final Map<String, Double> byPeriod = new LinkedHashMap<>();
    }
}
