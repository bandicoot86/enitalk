/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.opentok;

import com.enitalk.controllers.bots.BotController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
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
public class CheckAvailabilityRunnable implements Runnable, MessageListener {

    @Autowired
    private GoogleAuthorizationCodeFlow flow;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private BotController botController;
    @Autowired
    private Environment env;
    @Autowired
    private PubnubHistoryFetcher pubnub;
    @Autowired
    private VelocityEngine engine;

    protected static final Logger logger = LoggerFactory.getLogger("y2-check-runnable");

    @Override
    @Scheduled(fixedDelay = 10000L)
    public void run() {
        try {
            Query q = Query.query(Criteria.where("video").in(2, 3).
                    andOperator(Criteria.where("checkDate").lt(DateTime.now().toDate()), Criteria.where("video").exists(true)));
            List<HashMap> evs = mongo.find(q, HashMap.class, "events");
            if (evs.isEmpty()) {
                return;
            }

            ArrayNode events = jackson.convertValue(evs, ArrayNode.class);
            Iterator<JsonNode> it = events.elements();
            mongo.updateMulti(q, new Update().set("video", 3), "events");

            while (it.hasNext()) {
                JsonNode en = it.next();
                rabbit.send("youtube_check", MessageBuilder.withBody(jackson.writeValueAsBytes(en)).build());
            }

        } catch (Exception e) {
            logger.info(ExceptionUtils.getFullStackTrace(e));
        }
    }

