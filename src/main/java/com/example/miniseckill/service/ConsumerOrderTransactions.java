package com.example.miniseckill.service;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claim + outcome share one connection and one commit. Every method must be
 * called through this Spring proxy, never from another method of this class.
 * On rollback the claim disappears; broker redelivery can claim SENT/SENDING
 * again, without a retry-state write that itself requires a spare connection. */
@Service
public class ConsumerOrderTransactions {
    private final SeckillMessageMapper messages;
    private final OrderService orders;

    public ConsumerOrderTransactions(SeckillMessageMapper messages, OrderService orders) {
        this.messages = messages;
        this.orders = orders;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean create(SeckillMessage message) {
        if (!claim(message)) { return false; }
        orders.createOrderFromConsumingMessage(message);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean reconcile(SeckillMessage message) {
        if (!claim(message)) { return false; }
        orders.reconcileExistingOrderFromConsumingMessage(message);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean failStock(SeckillMessage message) {
        if (!claim(message)) { return false; }
        orders.recordFailedOrder(message);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markDead(SeckillMessage message, String error) {
        if (!claim(message)) { return false; }
        if (messages.markDeadFromConsuming(message.getRequestId(), MessageStatus.DEAD.getCode(),
                MessageStatus.CONSUMING.getCode(), error) != 1) {
            throw new IllegalStateException("lost message claim while recording terminal failure");
        }
        return true;
    }

    private boolean claim(SeckillMessage message) {
        return messages.markConsuming(message.getRequestId(), MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(), MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()) == 1;
    }
}
