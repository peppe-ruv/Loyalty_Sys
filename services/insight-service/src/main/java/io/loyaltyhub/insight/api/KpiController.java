package io.loyaltyhub.insight.api;

import io.loyaltyhub.insight.application.KpiService;
import io.loyaltyhub.insight.application.KpiService.Breakdown;
import io.loyaltyhub.insight.application.KpiService.Overview;
import io.loyaltyhub.insight.application.KpiService.TimeSeries;
import io.loyaltyhub.insight.application.KpiService.Window;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * KPI di programma per BO-01 (docs/servizi/insight-service.md §3). Finestra: {@code from/to} (ISO) oppure
 * {@code days} (7/30/90, default 30) fino a oggi.
 */
@RestController
@RequestMapping("/v1/kpi")
public class KpiController {

    private final KpiService kpi;

    public KpiController(KpiService kpi) {
        this.kpi = kpi;
    }

    @GetMapping("/overview")
    public Overview overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int days) {
        return kpi.overview(kpi.window(from, to, days));
    }

    @GetMapping("/timeseries")
    public TimeSeries timeseries(
            @RequestParam String metric,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "day") String granularity) {
        Window w = kpi.window(from, to, days);
        return kpi.timeseries(metric, w.from(), w.to(), granularity);
    }

    @GetMapping("/breakdown")
    public Breakdown breakdown(
            @RequestParam String metric,
            @RequestParam(defaultValue = "source") String dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5") int limit) {
        Window w = kpi.window(from, to, days);
        return kpi.breakdown(metric, dimension, w.from(), w.to(), limit);
    }
}
