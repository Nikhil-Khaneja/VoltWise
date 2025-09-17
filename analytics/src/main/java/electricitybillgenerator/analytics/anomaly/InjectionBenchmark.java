package electricitybillgenerator.analytics.anomaly;

import electricitybillgenerator.analytics.data.MinuteData;

import java.util.Random;

/**
 * Measures detector precision/recall by injecting synthetic faults into readings the detector
 * already considers clean, then checking which injected minutes get flagged.
 */
public final class InjectionBenchmark {

    public record Score(int injectedEvents, int detectedEvents, long injectedMinutes,
                        long flaggedInjectedMinutes, long falseAlarmMinutes, long cleanMinutes) {

        /** Fraction of injected events with at least one flagged minute. */
        public double eventRecall() {
            return injectedEvents == 0 ? 0 : (double) detectedEvents / injectedEvents;
        }

        /** Of the minutes newly flagged after injection, the share that really were injected. */
        public double minutePrecision() {
            long flagged = flaggedInjectedMinutes + falseAlarmMinutes;
            return flagged == 0 ? 0 : (double) flaggedInjectedMinutes / flagged;
        }

        public double minuteRecall() {
            return injectedMinutes == 0 ? 0 : (double) flaggedInjectedMinutes / injectedMinutes;
        }
    }

    private InjectionBenchmark() {
    }

    public static Score run(MinuteData original, AnomalyDetector detector, int eventsPerType, long seed) {
        AnomalyDetector.Result before = detector.detect(original);
        MinuteData d = original.copy();
        boolean[] injected = new boolean[d.size()];
        int[][] events = new int[eventsPerType * 3][];
        Random rnd = new Random(seed);
        int e = 0;

        for (int k = 0; k < eventsPerType; k++) {
            // Spike: a single minute jumps by 1-9 kW (the detector threshold is 4 kW, so small ones are missed).
            int i = cleanStart(before, d, rnd, 3);
            d.activeKw()[i + 1] += 1 + 8 * rnd.nextFloat();
            mark(injected, i + 1, i + 2);
            events[e++] = new int[]{i + 1, i + 2};

            // Flatline: register freezes at the current value for 1-8 hours.
            int len = 60 + rnd.nextInt(420);
            i = cleanStart(before, d, rnd, len);
            float stuck = Math.max(0.3f, d.activeKw()[i]);
            for (int j = i; j < i + len; j++) {
                d.activeKw()[j] = stuck;
            }
            mark(injected, i, i + len);
            events[e++] = new int[]{i, i + len};

            // Calibration fault: reported power scaled 1.05-3x for 10-60 minutes.
            len = 10 + rnd.nextInt(50);
            i = cleanStart(before, d, rnd, len);
            float factor = 1.05f + 1.95f * rnd.nextFloat();
            for (int j = i; j < i + len; j++) {
                d.activeKw()[j] = d.activeKw()[j] * factor;
            }
            mark(injected, i, i + len);
            events[e++] = new int[]{i, i + len};
        }

        AnomalyDetector.Result after = detector.detect(d);
        long injMin = 0, hit = 0, falseAlarm = 0, clean = 0;
        for (int i = 0; i < d.size(); i++) {
            boolean newlyFlagged = after.isBad(i) && !before.isBad(i);
            if (injected[i]) {
                injMin++;
                if (newlyFlagged) {
                    hit++;
                }
            } else if (!before.isBad(i)) {
                clean++;
                if (newlyFlagged) {
                    falseAlarm++;
                }
            }
        }
        int detected = 0;
        for (int[] ev : events) {
            for (int j = ev[0]; j < ev[1]; j++) {
                if (after.isBad(j) && !before.isBad(j)) {
                    detected++;
                    break;
                }
            }
        }
        return new Score(events.length, detected, injMin, hit, falseAlarm, clean);
    }

    private static int cleanStart(AnomalyDetector.Result r, MinuteData d, Random rnd, int len) {
        while (true) {
            int i = 1 + rnd.nextInt(d.size() - len - 3);
            boolean ok = true;
            for (int j = i - 1; j < i + len + 2 && ok; j++) {
                ok = !r.isBad(j);
            }
            if (ok) {
                return i;
            }
        }
    }

    private static void mark(boolean[] m, int a, int b) {
        for (int i = a; i < b; i++) {
            m[i] = true;
        }
    }
}
