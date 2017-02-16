/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 *
 * @author astrologer
 */
@Configuration
public class BotConfig {

    @Autowired
    private Environment env;
    @Autowired
    private ObjectMapper jackson;

    protected final static Logger logger = LoggerFactory.getLogger("cache-bot-api");

    @Bean
    @Primary
    public LoadingCache<String, String> tokenCache() {
        LoadingCache<String, String> cache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.DAYS).build(new CacheLoader<String, String>() {

            @Override
            public String load(String key) throws Exception {
                String id = jackson.createObjectNode().put("login", env.getProperty("bot.login")).put("password", env.getProperty("bot.pass")).toString();
                byte[] auth = Request.Post(env.getProperty("bot.auth")).bodyString(id, ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
                JsonNode tree = jackson.readTree(auth);
                logger.info("Bot token came {}", tree);
                String authToken = tree.path("token").asText();
                return authToken;
            }

        });
        return cache;
    }

    @Bean(name = "offsetMap")
    public TreeMap<Long, String> timezones() throws IOException {
        ObjectMapper j = new ObjectMapper();
        JsonNode o = j.readTree(new ClassPathResource("dates/treeTz.json").getInputStream());
        TreeMap<Long, String> offsets = new TreeMap<>();
        Iterator<JsonNode> it = o.elements();
        while (it.hasNext()) {
            JsonNode el = it.next();
            String key = el.fieldNames().next();
            offsets.put(Long.valueOf(key), el.path(key).iterator().next().toString());
        }

        return offsets;

    }

}
