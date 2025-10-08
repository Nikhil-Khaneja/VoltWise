package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.agent.ExtractionCheck;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.data.PriceSeries;
import electricitybillgenerator.analytics.tariff.BillCalculator;
import electricitybillgenerator.analytics.tariff.TariffSpec;
import electricitybillgenerator.analytics.tariff.TouPeriod;
import electricitybillgenerator.analytics.tariff.WholesaleTouBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TariffTest {

    private final TariffSpec tariff = Fixtures.simpleTou();

    @Test
    void ratesFollowPeriodsAndWeekends() {
        LocalDateTime friday = LocalDateTime.of(2009, 1, 2, 17, 0);
        LocalDateTime saturday = friday.plusDays(1);
        assertEquals(0.30, tariff.rateAt(friday));
        assertEquals(0.10, tariff.rateAt(saturday));
        assertEquals(0.10, tariff.rateAt(friday.withHour(21)));
        assertTrue(tariff.isCheapestHour(friday.withHour(3)));
        assertFalse(tariff.isCheapestHour(friday));
    }

    @Test
    void firstMatchingPeriodWins() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        TariffSpec t = new TariffSpec("order", 0, 0, 0.05, List.of(
                new TouPeriod("narrow", 0.50, all, false, List.of(18)),
                new TouPeriod("wide", 0.20, all, false, List.of(17, 18, 19))));
        assertEquals(0.50, t.rateAt(LocalDateTime.of(2009, 3, 1, 18, 0)));
        assertEquals(0.20, t.rateAt(LocalDateTime.of(2009, 3, 1, 17, 0)));
    }

    @Test
    void billMatchesHandCalculation() {
        // Friday 2 Jan 2009: 2 kW for 24 h, plus 3 kW extra at 17:00 (on-peak).
        double[] v = new double[24];
        Arrays.fill(v, 2.0);
        v[17] = 5.0;
        HourlySeries s = new HourlySeries(LocalDateTime.of(2009, 1, 2, 0, 0), v);
        BillCalculator.MonthlyBill b = BillCalculator.bill(tariff, s).get(0);
        double onPeakKwh = 2 * 4 + 5;   // hours 16,18,19,20 at 2 kW plus hour 17 at 5 kW
        double offPeakKwh = 2 * 19;
        assertEquals(onPeakKwh + offPeakKwh, b.energyKwh(), 1e-9);
        assertEquals(onPeakKwh * 0.30 + offPeakKwh * 0.10, b.energyCost(), 1e-9);
        assertEquals(5.0, b.peakKw(), 1e-9);
        assertEquals(50.0, b.demandCost(), 1e-9);
        assertEquals(5.0, b.customerCharge(), 1e-9);
        assertEquals(onPeakKwh * 0.30 + offPeakKwh * 0.10 + 55, b.total(), 1e-9);
    }

    @Test
    void billsSplitByCalendarMonth() {
        double[] v = new double[48];
        Arrays.fill(v, 1.0);
        HourlySeries s = new HourlySeries(LocalDateTime.of(2009, 1, 31, 0, 0), v);
        List<BillCalculator.MonthlyBill> bills = BillCalculator.bill(tariff, s);
        assertEquals(2, bills.size());
        assertEquals(24, bills.get(0).energyKwh(), 1e-9);
        assertEquals(24, bills.get(1).energyKwh(), 1e-9);
    }

    @Test
    void wholesaleBuilderPutsOnPeakOverTheMostExpensiveWindow() {
        List<LocalDateTime> times = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2024, 1, 1); d.getYear() == 2024; d = d.plusDays(1)) {
            for (int h = 0; h < 24; h++) {
                times.add(d.atTime(h, 0));
                prices.add(h >= 17 && h <= 21 ? 120.0 : h >= 12 ? 60.0 : 30.0);
            }
        }
        TariffSpec t = new WholesaleTouBuilder().deliveryAdder(0.1).build(new PriceSeries("test", times, prices));
        assertTrue(t.validate().isEmpty(), t.validate().toString());
        TouPeriod winterOn = t.periods().stream().filter(p -> p.name().equals("Winter On-Peak")).findFirst().orElseThrow();
        assertEquals(List.of(17, 18, 19, 20, 21), winterOn.hours());
        assertEquals(0.22, winterOn.energyRatePerKwh(), 1e-9);
        assertEquals(15.0, t.demandChargePerKw());
    }

    @Test
    void validationCatchesImplausibleExtraction() {
        TariffSpec bad = new TariffSpec("bad", 10, 15, 0.1, List.of(
                new TouPeriod("cents not dollars", 18.3, List.of(1), true, List.of(17)),
                new TouPeriod("bad hour", 0.2, List.of(13), true, List.of(24))));
        List<String> errors = bad.validate();
        assertEquals(3, errors.size(), errors.toString());
    }

    @Test
    void extractionCheckScoresIdenticalTariffsPerfectly() {
        ExtractionCheck.Result r = ExtractionCheck.compare(tariff, tariff);
        assertEquals(1.0, r.hourlyRateAccuracy());
        assertTrue(r.demandChargeMatches() && r.customerChargeMatches());

        TariffSpec wrongHours = new TariffSpec("x", 5, 10, 0.10, List.of(new TouPeriod("On-Peak", 0.30,
                tariff.periods().get(0).months(), true, List.of(15, 16, 17, 18, 19))));
        assertTrue(ExtractionCheck.compare(tariff, wrongHours).hourlyRateAccuracy() < 1.0);
    }
}
