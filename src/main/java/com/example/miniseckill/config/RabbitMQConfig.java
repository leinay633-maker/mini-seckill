package com.example.miniseckill.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ queues, exchange, JSON serialization, and manual ACK policy.
 */
@Configuration
@EnableRabbit
public class RabbitMQConfig {

    public static final String SECKILL_ORDER_EXCHANGE = "mini.seckill.order.exchange";
    public static final String SECKILL_ORDER_QUEUE = "mini.seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "mini.seckill.order";
    public static final String SECKILL_DEAD_EXCHANGE = "mini.seckill.dead.exchange";
    public static final String SECKILL_DEAD_QUEUE = "mini.seckill.dead.queue";
    public static final String SECKILL_DEAD_ROUTING_KEY = "mini.seckill.dead";

    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange seckillDeadExchange() {
        return new DirectExchange(SECKILL_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", SECKILL_DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", SECKILL_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue seckillDeadQueue() {
        return QueueBuilder.durable(SECKILL_DEAD_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding seckillDeadBinding() {
        return BindingBuilder.bind(seckillDeadQueue())
                .to(seckillDeadExchange())
                .with(SECKILL_DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory manualAckRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            SeckillProperties seckillProperties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        SeckillProperties.MqConsumer consumer = seckillProperties.getMqConsumer();
        factory.setConcurrentConsumers(Math.max(1, consumer.getConcurrentConsumers()));
        factory.setMaxConcurrentConsumers(Math.max(consumer.getConcurrentConsumers(), consumer.getMaxConcurrentConsumers()));
        factory.setPrefetchCount(Math.max(1, consumer.getPrefetchCount()));
        factory.setAutoStartup(consumer.isAutoStartup());
        return factory;
    }
}
