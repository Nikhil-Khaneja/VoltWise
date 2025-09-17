package electricitybillgenerator.analytics.anomaly;

import electricitybillgenerator.analytics.data.MinuteData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Flags bad meter readings before they reach billing. Rules are deliberately simple and
 * explainable, because a billing dispute needs a reason, not a score.
 */
public final class AnomalyDetector {

    /** Minimum run of identical readings to call a flatline. */
    private final int flatlineMinutes;
    /** A reading must exceed both neighbours by this much to be an isolated spike. */
    private final double spikeJumpKw;
    /** Hard ceiling; the UCI home has roughly a 12 kVA service. */
    private final double serviceLimitKw;
    /** Nominal 230 V +/- 10 %. */
    private final double minVolt;
    private final double maxVolt;

    public AnomalyDetector() {
        this(180, 4.0, 12.0, 207.0, 253.0);
    }

    public AnomalyDetector(int flatlineMinutes, double spikeJumpKw, double serviceLimitKw, double minVolt, double maxVolt) {
        this.flatlineMinutes = flatlineMinutes;
        this.spikeJumpKw = spikeJumpKw;
        this.serviceLimitKw = serviceLimitKw;
        this.minVolt = minVolt;
        this.maxVolt = maxVolt;
    }

    public Result detect(MinuteData d) {
        int n = d.size();
        Anomaly.Type[] flag = new Anomaly.Type[n];
        float[] kw = d.activeKw();

        for (int i = 0; i < n; i++) {
            if (Float.isNaN(kw[i])) {
                flag[i] = Anomaly.Type.MISSING;
            }
        }

        // Flatlines: identical readings for a long run. Near-zero runs (house empty) are normal.
        int runStart = 0;
        for (int i = 1; i <= n; i++) {
            boolean same = i < n && !Float.isNaN(kw[i]) && kw[i] == kw[runStart];
            if (!same) {
                if (i - runStart >= flatlineMinutes && !Float.isNaN(kw[runStart]) && kw[runStart] > 0.1f) {
                    for (int j = runStart; j < i; j++) {
                        flag[j] = Anomaly.Type.FLATLINE;
                    }
                }
                runStart = i;
            }
        }

        for (int i = 0; i < n; i++) {
            if (flag[i] != null) {
                continue;
            }
            double p = kw[i];
            if (p > serviceLimitKw || isIsolatedSpike(kw, i)) {
                flag[i] = Anomaly.Type.SPIKE;
                continue;
            }
            double v = d.voltage()[i];
            double amps = d.intensity()[i];
            if (!Double.isNaN(v) && (v < minVolt || v > maxVolt)) {
                flag[i] = Anomaly.Type.PHYSICS;
                continue;
            }
            // Active power cannot exceed apparent power S = V * I. Intensity is reported in 0.2 A
            // steps, so allow one step of rounding plus a small absolute margin.
            if (!Double.isNaN(v) && !Double.isNaN(amps)) {
                double apparentKva = v * (amps + 0.2) / 1000.0;
                if (p > apparentKva * 1.02 + 0.05) {
                    flag[i] = Anomaly.Type.PHYSICS;
                }
            }
        }
        return new Result(flag, toRuns(flag));
    }

    private boolean isIsolatedSpike(float[] kw, int i) {
        if (i == 0 || i == kw.length - 1) {
            return false;
        }
        float prev = kw[i - 1];
        float next = kw[i + 1];
        if (Float.isNaN(prev) || Float.isNaN(next)) {
            return false;
        }
        return kw[i] - Math.max(prev, next) > spikeJumpKw;
    }

    private static List<Anomaly> toRuns(Anomaly.Type[] flag) {
        List<Anomaly> runs = new ArrayList<>();
        int i = 0;
        while (i < flag.length) {
            if (flag[i] == null) {
                i++;
                continue;
            }
            int j = i;
            while (j < flag.length && flag[j] == flag[i]) {
                j++;
            }
            runs.add(new Anomaly(flag[i], i, j));
            i = j;
        }
        return runs;
    }

    /** Per-minute flags (null = good) plus the same information grouped into runs. */
    public record Result(Anomaly.Type[] flags, List<Anomaly> runs) {

        public boolean isBad(int i) {
            return flags[i] != null;
        }

        public Map<Anomaly.Type, Long> minutesByType() {
            Map<Anomaly.Type, Long> m = new EnumMap<>(Anomaly.Type.class);
            for (Anomaly.Type t : Anomaly.Type.values()) {
                m.put(t, 0L);
            }
            for (Anomaly a : runs) {
                m.merge(a.type(), (long) a.length(), Long::sum);
            }
            return m;
        }

        public Map<Anomaly.Type, Long> eventsByType() {
            Map<Anomaly.Type, Long> m = new EnumMap<>(Anomaly.Type.class);
            for (Anomaly.Type t : Anomaly.Type.values()) {
                m.put(t, 0L);
            }
            for (Anomaly a : runs) {
                m.merge(a.type(), 1L, Long::sum);
            }
            return m;
        }
    }
}
