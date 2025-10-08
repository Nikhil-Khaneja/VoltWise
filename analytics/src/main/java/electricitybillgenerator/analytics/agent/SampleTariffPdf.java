package electricitybillgenerator.analytics.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import electricitybillgenerator.analytics.Experiment;
import electricitybillgenerator.analytics.data.PriceSeries;
import electricitybillgenerator.analytics.tariff.TariffSpec;
import electricitybillgenerator.analytics.tariff.TouPeriod;
import electricitybillgenerator.analytics.tariff.WholesaleTouBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a tariff as a utility-style rate sheet PDF, plus the ground-truth JSON beside it.
 * Gives the extraction agent a test input whose correct answer is known.
 */
public final class SampleTariffPdf {

    private SampleTariffPdf() {
    }

    public static void write(Experiment.Config cfg, Path pdf) throws IOException {
        TariffSpec t = new WholesaleTouBuilder()
                .demandCharge(cfg.demandChargePerKw())
                .deliveryAdder(cfg.deliveryAdderPerKwh())
                .customerCharge(cfg.customerChargePerMonth())
                .build(PriceSeries.readCsv(cfg.pricesFile()));
        write(t, pdf);
        Path truth = Path.of(pdf.toString().replaceAll("\\.pdf$", "") + ".truth.json");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(truth.toFile(), t);
        System.out.println("Wrote " + pdf + " and " + truth);
    }

    public static void write(TariffSpec t, Path pdf) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("SCHEDULE R-TOU-D  -  Residential Time-of-Use Service with Demand");
        lines.add("Rate sheet effective January 1, 2025");
        lines.add("");
        lines.add("APPLICABILITY: Residential customers taking service through an interval meter.");
        lines.add("");
        lines.add("MONTHLY CHARGES");
        lines.add(String.format(Locale.US, "  Customer Charge ............................ $%.2f per month", t.customerChargePerMonth()));
        lines.add(String.format(Locale.US, "  Demand Charge .............................. $%.2f per kW", t.demandChargePerKw()));
        lines.add("     Billing demand is the customer's highest 60-minute average kW in the billing month.");
        lines.add("");
        lines.add("ENERGY CHARGES (all per-kWh components, delivery and supply combined)");
        for (TouPeriod p : t.periods()) {
            lines.add(String.format(Locale.US, "  %-26s %s, %s ... %.4f $/kWh", p.name(),
                    p.weekdaysOnly() ? "Mon-Fri" : "All days", hourText(p.hours()), p.energyRatePerKwh()));
            lines.add("        Months: " + monthText(p.months()));
        }
        lines.add("");
        lines.add("TIME PERIOD DEFINITIONS");
        lines.add("  Periods are applied in the order listed above; the first matching period sets the price.");
        lines.add("  Hours are hour-beginning, local prevailing time.");
        lines.add(String.format(Locale.US, "  Any hour not covered by a period above is billed at %.4f $/kWh.", t.defaultEnergyRatePerKwh()));

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 8.5f);
                cs.setLeading(12f);
                cs.newLineAtOffset(40, 740);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            Files.createDirectories(pdf.toAbsolutePath().getParent());
            doc.save(pdf.toFile());
        }
    }

    private static String hourText(List<Integer> hours) {
        if (hours.size() == 24) {
            return "all hours";
        }
        // Print contiguous runs, e.g. "07:00-12:00 and 17:00-20:00".
        List<String> runs = new ArrayList<>();
        int i = 0;
        while (i < hours.size()) {
            int j = i;
            while (j + 1 < hours.size() && hours.get(j + 1) == hours.get(j) + 1) {
                j++;
            }
            runs.add(String.format("%02d:00-%02d:00", hours.get(i), hours.get(j) + 1));
            i = j + 1;
        }
        return String.join(" and ", runs);
    }

    private static String monthText(List<Integer> months) {
        List<String> names = new ArrayList<>();
        for (int m : months) {
            names.add(Month.of(m).getDisplayName(TextStyle.SHORT, Locale.US));
        }
        return String.join(", ", names);
    }
}
