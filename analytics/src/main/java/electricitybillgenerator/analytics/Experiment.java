package electricitybillgenerator.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import electricitybillgenerator.analytics.anomaly.AnomalyDetector;
import electricitybillgenerator.analytics.anomaly.InjectionBenchmark;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.data.HourlyAggregator;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.data.MinuteData;
import electricitybillgenerator.analytics.data.PriceSeries;
import electricitybillgenerator.analytics.data.UciLoader;
import electricitybillgenerator.analytics.dispatch.DispatchResult;
import electricitybillgenerator.analytics.dispatch.ForecastDispatcher;
import electricitybillgenerator.analytics.dispatch.OptimalDispatcher;
import electricitybillgenerator.analytics.dispatch.ThresholdDispatcher;
import electricitybillgenerator.analytics.forecast.ForecastMetrics;
import electricitybillgenerator.analytics.forecast.ForecastSelection;
import electricitybillgenerator.analytics.report.Charts;
import electricitybillgenerator.analytics.report.LoadAnalysis;
import electricitybillgenerator.analytics.report.StrategyResult;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;
import electricitybillgenerator.analytics.tariff.WholesaleTouBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * End-to-end study: clean the meter data, analyse the load, price it on a TOU + demand tariff,
 * then compare battery dispatch strategies on a held-out year (2009).
 */
public final class Experiment {

    public record Config(Path uciFile, Path pricesFile, Path outDir, double demandChargePerKw,
                         double deliveryAdderPerKwh, double customerChargePerMonth, double batteryCostPerKwh) {
    }

    static final LocalDateTime TRAIN_FROM = LocalDateTime.of(2008, 1, 1, 0, 0);
    static final LocalDateTime TEST_FROM = LocalDateTime.of(2009, 1, 1, 0, 0);
    static final LocalDateTime TEST_TO = LocalDateTime.of(2010, 1, 1, 0, 0);

    private final Config cfg;
    private final Map<String, Object> out = new LinkedHashMap<>();

    public Experiment(Config cfg) {
        this.cfg = cfg;
    }

    public Map<String, Object> run() throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path charts = cfg.outDir().resolve("charts");
        Files.createDirectories(charts);

        // 1. Meter data and anomaly detection
        log("Loading %s", cfg.uciFile());
        MinuteData raw = UciLoader.load(cfg.uciFile());
        AnomalyDetector detector = new AnomalyDetector();
        AnomalyDetector.Result flags = detector.detect(raw);
        InjectionBenchmark.Score inj = InjectionBenchmark.run(raw, detector, 200, 7);
        HourlyAggregator.Output agg = HourlyAggregator.aggregate(raw, flags, 30);
        HourlySeries all = agg.series();
        out.put("data", Map.of(
                "minutes", raw.size(),
                "first", raw.start().toString(),
                "last", raw.time(raw.size() - 1).toString(),
                "hours", all.size(),
                "imputedHours", agg.imputedHours()));
        out.put("anomalies", Map.of(
                "minutesByType", flags.minutesByType(),
                "eventsByType", flags.eventsByType(),
                "injectionBenchmark", Map.of(
                        "injectedEvents", inj.injectedEvents(),
                        "eventRecall", inj.eventRecall(),
                        "minuteRecall", inj.minuteRecall(),
                        "minutePrecision", inj.minutePrecision(),
                        "falseAlarmMinutes", inj.falseAlarmMinutes(),
                        "falseAlarmRate", (double) inj.falseAlarmMinutes() / inj.cleanMinutes())));
        log("Anomalies: %s, injection recall %.3f precision %.3f", flags.eventsByType(), inj.eventRecall(),
                inj.minutePrecision());

        // 2. Load analysis
        LoadAnalysis.Summary full = LoadAnalysis.analyse(all);
        HourlySeries test = all.slice(TEST_FROM, TEST_TO);
        HourlySeries trainYear = all.slice(TRAIN_FROM, TEST_FROM);
        LoadAnalysis.Summary testSummary = LoadAnalysis.analyse(test);
        out.put("load", Map.of("full", loadMap(full), "testYear2009", loadMap(testSummary)));
        Charts.dailyProfile(full, charts.resolve("daily_profile.png"));

        // 3. Tariff from ISO-NE wholesale prices
        PriceSeries prices = PriceSeries.readCsv(cfg.pricesFile());
        TariffSpec tariff = tariff(prices, cfg.demandChargePerKw());
        out.put("tariff", tariff);
        List<BillCalculator.MonthlyBill> baseline = BillCalculator.bill(tariff, test);
        double baseTotal = BillCalculator.total(baseline);
        log("Baseline 2009 bill: $%.2f", baseTotal);

        // 4-6. Forecasting, then strategies for the default battery
        BatterySpec battery = BatterySpec.homeDefault();
        log("Selecting forecaster on 2008 (trained on Dec 2006-2007)");
        ForecastSelection.Choice choice = ForecastSelection.select(all, TRAIN_FROM, TEST_FROM, battery, tariff);
        ForecastDispatcher.DayAheadForecast chosen = choice.forecast();
        log("Chosen forecaster: %s; 2008 validation savings %s", choice.name(), choice.validationSavings());

