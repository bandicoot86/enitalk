/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.opentok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.HashMultimap;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class OpenTokListener implements MessageListener {

    protected static final Logger logger = LoggerFactory.getLogger("opentok-callback");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;

    @Scheduled(fixedDelay = 10000L)
    public void updateFinalStatus() {
        try {
            Criteria cc3 = Criteria.where("status").is(2);
            Criteria cc1 = Criteria.where("video").exists(false);
            Query q = Query.query(Criteria.where("endDate").lt(new DateTime().toDate()).andOperator(cc3, cc1));
            q.fields().exclude("_id").include("opentok").include("ii");

            List<HashMap> eligibleEvents = mongo.find(q, HashMap.class, "events");
            if (!eligibleEvents.isEmpty()) {
                ArrayNode evs = jackson.convertValue(eligibleEvents, ArrayNode.class);
                Iterator<JsonNode> evIt = evs.elements();
                while (evIt.hasNext()) {
                    JsonNode ev = evIt.next();
                    HashMultimap<String, String> mmap = HashMultimap.create();

                    List<JsonNode> alls = ev.path("opentok").findParents("id");
                    alls.forEach((JsonNode op) -> {
                        mmap.put(op.path("id").asText(), op.path("status").asText());
                    });

                    logger.info("Opentok multimap {}", mmap);
                    long uploadedArchives = mmap.keySet().stream().filter((String id) -> {
                        return mmap.get(id).contains("uploaded");
                    }).count();
                    if (uploadedArchives == mmap.keySet().size()) {
                        logger.info("All archives uploaded, process further");
                        mongo.updateFirst(Query.query(Criteria.where("ii").is(ev.path("ii").asText())), new Update().set("video", 0), "events");

                    } else {
                        logger.info("Only {} of {} archives uploaded", uploadedArchives, mmap);
                    }
                }
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @Override
    @RabbitListener(queues = "tokbox")
    public void onMessage(Message msg) {
        try {
            ObjectNode tree = (ObjectNode) jackson.readTree(msg.getBody());
            logger.info("Tokbox status came {}", tree);

            Query q = Query.query(Criteria.where("sessionId").is(tree.path("sessionId").asText()));
            HashMap event = mongo.findOne(q, HashMap.class, "events");
            if (event != null) {
                String status = tree.path("status").asText();
                ObjectNode evJson = jackson.convertValue(event, ObjectNode.class);

                HashMap item = jackson.convertValue(tree, HashMap.class);
                item.put("came", new Date());
                Update update = new Update().push("opentok", item);

                switch (status) {
                    case "uploaded":
                        logger.info("Video uploaded for event {} ", evJson.path("ii").asText());
                        break;
                    case "paused":
                        logger.info("Paused event {}", evJson.path("ii").asText());

                        break;
                    default:
                }
                mongo.updateFirst(q, update, "events");
            }
        } catch (Exception e) {
            logger.info(ExceptionUtils.getFullStackTrace(e));
        } finally {
        }
    }

}
