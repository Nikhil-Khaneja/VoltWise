package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.tariff.TariffSpec;
import electricitybillgenerator.analytics.tariff.TouPeriod;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/** Small synthetic inputs shared by tests. */
public final class Fixtures {

    private Fixtures() {
    }

    /** Off-peak 0.10, weekday 16-20 on-peak 0.30, $10/kW demand, $5/month. */
    public static TariffSpec simpleTou() {
        List<Integer> allMonths = IntStream.rangeClosed(1, 12).boxed().toList();
        return new TariffSpec("test", 5, 10, 0.10, List.of(
                new TouPeriod("On-Peak", 0.30, allMonths, true, List.of(16, 17, 18, 19, 20))));
    }

    /** Household-like load: 0.5 kW base, an evening bump, and random spikes. */
    public static HourlySeries household(LocalDateTime start, int days, long seed) {
        Random rnd = new Random(seed);
        double[] v = new double[days * 24];
        for (int i = 0; i < v.length; i++) {
            int h = i % 24;
            double evening = h >= 17 && h <= 21 ? 1.5 : 0;
            double morning = h >= 7 && h <= 8 ? 0.8 : 0;
            double spike = rnd.nextDouble() < 0.02 ? 2 + 3 * rnd.nextDouble() : 0;
            v[i] = 0.5 + evening + morning + spike + 0.2 * rnd.nextDouble();
        }
        return new HourlySeries(start, v);
    }
}
