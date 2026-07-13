package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@MybatisTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SkuStockMapperIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mini_seckill_it")
            .withUsername("miniseckill")
            .withPassword("miniseckill");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SkuStockMapper skuStockMapper;

    @Autowired
    private SkuStockSegmentMapper skuStockSegmentMapper;

    @Test
    void shouldDeductSegmentStockAndSyncSummaryWithMysqlContainer() {
        createSchema();

        skuStockMapper.upsertStock(1L, 1001L, 10);
        skuStockSegmentMapper.upsertSegment(1L, 1001L, 0, 6);
        skuStockSegmentMapper.upsertSegment(1L, 1001L, 1, 4);

        assertEquals(1, skuStockSegmentMapper.decreaseSegmentStock(1L, 1001L, 0));
        assertEquals(1, skuStockSegmentMapper.decreaseAnySegmentStock(1L, 1001L));
        assertEquals(8, skuStockSegmentMapper.sumAvailableStock(1L, 1001L));
        assertEquals(2, skuStockSegmentMapper.sumSoldCount(1L, 1001L));

        skuStockMapper.syncFromSegments(1L, 1001L);
        SkuStock stock = skuStockMapper.selectBySkuId(1L, 1001L);

        assertNotNull(stock);
        assertEquals(10, stock.getTotalStock());
        assertEquals(8, stock.getAvailableStock());
        assertEquals(2, stock.getSoldCount());
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sku_stock (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  activity_id BIGINT NOT NULL DEFAULT 1,
                  sku_id BIGINT NOT NULL,
                  total_stock INT NOT NULL,
                  available_stock INT NOT NULL,
                  sold_count INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL,
                  updated_at DATETIME NOT NULL,
                  UNIQUE KEY uk_activity_sku (activity_id, sku_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sku_stock_segment (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  activity_id BIGINT NOT NULL,
                  sku_id BIGINT NOT NULL,
                  segment_id INT NOT NULL,
                  total_stock INT NOT NULL,
                  available_stock INT NOT NULL,
                  sold_count INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL,
                  updated_at DATETIME NOT NULL,
                  UNIQUE KEY uk_activity_sku_segment (activity_id, sku_id, segment_id),
                  KEY idx_activity_sku (activity_id, sku_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }
}
