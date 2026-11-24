package com.compsych.licmon;

import com.compsych.licmon.domain.License;
import com.compsych.licmon.service.IntakeService;
import com.compsych.licmon.store.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntakeServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);
    static final String HEADER = "npi,first_name,last_name,license_number,license_state,license_expiration\n";

    final InMemoryStore store = new InMemoryStore();
    final IntakeService intake = new IntakeService(store, CLOCK);

    IntakeService.Result ingest(String body) throws Exception {
        return intake.ingestCsv(new StringReader(HEADER + body));
    }

    @Test
    void goodRowsKeptBadRowsReported() throws Exception {
        IntakeService.Result r = ingest("""
                1234567890,Ann,Lee,NY-44821,NY,2027-05-30
                1234567890,Ann,Lee,NJ-1,nj,6/1/2027
                123,Bad,Npi,X-1,NY,2027-01-01
                2222222222,Bad,State,X-2,ZZ,2027-01-01
                3333333333,Bad,Date,X-3,NY,2027-02-30
                """);
        assertEquals(2, r.inserted());
        assertEquals(3, r.errors().size());
        assertEquals(4, r.errors().get(0).line());
        assertEquals(LocalDate.of(2027, 6, 1), store.license(License.keyOf("NJ", "NJ-1")).orElseThrow().licenseExpiration());
        assertEquals(1, store.provider("1234567890").stream().count());
    }

    @Test
    void reIngestIsIdempotentAndDateChangeIsAnUpdate() throws Exception {
        String row = "1234567890,Ann,Lee,NY-44821,NY,2027-05-30\n";
        assertEquals(1, ingest(row).inserted());
        IntakeService.Result again = ingest(row);
        assertEquals(0, again.inserted());
        assertEquals(1, again.unchanged());
        IntakeService.Result renewed = ingest("1234567890,Ann,Lee,NY-44821,NY,2029-05-30\n");
        assertEquals(1, renewed.updated());
        assertEquals(1, store.licenses().size());
    }

    @Test
    void licenseClaimedByDifferentNpiIsRejected() throws Exception {
        ingest("1234567890,Ann,Lee,NY-44821,NY,2027-05-30\n");
        IntakeService.Result r = ingest("9999999999,Bob,Roe,NY-44821,NY,2028-01-01\n");
        assertEquals(1, r.errors().size());
        assertEquals("1234567890", store.license(License.keyOf("NY", "NY-44821")).orElseThrow().npi());
    }

    @Test
    void quotedFieldsAndNameChangeWarning() throws Exception {
        ingest("1234567890,Ann,Lee,NY-44821,NY,2027-05-30\n");
        IntakeService.Result r = ingest("1234567890,\"Ann, Jr\",Lee,NY-44821,NY,2027-05-30\n");
        assertTrue(r.errors().isEmpty());
        assertEquals(1, r.warnings().size());
        assertEquals("Ann, Jr", store.provider("1234567890").orElseThrow().firstName());
    }
}