    private String makeChat(JsonNode event) {
        String out = null;
        try {
            ArrayList<JsonNode> data = pubnub.fetchData(event);
            if (data.isEmpty()) {
                logger.info("No text for event {}", event.path("ii").asText());
                return out;
            }

            String chat = pubnub.makeChatText(event, data);
            return chat;
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;
    }

    @Override
    @RabbitListener(queues = "youtube_check")
    public void onMessage(Message msg) {
        try {
            JsonNode event = jackson.readTree(msg.getBody());
            String ii = event.path("ii").asText();
            logger.info("Check youtube came {}", ii);

            List<String> videos = jackson.convertValue(event.path("yt"), List.class);
            List<String> parts = new ArrayList<>();
            videos.stream().forEach((String link) -> {
                parts.add(StringUtils.substringAfterLast(link, "/"));
            });

            Credential credential = flow.loadCredential("yt");
            YouTube youtube = new YouTube.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
                    .setApplicationName("enitalk").build();
            boolean refreshed = credential.refreshToken();
            logger.info("Yt refreshed {}", refreshed);

            HttpResponse rs = youtube.videos().list("processingDetails").setId(StringUtils.join(parts, ',')).executeUnparsed();
            InputStream is = rs.getContent();
            byte[] b = IOUtils.toByteArray(is);
            IOUtils.closeQuietly(is);

            JsonNode listTree = jackson.readTree(b);
            logger.info("List tree {}", listTree);

            List<JsonNode> items = listTree.path("items").findParents("id");
            long finished = items.stream().filter((JsonNode j) -> {
                return j.at("/processingDetails/processingStatus").asText().equals("succeeded");
            }).count();

            Query q = Query.query(Criteria.where("ii").is(ii));
            if (finished == parts.size()) {
                logger.info("Processing finished {}", ii);

                //send notification and email
                ObjectNode tree = (ObjectNode) jackson.readTree(new ClassPathResource("emails/videoUploaded.json").getInputStream());
                tree.put("To", event.at("/student/email").asText());
//                String text = tree.path("HtmlBody").asText() + StringUtils.join(videos, "\n");

                StringWriter writer = new StringWriter(29 * 1024);
                Template t = engine.getTemplate("video.html");
                VelocityContext context = new VelocityContext();
                context.put("video", videos.iterator().next());
                t.merge(context, writer);

                tree.put("HtmlBody", writer.toString());

                //make chat and attach it
                String chatTxt = makeChat(event);
                if (StringUtils.isNotBlank(chatTxt)) {
                    ArrayNode attachments = jackson.createArrayNode();
                    ObjectNode a = attachments.addObject();
                    a.put("Name", "chat.txt");
                    a.put("ContentType", "text/plain");
                    a.put("Content", chatTxt.getBytes("UTF-8"));

                    tree.set("Attachments", attachments);
                } else {
                    logger.info("No chat available for {}", event.path("ii").asText());
                }

                logger.info("Sending video and chat {} to student", ii);

                org.apache.http.HttpResponse response = Request.Post("https://api.postmarkapp.com/email").
                        addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token")).
                        bodyByteArray(jackson.writeValueAsBytes(tree), ContentType.APPLICATION_JSON).execute().returnResponse();
                byte[] r = EntityUtils.toByteArray(response.getEntity());
                JsonNode emailResp = jackson.readTree(r);

                Update u = new Update().set("video", 4);
                if (StringUtils.isNotBlank(chatTxt)) {
                    u.set("chat", chatTxt);
                }

                u.set("student.uploader.rq", jackson.convertValue(tree, HashMap.class));
                u.set("student.uploader.rs", jackson.convertValue(emailResp, HashMap.class));

                tree.put("To", event.at("/teacher/email").asText());
                logger.info("Sending video and chat {} to teacher", ii);

                org.apache.http.HttpResponse response2 = Request.Post("https://api.postmarkapp.com/email").
                        addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token")).
                        bodyByteArray(jackson.writeValueAsBytes(tree), ContentType.APPLICATION_JSON).execute().returnResponse();
                byte[] r2 = EntityUtils.toByteArray(response2.getEntity());
                JsonNode emailResp2 = jackson.readTree(r2);

                u.set("teacher.uploader.rq", jackson.convertValue(tree, HashMap.class));
                u.set("teacher.uploader.rs", jackson.convertValue(emailResp2, HashMap.class));
                u.set("f", 1);

                mongo.updateFirst(q, u, "events");

//                JsonNode dest = event.at("/student/dest");
//
//                ArrayNode msgs = jackson.createArrayNode();
//                ObjectNode o = msgs.addObject();
//                o.set("dest", dest);
//                ObjectNode m = jackson.createObjectNode();
//                o.set("message", m);
//                m.put("text", "0x1f3a5 We have uploaded your lesson to Youtube. It is available to you and the teacher only. \n"
//                        + "Please, do not share it with anyone\n Also, we sent the video link and the text chat to your email.");
//
//                ArrayNode buttons = jackson.createArrayNode();
//                m.set("buttons", buttons);
//                m.put("buttonsPerRow", 1);
//
//                if (videos.size() == 1) {
//                    botController.makeButtonHref(buttons, "Watch on Youtube", videos.get(0));
//                } else {
//                    AtomicInteger cc = new AtomicInteger(1);
//                    videos.stream().forEach((String y) -> {
//                        botController.makeButtonHref(buttons, "Watch on Youtube, part " + cc.getAndIncrement(), y);
//                    });
//                }
//
//                botController.sendMessages(msgs);
//
//                sendFeedback(dest, event);

            } else {
                logger.info("{} parts only finished for {}", finished, ii);
                mongo.updateFirst(q, new Update().inc("check", 1)
                        .set("checkDate", new DateTime().plusMinutes(12).toDate()), "events");
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendFeedback(JsonNode dest, JsonNode event) {
        try {
            JsonNode feedbackCommand = botController.getBotCommandsByTag(dest, "end.feedback");

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", "end.feedback");
            tag.put("ignoreWh", true);
            botController.sendTag(tag);

            ArrayNode msgs = jackson.createArrayNode();
            ObjectNode o = msgs.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);
            m.put("text", feedbackCommand.at("/message/text").asText());

            ArrayNode buttons = jackson.createArrayNode();
            m.set("buttons", buttons);
            m.put("buttonsPerRow", 5);

            String ii = event.path("ii").asText();

            botController.makeButton(buttons, "1", ii + ":1");
            botController.makeButton(buttons, "2", ii + ":2");
            botController.makeButton(buttons, "3", ii + ":3");
            botController.makeButton(buttons, "4", ii + ":4");
            botController.makeButton(buttons, "5", ii + ":5");

            botController.sendMessages(msgs);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

}
