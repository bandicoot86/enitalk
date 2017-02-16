package com.enitalk.controllers.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.urlshortener.Urlshortener;
import com.google.api.services.youtube.YouTube;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
import org.joda.time.Seconds;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author astrologer
 */
@Controller
@RequestMapping("/youtube")
public class VideoYoutubeAndOnAirController extends BotAware {

    protected final static Logger logger = LoggerFactory.getLogger("yt-ctrl-api");

    @Autowired
    private FileDataStoreFactory store;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;
    @Autowired
    private MongoTemplate mongo;

    @RequestMapping(method = RequestMethod.POST, value = "/video/list", produces = "application/json")
    @ResponseBody
    public byte[] listVideo(@RequestBody ObjectNode node) {
        byte[] response = null;
        try {
            logger.info("Loading video details for {}", node);
            Credential credential = flow.loadCredential(node.path("user").asText());
            YouTube youtube = new YouTube.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
                    .setApplicationName("enitalk").build();
            boolean refreshed = credential.refreshToken();
            logger.info("Token refreshed {} id {}", refreshed, node.path("id").asText());
            YouTube.Videos.List list = youtube.videos().list("id,liveStreamingDetails,recordingDetails,status,statistics");
            list.setId(node.path("id").asText());
            response = IOUtils.toByteArray(list.executeUnparsed().getContent());

            JsonNode json = jackson.readTree(response);
            logger.info("Jackson {}", json);
            Iterator<JsonNode> it = json.path("items").elements();
            if (it.hasNext()) {
                JsonNode item = it.next();
                JsonNode ld = item.path("liveStreamingDetails");

                if (ld.has("actualStartTime") && ld.has("actualEndTime")) {
                    String actualStartTime = ld.path("actualStartTime").asText();
                    String actualEndTime = ld.path("actualEndTime").asText();
                    DateTimeFormatter fmt = ISODateTimeFormat.dateTime();

                    DateTime sd = fmt.parseDateTime(actualStartTime);
                    DateTime ed = fmt.parseDateTime(actualEndTime);
                    logger.info("Sd {} ed {}", sd, ed);
                    logger.info("Call was {} seconds-long", Seconds.secondsBetween(sd, ed).getSeconds());

                }

            } else {
                //some error, fuck all
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return response;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/onair/join/{id}", produces = "text/html")
    @ResponseBody
    public byte[] joinOnAir(@PathVariable String id) throws IOException {
        HashMap ev = mongo.findOne(Query.query(Criteria.where("ii").is(id).andOperator(Criteria.where("status").is(2))), HashMap.class, "events");
        if (ev == null) {
            return "Sorry, no such event found.".getBytes();
        }

        ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
        return evJson.path("hangoutUrl").asText().getBytes();
    }

    @RequestMapping(method = RequestMethod.GET, value = "/onair/{id}", produces = "text/html")
    @ResponseBody
    public byte[] onAirButton(@PathVariable String id) throws IOException {
        byte[] out = null;
        try {
            HashMap ev = mongo.findOne(Query.query(Criteria.where("ii").is(id).andOperator(Criteria.where("status").is(2))), HashMap.class, "events");
            if (ev == null) {
                return "Sorry, no such event found.".getBytes();
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
            String ddt = evJson.at("/student/scheduledDate").asText();
            String tz = evJson.at("/student/calendar/timeZone").asText();
            DateTimeZone tzz = DateTimeZone.forID(tz);

            DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZone(tzz);
            DateTime scheduleDate = fmt.parseDateTime(ddt);
            logger.info("Scheduled date parsed {}", scheduleDate);

            DateTime now = new DateTime().withZone(tzz);
            logger.info("Now in student tz {}", now);

            int btw = Minutes.minutesBetween(now, scheduleDate).getMinutes();
            logger.info("Minutes between {}", btw);

//            if (btw > 10) {
//                String rs = "You're a bit early, please visit this page at " + scheduleDate.minusMinutes(10).toString("yyyy/MM/dd HH:mm:ss");
//                return rs.getBytes();
//            }            
//            
//            if (btw < -62) {
//                String rs = "It seems that your session has already ended or your booking has expired. Please, contact us at support@enitalk.com if there seems to be a mistake.";
//                return rs.getBytes();
//            }
            String onair = IOUtils.toString(new ClassPathResource("onair.html").getInputStream(), "UTF-8");
            onair = String.format(onair, id);
            logger.info("On air to send {}", onair);

            out = onair.getBytes();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/participant/added", produces = "text/html")
    @ResponseBody
    public void participantAdded(@RequestBody ObjectNode json) {
        try {
            logger.info("Participant added {}", json);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public Urlshortener getGoogleShortener(String id) throws IOException {
        Credential credential = flow.loadCredential(id);
        credential.refreshToken();
        return new Urlshortener.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential).setApplicationName("enitalk").build();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/hangout", produces = "application/json")
    @ResponseBody
    public void updateHangoutUrl(@RequestBody ObjectNode json) {
        try {
            logger.info("Hangout url came {}", json);
            String hangoutUrl = json.path("hgUrl").asText();
            String event = json.path("ev").asText();

            Query q = Query.query(Criteria.where("ii").is(event));
            q.fields().exclude("_id");
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            if (ev == null) {
                logger.error("No event found for update. Json from hangout {}", json);
                return;
            }

            //send to messenger
            ObjectNode evTree = jackson.convertValue(ev, ObjectNode.class);
            JsonNode dest = evTree.at("/student/dest");

            ArrayNode a = jackson.createArrayNode();
            ObjectNode o = a.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", IOUtils.toString(new ClassPathResource("txts/hangoutUrlCame.txt").getInputStream()));

//            ArrayNode b = jackson.createArrayNode();
//            makeButtonHref(b, "Join the lesson", hangoutUrl);
//            message.set("buttons", b);
            sendMessages(a);

            //send email
            String email = evTree.at("/student/people/emails/0/value").asText();
            logger.info("Student email {}", email);

            ObjectNode tree = (ObjectNode) jackson.readTree(new ClassPathResource("email.json").getInputStream());
            tree.put("To", email);
            tree.put("HtmlBody", tree.path("HtmlBody").asText() + hangoutUrl);

            byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                    .bodyString(tree.toString(), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            JsonNode rsTree = jackson.readTree(rs);
            logger.info("Postmark {} response to student {}", tree, rsTree);

            mongo.updateFirst(q, new Update().set("hangoutUrl", hangoutUrl).set("student.inviteEmail.rq", jackson.convertValue(tree, HashMap.class))
                    .set("student.inviteEmail.rs", jackson.convertValue(rsTree, HashMap.class)), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/liveid", produces = "application/json")
    @ResponseBody
    public void updateLiveId(@RequestBody ObjectNode json) {
        try {
            logger.info("Liveid came {}", json);
            String liveId = json.path("liveId").asText();
            String event = json.path("ev").asText();

            Query q = Query.query(Criteria.where("ii").is(event));
            q.fields().exclude("_id");
            mongo.updateFirst(Query.query(Criteria.where("ii").is(event)), new Update().push("liveId", liveId), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

    }

    @RequestMapping(method = RequestMethod.POST, value = "/broadcast/toggle", produces = "application/json")
    @ResponseBody
    public void broadcastToggle(@RequestBody ObjectNode json) {
        try {
            logger.info("Broadcast changed {}", json);

            String event = json.path("ev").asText();

            Query q = Query.query(Criteria.where("ii").is(event));
            q.fields().exclude("_id");
            mongo.updateFirst(q, new Update().set("done", true), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

}
