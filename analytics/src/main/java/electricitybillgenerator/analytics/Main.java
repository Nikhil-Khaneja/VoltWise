package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.data.IsoNePriceFetcher;
import electricitybillgenerator.analytics.data.PriceSeries;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Main {

    private static final String USAGE = """
            usage: java -jar target/peakshave.jar <command> [--option value ...]

              fetch-prices   Download ISO-NE day-ahead hourly LMPs for one year
                             --year 2024  --location .Z.NEMASSBOST
              run            Run the full study and write results/metrics.json + charts
                             --uci data/raw/household_power_consumption.txt
                             --prices data/isone_da_lmp_2024.csv  --out results
                             --demand-charge 15  --delivery-adder 0.12  --customer-charge 10
                             --battery-cost 1000
              agent          Extract a tariff from a PDF with Claude, then let it run what-if simulations
                             --pdf tariff.pdf  [--uci ... --prices ... --out results]
              sample-tariff  Write the derived tariff as a PDF (test input for the agent)
                             --prices data/isone_da_lmp_2024.csv  --out results/sample_tariff.pdf
            """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            return;
        }
        Map<String, String> opt = options(args);
        switch (args[0]) {
            case "fetch-prices" -> {
                int year = Integer.parseInt(opt.getOrDefault("year", "2024"));
                String location = opt.getOrDefault("location", ".Z.NEMASSBOST");
                PriceSeries prices = new IsoNePriceFetcher(location).fetchYear(year, 6);
                Path out = Path.of("data", "isone_da_lmp_" + year + ".csv");
                prices.writeCsv(out);
                System.out.printf("Wrote %d hourly prices for %s to %s%n", prices.size(), location, out);
            }
            case "run" -> new Experiment(config(opt)).run();
            case "agent" -> electricitybillgenerator.analytics.agent.TariffAgent.main(config(opt), Path.of(required(opt, "pdf")));
            case "sample-tariff" -> electricitybillgenerator.analytics.agent.SampleTariffPdf.write(
                    config(opt), Path.of(opt.getOrDefault("out", "results/sample_tariff.pdf")));
            default -> System.out.print(USAGE);
        }
    }

    static Experiment.Config config(Map<String, String> opt) {
        return new Experiment.Config(
                Path.of(opt.getOrDefault("uci", "data/raw/household_power_consumption.txt")),
                Path.of(opt.getOrDefault("prices", "data/isone_da_lmp_2024.csv")),
                Path.of(opt.getOrDefault("out", "results")),
                Double.parseDouble(opt.getOrDefault("demand-charge", "15")),
                Double.parseDouble(opt.getOrDefault("delivery-adder", "0.12")),
                Double.parseDouble(opt.getOrDefault("customer-charge", "10")),
                Double.parseDouble(opt.getOrDefault("battery-cost", "1000")));
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option, got " + args[i]);
            }
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private static String required(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
    }
}
