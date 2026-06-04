package com.example.miniseckill.service;

import com.example.miniseckill.dto.RedisRecoveryResponse;

/**
 * Rebuilds Redis stock after Redis data loss or restart.
 */
public interface RedisRecoveryService {

    RedisRecoveryResponse recoverRedisStock();
}
