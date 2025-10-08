package electricitybillgenerator.analytics.agent;

import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.time.LocalDateTime;

/**
 * Scores an extracted tariff against ground truth by pricing every hour of a year with both
 * and comparing, which catches errors in rates, hours, months and period order alike.
 */
public final class ExtractionCheck {

    public record Result(double hourlyRateAccuracy, boolean demandChargeMatches, boolean customerChargeMatches) {
        @Override
        public String toString() {
            return String.format("%.1f%% of hours priced correctly, demand charge %s, customer charge %s",
                    100 * hourlyRateAccuracy, demandChargeMatches ? "ok" : "WRONG", customerChargeMatches ? "ok" : "WRONG");
        }
    }

    private ExtractionCheck() {
    }

    public static Result compare(TariffSpec expected, TariffSpec actual) {
        LocalDateTime t = LocalDateTime.of(2009, 1, 1, 0, 0);
        int hours = 0, ok = 0;
        while (t.getYear() == 2009) {
            if (Math.abs(expected.rateAt(t) - actual.rateAt(t)) < 5e-4) {
                ok++;
            }
            hours++;
            t = t.plusHours(1);
        }
        return new Result((double) ok / hours,
                Math.abs(expected.demandChargePerKw() - actual.demandChargePerKw()) < 1e-6,
                Math.abs(expected.customerChargePerMonth() - actual.customerChargePerMonth()) < 1e-6);
    }
}
