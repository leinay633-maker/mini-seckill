package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.dto.MessageReplayResponse;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.MessageAdminService;
import com.example.miniseckill.service.SeckillMetrics;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Replays dead or failed local messages back to RabbitMQ.
 */
@Service
public class MessageAdminServiceImpl implements MessageAdminService {

    private final SeckillMessageMapper seckillMessageMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final SeckillProducer seckillProducer;
    private final SeckillMetrics seckillMetrics;

    public MessageAdminServiceImpl(SeckillMessageMapper seckillMessageMapper,
                                   CompensationRecordMapper compensationRecordMapper,
                                   SeckillProducer seckillProducer,
                                   SeckillMetrics seckillMetrics) {
        this.seckillMessageMapper = seckillMessageMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.seckillProducer = seckillProducer;
        this.seckillMetrics = seckillMetrics;
    }

    @Override
    public MessageReplayResponse replayOne(String requestId) {
        SeckillMessageRecord record = seckillMessageMapper.selectByRequestId(requestId);
        if (record == null) {
            throw new BusinessException(404, "消息不存在");
        }
        MessageReplayResponse response = new MessageReplayResponse();
        replayRecord(record, response);
        return response;
    }

    @Override
    public MessageReplayResponse replayDead(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<SeckillMessageRecord> records = seckillMessageMapper.selectDead(MessageStatus.DEAD.getCode(), safeLimit);
        MessageReplayResponse response = new MessageReplayResponse();
        for (SeckillMessageRecord record : records) {
            replayRecord(record, response);
        }
        return response;
    }

    private void replayRecord(SeckillMessageRecord record, MessageReplayResponse response) {
        int updated = seckillMessageMapper.markReplayed(
                record.getRequestId(),
                MessageStatus.REPLAYED.getCode(),
                MessageStatus.DEAD.getCode(),
                MessageStatus.TIMEOUT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode()
        );
        if (updated != 1) {
            response.addSkipped(record.getRequestId());
            seckillMetrics.replay("skipped");
            return;
        }
        SeckillMessage message = new SeckillMessage(
                record.getRequestId(),
                record.getActivityId(),
                record.getUserId(),
                record.getSkuId(),
                System.currentTimeMillis()
        );
        seckillProducer.send(message);
        insertCompensation(record, "MESSAGE_REPLAY", "PUBLISHED", "manual replay to RabbitMQ");
        response.addReplayed(record.getRequestId());
        seckillMetrics.replay("published");
    }

    private void insertCompensation(SeckillMessageRecord record, String type, String status, String detail) {
        CompensationRecord compensation = new CompensationRecord();
        compensation.setRequestId(record.getRequestId());
        compensation.setActivityId(record.getActivityId());
        compensation.setSkuId(record.getSkuId());
        compensation.setType(type);
        compensation.setStatus(status);
        compensation.setDetail(detail);
        compensationRecordMapper.insert(compensation);
    }
}
