package electricitybillgenerator.analytics.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads ISO New England day-ahead hourly LMPs (public daily CSV reports) and keeps
 * only one pricing location, e.g. ".Z.NEMASSBOST" (the NEMA/Boston load zone).
 */
public final class IsoNePriceFetcher {

    private static final String URL = "https://www.iso-ne.com/histRpts/da-lmp/WW_DALMP_ISO_%s.csv";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String location;

    public IsoNePriceFetcher(String location) {
        this.location = location;
    }

    public PriceSeries fetchYear(int year, int parallelism) throws IOException, InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            Map<LocalDate, Future<List<double[]>>> jobs = new TreeMap<>();
            for (LocalDate d = LocalDate.of(year, 1, 1); d.getYear() == year; d = d.plusDays(1)) {
                LocalDate day = d;
                jobs.put(day, pool.submit(() -> fetchDay(day)));
            }
            List<LocalDateTime> times = new ArrayList<>();
            List<Double> prices = new ArrayList<>();
            for (Map.Entry<LocalDate, Future<List<double[]>>> e : jobs.entrySet()) {
                List<double[]> rows;
                try {
                    rows = e.getValue().get();
                } catch (Exception ex) {
                    throw new IOException("Failed to fetch " + e.getKey(), ex);
                }
                for (double[] row : rows) {
                    times.add(e.getKey().atTime((int) row[0], 0));
                    prices.add(row[1]);
                }
            }
            return new PriceSeries(location, times, prices);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Returns {hourStart, lmp} rows for one day. The repeated DST hour ("02X") is dropped. */
    List<double[]> fetchDay(LocalDate day) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(URL, day.format(YYYYMMDD))))
                .header("User-Agent", "Mozilla/5.0 (peakshave research script)")
                .timeout(Duration.ofMinutes(2))
                .build();
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    resp.body().close();
                    throw new IOException("HTTP " + resp.statusCode() + " for " + day);
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    return parse(r, location);
                }
            } catch (IOException ex) {
                last = ex;
                Thread.sleep(2000L * (attempt + 1));
            }
        }
        throw last;
    }

    static List<double[]> parse(BufferedReader r, String location) throws IOException {
        List<double[]> rows = new ArrayList<>();
        String quoted = "\"" + location + "\"";
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.startsWith("\"D\"") || !line.contains(quoted)) {
                continue;
            }
            String[] f = line.replace("\"", "").split(",");
            // D, Date, Hour Ending, Location ID, Location Name, Location Type, LMP, ...
            String he = f[2];
            if (he.endsWith("X")) {
                continue;
            }
            int hourEnding = Integer.parseInt(he);
            rows.add(new double[]{hourEnding - 1, Double.parseDouble(f[6])});
        }
        return rows;
    }
}
