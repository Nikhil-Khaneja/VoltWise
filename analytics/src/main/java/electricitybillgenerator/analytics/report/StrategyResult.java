package electricitybillgenerator.analytics.report;

import electricitybillgenerator.analytics.dispatch.DispatchResult;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.util.List;

/** Bill outcome of one dispatch strategy compared with the no-battery baseline. */
public record StrategyResult(String strategy, double annualBill, double savings, double savingsPct,
                             double avgMonthlyPeakKw, double peakReductionPct, double maxPeakKw,
                             double demandCost, double energyCost, double batteryThroughputKwh) {

    public static StrategyResult of(DispatchResult r, TariffSpec tariff, List<BillCalculator.MonthlyBill> baseline) {
        List<BillCalculator.MonthlyBill> bills = BillCalculator.bill(tariff, r.grid());
        double total = BillCalculator.total(bills);
        double base = BillCalculator.total(baseline);
        double avgPeak = bills.stream().mapToDouble(BillCalculator.MonthlyBill::peakKw).average().orElse(0);
        double basePeak = baseline.stream().mapToDouble(BillCalculator.MonthlyBill::peakKw).average().orElse(0);
        double maxPeak = bills.stream().mapToDouble(BillCalculator.MonthlyBill::peakKw).max().orElse(0);
        double throughput = 0;
        for (double d : r.discharge()) {
            throughput += d;
        }
        return new StrategyResult(r.strategy(), total, base - total, 100 * (base - total) / base, avgPeak,
                100 * (basePeak - avgPeak) / basePeak, maxPeak,
                bills.stream().mapToDouble(BillCalculator.MonthlyBill::demandCost).sum(),
                bills.stream().mapToDouble(BillCalculator.MonthlyBill::energyCost).sum(), throughput);
    }
}
