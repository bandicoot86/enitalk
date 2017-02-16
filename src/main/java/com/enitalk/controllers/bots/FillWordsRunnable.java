/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.bots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Iterator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 *
 * @author krash
 */
@Component
public class FillWordsRunnable {

    protected final static Logger logger = LoggerFactory.getLogger("wordnik-api");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private Environment env;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private RedisTemplate<String, String> redis;


//    @Scheduled(fixedDelay = 1000 * 60 * 60 * 12)
    public void init() {
        try {
            sendCandidates(env.getProperty("words.verb"), 0);
            sendCandidates(env.getProperty("words.adjective"), 1);
            sendCandidates(env.getProperty("words.adverb"), 2);
            sendCandidates(env.getProperty("words.noun"), 3);
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendCandidates(String url, Integer type) {
        try {
            Response json = Request.Get(url).execute();
            String rs = json.returnContent().asString();
            JsonNode randomContent = jackson.readTree(rs);

            Iterator<JsonNode> els = randomContent.elements();
            while (els.hasNext()) {
                ObjectNode el = (ObjectNode) els.next();
                if (Character.isUpperCase(el.path("word").asText().charAt(0)) || StringUtils.contains(el.path("word").asText(), " ")) {
                    els.remove();
                } else {
                    el.put("type", type);
                    rabbit.send("words", MessageBuilder.withBody(jackson.writeValueAsBytes(el)).build());
                }
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RabbitListener(queues = "words")
    public void process(Message msg) {
        try {
            ObjectNode word = (ObjectNode) jackson.readTree(msg.getBody());
            if (mongo.count(Query.query(Criteria.where("id").is(word.path("id").asInt())), "words") > 0L) {
                return;
            }
            
            String f = env.getProperty("words.def");
            String url = String.format(f, word.path("word").asText());
            byte[] def = Request.Get(url).execute().returnContent().asBytes();
            JsonNode defJson = jackson.readTree(def);

            word.set("def", defJson);
            word.put("i", redis.opsForValue().increment("wc", 1));

            mongo.insert(jackson.convertValue(word, HashMap.class), "words");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
}