        List<ForecastMetrics> fm = new ArrayList<>();
        List<Map<String, Object>> fmOut = new ArrayList<>();
        Map<String, DispatchResult> forecastRuns = new LinkedHashMap<>();
        for (Map.Entry<String, ForecastDispatcher.DayAheadForecast> e : choice.testForecasts().entrySet()) {
            ForecastMetrics m = ForecastMetrics.evaluate(e.getKey(), test, e.getValue());
            fm.add(m);
            DispatchResult r = new ForecastDispatcher("MPC + " + e.getKey(), e.getValue()).dispatch(test, battery, tariff);
            forecastRuns.put(e.getKey(), r);
            StrategyResult sr = StrategyResult.of(r, tariff, baseline);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", m.name());
            row.put("mape", m.mape());
            row.put("wape", m.wape());
            row.put("maeKw", m.maeKw());
            row.put("rmseKw", m.rmseKw());
            row.put("dailyPeakMape", m.dailyPeakMape());
            row.put("coverage", m.coverage());
            row.put("validationSavings2008", choice.validationSavings().get(e.getKey()));
            row.put("testSavings2009", sr.savings());
            row.put("selected", e.getKey().equals(choice.name()));
            fmOut.add(row);
            log("Forecast %-16s MAPE %.3f WAPE %.3f peakMAPE %.3f coverage %.3f | MPC savings 2008 $%.0f 2009 $%.0f",
                    m.name(), m.mape(), m.wape(), m.dailyPeakMape(), m.coverage(),
                    choice.validationSavings().get(e.getKey()), sr.savings());
        }
        out.put("forecast", Map.of("selected", choice.name(), "models", fmOut));

        Map<String, DispatchResult> runs = new LinkedHashMap<>();
        runs.put("No battery", DispatchResult.none(test));
        runs.put("Threshold rule (tuned on 2008)", ThresholdDispatcher.tune(trainYear, battery, tariff, 40).dispatch(test, battery, tariff));
        runs.put("Forecast MPC (" + choice.name() + ", selected on 2008)", forecastRuns.get(choice.name()));
        runs.put("Forecast MPC (perfect day-ahead forecast)", ForecastDispatcher.perfect(test).dispatch(test, battery, tariff));
        runs.put("Annual LP (perfect foresight)", new OptimalDispatcher().dispatch(test, battery, tariff));

        List<StrategyResult> strategies = new ArrayList<>();
        Map<String, List<Double>> peaks = new LinkedHashMap<>();
        for (Map.Entry<String, DispatchResult> e : runs.entrySet()) {
            StrategyResult r = StrategyResult.of(e.getValue(), tariff, baseline);
            r = new StrategyResult(e.getKey(), r.annualBill(), r.savings(), r.savingsPct(), r.avgMonthlyPeakKw(),
                    r.peakReductionPct(), r.maxPeakKw(), r.demandCost(), r.energyCost(), r.batteryThroughputKwh());
            strategies.add(r);
            log("%-48s bill $%.2f saved $%.2f (%.1f%%) avg peak %.2f kW (-%.1f%%)", e.getKey(), r.annualBill(),
                    r.savings(), r.savingsPct(), r.avgMonthlyPeakKw(), r.peakReductionPct());
            if (!e.getKey().contains("perfect day-ahead")) {
                peaks.put(e.getKey().replaceAll(" \\(.*", ""), BillCalculator.bill(tariff, e.getValue().grid()).stream()
                        .map(BillCalculator.MonthlyBill::peakKw).toList());
            }
        }
        out.put("battery", battery);
        out.put("strategies", strategies);
        double capex = battery.capacityKwh() * cfg.batteryCostPerKwh();
        out.put("payback", paybackMap(capex, strategies));

        // Charts
        List<String> months = baseline.stream().map(b -> b.month().getMonth().toString().substring(0, 3)).toList();
        Charts.monthlyPeaks(months, peaks, charts.resolve("monthly_peaks.png"));
        int peakHour = argMax(test.values());
        int weekStart = Math.max(0, (peakHour / 24 - 3) * 24);
        Charts.dispatchWeek(forecastRuns.get(choice.name()), weekStart,
                "Forecast MPC dispatch around the 2009 annual peak", charts.resolve("dispatch_week.png"));
        Map<String, double[]> fc = new LinkedHashMap<>();
        for (ForecastMetrics m : fm) {
            if (m.name().equals("GBT mean") || m.name().equals("Seasonal naive") || m.name().equals(choice.name())) {
                fc.put(m.name(), m.predictions());
            }
        }
        Charts.forecastWeek(test, fc, weekStart, charts.resolve("forecast_week.png"));

