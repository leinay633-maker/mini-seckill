package com.example.miniseckill.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.StockCoordinationProperties;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.impl.MessageAdminServiceImpl;
import com.example.miniseckill.support.CoordinatorHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CoordinationBoundaryTest {
    @TempDir Path directory;

    @Test void stockWritesAndCancellationRefuseAmbientDatabaseTransaction() {
        CoordinatorHarness h = new CoordinatorHarness();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> h.inventory.reserve(1L,1001L,7L,"r"));
            assertThrows(IllegalStateException.class, () -> h.inventory.accepted(1L,1001L,7L,"r"));
            assertThrows(IllegalStateException.class, () -> h.inventory.resolveUncertain(1L,1001L,7L,"r"));
            assertThrows(IllegalStateException.class, () -> h.inventory.initializeNew(1L,1001L,10));
            assertThrows(IllegalStateException.class, () -> h.inventory.reconcile(1L,1001L));
            verifyNoInteractions(h.messages,h.facts,h.faults);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertDoesNotThrow(h::initialize);
    }

    @Test void invalidPolicyIsRejectedAtStartup() {
        StockCoordinationProperties p = new StockCoordinationProperties();
        assertDoesNotThrow(p::validate);
        p.setRepairLease(Duration.ZERO); assertThrows(IllegalArgumentException.class,p::validate);
        p.setRepairLease(Duration.ofSeconds(20)); p.setReservationTimeout(Duration.ofMillis(1));
        assertThrows(IllegalArgumentException.class,p::validate);
        p.setReservationTimeout(Duration.ofSeconds(60)); p.setRecoveryBatchSize(1001);
        assertThrows(IllegalArgumentException.class,p::validate);
    }

    @Test void faultsDisabledByDefaultNeverTouchAnArmedDirectory() throws Exception {
        StockCoordinationProperties p = new StockCoordinationProperties(); p.setFaultDirectory(directory.toString());
        Files.writeString(directory.resolve("after-reserve.arm"), "armed");
        new CoordinationFaults(p,mock(Environment.class)).hit("after-reserve","identity");
        assertTrue(Files.exists(directory.resolve("after-reserve.arm")));
        assertFalse(Files.exists(directory.resolve("after-reserve.hit")));
    }

    @Test void faultsRequireExplicitProfileAndDirectory() {
        StockCoordinationProperties p = new StockCoordinationProperties(); p.setFaultsEnabled(true);
        Environment env = mock(Environment.class);
        assertThrows(IllegalArgumentException.class, () -> new CoordinationFaults(p,env));
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> new CoordinationFaults(p,env));
        p.setFaultDirectory(directory.toString());
        assertDoesNotThrow(() -> new CoordinationFaults(p,env));
    }

    @Test void oneShotBarrierCannotBeRearmedBySecondInvocation() throws Exception {
        StockCoordinationProperties p = new StockCoordinationProperties();
        p.setFaultsEnabled(true); p.setFaultDirectory(directory.toString());
        Environment env = mock(Environment.class); when(env.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        Files.writeString(directory.resolve("after-reserve.arm"),"armed");
        Files.writeString(directory.resolve("after-reserve.release"),"release");
        CoordinationFaults f = new CoordinationFaults(p,env);
        f.hit("after-reserve","first"); f.hit("after-reserve","second");
        assertEquals("first\n",Files.readString(directory.resolve("after-reserve.hit")));
        assertTrue(Files.exists(directory.resolve("after-reserve.claimed")));
    }

    @ParameterizedTest @ValueSource(ints={1,2,6,7,10,11})
    void manualReplayCannotReopenTerminalOrConsumerOwnedRows(int status) {
        SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
        SeckillMessageRecord row = new SeckillMessageRecord(); row.setStatus(status);row.setRequestId("r");
        when(messages.selectByRequestId("r")).thenReturn(row);
        SeckillProducer producer = mock(SeckillProducer.class);
        MessageAdminServiceImpl admin = new MessageAdminServiceImpl(messages,mock(CompensationRecordMapper.class),producer,mock(SeckillMetrics.class));
        assertEquals(409,assertThrows(BusinessException.class, () -> admin.replayOne("r")).getCode());
        verifyNoInteractions(producer);
    }

    @Test void bulkDeadReplayRefusedWithoutEvenScanning() {
        SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
        SeckillProducer producer = mock(SeckillProducer.class);
        MessageAdminServiceImpl admin = new MessageAdminServiceImpl(messages,mock(CompensationRecordMapper.class),producer,mock(SeckillMetrics.class));
        assertThrows(BusinessException.class, () -> admin.replayDead(100));
        verifyNoInteractions(messages,producer);
    }

    @Test void retryCandidateThatLostItsLeaseDoesNotClaimPublishedSuccess() {
        SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
        SeckillMessageRecord row = new SeckillMessageRecord(); row.setStatus(3);row.setRequestId("r");
        when(messages.selectByRequestId("r")).thenReturn(row);
        SeckillProducer producer = mock(SeckillProducer.class);
        CompensationRecordMapper audit = mock(CompensationRecordMapper.class);
        MessageAdminServiceImpl admin = new MessageAdminServiceImpl(messages,audit,producer,mock(SeckillMetrics.class));
        assertEquals(1,admin.replayOne("r").getSkippedCount()); verifyNoInteractions(audit);
    }

    @Test void existingSkuCannotResetSegmentFacts() {
        SkuStockMapper stocks = mock(SkuStockMapper.class);
        SkuStockSegmentMapper segments = mock(SkuStockSegmentMapper.class);
        when(stocks.insertStock(1L,1001L,100)).thenThrow(new DuplicateKeyException("existing"));
        assertThrows(BusinessException.class, () -> new StockInitialization(stocks,segments,new SeckillProperties()).create(1L,1001L,100));
        verifyNoInteractions(segments);
    }
}
