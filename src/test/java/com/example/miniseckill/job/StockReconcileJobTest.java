package com.example.miniseckill.job;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.service.InventoryCoordinator;
import java.util.List;
import org.junit.jupiter.api.Test;
class StockReconcileJobTest {
    final SkuStockMapper stocks=mock(SkuStockMapper.class);final InventoryCoordinator inventory=mock(InventoryCoordinator.class);final SeckillProperties properties=new SeckillProperties();
    SkuStock stock(long id) {SkuStock s=new SkuStock();s.setId(id);s.setActivityId(1L);s.setSkuId(1000+id);return s;}
    @Test void eachDiscoveredSkuUsesGuardedCoordinatorAndFailureDoesNotStopPage() {
        when(stocks.selectPageAfterId(anyLong(),anyInt())).thenReturn(List.of(stock(1),stock(2)));
        when(inventory.reconcile(1L,1001L)).thenThrow(new IllegalStateException("Redis unavailable"));
        new StockReconcileJob(stocks,inventory,properties).reconcileStock();
        verify(inventory).reconcile(1L,1001L);verify(inventory).reconcile(1L,1002L);
    }
    @Test void boundedPagesRotateInsteadOfStarvingOlderSkus() {
        properties.getReconcile().setScanLimit(1);properties.getReconcile().setPageSize(1);
        when(stocks.selectPageAfterId(0,1)).thenReturn(List.of(stock(1)));when(stocks.selectPageAfterId(1,1)).thenReturn(List.of(stock(2)));
        StockReconcileJob job=new StockReconcileJob(stocks,inventory,properties);job.reconcileStock();job.reconcileStock();
        verify(inventory).reconcile(1L,1002L);
    }
    @Test void disabledMeansNoReads() {properties.getReconcile().setEnabled(false);new StockReconcileJob(stocks,inventory,properties).reconcileStock();verifyNoInteractions(stocks,inventory);}
}
