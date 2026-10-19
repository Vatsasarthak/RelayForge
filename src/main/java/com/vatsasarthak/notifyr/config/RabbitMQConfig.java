package com.vatsasarthak.notifyr.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${notification.queue.name}")
    private String queueName;

    @Value("${notification.exchange.name}")
    private String exchangeName;

    @Value("${notification.routing-key}")
    private String routingKey;

    // for retries
    @Value("${notification.retry.exchange}")
    private String retryExchangeName;

    @Value("${notification.retry.queue-30s}")
    private String retryQueue30s;

    @Value("${notification.retry.queue-2m}")
    private String retryQueue2m;

    @Value("${notification.retry.queue-5m}")
    private String retryQueue5m;

    @Value("${notification.retry.routing-key-30s}")
    private String retryRoutingKey30s;

    @Value("${notification.retry.routing-key-2m}")
    private String retryRoutingKey2m;

    @Value("${notification.retry.routing-key-5m}")
    private String retryRoutingKey5m;

    // for dlq
    @Value("${notification.dlq.exchange}")
    private String dlqExchangeName;

    @Value("${notification.dlq.queue}")
    private String dlqQueueName;

    @Value("${notification.dlq.routing-key}")
    private String dlqRoutingKey;

    @Value("${notification.retry.ttl-30s}")
    private long ttl30s;

    @Value("${notification.retry.ttl-2m}")
    private long ttl2m;

    @Value("${notification.retry.ttl-5m}")
    private long ttl5m;

    @Value("${notification.campaign-dispatch.exchange}")
    private String campaignDispatchExchangeName;

    @Value("${notification.campaign-dispatch.queue}")
    private String campaignDispatchQueueName;

    @Value("${notification.campaign-dispatch.routing-key}")
    private String campaignDispatchRoutingKey;


    @Bean
    public Queue notificationQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(exchangeName);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder
                .bind(notificationQueue)
                .to(notificationExchange)
                .with(routingKey);
    }

    // retry exchange
    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(retryExchangeName);
    }

    @Bean
    public Queue retryQueue30s() {  // 30 seconds
        return QueueBuilder.durable(retryQueue30s)
                .withArgument("x-message-ttl", ttl30s)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding30s(Queue retryQueue30s, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue30s).to(retryExchange).with(retryRoutingKey30s);
    }

    @Bean
    public Queue retryQueue2m() {   // 2 mins
        return QueueBuilder.durable(retryQueue2m)
                .withArgument("x-message-ttl", ttl2m)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding2m(Queue retryQueue2m, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue2m).to(retryExchange).with(retryRoutingKey2m);
    }

    @Bean
    public Queue retryQueue5m() {   // 5 mins
        return QueueBuilder.durable(retryQueue5m)
                .withArgument("x-message-ttl", ttl5m)
                .withArgument("x-dead-letter-exchange", exchangeName)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding retryBinding5m(Queue retryQueue5m, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue5m).to(retryExchange).with(retryRoutingKey5m);
    }

    // dlq

    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange(dlqExchangeName);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(dlqQueueName).build();
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlqExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlqExchange).with(dlqRoutingKey);
    }


    // Used by the listener retry interceptor (spring.rabbitmq.listener.simple.retry) once its attempts
    // are exhausted: the message is parked in the DLQ instead of being dropped or redelivered forever.
    @Bean
    public MessageRecoverer deadLetterMessageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, dlqExchangeName, dlqRoutingKey);
    }

    // On shutdown, give a consumer time to finish the delivery it is working on (provider calls time out
    // after 10 s) before the connection closes; the default is 5 s. Unacknowledged prefetched messages
    // go back to the queue.
    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> gracefulShutdownCustomizer() {
        return container -> container.setShutdownTimeout(20_000);
    }

    // The DLQ consumer acknowledges manually and has no retry/recoverer advice: republishing a failed
    // dead letter to the DLQ would just loop it. DeadLetterConsumer nacks with requeue after a pause instead.
    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(10);
        return factory;
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange campaignDispatchExchange(){
        return new DirectExchange(campaignDispatchExchangeName);
    }

    @Bean
    public Queue campaignDispatchQueue(){
        return new Queue(campaignDispatchQueueName, true);
    }

    @Bean
    public Binding campaignDispatchBinding(
            Queue campaignDispatchQueue,
            DirectExchange campaignDispatchExchange){

        return BindingBuilder.bind(campaignDispatchQueue)
                .to(campaignDispatchExchange)
                .with(campaignDispatchRoutingKey);
    }
}
