package com.example.miniseckill.service.impl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class RedisRecoveryServiceImplTest {
    final SkuStockMapper stocks=mock(SkuStockMapper.class);final InventoryCoordinator inventory=mock(InventoryCoordinator.class);final SeckillProperties p=new SeckillProperties();final RedisRecoveryStateService state=mock(RedisRecoveryStateService.class);
    final RedisRecoveryServiceImpl service=new RedisRecoveryServiceImpl(stocks,inventory,p,state);
    void page() {SkuStock s=new SkuStock();s.setId(1L);s.setActivityId(1L);s.setSkuId(1001L);when(stocks.selectPageAfterId(eq(0L),anyInt())).thenReturn(List.of(s));}
    @Test void onlySuccessfulGuardedRepairsCountAsRecovered() {page();when(inventory.reconcile(1L,1001L)).thenReturn(new InventoryCoordinator.RepairResult("APPLIED",3L));var r=service.recoverRedisStock();assertEquals(1,r.getRecoveredSkuCount());assertEquals(3,r.getTotalExpectedRedisStock());verify(state).markRecovered();}
    @Test void missingMetadataDoesNotGetReinitializedOrReportedAsSuccess() {page();when(inventory.reconcile(1L,1001L)).thenReturn(new InventoryCoordinator.RepairResult("UNINITIALIZED",null));assertThrows(BusinessException.class,service::recoverRedisStock);verify(state,never()).markRecovered();verify(inventory,never()).initializeNew(anyLong(),anyLong(),anyInt());}
    @Test void busyOrInFlightLeavesLocalRecoveryGateClosed() {page();when(inventory.reconcile(1L,1001L)).thenReturn(new InventoryCoordinator.RepairResult("INFLIGHT",null));assertThrows(BusinessException.class,service::recoverRedisStock);verify(state).markRecovering(anyString());verify(state,never()).markRecovered();}
    @Test void scanBudgetIsNotProofOfFullRecovery() {page();p.getRedisRecovery().setScanLimit(1);when(inventory.reconcile(1L,1001L)).thenReturn(new InventoryCoordinator.RepairResult("UNCHANGED",3L));when(stocks.selectPageAfterId(1,1)).thenReturn(List.of(new SkuStock()));assertThrows(BusinessException.class,service::recoverRedisStock);verify(state,never()).markRecovered();}
}
