package com.enitalk.controllers.bots;

import com.enitalk.configs.TeacherFinder;
import com.enitalk.controllers.paypal.PaypalController;
import com.enitalk.controllers.youtube.BotAware;
import com.enitalk.controllers.youtube.GoogleCalendarController;
import com.enitalk.controllers.youtube.OAuthController;
import com.enitalk.tinkoff.TinkoffController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.repackaged.com.google.common.base.Joiner;
import com.google.common.cache.LoadingCache;
import com.mongodb.WriteResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author astrologer
 */
@Controller
@RequestMapping("/bot")
public class BotController extends BotAware {

    protected final static Logger logger = LoggerFactory.getLogger("enitalk-ctrl-api");

    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private OAuthController yt;
    @Autowired
    private Environment env;
    @Autowired
    private PaypalController paypal;
    @Autowired
    private GoogleCalendarController calendar;
    @Autowired
    private ScheduledExecutorService ex;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;

    @Autowired
    private TeacherFinder teachersIds;

    @Autowired
    @Qualifier("skipCache")
    private LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesCache;

    @Autowired
    private RedisTemplate<String, String> redis;
    @Autowired
    @Qualifier("teachers")
    LoadingCache<String, List<String>> teachers;
    @Autowired
    TinkoffController tinkoffCtrl;

