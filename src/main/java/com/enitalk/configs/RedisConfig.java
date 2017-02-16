/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 *
 * @author astrologer
 */
@Configuration
public class RedisConfig {

    protected static final Logger logger = LoggerFactory.getLogger("pandly-redis");

    @Autowired
    @Qualifier("environment")
    private Environment env;

    @Bean
    public JedisConnectionFactory jedisConnectionFactory() {
        JedisConnectionFactory j = new JedisConnectionFactory();
        j.setUsePool(true);
        String redisHost = env.getProperty("redis.host", "localhost");
        logger.info("Redis env host {}", redisHost);
        j.setHostName(redisHost);
        if (StringUtils.isNotBlank(env.getProperty("redis.pass"))) {
            j.setPassword(env.getProperty("redis.pass"));
        }
        return j;
    }

    @Bean
    public RedisSerializer redisSerializer() {
        return new StringRedisSerializer();
    }

    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(jedisConnectionFactory());
        redisTemplate.setKeySerializer(redisSerializer());
        redisTemplate.setHashKeySerializer(redisSerializer());
        redisTemplate.setHashValueSerializer(redisSerializer());

        return redisTemplate;
    }

}
