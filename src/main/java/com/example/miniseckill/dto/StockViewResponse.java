package com.example.miniseckill.dto;

/**
 * Combined Redis and MySQL stock view for debugging and interviews.
 */
public class StockViewResponse {

    private Long skuId;
    private Long activityId;
    private Integer redisStock;
    private Integer mysqlTotalStock;
    private Integer mysqlAvailableStock;
    private Integer mysqlSoldCount;
    private Boolean shardingEnabled;
    private Integer bucketCount;
    private Boolean mysqlSegmentEnabled;
    private Integer mysqlSegmentCount;

    public StockViewResponse() {
    }

    public StockViewResponse(Long activityId, Long skuId, Integer redisStock, Integer mysqlTotalStock,
                             Integer mysqlAvailableStock, Integer mysqlSoldCount) {
        this.activityId = activityId;
        this.skuId = skuId;
        this.redisStock = redisStock;
        this.mysqlTotalStock = mysqlTotalStock;
        this.mysqlAvailableStock = mysqlAvailableStock;
        this.mysqlSoldCount = mysqlSoldCount;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Integer getRedisStock() {
        return redisStock;
    }

    public void setRedisStock(Integer redisStock) {
        this.redisStock = redisStock;
    }

    public Integer getMysqlTotalStock() {
        return mysqlTotalStock;
    }

    public void setMysqlTotalStock(Integer mysqlTotalStock) {
        this.mysqlTotalStock = mysqlTotalStock;
    }

    public Integer getMysqlAvailableStock() {
        return mysqlAvailableStock;
    }

    public void setMysqlAvailableStock(Integer mysqlAvailableStock) {
        this.mysqlAvailableStock = mysqlAvailableStock;
    }

    public Integer getMysqlSoldCount() {
        return mysqlSoldCount;
    }

    public void setMysqlSoldCount(Integer mysqlSoldCount) {
        this.mysqlSoldCount = mysqlSoldCount;
    }

    public Boolean getShardingEnabled() {
        return shardingEnabled;
    }

    public void setShardingEnabled(Boolean shardingEnabled) {
        this.shardingEnabled = shardingEnabled;
    }

    public Integer getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(Integer bucketCount) {
        this.bucketCount = bucketCount;
    }

    public Boolean getMysqlSegmentEnabled() {
        return mysqlSegmentEnabled;
    }

    public void setMysqlSegmentEnabled(Boolean mysqlSegmentEnabled) {
        this.mysqlSegmentEnabled = mysqlSegmentEnabled;
    }

    public Integer getMysqlSegmentCount() {
        return mysqlSegmentCount;
    }

    public void setMysqlSegmentCount(Integer mysqlSegmentCount) {
        this.mysqlSegmentCount = mysqlSegmentCount;
    }
}
