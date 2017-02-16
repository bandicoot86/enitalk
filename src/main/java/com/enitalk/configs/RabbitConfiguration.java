/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 *
 * @author astrologer
 */
@Configuration
@EnableRabbit
public class RabbitConfiguration implements RabbitListenerConfigurer {

    @Autowired
    @Qualifier("environment")
    private Environment env;
    protected static final Logger logger = LoggerFactory.getLogger("rabbit");

    @Bean
    public ConnectionFactory smConnectionFactory() {

        String host = env.getProperty("mqHost");
        String user = env.getProperty("mqUser");
        String pass = env.getProperty("mqPass");
        logger.info(String.format("Parameters inner: host[%s] user [%s] pass[%s]", host, user, pass));

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(host);
        connectionFactory.setUsername(user);
        connectionFactory.setPassword(pass);
        return connectionFactory;
    }

    @Bean
    public RabbitAdmin smAmqpAdmin() {
        return new RabbitAdmin(smConnectionFactory());
    }

    @Bean
    public RabbitTemplate smRabbitTemplate() {
        return new RabbitTemplate(smConnectionFactory());
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {

    }

    @Bean
    public Queue openTok() {
        Queue sdInViber = new Queue("tokbox", true, false, false);
        return sdInViber;
    }

    @Bean
    public Queue finished() {
        Queue sdInViber = new Queue("finished", true, false, false);
        return sdInViber;
    }

    @Bean
    public Queue yotubeCheck() {
        Queue sdInViber = new Queue("youtube_check", true, false, false);
        return sdInViber;
    }

    @Bean
    public Queue words() {
        Queue sdInViber = new Queue("words", true, false, false);
        return sdInViber;
    }

    @Bean
    public Queue eniwords() {
        Queue sdInViber = new Queue("eniwords", true, false, false);
        return sdInViber;
    }

    public SimpleRabbitListenerEndpoint registerEndpoint(Queue q, MessageListener listener) {
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setQueues(q);
        endpoint.setId(q.getName());
        endpoint.setMessageListener(listener);
        endpoint.setupListenerContainer(rabbitListenerContainerFactory().createListenerContainer(endpoint));
        return endpoint;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory() {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(smConnectionFactory());
        factory.setConcurrentConsumers(5);
        factory.setMaxConcurrentConsumers(50);
        return factory;
    }
}
