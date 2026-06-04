package com.example.miniseckill.service;

import com.example.miniseckill.dto.MessageReplayResponse;

/**
 * Manual replay entry for local message records that need compensation.
 */
public interface MessageAdminService {

    MessageReplayResponse replayOne(String requestId);

    MessageReplayResponse replayDead(int limit);
}
