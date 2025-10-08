package electricitybillgenerator.analytics.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.DispatchResult;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;
import electricitybillgenerator.analytics.dispatch.OptimalDispatcher;
import electricitybillgenerator.analytics.dispatch.ThresholdDispatcher;
import electricitybillgenerator.analytics.report.LoadAnalysis;
import electricitybillgenerator.analytics.report.StrategyResult;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tools the agent can call. The SDK's tool runner instantiates each class from Claude's JSON
 * input and calls {@link Supplier#get()}, so shared state lives in {@link Context}.
 */
public final class AgentTools {

    /** Simulation inputs shared by all tool calls in one agent run. */
    public record Context(HourlySeries test, HourlySeries trainYear, TariffSpec tariff,
                          ForecastDispatcher.DayAheadForecast forecast, double batteryCostPerKwh) {
    }

    static volatile Context context;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentTools() {
    }

    @JsonClassDescription("Summarise the customer's hourly load for the simulation year (2009): energy, "
            + "average and peak kW, load factor, typical peak hour, and the baseline bill under the extracted tariff.")
    public static final class DescribeLoad implements Supplier<String> {
        @Override
        public String get() {
            Context c = context;
            LoadAnalysis.Summary s = LoadAnalysis.analyse(c.test());
            var bills = BillCalculator.bill(c.tariff(), c.test());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("annualKwh", round(s.totalKwh()));
            m.put("avgKw", round(s.avgKw()));
            m.put("peakKw", round(s.peakKw()));
            m.put("loadFactor", round(s.loadFactor()));
            m.put("mostCommonDailyPeakHour", s.mostCommonPeakHour());
            m.put("weekendToWeekdayRatio", round(s.weekendToWeekdayRatio()));
            m.put("baselineAnnualBill", round(BillCalculator.total(bills)));
            m.put("baselineDemandCharges", round(bills.stream().mapToDouble(BillCalculator.MonthlyBill::demandCost).sum()));
            m.put("baselineEnergyCharges", round(bills.stream().mapToDouble(BillCalculator.MonthlyBill::energyCost).sum()));
            return write(m);
        }
    }

    @JsonClassDescription("Simulate a battery for the year under the extracted tariff and return the bill, "
            + "savings versus no battery, peak reduction, capital cost and simple payback.")
    public static final class SimulateBattery implements Supplier<String> {
        @JsonPropertyDescription("Usable battery capacity in kWh, between 1 and 100")
        public double capacityKwh;

        @JsonPropertyDescription("Maximum charge/discharge power in kW, between 0.5 and 50")
        public double powerKw;

        @JsonPropertyDescription("Dispatch strategy: 'rule' (threshold rule tuned on 2008), "
                + "'forecast' (day-ahead forecast + LP, deployable), or 'optimal' (perfect-foresight LP, an upper bound)")
        public String strategy;

        @Override
        public String get() {
            if (!(capacityKwh >= 1 && capacityKwh <= 100 && powerKw >= 0.5 && powerKw <= 50)) {
                return write(Map.of("error", "capacityKwh must be 1-100 and powerKw 0.5-50"));
            }
            Context c = context;
            BatterySpec b = BatterySpec.of(capacityKwh, powerKw);
            DispatchResult r = switch (strategy == null ? "" : strategy) {
                case "rule" -> ThresholdDispatcher.tune(c.trainYear(), b, c.tariff(), 40).dispatch(c.test(), b, c.tariff());
                case "forecast" -> new ForecastDispatcher("forecast", c.forecast()).dispatch(c.test(), b, c.tariff());
                case "optimal" -> new OptimalDispatcher().dispatch(c.test(), b, c.tariff());
                default -> null;
            };
            if (r == null) {
                return write(Map.of("error", "strategy must be 'rule', 'forecast' or 'optimal'"));
            }
            StrategyResult s = StrategyResult.of(r, c.tariff(), BillCalculator.bill(c.tariff(), c.test()));
            double capex = capacityKwh * c.batteryCostPerKwh();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy", strategy);
            m.put("annualBill", round(s.annualBill()));
            m.put("annualSavings", round(s.savings()));
            m.put("savingsPct", round(s.savingsPct()));
            m.put("avgMonthlyPeakKw", round(s.avgMonthlyPeakKw()));
            m.put("peakReductionPct", round(s.peakReductionPct()));
            m.put("capexUsd", round(capex));
            m.put("simplePaybackYears", s.savings() > 0 ? round(capex / s.savings()) : "never");
            return write(m);
        }
    }

    private static double round(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
