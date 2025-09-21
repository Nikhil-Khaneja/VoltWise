package electricitybillgenerator.analytics.tariff;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A time-of-use tariff with a monthly demand charge. Periods are matched in order; the first
 * period covering an hour sets its price, and {@code defaultEnergyRatePerKwh} covers the rest.
 */
public record TariffSpec(
        @JsonPropertyDescription("Tariff or rate schedule name")
        String name,
        @JsonPropertyDescription("Fixed customer charge in dollars per month")
        double customerChargePerMonth,
        @JsonPropertyDescription("Demand charge in dollars per kW of the month's highest hourly demand")
        double demandChargePerKw,
        @JsonPropertyDescription("Energy price in dollars per kWh for hours not covered by any period")
        double defaultEnergyRatePerKwh,
        @JsonPropertyDescription("Time-of-use periods, most specific first")
        List<TouPeriod> periods) {

    public double rateAt(LocalDateTime t) {
        TouPeriod p = periodAt(t);
        return p == null ? defaultEnergyRatePerKwh : p.energyRatePerKwh();
    }

    public String periodNameAt(LocalDateTime t) {
        TouPeriod p = periodAt(t);
        return p == null ? "Default" : p.name();
    }

    public TouPeriod periodAt(LocalDateTime t) {
        boolean weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
        for (TouPeriod p : periods) {
            if (p.months().contains(t.getMonthValue())
                    && (!p.weekdaysOnly() || !weekend)
                    && p.hours().contains(t.getHour())) {
                return p;
            }
        }
        return null;
    }

    /** True when t is in the cheapest energy price that applies on its day. */
    public boolean isCheapestHour(LocalDateTime t) {
        double min = Double.MAX_VALUE;
        LocalDateTime day = t.toLocalDate().atStartOfDay();
        for (int h = 0; h < 24; h++) {
            min = Math.min(min, rateAt(day.plusHours(h)));
        }
        return rateAt(t) <= min + 1e-9;
    }

    /** Problems that make the tariff unusable, e.g. after LLM extraction. Empty means valid. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (customerChargePerMonth < 0 || customerChargePerMonth > 1000) {
            errors.add("customer charge out of range: " + customerChargePerMonth);
        }
        if (demandChargePerKw < 0 || demandChargePerKw > 100) {
            errors.add("demand charge out of range: " + demandChargePerKw);
        }
        if (defaultEnergyRatePerKwh < 0 || defaultEnergyRatePerKwh > 2) {
            errors.add("default energy rate out of range: " + defaultEnergyRatePerKwh);
        }
        for (TouPeriod p : periods) {
            if (p.energyRatePerKwh() < 0 || p.energyRatePerKwh() > 2) {
                errors.add(p.name() + ": energy rate out of range " + p.energyRatePerKwh());
            }
            if (p.months().isEmpty() || p.months().stream().anyMatch(m -> m < 1 || m > 12)) {
                errors.add(p.name() + ": bad months " + p.months());
            }
            if (p.hours().isEmpty() || p.hours().stream().anyMatch(h -> h < 0 || h > 23)) {
                errors.add(p.name() + ": bad hours " + p.hours());
            }
        }
        return errors;
    }
}
