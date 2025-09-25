package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.battery.Battery;
import electricitybillgenerator.analytics.battery.BatterySpec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatteryTest {

    private final BatterySpec spec = new BatterySpec(10, 4, 0.81, 0.1, 0.9, 0.5);

    @Test
    void chargeIsLimitedByPowerAndApplyEfficiency() {
        Battery b = new Battery(spec);
        double drawn = b.charge(100);
        assertEquals(4, drawn, 1e-9, "power limit");
        assertEquals(5 + 4 * 0.9, b.socKwh(), 1e-9, "sqrt(0.81) = 0.9 one-way efficiency");
    }

    @Test
    void chargeStopsAtMaxSoc() {
        Battery b = new Battery(spec);
        for (int i = 0; i < 10; i++) {
            b.charge(4);
        }
        assertEquals(9, b.socKwh(), 1e-9);
        assertEquals(0, b.charge(4), 1e-9);
    }

    @Test
    void dischargeStopsAtMinSocAndPaysLosses() {
        Battery b = new Battery(spec, 9);
        double delivered = b.discharge(100);
        assertEquals(4, delivered, 1e-9, "power limit");
        assertEquals(9 - 4 / 0.9, b.socKwh(), 1e-9, "stored energy pays the discharge loss");
        double rest = b.discharge(100);
        assertEquals((9 - 4 / 0.9 - 1) * 0.9, rest, 1e-9, "only energy above min SoC is usable");
        assertEquals(1, b.socKwh(), 1e-9);
        assertEquals(0, b.discharge(1), 1e-9);
    }

    @Test
    void roundTripLosesTheConfiguredFraction() {
        Battery b = new Battery(spec, 1);
        double in = b.charge(4) + b.charge(4);
        double out = b.discharge(4) + b.discharge(4) + b.discharge(4);
        assertEquals(0.81, out / in, 1e-9);
    }

    @Test
    void randomRequestsNeverBreakLimits() {
        Battery b = new Battery(spec);
        Random rnd = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            double before = b.socKwh();
            double req = rnd.nextDouble() * 10;
            if (rnd.nextBoolean()) {
                double got = b.charge(req);
                assertTrue(got >= 0 && got <= spec.powerKw() + 1e-12);
                assertEquals(before + got * spec.chargeEfficiency(), b.socKwh(), 1e-9);
            } else {
                double got = b.discharge(req);
                assertTrue(got >= 0 && got <= spec.powerKw() + 1e-12);
                assertEquals(before - got / spec.dischargeEfficiency(), b.socKwh(), 1e-9);
            }
            assertTrue(b.socKwh() >= spec.minSocKwh() - 1e-9 && b.socKwh() <= spec.maxSocKwh() + 1e-9);
        }
    }

    @Test
    void invalidSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BatterySpec(0, 1, 0.9, 0, 1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new BatterySpec(10, 1, 1.2, 0, 1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new BatterySpec(10, 1, 0.9, 0.6, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new BatterySpec(10, 1, 0.9, 0.2, 0.9, 0.1));
    }
}
