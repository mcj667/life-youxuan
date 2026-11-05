package com.youxuan.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置:声明秒杀下单用的交换机、队列、绑定,以及 JSON 消息转换器。
 */
@Configuration
public class   RabbitMQConfig {

    // 秒杀下单交换机
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";
    // 秒杀下单队列
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    // 路由 key
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    /**
     * 直连交换机(durable=true:broker 重启后交换机还在)
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE, true, false);
    }

    /**
     * 队列(durable=true:broker 重启后队列还在,消息不丢)
     */
    @Bean
    public Queue seckillOrderQueue() {
        return new Queue(SECKILL_ORDER_QUEUE, true);
    }

    /**
     * 把队列按 routing key 绑定到交换机
     */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * 用 JSON 收发 VoucherOrder 对象(默认的 Java 序列化不通用,也不方便看消息内容)
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
