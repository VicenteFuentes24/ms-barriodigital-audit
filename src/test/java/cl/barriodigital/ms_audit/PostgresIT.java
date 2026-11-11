package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.service.*;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import cl.barriodigital.ms_audit.dto.RequestEventV1;
import cl.barriodigital.ms_audit.kafka.RequestEventListener;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cl.barriodigital.ms_audit.TestEvents.*;

/** Opt-in. Always uses a newly-created isolated database; ignores DB_* environment values. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.kafka.listener.auto-startup=false", "spring.kafka.bootstrap-servers=127.0.0.1:1",
    "spring.flyway.enabled=true", "audit.security.tenant-id=11111111-1111-1111-1111-111111111111",
    "audit.security.audience=audit-test", "logging.level.root=WARN", "spring.main.banner-mode=off",
    "debug=false", "logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN"
})
class PostgresIT {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
        .withDatabaseName("audit_isolated_test")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(256L*1024*1024));
    static { POSTGRES.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @AfterAll static void stopDatabase() { POSTGRES.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired EventProcessingService events;
    @Autowired AuditQueryService queries;
    @Autowired FailedEventService failures;
    @Autowired RecoveryService recovery;
    @Autowired RequestEventListener listener;
    @Autowired PlatformTransactionManager transactions;
    @Value("${local.server.port}") int port;
    @BeforeEach void resetIsolatedDatabase() {
        jdbc.execute("TRUNCATE bd_audit.audit_event, bd_audit.failed_event RESTART IDENTITY");
    }
    boolean process(RequestEventV1 e) { return events.process(e,"bd.requests.events.v1",0,e.requestVersion()); }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM bd_audit.audit_event",Long.class); }
    @Test void migrationHistoryAndHibernateValidation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bd_audit.flyway_schema_history WHERE success=true",Long.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('bd_report','bd_requests_kafka')",Long.class)).isZero();
    }
    @Test void createdChangedDuplicatesAndOutOfOrderHistory() {
        var first = created(1); var second = changed(1,2); var third = changed(1,3);
        process(third); process(first); process(second);
        assertThat(process(first)).isFalse(); assertThat(count()).isEqualTo(3);
        var results = queries.list(null,null,null,1L,0,100);
        assertThat(results.getContent()).extracting(e -> e.id()).containsExactly(third.eventId().toString(),second.eventId().toString(),first.eventId().toString());
        assertThat(results.getContent()).allSatisfy(e -> assertThat(e.user()).isNull());
    }
    @Test void identityAndLogicalVersionConflictsPreserveHistory() {
        var e = created(1); process(e);
        var conflicting = new RequestEventV1(e.eventId(),e.eventType(),1,1L,1L,e.procedureId(),"Different name",null,e.status(),e.occurredAt());
        assertThatThrownBy(() -> process(conflicting)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> process(created(1))).isInstanceOf(InvalidEventException.class);
        assertThat(count()).isEqualTo(1);
    }
    @Test void appendOnlyTriggerRejectsUpdatesAndDeletes() {
        process(created(1));
        assertThatThrownBy(() -> jdbc.update("UPDATE bd_audit.audit_event SET actor='forged' WHERE request_id=1")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bd_audit.audit_event WHERE request_id=1")).isInstanceOf(DataAccessException.class);
        assertThat(count()).isEqualTo(1);
    }
    @Test void transactionRollbackLeavesNoProcessingMarker() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { process(created(1)); tx.setRollbackOnly(); });
        assertThat(count()).isZero();
    }
    @Test void concurrentDuplicatesHaveOneRow() throws Exception {
        var e = created(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> task = () -> { gate.await(); return process(e); };
            var a = executor.submit(task); var b = executor.submit(task); gate.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count()).isEqualTo(1);
    }
    @Test void dateTypeRequestCombinedAndPagination() {
        process(created(1)); process(changed(1,2)); process(created(2));
        assertThat(queries.list(null,LocalDate.of(2026,11,10),"REQUEST_STATUS_CHANGED",1L,0,1).getContent()).hasSize(1);
        assertThat(queries.list(null,LocalDate.of(2026,11,9),null,null,0,100).getContent()).isEmpty();
        var page = queries.list(null,null,null,null,0,2);
        assertThat(page.getTotalElements()).isEqualTo(3); assertThat(page.hasNext()).isTrue();
        assertThat(queries.list(null,null,null,null,1,2).getContent()).hasSize(1);
    }
    @Test void userFilterDoesNotInventActorOrAllowSqlInjection() {
        process(created(1));
        for (String user : List.of("ana", "%", "' OR 1=1 --", "_"))
            assertThat(queries.list(user,null,null,null,0,100).getContent()).isEmpty();
    }
    @Test void validatesQueryBoundsAndType() {
        assertThatThrownBy(() -> queries.list(null,LocalDate.MAX,null,null,0,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.list(null,null,"UNKNOWN",null,0,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.list(null,null,null,null,-1,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.list(null,null,null,null,0,201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.list(null,null,null,0L,0,100)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durableQuarantineAndAtomicReplay() {
        var e = created(1); var record = record(e,5);
        failures.record(record,new IllegalStateException("private details"));
        failures.record(record,new IllegalStateException("duplicate"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bd_audit.failed_event",Long.class)).isEqualTo(1);
        assertThat(recovery.recover(1)).isTrue(); assertThat(recovery.recover(1)).isFalse();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM bd_audit.failed_event WHERE id=1",byte[].class)).isEqualTo(record.value());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM bd_audit.failed_event WHERE id=1",String.class)).doesNotContain("private");
    }
    @Test void invalidQuarantineReplayKeepsOriginal() {
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>("bd.requests.events.v1",0,7,bytes("1"),bytes("{"));
        failures.record(record,new InvalidEventException("INVALID_JSON_OR_CONTRACT"));
        assertThatThrownBy(() -> recovery.recover(1)).isInstanceOf(InvalidEventException.class);
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bd_audit.failed_event WHERE recovered_at IS NULL",Long.class)).isEqualTo(1);
    }
    @Test void acknowledgeObservesCommittedHistory() {
        var e = created(1); var ack = mock(Acknowledgment.class);
        doAnswer(call -> { assertThat(count()).isEqualTo(1); return null; }).when(ack).acknowledge();
        listener.consume(record(e,1),ack); verify(ack).acknowledge();
    }
    @Test void dateFilterUsesSantiagoDayBoundaries() {
        var base = created(1);
        var before = new RequestEventV1(base.eventId(),base.eventType(),1,1L,1L,base.procedureId(),base.procedureName(),null,base.status(),java.time.Instant.parse("2026-11-10T02:59:59Z"));
        var at = new RequestEventV1(UUID.randomUUID(),base.eventType(),1,2L,1L,base.procedureId(),base.procedureName(),null,base.status(),java.time.Instant.parse("2026-11-10T03:00:00Z"));
        process(before); process(at);
        assertThat(queries.list(null,LocalDate.of(2026,11,9),null,null,0,100).getContent()).extracting(e -> e.entityId()).containsExactly("1");
        assertThat(queries.list(null,LocalDate.of(2026,11,10),null,null,0,100).getContent()).extracting(e -> e.entityId()).containsExactly("2");
    }
    @Test void healthIsMinimalAndDirectApiIsProtected() throws Exception {
        var client = HttpClient.newHttpClient();
        var health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/actuator/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200); assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
        var audit = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/audit")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(audit.statusCode()).isEqualTo(401);
    }
}
