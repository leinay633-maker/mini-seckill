package com.example.miniseckill.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumerOrderTransactionsTest {
    private final SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
    private final OrderService orders = mock(OrderService.class);
    private final SeckillMessage message = new SeckillMessage("test", 1L, 1L, 1001L, 1L);
    private ConsumerOrderTransactions transactions;
    @BeforeEach void setUp() { transactions = new ConsumerOrderTransactions(messages, orders); }
    @Test void createClaimsBeforeOrderBody() {
        claim();
        assertTrue(transactions.create(message));
        var sequence = inOrder(messages, orders);
        sequence.verify(messages).markConsuming(eq("test"), anyInt(), anyInt(), anyInt(), anyInt());
        sequence.verify(orders).createOrderFromConsumingMessage(message);
    }
    @Test void reconcileClaimsBeforeReadingBusinessFact() {
        claim(); assertTrue(transactions.reconcile(message));
        verify(orders).reconcileExistingOrderFromConsumingMessage(message);
    }
    @Test void businessFailureClaimsBeforeRecordingOrder() {
        claim(); assertTrue(transactions.failStock(message)); verify(orders).recordFailedOrder(message);
    }
    @Test void deadTransitionRequiresTheClaim() {
        claim();
        when(messages.markDeadFromConsuming("test", MessageStatus.DEAD.getCode(),
                MessageStatus.CONSUMING.getCode(), "error")).thenReturn(1);
        assertTrue(transactions.markDead(message, "error"));
    }
    @Test void lostClaimNeverWritesAnOutcome() {
        assertFalse(transactions.create(message));
        assertFalse(transactions.reconcile(message));
        assertFalse(transactions.failStock(message));
        assertFalse(transactions.markDead(message, "error"));
        verifyNoInteractions(orders);
        verify(messages, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
    }
    @Test void failedDeadCasRaisesAnErrorSoProxyRollsBack() {
        claim(); assertThrows(IllegalStateException.class, () -> transactions.markDead(message, "error"));
    }
    private void claim() {
        when(messages.markConsuming("test", MessageStatus.CONSUMING.getCode(), MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(), MessageStatus.REPLAYED.getCode())).thenReturn(1);
    }
}
