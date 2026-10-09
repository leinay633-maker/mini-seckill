package com.example.miniseckill.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.config.ConsumerExecutionProperties;
import com.example.miniseckill.config.ConsumerPoolContext;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.service.ConsumerOrderTransactions;
import com.example.miniseckill.service.SeckillMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class SeckillConsumerTest {
    @Mock ConsumerOrderTransactions transactions;
    @Mock SeckillLogMapper logs;
    @Mock CompensationRecordMapper compensation;
    @Mock StringRedisTemplate redis;
    @Mock SeckillMetrics metrics;
    @Mock Channel channel;
    private SeckillConsumer consumer;
    private final SeckillMessage message = new SeckillMessage("req-001", 1L, 10007L, 1001L, 1L);

    @BeforeEach
    void setUp() {
        ConsumerExecutionProperties execution = new ConsumerExecutionProperties();
        execution.setRetryBackoff(Duration.ZERO);
        consumer = new SeckillConsumer(transactions, logs, compensation, redis,
                new SeckillProperties(), execution, metrics);
    }

    @Test
    void terminalMessageIsAcknowledgedWithoutBusinessWrites() throws Exception {
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        verify(metrics).mq("consume_skipped");
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void successUsesConsumerRoleAndAcksOnlyAfterTransactionReturns() throws Exception {
        when(transactions.create(message)).thenAnswer(invocation -> {
            assertTrue(ConsumerPoolContext.isConsumer());
            verifyNoInteractions(channel);
            return true;
        });
        doAnswer(invocation -> {
            assertFalse(ConsumerPoolContext.isConsumer());
            return null;
        }).when(channel).basicAck(42L, false);
        consumer.consume(message, raw(), channel);
        verify(metrics).mq("consume_success");
        verifyNoInteractions(compensation, redis);
        assertFalse(ConsumerPoolContext.isConsumer());
    }

    static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(new CannotGetJdbcConnectionException("claim cannot borrow"),
                new CannotCreateTransactionException("transaction cannot borrow"),
                new TransientDataAccessResourceException("temporary database failure"));
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void infrastructureFailureNacksWithoutRetryStateWriteOrDead(RuntimeException failure) throws Exception {
        when(transactions.create(message)).thenThrow(failure);
        consumer.consume(message, raw(), channel);
        requeued();
        verify(transactions, never()).markDead(any(), any());
        verifyNoInteractions(compensation, redis);
        assertFalse(ConsumerPoolContext.isConsumer());
    }

    @Test
    void wrappedConnectionFailureIsAlsoRetryable() throws Exception {
        when(transactions.create(message)).thenThrow(new IllegalStateException("mybatis wrapper",
                new CannotGetJdbcConnectionException("connection timeout")));
        consumer.consume(message, raw(), channel);
        requeued();
        verify(transactions, never()).markDead(any(), any());
    }

    @Test
    void duplicateReconciliationMustCommitBeforeAck() throws Exception {
        when(transactions.create(message)).thenThrow(new DuplicateKeyException("duplicate"));
        when(transactions.reconcile(message)).thenReturn(true);
        consumer.consume(message, raw(), channel);
        var order = inOrder(transactions, channel);
        order.verify(transactions).create(message);
        order.verify(transactions).reconcile(message);
        order.verify(channel).basicAck(42L, false);
        verify(metrics).mq("duplicate_acked");
    }

    @Test
    void reconciliationFailureDoesNotLeaveAnUnsettledDeliveryOrPretendSuccess() throws Exception {
        when(transactions.create(message)).thenThrow(new DuplicateKeyException("order id collision"));
        when(transactions.reconcile(message)).thenThrow(new IllegalStateException("no matching business fact"));
        consumer.consume(message, raw(), channel);
        requeued();
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void stockFailureIsAckedOnlyAfterDurableFailureCommit() throws Exception {
        when(transactions.create(message)).thenThrow(new InsufficientStockException("empty"));
        when(transactions.failStock(message)).thenReturn(true);
        consumer.consume(message, raw(), channel);
        var order = inOrder(transactions, channel);
        order.verify(transactions).create(message);
        order.verify(transactions).failStock(message);
        order.verify(channel).basicAck(42L, false);
        verify(metrics).mq("stock_guard_failed");
    }

    @Test
    void failedOrderHandlerCannotBorrowSoDeliveryIsRequeued() throws Exception {
        when(transactions.create(message)).thenThrow(new InsufficientStockException("empty"));
        when(transactions.failStock(message)).thenThrow(new CannotCreateTransactionException("starved"));
        consumer.consume(message, raw(), channel);
        requeued();
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void deadHandlerFailureIsRequeuedWithoutFailureProjection() throws Exception {
        when(transactions.create(message)).thenThrow(new IllegalArgumentException("bad payload"));
        when(transactions.markDead(eq(message), any())).thenThrow(new CannotGetJdbcConnectionException("starved"));
        consumer.consume(message, raw(), channel);
        requeued();
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void committedPermanentFailureIsDeadLettered() throws Exception {
        when(transactions.create(message)).thenThrow(new IllegalArgumentException("bad payload"));
        when(transactions.markDead(eq(message), any())).thenReturn(true);
        consumer.consume(message, raw(), channel);
        verify(channel).basicNack(42L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(compensation).insert(any());
        verify(metrics).mq("dead_lettered");
    }

    @Test
    void lostDeadClaimNeverOverwritesANewerOutcome() throws Exception {
        when(transactions.create(message)).thenThrow(new IllegalArgumentException("bad payload"));
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void lostReconcileClaimIsSkipped() throws Exception {
        when(transactions.create(message)).thenThrow(new DuplicateKeyException("duplicate"));
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        verify(metrics).mq("consume_skipped");
    }

    @Test
    void lostFailureClaimIsSkipped() throws Exception {
        when(transactions.create(message)).thenThrow(new InsufficientStockException("empty"));
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void ackIoFailureCannotWriteBusinessFailureOrAttemptSecondSettlement() throws Exception {
        when(transactions.create(message)).thenReturn(true);
        doThrow(new IOException("ack failed")).when(channel).basicAck(42L, false);
        assertThrows(IOException.class, () -> consumer.consume(message, raw(), channel));
        verify(channel).abort();
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(transactions, never()).markDead(any(), any());
        verifyNoInteractions(compensation, redis);
    }

    @Test
    void ackRuntimeFailureAlsoClosesTransportWithoutBusinessCompensation() throws Exception {
        when(transactions.create(message)).thenReturn(true);
        doThrow(new IllegalStateException("closed")).when(channel).basicAck(42L, false);
        assertThrows(IllegalStateException.class, () -> consumer.consume(message, raw(), channel));
        verify(channel).abort();
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void nackFailureClosesPhysicalCachedChannelAndPreservesOriginalException() throws Exception {
        ChannelProxy proxy = mock(ChannelProxy.class);
        when(proxy.getTargetChannel()).thenReturn(channel);
        when(transactions.create(message)).thenThrow(new CannotCreateTransactionException("starved"));
        IOException expected = new IOException("nack failed");
        doThrow(expected).when(proxy).basicNack(42L, false, true);
        doThrow(new IOException("abort failed")).when(channel).abort();
        assertSame(expected, assertThrows(IOException.class, () -> consumer.consume(message, raw(), proxy)));
        assertEquals(1, expected.getSuppressed().length);
        verify(channel).abort();
        verify(proxy, never()).basicAck(anyLong(), anyBoolean());
        verify(proxy, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void observabilityFailureCannotUndoCommit() throws Exception {
        when(transactions.create(message)).thenReturn(true);
        doThrow(new IllegalStateException("metric failed")).when(metrics).mq("consume_success");
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        verify(transactions, never()).markDead(any(), any());
    }

    @Test
    void bestEffortLoggingFailureDoesNotPreventSettlement() throws Exception {
        when(transactions.create(message)).thenThrow(new DuplicateKeyException("duplicate"));
        when(transactions.reconcile(message)).thenReturn(true);
        doThrow(new CannotGetJdbcConnectionException("log starved"))
                .when(logs).insertLog(any(), anyLong(), anyLong(), anyLong(), any());
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
    }

    @Test
    void interruptedBackoffStillRequeuesAndRestoresInterrupt() throws Exception {
        when(transactions.create(message)).thenThrow(new CannotCreateTransactionException("starved"));
        Thread.currentThread().interrupt();
        try {
            consumer.consume(message, raw(), channel);
            assertTrue(Thread.currentThread().isInterrupted());
            requeued();
        } finally { Thread.interrupted(); }
    }

    private void requeued() throws IOException {
        verify(channel).basicNack(42L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(metrics).mq("consume_retry_requeued");
    }

    private Message raw() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
