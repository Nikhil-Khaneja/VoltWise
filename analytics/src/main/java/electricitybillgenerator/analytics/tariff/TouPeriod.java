package electricitybillgenerator.analytics.tariff;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** One time-of-use energy price window. */
public record TouPeriod(
        @JsonPropertyDescription("Period name as written in the tariff, e.g. 'Summer On-Peak'")
        String name,
        @JsonPropertyDescription("Energy price in dollars per kWh for this period (all per-kWh charges combined)")
        double energyRatePerKwh,
        @JsonPropertyDescription("Calendar months (1-12) when the period applies")
        List<Integer> months,
        @JsonPropertyDescription("True if the period applies Monday-Friday only")
        boolean weekdaysOnly,
        @JsonPropertyDescription("Hour-starting values (0-23) covered by the period, e.g. 14 means 14:00-15:00")
        List<Integer> hours) {
}