    @Autowired
    private OfferTimezone offerTimezones;

//    @RequestMapping(method = RequestMethod.POST, value = "/booking/cancel", produces = "application/json")
//    @ResponseBody
    public ObjectNode cancelBooking(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Cancel booking req came {}", json);
            JsonNode dest = json.path("dest");
            String text = json.path("text").asText();

            if (StringUtils.equals(text, "b")) {
                back(json);
                return out;
            }

            Criteria c = Criteria.where("student.dest.sendTo").is(json.at("/dest/sendTo").asLong());
            Criteria st = Criteria.where("status").is(2);

            c.andOperator(st);
            Query q = Query.query(c);
            q.fields().exclude("_id").include("dd").include("ii").include("student");

            JsonNode command = getBotCommandsByTag(dest, "cancel.booking");

            List<HashMap> events = mongo.find(q, HashMap.class, "events");

            if (events.isEmpty()) {
                //there is no events found
                ArrayNode msg = jackson.createArrayNode();
                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", "0x1f4a2 You have no scheduled lessons!");
                sendMessages(msg);
                back(json);

                return out;

            } else {
                ArrayNode paidEvents = jackson.convertValue(events, ArrayNode.class);

                Iterator<JsonNode> els = paidEvents.elements();
                ArrayList<JsonNode> eligible = new ArrayList<>();
                DateTime now = DateTime.now(DateTimeZone.UTC);
                while (els.hasNext()) {
                    JsonNode el = els.next();
                    DateTime schDate = new DateTime(el.path("dd").asLong(), DateTimeZone.UTC);
                    int minutes = Minutes.minutesBetween(now, schDate).getMinutes();
                    logger.info("Minutes between {}", minutes);
                    if (minutes > 24 * 60) {
                        eligible.add(el);
                    }

                }

                if (eligible.isEmpty()) {
                    ArrayNode msg = jackson.createArrayNode();
                    ObjectNode o = msg.addObject();
                    o.set("dest", dest);
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", "0x1f4a2 You have no scheduled lessons!");
                    sendMessages(msg);
                    back(json);

                    return out;
                }

                ObjectNode jlead = jackson.createObjectNode();
                jlead.set("dest", json.path("dest"));
                jlead.put("tag", "cancel.reason");
                jlead.put("ignoreWh", true);
                sendTag(jlead);

                ArrayNode msg = jackson.createArrayNode();
                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", command.at("/message/text").asText());

                ArrayNode a = jackson.createArrayNode();
                eligible.stream().forEach((JsonNode e) -> {
                    DateTime dd = new DateTime(e.path("dd").asLong(), DateTimeZone.forID(e.at("/student/calendar/timeZone").asText()));
                    makeButton(a, "0x1f55b " + dd.toString("MM/dd/yyyy HH:mm"), e.path("ii").asText());
                });

                message.set("buttons", a);

                sendMessages(msg);
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/cancel/confirm", produces = "application/json")
//    @ResponseBody
    public ObjectNode cancelConfirm(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Confirm cancel came {}", json);

            String eventId = json.path("text").asText();
            JsonNode dest = json.path("dest");

            ObjectNode jlead = jackson.createObjectNode();
            jlead.set("dest", json.path("dest"));
            jlead.put("tag", "cancel.do");
            jlead.put("ignoreWh", true);
            sendTag(jlead);

            JsonNode command = getBotCommandsByTag(dest, "cancel.booking");

            ArrayNode msg = jackson.createArrayNode();
            ObjectNode o = msg.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", command.at("/message/text").asText());

            ArrayNode a = jackson.createArrayNode();
            message.set("buttons", a);

            makeButton(a, "Too expensive ", "1:" + eventId);
            makeButton(a, "Cannot be there on time", "2:" + eventId);
            makeButton(a, "Other ", "3:" + eventId);
            //0x2753 ? symbol

            sendMessages(msg);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/cancel/do", produces = "application/json")
//    @ResponseBody
    public ObjectNode doCancel(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Do cancel came {}", json);
            JsonNode dest = json.path("dest");
            String[] data = json.path("text").asText().split(":");
            String reason = data[0];
            String event = data[1];

            JsonNode command = getBotCommandsByTag(dest, "cancel.do");

            Query q = Query.query(Criteria.where("ii").is(event).andOperator(Criteria.where("status").is(2)));
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            if (ev != null) {
                ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
                JsonNode links = evJson.at("/student/paypal/paymentExecuteResponse/transactions/0/related_resources/0/sale/links");

                List<JsonNode> linksList = links.findParents("rel");
                JsonNode refundLink = linksList.stream().filter((JsonNode l) -> {
                    return l.path("rel").asText("").equals("refund");
                }).findFirst().get();

                logger.info("Refund link {}", refundLink);

                String authToken = paypal.authPaypal();
                logger.info("Auth token for refund {}", authToken);

                byte[] response = Request.Post(refundLink.path("href").asText()).bodyString(jackson.createObjectNode().toString(),
                        ContentType.APPLICATION_JSON).addHeader("Authorization", "Bearer " + authToken).execute().returnContent().asBytes();
                JsonNode refundJson = jackson.readTree(response);
                logger.info("Refund resp {}", refundJson);
                mongo.updateFirst(q, new Update().set("paypal.refundResp", jackson.convertValue(refundJson, HashMap.class)).
                        set("status", 10).set("refundReason", reason), "events");

                //invalidate teacher cache
                boolean added = datesCache.get(evJson.at("/teacher/i").asText()).add(new DateTime(evJson.path("dd").asLong(), DateTimeZone.UTC));
                logger.info("Refund date to tch returned {}", added);
                //send email and message to the user

                ArrayNode msg = jackson.createArrayNode();
                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", command.at("/message/text").asText());
                sendMessages(msg);

                back(json);

            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/google/auth", produces = "application/json")
    @ResponseBody
    public ObjectNode auth(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {

            logger.info("Google auth came from bot {}", json);
            JsonNode dest = json.path("dest");
            ArrayNode msg = jackson.createArrayNode();
            JsonNode command = getBotCommandsByTag(dest, "agree");
            Query q = Query.query(Criteria.where("dest.sendTo").is(json.at("/dest/sendTo").asLong()));

            String email = json.path("text").asText().toLowerCase();
            if (!StringUtils.equalsIgnoreCase(email, command.path("command").asText())) {
                String confirm = IOUtils.toString(new ClassPathResource("emails/confirmation.json").getInputStream(), "UTF-8");
                String cf = RandomStringUtils.randomAlphanumeric(16);

                String letter = String.format(confirm, env.getProperty("self.url") + "/confirm/" + cf);
                ObjectNode em = (ObjectNode) jackson.readTree(letter);
                em.put("To", email);

                HttpResponse response = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                        .bodyByteArray(jackson.writeValueAsBytes(em), ContentType.APPLICATION_JSON).execute().returnResponse();
                byte[] r = EntityUtils.toByteArray(response.getEntity());
                JsonNode rs = jackson.readTree(r);
                mongo.updateFirst(q, new Update().set("confirm.rq", jackson.convertValue(em, HashMap.class))
                        .set("confirm.rs", jackson.convertValue(rs, HashMap.class)).set("confirmCode", cf), "leads");
                if (rs.path("ErrorCode").asInt() == 0) {

                    ObjectNode jlead = jackson.createObjectNode();
                    jlead.set("dest", dest);
                    jlead.put("tag", env.getProperty("tag.googleAllow"));
                    jlead.put("text", "Welcome to Enitalk! What is your level? Please, choose below");
                    ArrayNode a = jackson.createArrayNode();
                    makeButton(a, "Beginner", "B1");
                    makeButton(a, "Intermediate", "B2");
                    makeButton(a, "Upper inter.", "C1");
                    makeButton(a, "Advanced", "C2");
                    makeButton(a, "Fluent", "CP");
                    jlead.set("buttons", a);
                    sendTag(jlead);

                    mongo.updateFirst(q, new Update().set("email", email), "leads");
                    return out;

                } else {
                    sendLastCommand(dest);
//                    ObjectNode o = msg.addObject();
//                    o.set("dest", dest);
//                    ObjectNode message = jackson.createObjectNode();
//                    o.set("message", message);
//                    message.put("text", "Please, provide a correct email address");
//                    sendMessages(msg);
                    return out;
                }

            }

            ObjectNode o = msg.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", command.at("/message/text").asText());

            sendMessages(msg);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/start", produces = "application/json")
    @ResponseBody
    public byte[] start(@RequestBody ObjectNode json) {
        byte[] out = null;
        try {
            logger.info("Start command issued {}", json);
            JsonNode dest = json.path("dest");
            Long userId = dest.path("sendTo").asLong();
            HashMap user = mongo.findOne(Query.query(Criteria.where("userId").in(userId)), HashMap.class, "leads");
            logger.info("User at start {}", user != null);

            if (user == null || !user.containsKey("email")) {
                if (user == null) {
                    ObjectNode cc = jackson.createObjectNode();
                    cc.set("userId", jackson.createArrayNode().add(userId));
                    cc.set("dest", dest);
//                    cc.put("coeff", env.getProperty("start.coeff", Double.class, 1.2));
                    cc.put("videoCost", 0);

                    mongo.insert(jackson.convertValue(cc, HashMap.class), "leads");
                }

                ObjectNode jlead = jackson.createObjectNode();
                jlead.set("dest", json.path("dest"));
                jlead.put("tag", "start");
                jlead.put("ignoreWh", true);
                jlead.put("sendMessage", true);
                sendTag(jlead);

            } else {
                ObjectNode userJson = jackson.convertValue(user, ObjectNode.class);
                if (userJson.path("calendar").path("timeZone").isMissingNode()) {

                    ObjectNode tag = jackson.createObjectNode();
                    tag.set("dest", dest);
                    tag.put("tag", env.getProperty("tag.gps"));
                    tag.put("ignoreWh", true);
                    sendTag(tag);

                    gps(json);
                    return jackson.writeValueAsBytes(jackson.createObjectNode());
                }

                ObjectNode jlead = jackson.createObjectNode();
                jlead.set("dest", dest);
                jlead.put("tag", env.getProperty("tag.browse"));
                sendTag(jlead);

            }

            out = jackson.writeValueAsBytes(jackson.createObjectNode());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/teacher/approve", produces = "application/json")
    @ResponseBody
    public void sendApproveLinkToTeacher(@RequestBody ObjectNode json) {
        try {
            logger.info("Approve requested for teacher {}");
            String i = json.path("i").asText();
            Query q = Query.query(Criteria.where("i").is(i));
            HashMap tch = mongo.findOne(q, HashMap.class, "teachers");
            if (tch != null) {
                ObjectNode tjson = jackson.convertValue(tch, ObjectNode.class);
                JsonNode dest = tjson.path("dest");

                ArrayNode msg = jackson.createArrayNode();

                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", "Awesome! We have verified your profile and the students around the globe can "
                        + "already book lessons with you. Stay tuned and keep an eye on your Messenger. We will send you notifications of new bookings. "
                        + "Also, you can find them at your personal email");

                mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong())),
                        new Update().set("visible", true).set("email", tjson.at("/welcome/rq/To").asText()), "teachers");

                ex.submit(() -> {
                    try {
                        datesCache.get(tjson.path("i").asText());
                    } catch (ExecutionException ex) {
                        logger.error(ExceptionUtils.getFullStackTrace(ex));
                    }
                });

//                sendMessages(msg);
            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/teacher", produces = "application/json")
    @ResponseBody
    public ObjectNode teacher(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Teacher came {}", json);
            JsonNode dest = json.path("dest");
            Long userId = dest.path("sendTo").asLong();

            Query q = Query.query(Criteria.where("dest.sendTo").is(userId));
            HashMap user = mongo.findOne(q, HashMap.class, "teachers");
            if (user == null) {
                ObjectNode cc = jackson.createObjectNode();
                cc.set("userId", jackson.createArrayNode().add(userId));
                cc.set("dest", json.path("dest"));
                cc.put("i", RandomStringUtils.randomNumeric(15));
                cc.put("visible", false);
                //might want to convert it to a Moscow time
                cc.put("cameDate", new DateTime().toString());
                cc.put("text", "Introduction text");
                cc.put("video", "https://www.youtube.com/watch?v=f-33_-uRLAU");
                cc.set("skills", jackson.createArrayNode().add("CAE").add("CPE").add("Business"));
//                cc.put("p", redis.opsForValue().increment("tcount", 1L));
                cc.set("lvl", jackson.createArrayNode().add("B1").add("CPE"));

                cc.put("rate", 10);
                cc.put("coeff", 0.8);
                cc.put("name", "John");
                cc.set("calendar", jackson.createObjectNode().put("timeZone", "Europe/Moscow"));
                cc.set("schedule", jackson.readTree(new ClassPathResource("dates/avdates.json").getInputStream()));

                mongo.insert(jackson.convertValue(cc, HashMap.class), "teachers");

                ArrayNode msg = jackson.createArrayNode();
                JsonNode command = getBotCommandsByTag(dest, "welcome.teacher");

                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", command.at("/message/text").asText());

                sendMessages(msg);

            } else {
                logger.info("Teacher already there {}", json);
                String text = json.path("text").asText().toLowerCase();
                ObjectNode teacherJson = jackson.convertValue(user, ObjectNode.class);
                logger.info("Teacher json {}", teacherJson);
                if (teacherJson.has("welcome") && teacherJson.at("/welcome/rs/ErrorCode").asInt() == 0) {

                    JsonNode em = jackson.readTree(new ClassPathResource("emails/teacherWelcome.json").getInputStream());
                    ArrayNode msg = jackson.createArrayNode();
                    ObjectNode o = msg.addObject();
                    o.set("dest", dest);
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", "It seems that we have already sent a message to <b>"
                            + teacherJson.at("/welcome/rs/To").asText()
                            + "</b>. If you have not received an email, drop us a line yourself at <b>" + em.path("From").asText() + "</b>");
                    sendMessages(msg);

                    return out;
                }

                if (StringUtils.equals(text, "i'm a teacher")) {
                    ArrayNode msg = jackson.createArrayNode();
                    JsonNode command = getBotCommandsByTag(dest, "welcome.teacher");

                    ObjectNode o = msg.addObject();
                    o.set("dest", dest);
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", command.at("/message/text").asText());

                    sendMessages(msg);
                    return out;
                }

                String html = IOUtils.toString(new ClassPathResource("emails/welcome.html").getInputStream(), "UTF-8");
                ObjectNode e = (ObjectNode) jackson.readTree(new ClassPathResource("emails/teacherWelcome.json").getInputStream());
                e.put("To", text);
                e.put("HtmlBody", html);

                logger.info("Sending welcome text {}", e);

                HttpResponse response = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                        .bodyByteArray(jackson.writeValueAsBytes(e), ContentType.APPLICATION_JSON).execute().returnResponse();

                byte[] rs = EntityUtils.toByteArray(response.getEntity());
                JsonNode ers = jackson.readTree(rs);
                logger.info("Welcome email response {}", ers);

                mongo.updateFirst(q, new Update().set("welcome.rq", jackson.convertValue(e, HashMap.class))
                        .set("welcome.rs", jackson.convertValue(ers, HashMap.class)), "teachers");

                if (ers.path("ErrorCode").asInt() != 0) {
                    //send message saying there something went wrong with email
                    ArrayNode msg = jackson.createArrayNode();
                    ObjectNode o = msg.addObject();
                    o.set("dest", dest);
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", "We have been unable to send a message to email <b>" + text + "</b>, please type in the correct email");
                    sendMessages(msg);
                } else {
                    //yes! we did it!
                    ArrayNode msg = jackson.createArrayNode();
                    ObjectNode o = msg.addObject();
                    o.set("dest", dest);
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", "We have sent a message to email " + text + ", please check your Inbox and reply to the message");
                    sendMessages(msg);

                }

            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/talking", produces = "application/json")
    @ResponseBody
    public ObjectNode talkingSession(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Want a session {}", json);
            Long userId = json.path("dest").path("sendTo").asLong();
            mongo.updateFirst(Query.query(Criteria.where("userId").is(userId)), new Update().set("want", true), HashMap.class, "leads");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/level", produces = "application/json")
    @ResponseBody
    public ObjectNode level(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Level came a {}", json);

            String lvl = json.path("text").asText();
            JsonNode dest = json.path("dest");
            Long userId = dest.path("sendTo").asLong();

            mongo.updateFirst(Query.query(Criteria.where("userId").in(userId)), new Update().set("lvl", lvl), "leads");

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.gps"));
            tag.put("ignoreWh", true);
            sendTag(tag);

            JsonNode commandGps = getBotCommandsByTag(dest, env.getProperty("tag.gps"));

            ArrayNode jlead = jackson.createArrayNode();

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", commandGps.path("message").path("text").asText());

//            message.set("buttons", buttons);
//            message.put("buttonsPerRow", 1);
//
//            timezones.keySet().forEach((String key) -> {
//                makeButton(buttons, key, timezones.get(key));
//            });
            sendMessages(jlead);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/gps", produces = "application/json")
    @ResponseBody
    public ObjectNode gps(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Gps came a {}", json);
            JsonNode dest = json.path("dest");
            String text = json.path("text").asText("");
//            Set<String> tzz = DateTimeZone.getAvailableIDs();
//
//            if (StringUtils.isNotBlank(text) && tzz.contains(text)) {
//                logger.info("Timezone id came {}", json);
//                
//            }

            if (!json.has("lat") || !json.has("lon")) {
                logger.info("First time for gps {}", json);
                ArrayNode msg = jackson.createArrayNode();
                ObjectNode o = msg.addObject();
                o.set("dest", dest);
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);

                JsonNode commandGps = getBotCommandsByTag(dest, env.getProperty("tag.gps"));

                message.put("text", commandGps.at("/message/text").asText());

                sendMessages(msg);
                return out;
            }

            double lat = json.at("/lat").asDouble();
            double lon = json.at("/lon").asDouble();

            Long userId = dest.path("sendTo").asLong();

            long ts = Instant.now().getEpochSecond();
            String url = "https://maps.googleapis.com/maps/api/timezone/json?timestamp=" + ts + "&location=" + lat + "," + lon;
            logger.info("Url tz {}", url);
            byte[] gs = Request.Get(url).execute().returnContent().asBytes();
            JsonNode ltree = jackson.readTree(gs);
            logger.info("Ltree {}", ltree);

            String currentTz = ltree.path("timeZoneId").asText("Europe/Moscow");

            WriteResult r = mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(userId)), new Update().set("calendar.timeZone", currentTz), "leads");
            logger.info("Tz updated {} {}", dest, r.getN());

            ArrayNode msg = jackson.createArrayNode();

            ObjectNode o = msg.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", "0x1f567 We have updated your timezone. You are in <b>" + currentTz + "</b> timezone and it is 0x231a "
                    + new DateTime().toDateTime(DateTimeZone.forID(currentTz)).toString("yyyy/MM/dd HH:mm:ss"));

            sendMessages(msg);

            ObjectNode jlead = jackson.createObjectNode();
            jlead.set("dest", dest);
            jlead.put("tag", env.getProperty("tag.browse"));
            sendTag(jlead);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    public String getUserTz(Long userId) {
        Query q = Query.query(Criteria.where("dest.sendTo").is(userId));
        q.fields().exclude("_id").include("calendar");
        HashMap lead = mongo.findOne(q, HashMap.class, "leads");
        if (lead == null) {
            return null;
        }
        ObjectNode leadJson = jackson.convertValue(lead, ObjectNode.class);
        return leadJson.at("/calendar/timeZone").asText();
    }

    @Autowired
    @Qualifier("exchangeCache")
    private LoadingCache<String, BigDecimal> exchange;

    public void makeTeacherMessage(ArrayNode jlead, JsonNode teacherJson, JsonNode dest, JsonNode studentJson) {
        try {
            ObjectNode o = jlead.addObject();
            o.set("dest", dest);

            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);

            List skills = jackson.convertValue(teacherJson.path("skills"), List.class);

            String teacherInfo = "0x2198 " + teacherJson.path("name").asText() + "\n";
            teacherInfo += "0x1f51d : " + StringUtils.join(skills, ", ") + "\n";
            BigDecimal coeff = BigDecimal.valueOf(studentJson.path("coeff").asDouble(teacherJson.path("coeff").asDouble(1.3d)));

            BigDecimal sum = teacherJson.path("rate").decimalValue().multiply(coeff, MathContext.DECIMAL64).
                    setScale(0, RoundingMode.UP);

            teacherInfo += "0x1f4b2 " + sum.toString() + "$ per 45 minutes ( ~ " + sum.multiply(exchange.get("")).setScale(0, RoundingMode.UP) + " RUB)\n";
            teacherInfo += teacherJson.path("video").asText();

            message.put("text", teacherInfo);

            ArrayNode buttons = jackson.createArrayNode();
            makeButton(buttons, "0x1f4de Book", teacherJson.path("i").asText());
            makeButtonHref(buttons, "Watch video", teacherJson.path("video").asText());
            message.set("buttons", buttons);
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

    }

    @RequestMapping(method = RequestMethod.POST, value = "/recommend", produces = "application/json")
    @ResponseBody
    public byte[] recommend(@RequestBody ObjectNode json) {
        byte[] out = null;
        try {
            logger.info("Recommendation needed {}", json);

            JsonNode dest = json.path("dest");
            String text = json.path("text").asText();

            //check correctness
            //unset search date
            boolean byDateSearch = json.path("byDate").asBoolean(false);
            logger.info("By date search {}", byDateSearch);
            Query q = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            HashMap student = mongo.findOne(q, HashMap.class, "leads");
            ObjectNode studentNode = jackson.convertValue(student, ObjectNode.class);
            
            if (studentNode.path("calendar").path("timeZone").isMissingNode()) {

                ObjectNode tag = jackson.createObjectNode();
                tag.set("dest", dest);
                tag.put("tag", env.getProperty("tag.gps"));
                tag.put("ignoreWh", true);
                sendTag(tag);

                gps(json);
                return jackson.writeValueAsBytes(jackson.createObjectNode());
            }

            if (!byDateSearch) {
                mongo.updateFirst(q, new Update().unset("scheduledDate"), "leads");
            }

            Long user = dest.path("sendTo").asLong();
//            String tz = getUserTz(user);

            boolean nextSearch = StringUtils.equals(text, "more");

            BoundListOperations<String, String> bound = redis.boundListOps("ttt:" + user);

            final AtomicBoolean searchDate = new AtomicBoolean(false);

            if (byDateSearch) {
                redis.delete("ttt:" + user);
                DateTimeFormatter f = ISODateTimeFormat.dateTime().withZone(DateTimeZone.forID(studentNode.at("/calendar/timeZone").asText()));
                DateTime ddt = f.parseDateTime(studentNode.at("/scheduledDate").asText()).toDateTime(DateTimeZone.UTC);
                logger.info("Looking for ddt {}", ddt);
                teachers.get("").stream().filter((String teacher) -> {
                    boolean yes = false;
                    try {
                        JsonNode tjson = jackson.readTree(teacher);
                        String tinCache = tjson.path("i").asText();

                        ConcurrentSkipListSet<DateTime> dates = datesCache.get(tinCache);
                        logger.info("Dd cc {} teacher id {}", dates, tinCache);
                        yes = datesCache.get(tinCache).contains(ddt);
                        logger.info("Contains {}", yes);

                    } catch (Exception ex) {
                        logger.error(ExceptionUtils.getFullStackTrace(ex));
                    }
                    return yes;
                }).forEach((String t) -> {
                    searchDate.set(true);
                    bound.rightPush(t);
                });

                if (bound.size() == 0L) {
                    logger.info("Not found any teacher by date {}", json);
                    //send message that no time found and offer available teachers
                    back(json);

                }

            }

            if (!nextSearch && !byDateSearch && !searchDate.get()) {
                logger.info("First recommendation redis");
                redis.delete("ttt:" + user);
                List<String> allTeachers = teachers.get("");
                logger.info("All teachers {}", allTeachers);
                ArrayList<String> copy = new ArrayList<>(allTeachers);
                Collections.shuffle(copy);
                bound.rightPushAll(copy.toArray(new String[]{}));
            }

            ArrayNode jlead = jackson.createArrayNode();
            JsonNode teachersJson = jackson.readTree(bound.leftPop());
            logger.info("Teacher json from redis {}", teachersJson);
            makeTeacherMessage(jlead, teachersJson, dest, studentNode);

            logger.info("Sending teachers {}", jlead);
            ArrayNode btns = (ArrayNode) jlead.elements().next().at("/message/buttons");
            if (bound.size() >= 1) {
                makeButton(btns, "0x1f50e More", "more");
            } else {
                makeButton(btns, "0x2b05 Back", "b");
            }

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.book"));
            tag.put("ignoreWh", true);
            sendTag(tag);

            sendMessages(jlead);

            out = jackson.writeValueAsBytes(jackson.createObjectNode());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/back", produces = "application/json")
    @ResponseBody
    public ObjectNode back(@RequestBody JsonNode json) {
        try {
            JsonNode dest = json.path("dest");

            Query q = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            mongo.updateFirst(q, new Update().unset("scheduledDate"), "leads");

            Query delete1 = Query.query(Criteria.where("student.dest.sendTo").is(dest.path("sendTo").asLong()).andOperator(Criteria.where("status").ne(2)));
            mongo.remove(delete1, "events");
            datesCache.invalidateAll();

            ObjectNode jlead = jackson.createObjectNode();
            jlead.set("dest", dest);
            jlead.put("tag", env.getProperty("tag.browse"));
            sendTag(jlead);
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return jackson.createObjectNode();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/find", produces = "application/json")
    @ResponseBody
    public ObjectNode searchByTime(@RequestBody ObjectNode json) {
        try {
            logger.info("Find by av time came a {}", json);
            JsonNode dest = json.path("dest");

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.hour.find"));
            tag.put("ignoreWh", true);
            sendTag(tag);

            JsonNode command = getBotCommandsByTag(dest, env.getProperty("tag.hour.find"));

            ArrayNode jlead = jackson.createArrayNode();
            ArrayNode buttons = jackson.createArrayNode();

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", command.path("message").path("text").asText());
            message.set("buttons", buttons);
            message.put("buttonsPerRow", 3);

//            DateTime now = new DateTime();
            Query q = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            HashMap student = mongo.findOne(q, HashMap.class, "leads");
            ObjectNode studentJson = jackson.convertValue(student, ObjectNode.class);
            DateTimeZone dzz = DateTimeZone.forID(studentJson.at("/calendar/timeZone").asText());
            long start = System.currentTimeMillis();
            TreeSet<DateTime> dds = new TreeSet<>();

            Collection<String> ids = teachersIds.teachers();
            ids.stream().forEach((String i) -> {
                try {
                    ConcurrentSkipListSet<DateTime> dates = datesCache.get(i);
                    dates.stream().forEach((DateTime t) -> {
                        dds.add(new DateTime(t, dzz).millisOfDay().setCopy(0));
                    });
                } catch (Exception ex) {
                    logger.error(ExceptionUtils.getFullStackTrace(ex));
                }
            });

            dds.stream().forEach((DateTime ddz) -> {
                makeButton(buttons, "0x1f30d " + ddz.toString("dd, EEE"), ddz.toString("dd/MM"));
            });

//            logger.info("Dates av {} {} ms", dds, System.currentTimeMillis() - start);
            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(jlead);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return jackson.createObjectNode();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/find/hour", produces = "application/json")
    @ResponseBody
    public ObjectNode findByHourAv(@RequestBody ObjectNode json) {
        try {
            logger.info("Find by av hour came a {}", json);
            JsonNode dest = json.path("dest");

            String dayToHaveSessionOn = json.path("text").asText();
            if (StringUtils.equals(dayToHaveSessionOn, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            Query q = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            mongo.updateFirst(q,
                    new Update().set("scheduledDate", dayToHaveSessionOn), HashMap.class, "leads");
            HashMap student = mongo.findOne(q, HashMap.class, "leads");
            ObjectNode studentJson = jackson.convertValue(student, ObjectNode.class);

            DateTimeZone dzz = DateTimeZone.forID(studentJson.at("/calendar/timeZone").asText());
            DateTimeFormatter fmt = DateTimeFormat.forPattern("dd/MM").withZone(dzz);
            DateTime date = fmt.parseDateTime(dayToHaveSessionOn);
            date = date.year().setCopy(new DateTime().getYear());
            logger.info("Date {}", date);

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.time.search"));
            tag.put("ignoreWh", true);
            sendTag(tag);

            JsonNode command = getBotCommandsByTag(dest, env.getProperty("tag.time.search"));

            ArrayNode jlead = jackson.createArrayNode();
            ArrayNode buttons = jackson.createArrayNode();

            TreeSet<DateTime> dds = new TreeSet<>();

            Collection<String> ids = teachersIds.teachers();
            ids.stream().forEach((String i) -> {
                try {
                    ConcurrentSkipListSet<DateTime> dates = datesCache.get(i);
                    dates.stream().forEach((DateTime t) -> {
                        dds.add(new DateTime(t, dzz));
                    });
                } catch (Exception ex) {
                    logger.error(ExceptionUtils.getFullStackTrace(ex));
                }
            });

            logger.info("Dds in search by time {}", Joiner.on("\n").join(dds));
            SortedSet<DateTime> subs = dds.subSet(date, date.plusDays(1).millisOfDay().setCopy(0));

            logger.info("Sorted dates to display {}", subs);

            subs.stream().forEach((DateTime ds) -> {
                makeButton(buttons, "0x1f55b " + ds.toString("HH:mm"), ds.toString("HH:mm"));
            });

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", command.path("message").path("text").asText());
            message.set("buttons", buttons);
            message.put("buttonsPerRow", 4);

            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(jlead);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return jackson.createObjectNode();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/do/find/time", produces = "application/json")
    @ResponseBody
    public ObjectNode doFind(@RequestBody ObjectNode json) {
        try {
            logger.info("Do search by time came {}", json);

            String text = json.path("text").asText();
            JsonNode dest = json.path("dest");

            if (StringUtils.equals(text, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            Query q = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            HashMap student = mongo.findOne(q, HashMap.class, "leads");
            ObjectNode stJson = jackson.convertValue(student, ObjectNode.class);
            String date = stJson.at("/scheduledDate").asText();

            DateTimeFormatter fmt = DateTimeFormat.forPattern("dd/MM").withZone(DateTimeZone.forID(stJson.at("/calendar/timeZone").asText()));
            DateTimeFormatter hhSs = DateTimeFormat.forPattern("HH:mm").withZone(DateTimeZone.forID(stJson.at("/calendar/timeZone").asText()));

            DateTime dateToLookFor = fmt.parseDateTime(date);
            DateTime hourSecond = hhSs.parseDateTime(text);

            final DateTime ddt = dateToLookFor.
                    hourOfDay().setCopy(hourSecond.getHourOfDay()).
                    minuteOfHour().setCopy(hourSecond.getMinuteOfHour()).
                    year().setCopy(new DateTime().year().get()).toDateTime(DateTimeZone.UTC);
            mongo.updateFirst(q, new Update().set("scheduledDate", ddt.toString()), "leads");

            recommend(json.put("byDate", true));
            return jackson.createObjectNode();

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return jackson.createObjectNode();
    }

    public ObjectNode insertEvent(String text, JsonNode dest, JsonNode teacherJson, JsonNode studentJson) {
        ObjectNode event = jackson.createObjectNode();

        event.set("teacher", teacherJson);
        event.set("student", studentJson);
        event.put("ii", RandomStringUtils.randomNumeric(32));
        event.put("status", 0);

        HashMap ev = jackson.convertValue(event, HashMap.class);
        ev.put("createDate", new Date());

        mongo.remove(findLastBooking(dest), "events");
        mongo.insert(ev, "events");

        return event;
    }

    private final static Pattern bookPattern = Pattern.compile("^(\\d{15}|more|b)$");

    @RequestMapping(method = RequestMethod.POST, value = "/book", produces = "application/json")
    @ResponseBody
    public ObjectNode book(@RequestBody ObjectNode json) {
        try {
            logger.info("Booking came a {}", json);
            String text = json.path("text").asText();
            JsonNode dest = json.path("dest");

            if (!bookPattern.matcher(text).find()) {
                sendLastCommand(dest);
                return jackson.createObjectNode();
            }

            if (StringUtils.equals(text, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            Query studentQuery = Query.query(Criteria.where("dest.sendTo").is(dest.path("sendTo").asLong()));
            studentQuery.fields().exclude("_id").exclude("confirm");

            HashMap student = mongo.findOne(studentQuery, HashMap.class, "leads");
            JsonNode studentJson = jackson.convertValue(student, JsonNode.class);

            if (text.length() < 15) {
                if (studentJson.has("scheduledDate")) {
                    json.put("byDate", true);
                }
                recommend(json);
                return jackson.createObjectNode();
            }

            Query tq = Query.query(Criteria.where("i").is(text));
            tq.fields().exclude("_id").exclude("av").exclude("welcome");
            HashMap teacher = mongo.findOne(tq, HashMap.class, "teachers");
            JsonNode teacherJson = jackson.convertValue(teacher, JsonNode.class);

            ObjectNode ev = insertEvent(text, dest, teacherJson, studentJson);

            if (studentJson.has("scheduledDate")) {
                logger.info("The date for booking already came redirecting to Pay step");
                String date = studentJson.path("scheduledDate").asText();
                logger.info("Date as text {}", date);
                DateTime dateParsed = ISODateTimeFormat.dateTime().parseDateTime(date);
                logger.info("Date time parsed {}", dateParsed);

                mongo.updateFirst(Query.query(Criteria.where("ii").is(ev.path("ii").asText())), new Update().set("student.scheduledDate",
                        dateParsed.toString("dd/MM")), "events");
                ObjectNode pp = jackson.createObjectNode();
                pp.set("dest", dest);
                pp.put("text", dateParsed.toString("HH:mm"));

                pay(pp);

                return jackson.createObjectNode();

            }

            JsonNode command = getBotCommandsByTag(dest, env.getProperty("tag.choose.day"));
            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.choose.hour"));
            tag.put("ignoreWh", true);
            sendTag(tag);

            ArrayNode jlead = jackson.createArrayNode();
            ArrayNode buttons = jackson.createArrayNode();

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode message = jackson.createObjectNode();
            o.set("message", message);
            message.put("text", command.path("message").path("text").asText());
            message.set("buttons", buttons);
            message.put("buttonsPerRow", 3);

            TreeSet<DateTime> dds = new TreeSet<>();

            DateTimeZone dzz = DateTimeZone.forID(studentJson.at("/calendar/timeZone").asText());

            ConcurrentSkipListSet<DateTime> avDates = datesCache.get(text);

            avDates.stream().forEach((DateTime t) -> {
                dds.add(new DateTime(t, dzz).millisOfDay().setCopy(0));
            });

            dds.stream().forEach((DateTime d) -> {
                makeButton(buttons, "0x1f30d " + d.toString("dd, MMM"), d.toString("dd/MM"));
            });

            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(jlead);

            return jackson.createObjectNode();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return jackson.createObjectNode();
    }

    private final static Pattern dayPattern = Pattern.compile("^(\\d{2}\\/\\d{2})|^(b)$");

    @RequestMapping(method = RequestMethod.POST, value = "/choose/hour", produces = "application/json")
    @ResponseBody
    public ObjectNode chooseHour(@RequestBody ObjectNode json) {
        try {
            logger.info("Choose hour came {}", json);
            String text = json.path("text").asText();

            if (!dayPattern.matcher(text).find()) {
                sendLastCommand(json.path("dest"));
                return jackson.createObjectNode();
            }

            if (StringUtils.equals(text, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            JsonNode dest = json.path("dest");
            String dayToHaveSessionOn = json.path("text").asText();
            DateTimeFormatter daySessionFmt = DateTimeFormat.forPattern("dd/MM");
            DateTime daySession = daySessionFmt.parseDateTime(dayToHaveSessionOn).year().setCopy(new DateTime().getYear());

            Query q = findLastBooking(dest);
            mongo.updateFirst(q,
                    new Update().set("student.scheduledDate", dayToHaveSessionOn), HashMap.class, "events");

            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            JsonNode evJson = jackson.convertValue(ev, JsonNode.class);

            logger.info("Student json from choose hour {}", evJson);

            TreeSet<DateTime> dds = new TreeSet<>();
            DateTimeZone dzz = DateTimeZone.forID(evJson.at("/student/calendar/timeZone").asText());
            ConcurrentSkipListSet<DateTime> avDates = datesCache.get(evJson.at("/teacher/i").asText());

            avDates.stream().forEach((DateTime t) -> {
                dds.add(new DateTime(t, dzz));
            });

            logger.info("Dds converted {} day session {}", dds, daySession);
            

            DateTime startDay = daySession.toDateTime(dzz).millisOfDay().setCopy(0);
            logger.info("Looking for lessons between {} {}", startDay,  startDay.plusDays(1).millisOfDay().setCopy(0));
            
            SortedSet<DateTime> hours = dds.subSet(startDay, startDay.plusDays(1).millisOfDay().setCopy(0));
            logger.info("Hours av for tttt {}", hours);

            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", env.getProperty("tag.pay"));
            tag.put("ignoreWh", true);

            sendTag(tag);

            ArrayNode jlead = jackson.createArrayNode();

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);

            JsonNode command = getBotCommandsByTag(dest, env.getProperty("tag.choose.hour"));
            m.put("text", command.path("message").path("text").asText());

            ArrayNode buttons = jackson.createArrayNode();
            m.set("buttons", buttons);
            m.put("buttonsPerRow", 3);

            hours.stream().forEach((DateTime h) -> {
                makeButton(buttons, "0x1f55b " + h.toString("HH:mm"), h.toDateTime(DateTimeZone.UTC).toString("HH:mm"));
            });

            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(jlead);

            return jackson.createObjectNode();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return jackson.createObjectNode();
    }

    public static void main(String[] args) {
        System.out.println(RandomStringUtils.randomNumeric(15));
    }

    public Query findLastBooking(JsonNode dest) {
        Query q = Query.query(Criteria.where("student.dest.sendTo").is(dest.path("sendTo").asLong()).andOperator(Criteria.where("status").is(0)));
        return q;
    }

    public BigDecimal calculateRubles(BigDecimal rate, BigDecimal coeff, BigDecimal video) throws InterruptedException, ExecutionException {
        BigDecimal sum = rate.multiply(coeff, MathContext.DECIMAL64).
                setScale(0, RoundingMode.UP).add(video);
        return paypal.getExchangeRate().get().multiply(sum);
    }

    private final static Pattern hourPattern = Pattern.compile("^(\\d{2}:\\d{2})|^(b)$");

    @RequestMapping(method = RequestMethod.POST, value = "/pay", produces = "application/json")
    @ResponseBody
    public ObjectNode pay(@RequestBody ObjectNode json) {
        try {
            logger.info("Pay came {}", json);
            String hhMM = json.path("text").asText();

            if (!hourPattern.matcher(hhMM).find()) {
                sendLastCommand(json.path("dest"));
                return jackson.createObjectNode();
            }

            if (StringUtils.equals(hhMM, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            JsonNode dest = json.path("dest");

            ArrayNode waitNode = jackson.createArrayNode();
            ObjectNode oWait = waitNode.addObject();
            oWait.set("dest", dest);
            ObjectNode mWait = jackson.createObjectNode();
            oWait.set("message", mWait);
            mWait.put("text", "0x1f4b3 We are generating payment links, just a second, please.");
            sendMessages(waitNode);

            Query q = findLastBooking(dest);
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            if (ev == null) {
                back(json);
                return jackson.createObjectNode();
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);

            String i = evJson.at("/teacher/i").asText();

            String studentTz = evJson.at("/student/calendar/timeZone").asText();
//            DateTimeFormatter daySessionFmt = DateTimeFormat.forPattern("dd/MM").withZone(DateTimeZone.forID(studentTz));
//            DateTimeFormatter timeFmt = DateTimeFormat.forPattern("HH:mm").withZone(DateTimeZone.forID(studentTz));

            DateTimeFormatter daySessionFmt = DateTimeFormat.forPattern("dd/MM").withZone(DateTimeZone.UTC);
            DateTimeFormatter timeFmt = DateTimeFormat.forPattern("HH:mm").withZone(DateTimeZone.UTC);

            String date = evJson.at("/student/scheduledDate").asText();
            logger.info("Parsing date from pay {}", date);
            DateTime ddd = daySessionFmt.parseDateTime(date);
            DateTime timeDate = timeFmt.parseDateTime(hhMM);
            logger.info("Mongo scheduled date {} utc date {}", date, ddd);

            DateTime refTime = ddd.
                    hourOfDay().setCopy(timeDate.getHourOfDay()).
                    minuteOfHour().setCopy(timeDate.getMinuteOfHour()).
                    year().setCopy(DateTime.now().getYear()).withZone(DateTimeZone.UTC);

            logger.info("Reftime {}", refTime);
            boolean removed = datesCache.get(i).remove(refTime);
            if (!removed) {
                logger.error("Seems the date has been already booked");

                ArrayNode jlead = jackson.createArrayNode();
                ObjectNode o = jlead.addObject();
                o.set("dest", dest);
                ObjectNode m = jackson.createObjectNode();
                o.set("message", m);
                m.put("text", "0x1f631 Sorry, but it seems that someone booked the event at your time just a moment ago. Please, choose another time");
                sendMessages(jlead);

                json.put("text", evJson.at("/student/scheduledDate").asText());
                chooseHour(json);

                return jackson.createObjectNode();
            }

            DateTime up = refTime.toDateTime(DateTimeZone.forID(studentTz));
            DateTime ttime = refTime.toDateTime(DateTimeZone.forID(evJson.at("/teacher/calendar/timeZone").asText()));

            //make summary
            Long invId = redis.opsForValue().increment("rbk", 1);
            Integer period = env.getProperty("revoke.interval", Integer.class);
            String text = "Your order is almost complete.\nYou can pay clicking on the buttons below or via an email order we have just sent.\n";
            text += "If you press <b>Back</b> button before the payment, we cancel your order and vacate the selected time. Press on the payment link and wait for further instructions to the messenger.\n";

            text += "<b>Order summary</b>:\n";
            text += "0x1f508 <b>Event ID</b> : " + invId + "\n";
            text += "0x1f562 <b>Schedule date</b>: " + up.toString("dd, MMM ") + up.toString("HH:mm-") + up.plusMinutes(45).toString("HH:mm") + "\n";
            text += "0x1f4e2 <b>Teacher</b>: " + evJson.at("/teacher/name").asText() + "\n";
            text += "0x26a0 Your pay link <b> will expire in " + period + " minutes</b>\n";
            text += "0x1f30f Exchange rate: <b>" + exchange.get("").toString() + "</b> RUB to 1 USD\n";
            text += "0x1f48c Your email: <b>" + evJson.at("/student/email").asText() + "</b> \n-------\n";

            ArrayNode jlead = jackson.createArrayNode();

            ObjectNode o = jlead.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);

            ArrayNode buttons = jackson.createArrayNode();
            m.set("buttons", buttons);
            m.put("buttonsPerRow", 1);

            BigDecimal coeff = BigDecimal.valueOf(evJson.at("/student/coeff").asDouble(evJson.at("/teacher/coeff").asDouble(1.3d)));
            BigDecimal usd = evJson.at("/teacher/rate").decimalValue().
                    multiply(coeff, MathContext.DECIMAL64).
                    setScale(0, RoundingMode.UP).setScale(0, RoundingMode.UP);

            BigDecimal sum = usd.multiply(exchange.get("")).setScale(0, RoundingMode.UP);

//            byte[] paypalResponse = paypal.requestPayment(sum, "RUB", evJson, exchange.get(""));
//            JsonNode pp = jackson.readTree(paypalResponse);
//            String sumRubles = pp.at("/paymentResponse/transactions/0/amount/total").asText();
            text += "0x1f4b3 Total : " + sum + " RUB (" + usd + " $)\n";
            m.put("text", text);

            String payUrl = env.getProperty("rbk.pay");
            payUrl += "MerchantLogin=" + env.getProperty("rbk.login") + "&";
            payUrl += "OutSum=" + sum + "&";
            payUrl += "InvoiceID=" + invId + "&";
            payUrl += "Description=" + URLEncoder.encode("Enitalk lesson payment with " + evJson.at("/teacher/name").asText() + " on " + up.toString("dd, MMM HH:mm"), "UTF-8") + "&";
            payUrl += "Encoding=utf-8&";
            payUrl += "ExpirationDate=" + new DateTime().plusMinutes(period).toDateTime(DateTimeZone.UTC).toDateTimeISO() + "&";
            if (env.getProperty("rbk.test", Boolean.class, false)) {
                payUrl += "IsTest=1&";
            }
            payUrl += "SignatureValue=" + DigestUtils.sha512Hex(env.getProperty("rbk.login") + ":" + sum + ":" + invId + ":" + env.getProperty("rbk.pass1"));

            logger.info("Robokassa url {}", payUrl);

            //tinkoff
            evJson.put("rubles", sum);

            evJson.put("desc", "Enitalk lesson, id " + evJson.path("ii").asText());
            evJson.put("invId", invId.toString());
            ObjectNode tinkoffResp = tinkoffCtrl.init(evJson);
            logger.info("Tinkoff resp {}", tinkoffResp);

            evJson.put("orderDate", up.toString("dd, MMM 'at' HH:mm"));

            Update qup = new Update().
                    set("student.scheduledDate", up.toString()).
                    set("teacher.scheduledDate", ttime.toString()).
                    //set("student.paypal", jackson.convertValue(pp, HashMap.class)).
                    set("status", 1).
                    set("rbkUrl", payUrl).
                    set("tinkoff", jackson.convertValue(tinkoffResp, HashMap.class)).
                    set("sum", sum.toString()).
                    set("invId", invId.toString()).set("yahooRate", exchange.get("").toString());

//            makeButtonHref(buttons, "0x1f4b3 Paypal", pp.path("approve_url").asText());
//            makeButtonHref(buttons, "0x1f4b3 Robokassa(RU only)", payUrl);
            if (tinkoffResp.has("payLink")) {
                String redirect = env.getProperty("self.url") + "/tinkoff/redirect/" + invId;
                
//                makeButtonHref(buttons, "0x1f4b3 Tinkoff bank (RU only)", tinkoffResp.path("payLink").asText());
                makeButtonHref(buttons, "0x1f4b3 Tinkoff bank (RU only)", redirect);
                
                //ObjectNode data = sendOrderEmail(evJson, tinkoffResp.path("payLink").asText());
                ObjectNode data = sendOrderEmail(evJson, redirect);
                qup.set("student.order", jackson.convertValue(data, HashMap.class));
            }

            mongo.updateFirst(q, qup, HashMap.class, "events");
            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(jlead);

            return jackson.createObjectNode();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return jackson.createObjectNode();
    }

    public ObjectNode sendOrderEmail(ObjectNode evJson, String link) {
        logger.info("Sending booking email {} link {}", evJson, link);
        ObjectNode o = jackson.createObjectNode();
        try {
            String studentEmail = evJson.at("/student/email").asText();
            ObjectNode tree = (ObjectNode) jackson.readTree(new ClassPathResource("emails/order.json").getInputStream());
            tree.put("To", studentEmail);

            VelocityContext studentContext = new VelocityContext();
            studentContext.put("date", evJson.path("orderDate").asText());
            studentContext.put("link", link);
            studentContext.put("price", evJson.path("rubles").decimalValue().toPlainString());

            StringWriter writer = new StringWriter(29 * 1024);
            Template t = engine.getTemplate("emails/order.html");

            t.merge(studentContext, writer);

            tree.put("HtmlBody", writer.toString());

            byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                    .bodyByteArray(jackson.writeValueAsBytes(tree), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            JsonNode irs = jackson.readTree(rs);

            tree.remove("Attachments");

            o.set("rq", tree);
            o.set("rs", irs);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return o;

    }

    @RequestMapping(method = RequestMethod.POST, value = "/mybookings", produces = "application/json")
    @ResponseBody
    public ObjectNode myBookings(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("My bookings came {}", json);

            JsonNode dest = json.path("dest");

            Criteria cr = Criteria.where("student.dest.sendTo").is(dest.path("sendTo").asLong());
            Criteria status2 = Criteria.where("status").is(2);
//            Criteria dd = Criteria.where("dd").gt(DateTime.now().toDate());
            cr.andOperator(status2);

            Query q = Query.query(cr);
            List<HashMap> events = mongo.find(q, HashMap.class, "events");
            if (events.isEmpty()) {
                logger.info("No events for {}", dest);
                //send a message indicating there is no bookings
                ArrayNode msgs = jackson.createArrayNode();
                ObjectNode o = msgs.addObject();
                o.set("dest", dest);
                ObjectNode m = jackson.createObjectNode();
                o.set("message", m);
                m.put("text", "You have no bookings. It is easy to fix, though! Select one of our teachers");
                sendMessages(msgs);
                back(json);

                return o;

            }

            JsonNode command = getBotCommandsByTag(dest, "mybookings");

            //send tag for the next step
            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", "list.booking.actions");
            tag.put("ignoreWh", true);
            sendTag(tag);

            //else make the dates of available bookings
            ArrayNode evsJson = jackson.convertValue(events, ArrayNode.class);

            ArrayNode msgs = jackson.createArrayNode();
            ObjectNode o = msgs.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);
            m.put("text", command.at("/message/text").asText());

            ArrayNode buttons = jackson.createArrayNode();
            m.set("buttons", buttons);
            m.put("buttonsPerRow", 1);

            DateTimeFormatter ddf = ISODateTimeFormat.dateTime();

            evsJson.findParents("ii").forEach((JsonNode j) -> {
                DateTime scheduledDate = ddf.parseDateTime(j.at("/student/scheduledDate").asText());
                makeButton(buttons, "0x1f4c5 " + scheduledDate.toString("yyyy/MM/dd HH:mm") + " (" + j.at("/teacher/name").asText() + ")", j.path("ii").asText());
            });

            makeButton(buttons, "0x2b05 Back", "b");

            sendMessages(msgs);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/booking/actions", produces = "application/json")
    @ResponseBody
    public ObjectNode bookingActionsList(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Booking actions came {}", json);
            String ii = json.path("text").asText();
            if (StringUtils.equals(ii, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            logger.info("Booking actions came {}", json);
            JsonNode dest = json.path("dest");

            HashMap ev = mongo.findOne(Query.query(Criteria.where("ii").is(ii)), HashMap.class, "events");
            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);

            //send execute action tag
            //
            JsonNode command = getBotCommandsByTag(dest, "booking.action.do");
            ArrayNode msgs = jackson.createArrayNode();
            ObjectNode o = msgs.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);
            m.put("text", command.at("/message/text").asText());

            ArrayNode buttons = jackson.createArrayNode();
            m.set("buttons", buttons);
            m.put("buttonsPerRow", 1);

            //send cancel booking if date is 24 hours before the lesson
            DateTime scheduledDate = new DateTime(evJson.path("dd").asLong(), DateTimeZone.UTC);
            int minutes = Minutes.minutesBetween(DateTime.now(DateTimeZone.UTC), scheduledDate).getMinutes();
            logger.info("Minutes between now and scheduled date {}", minutes, evJson.path("ii").asText());

            boolean onlyVideo = true;

            if (minutes > 0) {
                makeButton(buttons, "0x1f517 Send me the lesson link again", "link:" + ii);
                onlyVideo = false;
            }

            if (minutes > 24 * 60) {
                makeButton(buttons, "0x2716 Cancel lesson", "cancel:" + ii);
                onlyVideo = false;
            }

            if (evJson.path("video").asInt(0) == 4 && evJson.has("yt")) {
                ArrayNode links = (ArrayNode) evJson.path("yt");
                Iterator<JsonNode> it = evJson.path("yt").iterator();
                boolean putParts = links.size() > 1;

                int i = 1;
                while (it.hasNext()) {
                    makeButtonHref(buttons, "0x1f3a5 Watch on Youtube" + (putParts ? ", Part " + i : ""), it.next().asText());
                    i++;
                }
            }

            if (onlyVideo) {
                makeButton(buttons, "0x2b05 Back to browsing", "b");
            } else {
                makeButton(buttons, "0x2b05 Back", "b");
            }

            //send tag for the next step
            ObjectNode tag = jackson.createObjectNode();
            tag.set("dest", dest);
            tag.put("tag", "booking.action.do");
            tag.put("ignoreWh", true);
            sendTag(tag);

            sendMessages(msgs);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/booking/actions/do", produces = "application/json")
    @ResponseBody
    public ObjectNode bookingActionsDo(@RequestBody ObjectNode json) {
        ObjectNode out = jackson.createObjectNode();
        try {
            String text = json.path("text").asText();

            if (!StringUtils.contains(text, ":")) {
                back(json);
                return jackson.createObjectNode();
            }

            logger.info("Booking actions came {}", json);
            JsonNode dest = json.path("dest");
            String[] pair = text.split(":");
            String ii = pair[1];

            Query q = Query.query(Criteria.where("ii").is(ii));
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);

            String command = pair[0];

            switch (command) {
                case "cancel":
                    cancelBooking(json);
                    break;
                case "link":
                    //send the letter again
                    JsonNode email = evJson.at("/student/invitation/request");
                    byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                            .bodyByteArray(jackson.writeValueAsBytes(email), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
                    JsonNode irs = jackson.readTree(rs);

                    mongo.updateFirst(q, new Update().set("student.invitation.response", jackson.convertValue(irs, HashMap.class)).
                            inc("student.invitation.retries", 1), "events");
                    ArrayNode msgs = jackson.createArrayNode();
                    ObjectNode o = msgs.addObject();
                    o.set("dest", dest);
                    ObjectNode m = jackson.createObjectNode();
                    o.set("message", m);
                    m.put("text", "0x2714 We sent an invitation letter to your email, check your inbox and or a spam folder");

                    sendMessages(msgs);
                    back(json);

                    break;
                default:
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/feedback", produces = "application/json")
    @ResponseBody
    public ObjectNode receiveFeedback(@RequestBody ObjectNode json) {
        try {
            logger.info("Feedback received {}", json);
            String text = json.path("text").asText();

            JsonNode dest = json.path("dest");
            String[] pair = text.split(":");
            String ii = pair[0];
            String star = pair[1];

            Integer rating = Integer.valueOf(star);

            mongo.updateFirst(Query.query(Criteria.where("ii").is(ii)), new Update().set("rating", rating), "events");

            ArrayNode msgs = jackson.createArrayNode();
            ObjectNode o = msgs.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);
            m.put("text", "Thanks for the feedback!");

            sendMessages(msgs);

            back(json);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return jackson.createObjectNode();
    }

    @Autowired
    private VelocityEngine engine;

    @RequestMapping(method = RequestMethod.POST, value = "/comments", produces = "application/json")
    @ResponseBody
    public ObjectNode sendComments(@RequestBody ObjectNode json) {
        try {
            logger.info("Comments came {}", json);
            String text = json.path("text").asText();
            if (StringUtils.equals(text, "b")) {
                back(json);
                return jackson.createObjectNode();
            }

            HashMap ev = mongo.findOne(Query.query(Criteria.where("student.dest.sendTo").is(json.at("/dest/sendTo").asLong()).andOperator(Criteria.where("status").is(2))).
                    with(new Sort(Sort.Direction.DESC, "createDate")), HashMap.class, "events");
            if (ev != null) {
                JsonNode evJson = jackson.convertValue(ev, JsonNode.class);
                logger.info("Event for comments found {}", evJson.path("ii").asText());

                ObjectNode comments = (ObjectNode) jackson.readTree(new ClassPathResource("commentsEmail.json").getInputStream());
                comments.put("To", evJson.at("/teacher/email").asText());

                DateTimeZone tz = DateTimeZone.forID(evJson.at("/teacher/calendar/timeZone").asText());

                StringWriter writer = new StringWriter(32 * 1024);
                Template t = engine.getTemplate("comments.html");

                VelocityContext context = new VelocityContext();
                context.put("date", new DateTime(evJson.path("dd").asLong(), tz).toString("dd, MMM 'at' HH:mm"));
                context.put("comment", text);
                t.merge(context, writer);

                comments.put("Subject", "Comments for an event at " + new DateTime(evJson.path("dd").asLong(), tz).toString("dd, MMM 'at' HH:mm"));
                comments.put("HtmlBody", writer.toString());

                byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                        .bodyByteArray(jackson.writeValueAsBytes(comments), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
                JsonNode irs = jackson.readTree(rs);

                logger.info("Comments sent response {}", irs);

                mongo.updateFirst(Query.query(Criteria.where("ii").is(evJson.path("ii").asText())), new Update()
                        .set("teacher.comments.request", jackson.convertValue(comments, HashMap.class))
                        .set("teacher.comments.response", jackson.convertValue(irs, HashMap.class)), "events");

                ArrayNode msgs = jackson.createArrayNode();
                ObjectNode o = msgs.addObject();
                o.set("dest", json.path("dest"));
                ObjectNode m = jackson.createObjectNode();
                o.set("message", m);
                m.put("text", "0x1f4e3 We have sent your comments to the teacher! Thank you.");

                sendMessages(msgs);
                back(json);
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return jackson.createObjectNode();
    }

}
