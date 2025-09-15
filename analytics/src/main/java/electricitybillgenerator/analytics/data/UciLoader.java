package electricitybillgenerator.analytics.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * Reads the UCI "Individual Household Electric Power Consumption" file
 * (semicolon separated, '?' for missing values) into a gap-free minute grid.
 */
public final class UciLoader {

    private UciLoader() {
    }

    public static MinuteData load(Path file) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            String header = in.readLine();
            if (header == null || !header.startsWith("Date;Time;Global_active_power")) {
                throw new IOException("Not a UCI household power file: " + file);
            }
            LocalDateTime start = null;
            long startEpochMin = 0;
            int capacity = 2_200_000;
            float[] kw = filledNaN(capacity);
            float[] volt = filledNaN(capacity);
            float[] amp = filledNaN(capacity);
            int maxIdx = -1;

            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split(";", -1);
                if (f.length < 6) {
                    continue;
                }
                LocalDateTime t = parseTimestamp(f[0], f[1]);
                long epochMin = t.toEpochSecond(ZoneOffset.UTC) / 60;
                if (start == null) {
                    start = t;
                    startEpochMin = epochMin;
                }
                int idx = (int) (epochMin - startEpochMin);
                if (idx < 0) {
                    continue;
                }
                if (idx >= kw.length) {
                    int n = Math.max(idx + 1, kw.length * 2);
                    kw = grow(kw, n);
                    volt = grow(volt, n);
                    amp = grow(amp, n);
                }
                kw[idx] = parse(f[2]);
                volt[idx] = parse(f[4]);
                amp[idx] = parse(f[5]);
                maxIdx = Math.max(maxIdx, idx);
            }
            if (start == null) {
                throw new IOException("No data rows in " + file);
            }
            int n = maxIdx + 1;
            return new MinuteData(start, Arrays.copyOf(kw, n), Arrays.copyOf(volt, n), Arrays.copyOf(amp, n));
        }
    }

    static LocalDateTime parseTimestamp(String date, String time) {
        // date is d/M/yyyy, time is HH:mm:ss
        int s1 = date.indexOf('/');
        int s2 = date.indexOf('/', s1 + 1);
        int day = Integer.parseInt(date, 0, s1, 10);
        int month = Integer.parseInt(date, s1 + 1, s2, 10);
        int year = Integer.parseInt(date, s2 + 1, date.length(), 10);
        int hour = Integer.parseInt(time, 0, 2, 10);
        int minute = Integer.parseInt(time, 3, 5, 10);
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    private static float parse(String s) {
        if (s.isEmpty() || s.charAt(0) == '?') {
            return Float.NaN;
        }
        return Float.parseFloat(s);
    }

    private static float[] filledNaN(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    private static float[] grow(float[] a, int n) {
        float[] b = Arrays.copyOf(a, n);
        Arrays.fill(b, a.length, n, Float.NaN);
        return b;
    }
}
