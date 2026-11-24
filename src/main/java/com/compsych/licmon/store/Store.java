package com.compsych.licmon.store;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.domain.MonitorRun;
import com.compsych.licmon.domain.Provider;
import com.compsych.licmon.domain.Surfacing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage port. Shaped like the Postgres tables (provider, license, surfacing, monitor_run) so the
 * in-memory implementation can be swapped for a JDBC one without touching the services.
 */
public interface Store {

    Optional<Provider> provider(String npi);

    void putProvider(Provider p);

    Optional<License> license(String key);

    void putLicense(License l);

    Collection<License> licenses();

    /** Surfacings for one license, oldest first. */
    List<Surfacing> surfacings(String licenseKey);

    List<Surfacing> allSurfacings();

    /**
     * Records a run and the surfacings it produced as one unit (one transaction in Postgres).
     * Either both land or neither does, so a crash mid-run is simply re-run.
     */
    void commitRun(MonitorRun run, List<Surfacing> newSurfacings);

    List<MonitorRun> runs();
}
