package electricitybillgenerator.analytics.data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Hourly wholesale prices in $/MWh, keyed by local hour-start timestamp. */
public record PriceSeries(String location, List<LocalDateTime> times, List<Double> pricesPerMwh) {

    public int size() {
        return times.size();
    }

    public void writeCsv(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("# location=" + location + "\n");
            w.write("date,hour,lmp_usd_per_mwh\n");
            for (int i = 0; i < times.size(); i++) {
                LocalDateTime t = times.get(i);
                w.write(t.toLocalDate() + "," + t.getHour() + "," + pricesPerMwh.get(i) + "\n");
            }
        }
    }

    public static PriceSeries readCsv(Path file) throws IOException {
        List<LocalDateTime> times = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        String location = "unknown";
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("# location=")) {
                    location = line.substring("# location=".length());
                    continue;
                }
                if (line.startsWith("date") || line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",");
                times.add(LocalDate.parse(f[0]).atTime(Integer.parseInt(f[1]), 0));
                prices.add(Double.parseDouble(f[2]));
            }
        }
        return new PriceSeries(location, times, prices);
    }
}
