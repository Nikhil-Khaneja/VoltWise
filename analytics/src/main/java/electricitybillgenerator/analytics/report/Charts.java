package electricitybillgenerator.analytics.report;

import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.dispatch.DispatchResult;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** PNG charts for the README. */
public final class Charts {

    private Charts() {
    }

    public static void dailyProfile(LoadAnalysis.Summary s, Path out) throws IOException {
        XYChart c = new XYChartBuilder().width(900).height(450)
                .title("Average hourly load, Dec 2006 - Nov 2010").xAxisTitle("Hour of day").yAxisTitle("kW").build();
        double[] hours = IntStream.range(0, 24).asDoubleStream().toArray();
        c.addSeries("Weekday", hours, s.weekdayProfile()).setMarker(SeriesMarkers.CIRCLE);
        c.addSeries("Weekend", hours, s.weekendProfile()).setMarker(SeriesMarkers.DIAMOND);
        save(c, out);
    }

    public static void monthlyPeaks(List<String> months, Map<String, List<Double>> peaksByStrategy, Path out)
            throws IOException {
        CategoryChart c = new CategoryChartBuilder().width(1100).height(480)
                .title("Monthly billed peak demand, 2009").xAxisTitle("Month").yAxisTitle("Peak kW").build();
        c.getStyler().setXAxisLabelRotation(45);
        peaksByStrategy.forEach((name, peaks) -> c.addSeries(name, months, peaks));
        save(c, out);
    }

    public static void dispatchWeek(DispatchResult r, int from, String title, Path out) throws IOException {
        int to = Math.min(r.load().size(), from + 168);
        double[] x = IntStream.range(0, to - from).asDoubleStream().toArray();
        double[] load = slice(r.load().values(), from, to);
        double[] grid = slice(r.grid().values(), from, to);
        double[] soc = slice(r.soc(), from, to);
        XYChart c = new XYChartBuilder().width(1100).height(480).title(title)
                .xAxisTitle("Hours from " + r.load().time(from).toLocalDate()).yAxisTitle("kW").build();
        c.addSeries("Site load", x, load).setMarker(SeriesMarkers.NONE);
        c.addSeries("Grid import", x, grid).setMarker(SeriesMarkers.NONE);
        XYSeries socSeries = c.addSeries("Battery SoC (kWh)", x, soc);
        socSeries.setMarker(SeriesMarkers.NONE);
        socSeries.setYAxisGroup(1);
        c.setYAxisGroupTitle(1, "SoC kWh");
        c.getStyler().setYAxisGroupPosition(1, org.knowm.xchart.style.Styler.YAxisPosition.Right);
        save(c, out);
    }

    public static void forecastWeek(HourlySeries actual, Map<String, double[]> forecasts, int from, Path out)
            throws IOException {
        int to = Math.min(actual.size(), from + 168);
        double[] x = IntStream.range(0, to - from).asDoubleStream().toArray();
        XYChart c = new XYChartBuilder().width(1100).height(480)
                .title("Day-ahead forecasts vs actual, week of " + actual.time(from).toLocalDate())
                .xAxisTitle("Hour").yAxisTitle("kW").build();
        c.addSeries("Actual", x, slice(actual.values(), from, to)).setMarker(SeriesMarkers.NONE);
        forecasts.forEach((name, f) -> c.addSeries(name, x, slice(f, from, to)).setMarker(SeriesMarkers.NONE));
        save(c, out);
    }

    public static void sizeSweep(List<String> labels, Map<String, List<Double>> savingsByStrategy, Path out)
            throws IOException {
        CategoryChart c = new CategoryChartBuilder().width(900).height(450)
                .title("Annual bill savings by battery size, 2009").xAxisTitle("Battery").yAxisTitle("$ saved / year").build();
        savingsByStrategy.forEach((name, v) -> c.addSeries(name, labels, v));
        save(c, out);
    }

    private static double[] slice(double[] a, int from, int to) {
        double[] b = new double[to - from];
        System.arraycopy(a, from, b, 0, b.length);
        return b;
    }

    private static void save(Object chart, Path out) throws IOException {
        out.getParent().toFile().mkdirs();
        String base = out.toString().replaceAll("\\.png$", "");
        if (chart instanceof XYChart xy) {
            BitmapEncoder.saveBitmap(xy, base, BitmapEncoder.BitmapFormat.PNG);
        } else if (chart instanceof CategoryChart cc) {
            BitmapEncoder.saveBitmap(cc, base, BitmapEncoder.BitmapFormat.PNG);
        }
    }

    static List<String> labels(List<?> xs) {
        List<String> out = new ArrayList<>();
        xs.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }
}