        // Battery size sweep
        log("Battery size sweep");
        double[][] sizes = {{5, 2.5}, {10, 5}, {13.5, 5}, {20, 7}};
        List<Map<String, Object>> sweep = new ArrayList<>();
        List<String> sizeLabels = new ArrayList<>();
        Map<String, List<Double>> sweepSavings = new LinkedHashMap<>();
        for (double[] s : sizes) {
            BatterySpec b = BatterySpec.of(s[0], s[1]);
            String label = fmt(s[0]) + " kWh / " + fmt(s[1]) + " kW";
            sizeLabels.add(label);
            double ruleSav = StrategyResult.of(ThresholdDispatcher.tune(trainYear, b, tariff, 40).dispatch(test, b, tariff), tariff, baseline).savings();
            double mpc = StrategyResult.of(new ForecastDispatcher("mpc", chosen).dispatch(test, b, tariff), tariff, baseline).savings();
            double lp = StrategyResult.of(new OptimalDispatcher().dispatch(test, b, tariff), tariff, baseline).savings();
            double cap = s[0] * cfg.batteryCostPerKwh();
            sweep.add(Map.of("battery", label, "capexUsd", cap, "ruleSavings", ruleSav, "forecastMpcSavings", mpc,
                    "lpSavings", lp, "paybackYearsForecastMpc", payback(cap, mpc), "paybackYearsLp", payback(cap, lp)));
            sweepSavings.computeIfAbsent("Threshold rule", k -> new ArrayList<>()).add(ruleSav);
            sweepSavings.computeIfAbsent("Forecast MPC", k -> new ArrayList<>()).add(mpc);
            sweepSavings.computeIfAbsent("LP perfect foresight", k -> new ArrayList<>()).add(lp);
        }
        out.put("sizeSweep", sweep);
        Charts.sizeSweep(sizeLabels, sweepSavings, charts.resolve("size_sweep.png"));

        // Demand-charge sensitivity
        log("Demand charge sensitivity");
        List<Map<String, Object>> sens = new ArrayList<>();
        for (double dc : new double[]{0, 5, 10, 15, 20, 25}) {
            TariffSpec t = tariff(prices, dc);
            List<BillCalculator.MonthlyBill> base = BillCalculator.bill(t, test);
            StrategyResult mpc = StrategyResult.of(new ForecastDispatcher("mpc", chosen).dispatch(test, battery, t), t, base);
            StrategyResult lp = StrategyResult.of(new OptimalDispatcher().dispatch(test, battery, t), t, base);
            sens.add(Map.of("demandChargePerKw", dc, "baselineBill", BillCalculator.total(base),
                    "forecastMpcSavings", mpc.savings(), "lpSavings", lp.savings(),
                    "forecastMpcPeakReductionPct", mpc.peakReductionPct()));
        }
        out.put("demandChargeSensitivity", sens);
        out.put("assumptions", Map.of(
                "demandChargePerKw", cfg.demandChargePerKw(),
                "deliveryAdderPerKwh", cfg.deliveryAdderPerKwh(),
                "customerChargePerMonth", cfg.customerChargePerMonth(),
                "batteryCostPerKwh", cfg.batteryCostPerKwh(),
                "trainYear", "2008 (rule thresholds); Dec 2006-2008 (forecaster)",
                "testYear", "2009"));

        writeOutputs();
        return out;
    }

    private TariffSpec tariff(PriceSeries prices, double demandCharge) {
        return new WholesaleTouBuilder()
                .demandCharge(demandCharge)
                .deliveryAdder(cfg.deliveryAdderPerKwh())
                .customerCharge(cfg.customerChargePerMonth())
                .build(prices);
    }

    private Map<String, Object> paybackMap(double capex, List<StrategyResult> strategies) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capexUsd", capex);
        for (StrategyResult r : strategies) {
            if (!r.strategy().equalsIgnoreCase("no battery")) {
                m.put(r.strategy(), payback(capex, r.savings()));
            }
        }
        return m;
    }

    static double payback(double capex, double annualSavings) {
        return annualSavings <= 0 ? Double.POSITIVE_INFINITY : capex / annualSavings;
    }

    private static Map<String, Object> loadMap(LoadAnalysis.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalKwh", s.totalKwh());
        m.put("avgKw", s.avgKw());
        m.put("peakKw", s.peakKw());
        m.put("peakTime", s.peakTime().toString());
        m.put("loadFactor", s.loadFactor());
        m.put("avgMonthlyLoadFactor", s.avgMonthlyLoadFactor());
        m.put("mostCommonDailyPeakHour", s.mostCommonPeakHour());
        m.put("weekendToWeekdayRatio", s.weekendToWeekdayRatio());
        List<Map<String, Object>> months = new ArrayList<>();
        for (LoadAnalysis.MonthStats ms : s.months()) {
            months.add(Map.of("month", ms.month().toString(), "kwh", ms.energyKwh(), "peakKw", ms.peakKw(),
                    "peakTime", ms.peakTime().toString(), "loadFactor", ms.loadFactor()));
        }
        m.put("months", months);
        return m;
    }

    private void writeOutputs() throws IOException {
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path json = cfg.outDir().resolve("metrics.json");
        om.writeValue(json.toFile(), out);
        log("Wrote %s", json);
    }

    private static int argMax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((int) x) : String.valueOf(x);
    }

    static void log(String f, Object... args) {
        System.out.println(String.format(Locale.US, f, args));
    }
}
