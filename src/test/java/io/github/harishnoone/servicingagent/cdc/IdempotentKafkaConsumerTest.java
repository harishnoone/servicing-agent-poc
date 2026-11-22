package io.github.harishnoone.servicingagent.cdc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.model.CustomerProfile;
import io.github.harishnoone.servicingagent.support.FakeRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-1.2: dedup, per-row LSN ordering, and dedup release on failure so retries are not lost. */
class IdempotentKafkaConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final io.github.harishnoone.servicingagent.cache.WarehouseFallbackRepository warehouse =
            mock(io.github.harishnoone.servicingagent.cache.WarehouseFallbackRepository.class);
    private FakeRedis redis;
    private IdempotentKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        consumer = new IdempotentKafkaConsumer(redis.template, mapper, warehouse);
        ReflectionTestUtils.setField(consumer, "profileKeyPrefix", "customer:profile:");
        ReflectionTestUtils.setField(consumer, "dedupKeyPrefix", "processed:event:");
        ReflectionTestUtils.setField(consumer, "dedupTtlSeconds", 86400L);
    }

    private static String customerEvent(long lsn, int waivers) {
        return """
                {"before":null,"after":{"customer_id":"cust_123","full_name":"Elena Vance","account_tier":"Gold",
                 "tenure_months":36,"mrr":"499.00","waivers_used_12m":%d},
                 "source":{"table":"customers","lsn":%d},"op":"u"}""".formatted(waivers, lsn);
    }

    private static String billingEvent(long lsn, String invoice, String status) {
        return """
                {"after":{"invoice_id":"%s","customer_id":"cust_123","charge_type":"LATE_FEE","amount":"45.00",
                 "status":"%s","description":"Late fee"},"source":{"table":"billing_ledger","lsn":%d},"op":"c"}"""
                .formatted(invoice, status, lsn);
    }

    private CustomerProfile profile() throws Exception {
        return mapper.readValue(redis.store.get("customer:profile:cust_123"), CustomerProfile.class);
    }

    @Test
    void appliesEventAndSkipsExactDuplicate() throws Exception {
        consumer.handle(customerEvent(100, 0), "db.public.customers", 1);
        assertThat(redis.store).containsKey("processed:event:customers:100:cust_123");
        redis.store.put("customer:profile:cust_123", redis.store.get("customer:profile:cust_123")
                .replace("\"waiversUsed12m\":0", "\"waiversUsed12m\":9"));

        consumer.handle(customerEvent(100, 0), "db.public.customers-retry-0", 7); // same change, other offset
        assertThat(profile().waiversUsed12m()).isEqualTo(9); // untouched: dedup caught it
    }

    @Test
    void staleLsnForSameRowIsDropped() throws Exception {
        consumer.handle(customerEvent(200, 1), "db.public.customers", 1);
        consumer.handle(customerEvent(150, 0), "db.public.customers", 2);
        assertThat(profile().waiversUsed12m()).isEqualTo(1);
        assertThat(profile().lastAppliedLsn()).isEqualTo(200);
    }

    @Test
    void olderEventFromAnotherTableStillApplies() throws Exception {
        consumer.handle(customerEvent(300, 0), "db.public.customers", 1);
        consumer.handle(billingEvent(250, "INV-1", "UNPAID"), "db.public.billing_ledger", 1);
        assertThat(profile().recentCharges()).extracting(CustomerProfile.LedgerEntry::invoiceId).containsExactly("INV-1");
        assertThat(profile().lastAppliedLsn()).isEqualTo(300);

        consumer.handle(billingEvent(260, "INV-1", "WAIVED"), "db.public.billing_ledger", 2);
        assertThat(profile().recentCharges()).singleElement()
                .extracting(CustomerProfile.LedgerEntry::status).isEqualTo("WAIVED");
    }

    @Test
    void schemaWrappedEnvelopeIsUnwrapped() throws Exception {
        consumer.handle("{\"schema\":{},\"payload\":" + customerEvent(10, 0) + "}", "db.public.customers", 1);
        assertThat(profile().accountTier()).isEqualTo("Gold");
    }

    @Test
    void failureReleasesDedupKeySoRetryCanApply() throws Exception {
        var ops = redis.template.opsForValue();
        doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString());
        assertThatThrownBy(() -> consumer.handle(customerEvent(400, 0), "db.public.customers", 1))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.store).doesNotContainKey("processed:event:customers:400:cust_123");
    }

    // --- FR-1.5: full hydration when the cached profile is missing or incomplete -----------

    private static final String TICKET_EVENT = """
            {"after":{"ticket_id":"TCK-1","customer_id":"cust_123","category":"BILLING","subject":"Invoice question",
             "status":"OPEN","sla_deadline":"2026-11-30T10:00:00Z"},"source":{"table":"support_tickets","lsn":900},"op":"c"}""";

    private static CustomerProfile warehouseElena() {
        return new CustomerProfile("cust_123", "Elena Vance", "Gold", 36, new java.math.BigDecimal("499.00"), 0, 0L,
                List.of(new CustomerProfile.LedgerEntry("INV-A", "LATE_FEE", new java.math.BigDecimal("45.00"), "UNPAID", "Late fee")),
                List.of(new CustomerProfile.TicketSummary("TCK-1", "BILLING", "Invoice question", "OPEN", "2026-11-30T10:00:00Z")));
    }

    @Test
    void ticketBeforeCustomerHydratesAllThreeTablesFromWarehouse() throws Exception {
        when(warehouse.findProfile("cust_123")).thenReturn(java.util.Optional.of(warehouseElena()));

        consumer.handle(TICKET_EVENT, "db.public.support_tickets", 1);

        CustomerProfile p = profile();
        assertThat(p.fullName()).isEqualTo("Elena Vance");
        assertThat(p.accountTier()).isEqualTo("Gold");
        assertThat(p.recentCharges()).extracting(CustomerProfile.LedgerEntry::invoiceId).containsExactly("INV-A");
        assertThat(p.activeTickets()).extracting(CustomerProfile.TicketSummary::ticketId).containsExactly("TCK-1");
        assertThat(p.lastAppliedLsn()).isEqualTo(900);
        assertThat(redis.store).containsEntry("lsn:support_tickets:TCK-1", "900");

        consumer.handle(TICKET_EVENT.replace("\"lsn\":900", "\"lsn\":850"), "db.public.support_tickets", 2);
        assertThat(profile().activeTickets()).singleElement().extracting(CustomerProfile.TicketSummary::status).isEqualTo("OPEN");
    }

    @Test
    void stubFromEarlierEventIsReplacedByWarehouseProfile() throws Exception {
        consumer.handle(billingEvent(800, "INV-A", "UNPAID"), "db.public.billing_ledger", 1); // warehouse empty -> stub
        assertThat(IdempotentKafkaConsumer.isStub(profile())).isTrue();

        when(warehouse.findProfile("cust_123")).thenReturn(java.util.Optional.of(warehouseElena()));
        consumer.handle(TICKET_EVENT, "db.public.support_tickets", 1);
        assertThat(IdempotentKafkaConsumer.isStub(profile())).isFalse();
        assertThat(profile().accountTier()).isEqualTo("Gold");
    }

    @Test
    void completeProfileNeverReadsWarehouse() {
        consumer.handle(customerEvent(100, 0), "db.public.customers", 1);
        consumer.handle(billingEvent(110, "INV-1", "UNPAID"), "db.public.billing_ledger", 1);
        verify(warehouse, org.mockito.Mockito.times(1)).findProfile("cust_123"); // only for the first (missing) event
    }

    @Test
    void warehouseFailureLeavesEventForRetryWithoutStub() {
        when(warehouse.findProfile("cust_123")).thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        assertThatThrownBy(() -> consumer.handle(TICKET_EVENT, "db.public.support_tickets", 1))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertThat(redis.store).doesNotContainKey("customer:profile:cust_123")
                .doesNotContainKey("processed:event:support_tickets:900:TCK-1");
    }

    @Test
    void unparseablePayloadThrowsForDlt() {
        assertThatThrownBy(() -> consumer.handle("not-json{", "db.public.customers", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** FR-7.5: one INFO line per applied event for each table, ids only, customerId in MDC; none for skips. */
    @Test
    void logsEveryAppliedEventWithIdsOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(IdempotentKafkaConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.handle(customerEvent(500, 0), "db.public.customers", 11);
            consumer.handle(customerEvent(510, 1), "db.public.customers", 12);
            consumer.handle(billingEvent(520, "INV-9", "UNPAID"), "db.public.billing_ledger", 21);
            consumer.handle("""
                    {"after":{"ticket_id":"TCK-7","customer_id":"cust_123","category":"TECHNICAL",
                     "subject":"ERR-504-GW","status":"OPEN","sla_deadline":"2026-11-22T10:00:00Z"},
                     "source":{"table":"support_tickets","lsn":530},"op":"c"}""", "db.public.support_tickets", 31);
            consumer.handle(customerEvent(510, 1), "db.public.customers", 13); // duplicate: no applied line

            List<ILoggingEvent> applied = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("CDC applied")).toList();
            assertThat(applied).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "CDC applied table=customers id=cust_123 created op=u lsn=500 topic=db.public.customers offset=11",
                    "CDC applied table=customers id=cust_123 updated op=u lsn=510 topic=db.public.customers offset=12",
                    "CDC applied table=billing_ledger id=INV-9 created op=c lsn=520 topic=db.public.billing_ledger offset=21",
                    "CDC applied table=support_tickets id=TCK-7 created op=c lsn=530 topic=db.public.support_tickets offset=31");
            assertThat(applied).allSatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getMDCPropertyMap()).containsEntry("customerId", "cust_123");
                assertThat(e.getFormattedMessage()).doesNotContain("Elena", "45.00", "ERR-504-GW");
            });
            assertThat(MDC.get("customerId")).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
