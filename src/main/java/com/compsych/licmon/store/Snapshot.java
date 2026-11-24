package com.compsych.licmon.store;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.domain.Surfacing;

import java.util.List;

/** Serialized form of the whole store. One list per would-be Postgres table. */
public record Snapshot(int schemaVersion, List<Provider> providers, List<License> licenses,
                       List<Surfacing> surfacings, List<MonitorRun> runs) {
    public static final int CURRENT_VERSION = 1;
}
