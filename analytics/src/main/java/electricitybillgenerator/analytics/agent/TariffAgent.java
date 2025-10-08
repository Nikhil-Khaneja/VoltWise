package electricitybillgenerator.analytics.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import electricitybillgenerator.analytics.Experiment;
import electricitybillgenerator.analytics.anomaly.AnomalyDetector;
import electricitybillgenerator.analytics.data.HourlyAggregator;
import electricitybillgenerator.analytics.data.HourlySeries;
import electricitybillgenerator.analytics.data.MinuteData;
import electricitybillgenerator.analytics.data.UciLoader;
import electricitybillgenerator.analytics.battery.BatterySpec;
import electricitybillgenerator.analytics.forecast.ForecastSelection;
import electricitybillgenerator.analytics.tariff.TariffSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Two-stage agent:
 * <ol>
 *   <li><b>Extract</b>: Claude reads a utility tariff PDF and returns a {@link TariffSpec} through
 *       structured output; the result is range-checked before it is trusted.</li>
 *   <li><b>Analyse</b>: Claude calls the battery simulator as a tool to compare sizes and
 *       strategies under that tariff, then writes a recommendation with next steps.</li>
 * </ol>
 * Needs ANTHROPIC_API_KEY (or an `ant auth login` profile).
 */
public final class TariffAgent {

    static final String MODEL = "claude-opus-5-5";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final String EXTRACT_PROMPT = """
            Extract this utility rate schedule into the requested JSON structure.

            Rules:
            - energyRatePerKwh is the total of every per-kWh charge for that period, in dollars.
            - demandChargePerKw is dollars per kW of monthly billing demand; use 0 if there is none.
            - hours are hour-beginning integers 0-23, so a 14:00-19:00 window is [14, 15, 16, 17, 18].
            - Keep periods in the tariff's order of precedence; an all-hours period goes last within its season.
            - If the tariff states something you cannot represent, choose the closest representation; do not invent rates.
            """;

    private static final String ANALYSE_PROMPT = """
            You are advising a residential customer who has just been moved to the tariff below and is
            considering a home battery. Installed battery cost is $%.0f per kWh of capacity.

            Tariff (extracted from the customer's rate sheet):
            %s

            Use the tools to (1) understand the customer's load, and (2) compare at least three battery sizes,
            including both the deployable 'forecast' strategy and the 'optimal' upper bound for your preferred size.
            Then write a short recommendation in Markdown with:
            - a table of the configurations you simulated,
            - which configuration you recommend and why, including payback,
            - how much of the theoretical savings the deployable strategy captures,
            - three concrete next steps (for example data to collect or tariff alternatives to price).
            Only cite numbers returned by the tools.
            """;

    private TariffAgent() {
    }

    public static void main(Experiment.Config cfg, Path pdf) throws IOException {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        System.out.println("Stage 1: extracting tariff from " + pdf);
        TariffSpec tariff = extract(client, pdf);
        List<String> problems = tariff.validate();
        String tariffJson = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tariff);
        System.out.println(tariffJson);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Extracted tariff failed validation: " + problems);
        }
        Path truth = Path.of(pdf.toString().replaceAll("\\.pdf$", "") + ".truth.json");
        if (Files.exists(truth)) {
            TariffSpec expected = new ObjectMapper().readValue(truth.toFile(), TariffSpec.class);
            System.out.println("Extraction check vs " + truth + ": " + ExtractionCheck.compare(expected, tariff));
        }

        System.out.println("Stage 2: preparing load data and simulator");
        AgentTools.context = buildContext(cfg, tariff);

        BetaToolRunner runner = client.beta().messages().toolRunner(
                com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(16000L)
                        .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13," + FALLBACK_BETA)
                        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                        .addTool(AgentTools.DescribeLoad.class)
                        .addTool(AgentTools.SimulateBattery.class)
                        .addUserMessage(String.format(ANALYSE_PROMPT, cfg.batteryCostPerKwh(), tariffJson))
                        .build());

        StringBuilder report = new StringBuilder();
        for (BetaMessage message : runner) {
            message.content().forEach(block -> {
                block.toolUse().ifPresent(t -> System.out.println("  tool call: " + t.name() + " " + t._input()));
                block.text().ifPresent(t -> {
                    report.setLength(0);
                    report.append(t.text());
                });
            });
        }
        Path out = cfg.outDir().resolve("agent_report.md");
        Files.createDirectories(cfg.outDir());
        Files.writeString(out, report.toString());
        System.out.println(report);
        System.out.println("Wrote " + out);
    }

    static TariffSpec extract(AnthropicClient client, Path pdf) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(pdf));
        DocumentBlockParam doc = DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(b64).build())
                .title(pdf.getFileName().toString())
                .build();
        StructuredMessageCreateParams<TariffSpec> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .outputConfig(TariffSpec.class)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofDocument(doc),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(EXTRACT_PROMPT).build())))
                .build();
        StructuredMessage<TariffSpec> response = client.messages().create(params);
        if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
            throw new IllegalStateException("Claude declined to extract the tariff");
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no structured output in response"));
    }

    private static AgentTools.Context buildContext(Experiment.Config cfg, TariffSpec tariff) throws IOException {
        MinuteData raw = UciLoader.load(cfg.uciFile());
        AnomalyDetector.Result flags = new AnomalyDetector().detect(raw);
        HourlySeries all = HourlyAggregator.aggregate(raw, flags, 30).series();
        LocalDateTime testFrom = LocalDateTime.of(2009, 1, 1, 0, 0);
        HourlySeries test = all.slice(testFrom, testFrom.plusYears(1));
        HourlySeries trainYear = all.slice(testFrom.minusYears(1), testFrom);
        // Pick the forecaster for this tariff the same way the study does: by 2008 bill savings.
        ForecastSelection.Choice choice = ForecastSelection.select(all, testFrom.minusYears(1), testFrom,
                BatterySpec.homeDefault(), tariff);
        System.out.println("Forecaster for the 'forecast' strategy: " + choice.name());
        return new AgentTools.Context(test, trainYear, tariff, choice.forecast(), cfg.batteryCostPerKwh());
    }
}
