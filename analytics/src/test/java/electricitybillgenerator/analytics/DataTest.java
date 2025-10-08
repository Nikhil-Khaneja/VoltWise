package electricitybillgenerator.analytics;

import electricitybillgenerator.analytics.anomaly.Anomaly;
import electricitybillgenerator.analytics.anomaly.AnomalyDetector;
import electricitybillgenerator.analytics.anomaly.InjectionBenchmark;
import electricitybillgenerator.analytics.data.HourlyAggregator;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.data.MinuteData;
import electricitybillgenerator.analytics.data.UciLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataTest {

    /** Two days of plausible minute data: 230 V, intensity consistent with power. */
    private static MinuteData cleanMinutes(int minutes, long seed) {
        Random rnd = new Random(seed);
        float[] kw = new float[minutes], v = new float[minutes], a = new float[minutes];
        for (int i = 0; i < minutes; i++) {
            kw[i] = (float) (0.3 + 1.5 * Math.abs(Math.sin(i / 300.0)) + 0.05 * rnd.nextGaussian());
            kw[i] = Math.max(0.05f, kw[i]);
            v[i] = 230 + (float) rnd.nextGaussian();
            a[i] = Math.round(kw[i] * 1000 / v[i] * 5) / 5f + 0.2f;
        }
        return new MinuteData(LocalDateTime.of(2009, 1, 1, 0, 0), kw, v, a);
    }

    @Test
    void cleanDataRaisesNoFlags() {
        AnomalyDetector.Result r = new AnomalyDetector().detect(cleanMinutes(2880, 1));
        assertTrue(r.runs().isEmpty(), r.runs().toString());
    }

    @Test
    void detectsEachFaultType() {
        MinuteData d = cleanMinutes(2880, 2);
        d.activeKw()[100] = Float.NaN;
        d.activeKw()[500] += 6;                       // isolated spike
        for (int i = 1000; i < 1300; i++) {
            d.activeKw()[i] = 1.234f;                 // stuck register
        }
        d.voltage()[2000] = 260;                      // over-voltage
        d.activeKw()[2500] *= 3;                      // power above V * I
        AnomalyDetector.Result r = new AnomalyDetector().detect(d);
        assertEquals(Anomaly.Type.MISSING, r.flags()[100]);
        assertEquals(Anomaly.Type.SPIKE, r.flags()[500]);
        assertEquals(Anomaly.Type.FLATLINE, r.flags()[1150]);
        assertEquals(Anomaly.Type.PHYSICS, r.flags()[2000]);
        assertEquals(Anomaly.Type.PHYSICS, r.flags()[2500]);
        assertNull(r.flags()[1500]);
    }

    @Test
    void injectionBenchmarkFindsLargeFaults() {
        MinuteData d = cleanMinutes(60 * 24 * 30, 3);
        InjectionBenchmark.Score s = InjectionBenchmark.run(d, new AnomalyDetector(), 20, 5);
        assertEquals(60, s.injectedEvents());
        assertTrue(s.eventRecall() > 0.8, "recall " + s.eventRecall());
        assertTrue(s.minutePrecision() > 0.95, "precision " + s.minutePrecision());
    }

    @Test
    void hourlyAggregationAveragesGoodMinutesAndImputesGaps() {
        int hours = 24 * 9;
        float[] kw = new float[hours * 60];
        float[] v = new float[kw.length];
        float[] a = new float[kw.length];
        Arrays.fill(v, 230);
        Arrays.fill(a, 20);
        for (int i = 0; i < kw.length; i++) {
            kw[i] = (i / 60) % 24; // kW equals hour of day
        }
        Arrays.fill(kw, 60 * (24 * 8 + 5), 60 * (24 * 8 + 6), Float.NaN); // lose one whole hour on day 9
        MinuteData d = new MinuteData(LocalDateTime.of(2009, 1, 1, 0, 0), kw, v, a);
        HourlyAggregator.Output out = HourlyAggregator.aggregate(d, null, 30);
        HourlySeries s = out.series();
        assertEquals(hours, s.size());
        assertEquals(13.0, s.get(13), 1e-6);
        assertEquals(1, out.imputedHours());
        assertEquals(5.0, s.get(24 * 8 + 5), 1e-6, "imputed from the same hour a week earlier");
    }

    @Test
    void uciLoaderBuildsAGapFreeMinuteGrid(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("uci.txt");
        Files.writeString(f, String.join("\n", List.of(
                "Date;Time;Global_active_power;Global_reactive_power;Voltage;Global_intensity;Sub_metering_1;Sub_metering_2;Sub_metering_3",
                "31/12/2008;23:58:00;1.000;0.1;240.0;4.2;0;0;0",
                "31/12/2008;23:59:00;?;?;?;?;?;?;",
                "1/1/2009;00:01:00;2.500;0.1;239.0;10.4;0;0;1")) + "\n");
        MinuteData d = UciLoader.load(f);
        assertEquals(4, d.size());
        assertEquals(LocalDateTime.of(2008, 12, 31, 23, 58), d.start());
        assertEquals(1.0f, d.activeKw()[0]);
        assertTrue(Float.isNaN(d.activeKw()[1]));
        assertTrue(Float.isNaN(d.activeKw()[2]), "missing row in the file becomes NaN");
        assertEquals(2.5f, d.activeKw()[3]);
    }

    @Test
    void isoNeParserKeepsOneLocationAndSkipsTheRepeatedDstHour() throws Exception {
        String csv = """
                "C","Day-Ahead Energy Market Hourly LMP Report"
                "H","Date","Hour Ending","Location ID","Location Name","Location Type","Locational Marginal Price","Energy Component","Congestion Component","Marginal Loss Component"
                "D","11/03/2024","01","4000",".H.INTERNAL_HUB","HUB",30.00,30.0,0.0,0.0
                "D","11/03/2024","01","4008",".Z.NEMASSBOST","LOAD ZONE",31.50,30.0,0.0,1.5
                "D","11/03/2024","02","4008",".Z.NEMASSBOST","LOAD ZONE",29.00,30.0,0.0,-1.0
                "D","11/03/2024","02X","4008",".Z.NEMASSBOST","LOAD ZONE",28.00,30.0,0.0,-2.0
                """;
        Class<?> c = Class.forName("electricitybillgenerator.analytics.data.IsoNePriceFetcher");
        Method parse = c.getDeclaredMethod("parse", BufferedReader.class, String.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<double[]> rows = (List<double[]>) parse.invoke(null, new BufferedReader(new StringReader(csv)), ".Z.NEMASSBOST");
        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0)[0]);
        assertEquals(31.5, rows.get(0)[1]);
        assertEquals(1, rows.get(1)[0]);
    }
}
