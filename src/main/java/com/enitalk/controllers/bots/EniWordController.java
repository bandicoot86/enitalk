/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.bots;

import com.enitalk.controllers.youtube.BotAware;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.RateLimiter;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author krash
 */
@Controller
@RequestMapping("/eniword")
public class EniWordController extends BotAware {

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private BotController botCtrl;

    protected final static Logger logger = LoggerFactory.getLogger("eniword-api");

    @RequestMapping(method = RequestMethod.GET, value = "/cancel/{sendTo}")
    @ResponseBody
    public void cancelEniword(@PathVariable Long sendTo) {
        try {
            logger.info("Cancel EniWord came {}", sendTo);
            mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(sendTo)), new Update().set("eniword.disabled", true), "leads");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

//    @Scheduled(fixedDelay = 15000L)
    public void runEniword() {
        try {

            mongo.updateMulti(Query.query(Criteria.where("eniword.nextPing").exists(false)),
                    new Update().set("eniword.nextPing", new DateTime().minusSeconds(10).toDate())
                    .set("eniword.points", 300), "leads");

            Criteria d = Criteria.where("eniword.nextPing").lte(new Date());
            Criteria cal = Criteria.where("calendar").exists(true);
            Criteria unsubscribed = Criteria.where("eniword.disabled").exists(false);

            Query q = Query.query(Criteria.where("eniword.points").gt(0).andOperator(d, cal, unsubscribed));
            q.fields().exclude("_id").include("dest").include("eniword").include("calendar");

            List<HashMap> acolates = mongo.find(q, HashMap.class, "leads");
            ArrayNode leads = jackson.convertValue(acolates, ArrayNode.class);
            Iterator<JsonNode> els = leads.iterator();
            while (els.hasNext()) {
                JsonNode el = els.next();
                String tz = el.at("/calendar/timeZone").asText();
                DateTime now = new DateTime(DateTimeZone.forID(tz));

                if (now.hourOfDay().get() < 9 || now.getHourOfDay() > 20) {
                    logger.info("Too late to bother {}", el);
                    mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(el.at("/dest/sendTo").asLong())),
                            new Update().set("eniword.nextPing", new DateTime().plusHours(1).toDate()), "leads");
                    return;
                }

                mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(el.at("/dest/sendTo").asLong())),
                        new Update().set("eniword.nextPing", new DateTime().plusSeconds(60 * 40).toDate()), "leads");
                rabbit.send("eniwords", MessageBuilder.withBody(jackson.writeValueAsBytes(el)).build());
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    final static RateLimiter rate = RateLimiter.create(25d);

    @RabbitListener(queues = "eniwords")
    public void consume(Message msg) {
        try {
            JsonNode user = jackson.readTree(msg.getBody());
            logger.info("Sending eniword to student {}", user);

            Integer lastWord = user.at("/eniword/wc").asInt(0) + 1;
            HashMap word = mongo.findOne(Query.query(Criteria.where("i").gt(lastWord)), HashMap.class, "words");
            if (word == null) {
                return;
            }

            ObjectNode wToSend = jackson.convertValue(word, ObjectNode.class);

            String text = "0x1f4d8 <b>EniWord</b>\n";
            text += "0x1f4e2 Word: <b>" + wToSend.path("word").asText() + "</b>\n";
            text += "0x1f4cb Part of speech: <b>" + parts.get(wToSend.path("type").asInt()) + "</b>\n";
            text += "0x1f6a9 <b>Usage:</b>\n";
            JsonNode exs = wToSend.at("/def/examples");
            Iterator<JsonNode> els = exs.elements();
            int i = 1;
            while (els.hasNext() && i < 2) {
                text += i++ + ". " + els.next().path("text").asText() + "\n";
            }

            text += "---------\nWe will send you another awesome word in 40 minutes.\nPress /recommend to get back to the menu and browse teachers.";

            logger.info("Text to send {}", text);

            ArrayNode tg = jackson.createArrayNode();
            ObjectNode o = tg.addObject();
            o.set("dest", user.path("dest"));
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", text);

            //make unsubscribe button
            ArrayNode a = jackson.createArrayNode();
//            String buttonUrl = env.getProperty("self.url") + "/eniword/cancel/" + user.at("/dest/sendTo").asLong();
//            logger.info("Button url {}", buttonUrl);

//            makeButtonHref(a, "Stop sending words", buttonUrl);
            message.set("buttons", a);

            rate.acquire();
            sendMessages(tg);

            mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(user.at("/dest/sendTo").asLong())),
                    new Update().set("eniword.wc", wToSend.path("i").asLong()).inc("eniword.points", -1), "leads");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    private final static HashMap<Integer, String> parts = new HashMap<>();

    static {
        parts.put(0, "verb");
        parts.put(1, "adjective");
        parts.put(2, "adverb");
        parts.put(3, "noun");
    }

}
