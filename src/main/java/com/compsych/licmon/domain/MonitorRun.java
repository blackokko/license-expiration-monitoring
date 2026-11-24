package com.compsych.licmon.domain;

import java.time.Instant;
import java.time.LocalDate;

public record MonitorRun(String runId, LocalDate asOf, Instant startedAt, Instant finishedAt,
                         Status status, int licensesEvaluated, int surfacedCount, String error) {

    public enum Status { SUCCEEDED, FAILED }
}
