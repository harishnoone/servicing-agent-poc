package unit.tests.servicingagent.tools;

import unit.tests.servicingagent.dlt.DltService;
import unit.tests.servicingagent.telemetry.KpiMetricsService;
import unit.tests.servicingagent.telemetry.TraceCollector;
import unit.tests.servicingagent.telemetry.TurnState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** FR-7.2: internal tool exposing the executive KPI aggregate to the agent. */
@Component
public class ReportingTools {

    private final KpiMetricsService kpiMetricsService;
    private final DltService dltService;
    private final TraceCollector traceCollector;
    private final TurnState turnState;

    public ReportingTools(KpiMetricsService kpiMetricsService, DltService dltService,
                          TraceCollector traceCollector, TurnState turnState) {
        this.kpiMetricsService = kpiMetricsService;
        this.dltService        = dltService;
        this.traceCollector    = traceCollector;
        this.turnState         = turnState;
    }

    @Tool(description = "Return the executive KPI report: session count, autonomous deflection rate, "
            + "estimated AHT minutes saved, policy compliance score, and Kafka dead-letter depth. "
            + "Use only when asked for operational or executive metrics.")
    public KpiMetricsService.KpiReport getDeflectionReport() {
        long start = System.currentTimeMillis();
        kpiMetricsService.setDltDepth(dltService.totalDepth());
        KpiMetricsService.KpiReport report = kpiMetricsService.getReport();
        turnState.recordTool("getDeflectionReport");
        traceCollector.record("TOOL_CALL: getDeflectionReport", System.currentTimeMillis() - start);
        return report;
    }
}
