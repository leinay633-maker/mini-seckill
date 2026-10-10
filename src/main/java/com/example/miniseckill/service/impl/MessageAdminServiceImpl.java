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
 * Retries still-budgeted send states through the normal attempt claim. Terminal reopening is forbidden.
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
        throw new BusinessException(409,"终态消息禁止原地重放；请核对事实后以新请求重新准入");
    }

    private void replayRecord(SeckillMessageRecord record, MessageReplayResponse response) {
        if (!List.of(0,3,4,5,8,9).contains(record.getStatus()))
            throw new BusinessException(409,"只允许重试仍占用库存预算的发送态；不能重开终态消息");
        SeckillMessage message = new SeckillMessage(record.getRequestId(),record.getActivityId(),record.getUserId(),
                record.getSkuId(),System.currentTimeMillis());
        if (!seckillProducer.send(message)) { response.addSkipped(record.getRequestId()); return; }
        insertCompensation(record,"MESSAGE_RETRY","PUBLISHED","manual send-side retry with attempt token");
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
