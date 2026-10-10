package com.example.miniseckill.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.entity.*;
import com.example.miniseckill.support.CoordinatorHarness;
import com.example.miniseckill.util.RedisKeyUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
class InventoryCoordinatorTest {
    final CoordinatorHarness h=new CoordinatorHarness();
    final String total=RedisKeyUtil.stockKey(1L,1001L), inflight=InventoryCoordinator.inflightKey(1L,1001L), lease=RedisKeyUtil.reconcileLockKey(1L,1001L);
    void reserve() {h.initialize();h.owner("r");assertEquals(1L,h.inventory.reserve(1L,1001L,7L,"r"));}
    SeckillMessageRecord row(MessageStatus status) {SeckillMessageRecord r=new SeckillMessageRecord();r.setRequestId("r");r.setActivityId(1L);r.setSkuId(1001L);r.setUserId(7L);r.setStatus(status.getCode());return r;}
    @Test void unresolvedIntentPreventsEvenReadingAMysqlBudget() {
        reserve();assertEquals("INFLIGHT",h.inventory.reconcile(1L,1001L).outcome());verifyNoInteractions(h.facts);assertEquals("9",h.store.get(total));
    }
    @Test void cancellationFencePrecedesOneTimeRefundAndBlocksLateAdmission() {
        reserve();when(h.messages.selectByRequestId("r")).thenReturn(row(MessageStatus.CANCELLED));
        h.inventory.resolveUncertain(1L,1001L,7L,"r");h.inventory.resolveUncertain(1L,1001L,7L,"r");
        var order=inOrder(h.messages);order.verify(h.messages).insertAdmissionCancellation("r",1L,7L,1001L);order.verify(h.messages).selectByRequestId("r");
        assertEquals("10",h.store.get(total));assertEquals(0,h.store.hlen(inflight));
    }
    @Test void lostCommitReplyResolvedAsDurableNeverRefunds() {
        reserve();when(h.messages.selectByRequestId("r")).thenReturn(row(MessageStatus.SENDING));
        h.inventory.resolveUncertain(1L,1001L,7L,"r");assertEquals("9",h.store.get(total));assertEquals(0,h.store.hlen(inflight));
    }
    @Test void databaseUnknownDoesNotReleaseOrRefundAndAnotherInstanceCanRetry() {
        reserve();when(h.messages.insertAdmissionCancellation("r",1L,7L,1001L)).thenThrow(new IllegalStateException("DB timeout"));
        assertThrows(IllegalStateException.class,()->h.inventory.resolveUncertain(1L,1001L,7L,"r"));
        assertEquals("9",h.store.get(total));assertEquals(1,h.store.hlen(inflight));verify(h.messages,never()).selectByRequestId(anyString());
    }
    @Test void acceptedSettlementIsNoRefundAndFailureRemainsRecoverable() {
        reserve();h.store.delete(InventoryCoordinator.versionKey(1L,1001L));
        assertDoesNotThrow(()->h.inventory.accepted(1L,1001L,7L,"r"));assertEquals(1,h.store.hlen(inflight));assertEquals("9",h.store.get(total));
    }
    @Test void expiryAllowsResolverToCancelButDoesNotItselfExpireTheIntent() {
        reserve();when(h.messages.selectByRequestId("r")).thenReturn(row(MessageStatus.CANCELLED));
        h.inventory.recoverExpired(1L,1001L);verifyNoInteractions(h.messages);h.store.advance(60_001);
        h.inventory.recoverExpired(1L,1001L);assertEquals(0,h.store.hlen(inflight));assertEquals("10",h.store.get(total));
    }
    @Test void resolverIsolatesFailuresAndLeavesFailedRowVisible() {
        reserve();h.store.advance(60_001);when(h.messages.insertAdmissionCancellation(any(),any(),any(),any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertDoesNotThrow(()->h.inventory.recoverExpired(1L,1001L));verify(h.metrics).coordination("reservation_unknown");assertEquals(1,h.store.hlen(inflight));
    }
    @Test void expiredOwnerCannotRepairOrUnlockSuccessorEvenWithAValidMysqlSnapshot() {
        h.initialize();when(h.facts.snapshot(1L,1001L,false)).thenReturn(new StockFacts(10,10,0,0,0));
        doAnswer(i->{h.store.advance(20_001);assertTrue(h.store.lease(lease,"successor",20_000));return null;}).when(h.faults).hit(eq("after-snapshot"),anyString());
        assertEquals("LEASE_LOST",h.inventory.reconcile(1L,1001L).outcome());assertEquals("successor",h.store.get(lease));assertEquals("10",h.store.get(total));
    }
    @Test void acceptedConcurrentReservationInvalidatesOldBudgetEvenAfterLedgerIsEmpty() {
        h.initialize();h.owner("r");when(h.facts.snapshot(1L,1001L,false)).thenReturn(new StockFacts(10,10,0,0,0));
        doAnswer(i->{h.inventory.reserve(1L,1001L,7L,"r");h.inventory.accepted(1L,1001L,7L,"r");return null;}).when(h.faults).hit(eq("after-snapshot"),anyString());
        assertEquals("VERSION_CHANGED",h.inventory.reconcile(1L,1001L).outcome());assertEquals("9",h.store.get(total));assertEquals(0,h.store.hlen(inflight));
    }
    @Test void mysqlCountMismatchIsNotAutoCorrectedFromNonAtomicReads() {
        h.initialize();when(h.facts.snapshot(1L,1001L,false)).thenReturn(new StockFacts(10,8,2,1,0));
        assertEquals("MYSQL_MISMATCH",h.inventory.reconcile(1L,1001L).outcome());assertEquals("10",h.store.get(total));
    }
    @Test void validBudgetRepairAndLocalCacheFailureCannotReverseIt() {
        h.initialize();h.store.set(total,"1");when(h.facts.snapshot(1L,1001L,false)).thenReturn(new StockFacts(10,8,2,2,1));
        doThrow(new IllegalStateException("local cache")).when(h.soldOut).clear(1L,1001L);
        assertEquals("APPLIED",h.inventory.reconcile(1L,1001L).outcome());assertEquals("7",h.store.get(total));assertNull(h.store.get(lease));
    }
    @Test void busyLeaseAndMissingMetadataFailClosedWithoutASnapshot() {
        h.initialize();h.store.lease(lease,"B",20_000);assertEquals("BUSY",h.inventory.reconcile(1L,1001L).outcome());h.store.advance(20_001);h.store.delete(InventoryCoordinator.versionKey(1L,1001L));
        assertEquals("UNINITIALIZED",h.inventory.reconcile(1L,1001L).outcome());verifyNoInteractions(h.facts);
    }
    @Test void inheritedRepeatableReadTransactionIsRejected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->h.inventory.reconcile(1L,1001L));verifyNoInteractions(h.facts);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void factsRejectOverflowOrNegativeAndClampConservativeLiabilities() {
        assertTrue(new StockFacts(10,1,9,9,3).consistent());assertEquals(0,new StockFacts(10,1,9,9,3).expected());
        assertFalse(new StockFacts(10,11,-1,0,0).consistent());assertFalse(new StockFacts(10,1,8,8,0).consistent());
    }
    @Test void requestIdentityCollisionCannotCancelDifferentOwnersReservation() {
        reserve();var r=row(MessageStatus.CANCELLED);r.setUserId(999L);when(h.messages.selectByRequestId("r")).thenReturn(r);
        assertThrows(IllegalStateException.class,()->h.inventory.resolveUncertain(1L,1001L,7L,"r"));assertEquals(1,h.store.hlen(inflight));assertEquals("9",h.store.get(total));
    }
}
