package com.enitalk.controllers.paypal;

import com.enitalk.controllers.youtube.GoogleCalendarController;
import com.enitalk.controllers.youtube.VideoYoutubeAndOnAirController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.urlshortener.Urlshortener;
import com.google.common.base.Splitter;
import com.google.common.cache.LoadingCache;
import com.mongodb.WriteResult;
import com.ximpleware.AutoPilot;
import com.ximpleware.VTDGen;
import com.ximpleware.VTDNav;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author kraav
 */
@Controller
@RequestMapping("/paypal")
public class PaypalController extends BasicPaypal {

    @Autowired
    MongoTemplate mongo;

    protected static final Logger logger = LoggerFactory.getLogger("paypal-ctrl");

    @Autowired
    LoadingCache<String, String> tokenCache;
    @Autowired
    VideoYoutubeAndOnAirController yt;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;
    @Autowired
    private VelocityEngine engine;

    public String botAuth() throws ExecutionException {
        return tokenCache.get("");
    }

    public Urlshortener getGoogleShortener(String id) throws IOException {
        Credential credential = flow.loadCredential(id);
        credential.refreshToken();
        return new Urlshortener.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential).setApplicationName("enitalk").build();
    }

    public void sendTag(ObjectNode dest) throws IOException, ExecutionException {
        String auth = botAuth();
        String tagResponse = Request.Post(env.getProperty("bot.sendTag")).
                addHeader("Authorization", "Bearer " + auth).
                bodyString(dest.toString(), ContentType.APPLICATION_JSON).socketTimeout(5000).connectTimeout(3000).
                execute().
                returnContent().
                asString();

        logger.info("Tag command sent to a bot {}, response {}", dest, tagResponse);
    }

    public void sendMessages(ArrayNode msg) throws IOException, ExecutionException {
        String auth = botAuth();
        String tagResponse = Request.Post(env.getProperty("bot.sendMessage")).
                addHeader("Authorization", "Bearer " + auth).
                bodyString(msg.toString(), ContentType.APPLICATION_JSON).socketTimeout(10000).connectTimeout(5000).
                execute().
                returnContent().
                asString();

        logger.info("SendMsg sent to a bot {}, response {}", msg, tagResponse);
    }

    public void successPayment(ObjectNode event, ObjectNode body, JsonNode executeTree) {
        try {

            String ddt = event.at("/student/scheduledDate").asText();
            String tz = event.at("/student/calendar/timeZone").asText();
            DateTimeZone tzz = DateTimeZone.forID(tz);

            DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZone(tzz);
            DateTime scheduleDate = fmt.parseDateTime(ddt);
            Update update = new Update().
                    set("dd", scheduleDate.toDate()).
                    set("endDate", scheduleDate.plusMinutes(80).toDate()).
                    set("status", 2).
                    set("createDate", new DateTime().toDate()).
                    set("reminderDate", scheduleDate.minusMinutes(30).toDate());

            if (body != null && executeTree != null) {
                update.set("student.paypal.paymentExecuteRequest", jackson.convertValue(body, HashMap.class)).
                        set("student.paypal.paymentExecuteResponse", jackson.convertValue(executeTree, HashMap.class));
            }

            mongo.updateFirst(Query.query(Criteria.where("ii").is(event.path("ii").asText())), update, "events");

            sendSuccessMessage(event);
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "/success")
    @ResponseBody
    public byte[] success(@RequestParam Map<String, String> allRequestParams) {
        byte[] out = null;
        try {
            logger.info("Request params {}", allRequestParams);
            String token = authPaypal();
            logger.info("Auth token for Paypal {}", token);
            String url = env.getProperty("paypal.execute") + allRequestParams.get("paymentId") + "/execute";
            ObjectNode body = jackson.createObjectNode().put("payer_id", allRequestParams.get("PayerID"));
            logger.info("Executing payment {} to url {}", body, url);
            String paypalToken = allRequestParams.get("token");

            Query paypalQuery = Query.query(Criteria.where("student.paypal.ppToken").is(paypalToken).andOperator(Criteria.where("status").is(1)));
            HashMap ev = mongo.findOne(paypalQuery, HashMap.class, "events");
            if (ev != null) {

                ObjectNode event = jackson.convertValue(ev, ObjectNode.class);

                int exTime = (int) TimeUnit.MINUTES.toMillis(2);

                HttpPost post = new HttpPost(url);
                post.setEntity(new StringEntity(body.toString(), "UTF-8"));
                post.addHeader("Authorization", "Bearer " + token);
                post.addHeader("Content-type", "application/json");

                CloseableHttpResponse rs = client.execute(post);
                byte[] en = EntityUtils.toByteArray(rs.getEntity());
                IOUtils.closeQuietly(rs);

//                String rs = Request.Post(url).socketTimeout(exTime).
//                        connectTimeout(30000).bodyByteArray(jackson.writeValueAsBytes(body), ContentType.APPLICATION_JSON).
//                        addHeader("Authorization", "Bearer " + token).execute().
//                        returnContent().asString();
                JsonNode executeTree = jackson.readTree(en);
                logger.info("Execute payment response {}", executeTree);

                String state = executeTree.path("state").asText();
                logger.info("Payment state {}", state);

                successPayment(event, body, executeTree);
                return "We have received approval for the payment. Thank you! We are sending further instructions to your messenger. Please, close this window.".getBytes();

            } else {
                logger.info("Payment expired or processed");
                //might want to send notification to messenger

                return "Payment has been already processed or no such payment has ever existed".getBytes();
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;

    }

    @Autowired
    private GoogleCalendarController calendar;

    @Async
    public void saveCalendarStudent(JsonNode event) {
        try {
            String userId = event.at("/student/dest/sendTo").toString();
            ObjectNode nn = jackson.createObjectNode().put("user", userId);
            DateTimeFormatter ff = ISODateTimeFormat.dateTime();
            DateTimeFormatter noMillis = ISODateTimeFormat.dateTimeNoMillis();

            DateTime st = ff.parseDateTime(event.at("/student/scheduledDate").asText());
            nn.put("start", noMillis.print(st));
            nn.put("end", noMillis.print(st.plusHours(1)));

            byte[] rs = calendar.createEvent(nn);
            JsonNode tree = jackson.readTree(rs);

            WriteResult cal = mongo.updateFirst(Query.query(Criteria.where("ii").is(event.path("ii").asText())), new Update().set("student.calendarEvent",
                    jackson.convertValue(tree, HashMap.class)), "events");
            logger.info("Cal updated count {}", cal.getN());
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendSuccessTeacher(JsonNode event) {
        try {
            String ii = event.path("ii").asText();
            logger.info("Sending a teacher that a booking came his way {}", ii);

            String teacherTz = event.at("/teacher/calendar/timeZone").asText();
            DateTimeFormatter ffm = ISODateTimeFormat.dateTime();
            String linkTeacher = env.getProperty("self.url") + "/video/session/teacher/" + ii;

            String ddd = event.at("/student/scheduledDate").asText();

            DateTime d2 = ffm.parseDateTime(ddd).toDateTime(DateTimeZone.forID(teacherTz));
            String teacherEmail = event.at("/teacher/email").asText();
            ObjectNode tree = (ObjectNode) jackson.readTree(new ClassPathResource("teacherInvitation.json").getInputStream());
            tree.put("To", teacherEmail);

            VelocityContext teacherContext = new VelocityContext();
            teacherContext.put("scheduledDate", d2.toDateTime(DateTimeZone.forID(teacherTz)).toString("dd, MMM 'at' HH:mm"));
            teacherContext.put("link", linkTeacher);
            teacherContext.put("targetName", "");
            teacherContext.put("hello", "Student has booked a lesson with you!");

            StringWriter writer = new StringWriter(32 * 1024);
            Template t = engine.getTemplate("invite.html");

            t.merge(teacherContext, writer);

            tree.put("HtmlBody", writer.toString());

            byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                    .bodyByteArray(jackson.writeValueAsBytes(tree), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            JsonNode irs = jackson.readTree(rs);

            mongo.updateFirst(Query.query(Criteria.where("ii").is(ii)),
                    new Update().set("teacher.invitation.request", jackson.convertValue(tree, HashMap.class))
                    .set("teacher.invitation.response", jackson.convertValue(irs, HashMap.class)), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendSuccessStudent(JsonNode event) {
        try {
            logger.info("Sending a student that a booking came his way");

            String studentTz = event.at("/student/calendar/timeZone").asText();
            String ii = event.path("ii").asText();
            JsonNode dest = event.at("/student/dest");
            String linkStudent = env.getProperty("self.url") + "/video/session/student/" + ii;

//            ArrayNode jlead2 = jackson.createArrayNode();
//            ObjectNode o2 = jlead2.addObject();
//            o2.set("dest", dest);
//            ObjectNode message2 = jackson.createObjectNode();
//            o2.set("message", message2);
            String ddd = event.at("/student/scheduledDate").asText();

            DateTimeFormatter ffm = ISODateTimeFormat.dateTime();
            DateTime d2 = ffm.parseDateTime(ddd).toDateTime(DateTimeZone.forID(studentTz));
//            String teacherTemplate = IOUtils.toString(new ClassPathResource("txts/paypalSuccess.txt").getInputStream());
//            String teacherTxt = String.format(teacherTemplate, d2.minusMinutes(2).toString("dd, MMM 'at' HH:mm"));
//            message2.put("text", teacherTxt);

//            sendMessages(jlead2);
            //find comments tag
//            JsonNode commentsTag = getBotCommandsByTag(dest, "comments");
//            ObjectNode jlead = jackson.createObjectNode();
//            jlead.set("dest", dest);
//            jlead.put("tag", "comments");
//            jlead.put("ignoreWh", true);
//            sendTag(jlead);
//            ArrayNode jlead3 = jackson.createArrayNode();
//            ObjectNode o3 = jlead3.addObject();
//            o3.set("dest", dest);
//            ObjectNode message3 = jackson.createObjectNode();
//            o3.set("message", message3);
//            logger.info("Commands tag text {}", commentsTag.at("/message/text"));
//            message3.put("text", commentsTag.at("/message/text").asText());
//
//            ArrayNode buttons = jackson.createArrayNode();
//            message3.set("buttons", buttons);
//            message3.put("buttonsPerRow", 1);
//            makeButton(buttons, "0x2b05 Back", "b");
//            sendMessages(jlead3);
            String studentEmail = event.at("/student/email").asText();
            ObjectNode tree = (ObjectNode) jackson.readTree(new ClassPathResource("teacherInvitation.json").getInputStream());
            tree.put("To", studentEmail);

            VelocityContext studentContext = new VelocityContext();
            studentContext.put("scheduledDate", d2.toDateTime(DateTimeZone.forID(studentTz)).toString("dd, MMM 'at' HH:mm"));
            studentContext.put("link", linkStudent);
            studentContext.put("targetName", " with " + event.at("/teacher/name").asText());
            studentContext.put("hello", "Thank you for booking a lesson!");

            StringWriter writer = new StringWriter(32 * 1024);
            Template t = engine.getTemplate("invite.html");

            t.merge(studentContext, writer);

            tree.put("HtmlBody", writer.toString());

            byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                    .bodyByteArray(jackson.writeValueAsBytes(tree), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            JsonNode irs = jackson.readTree(rs);

            mongo.updateFirst(Query.query(Criteria.where("ii").is(ii)),
                    new Update().set("student.invitation.request", jackson.convertValue(tree, HashMap.class))
                    .set("student.invitation.response", jackson.convertValue(irs, HashMap.class)), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendSuccessMessage(JsonNode evJson) {
        try {
            sendSuccessStudent(evJson);
            sendSuccessTeacher(evJson);

            if (!evJson.at("/student/comments").isMissingNode()) {
                ObjectNode comments = (ObjectNode) jackson.readTree(new ClassPathResource("commentsEmail.json").getInputStream());
                comments.put("To", evJson.at("/teacher/email").asText());

                DateTimeZone tz = DateTimeZone.forID(evJson.at("/teacher/calendar/timeZone").asText());

                StringWriter writer = new StringWriter(32 * 1024);
                Template t = engine.getTemplate("comments.html");

                VelocityContext context = new VelocityContext();
                context.put("date", new DateTime(evJson.path("dd").asLong(), tz).toString("dd, MMM 'at' HH:mm"));
                context.put("comment", evJson.at("/student/comments").asText());
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
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

    }

    @RequestMapping(method = RequestMethod.GET, value = "/cancel")
    @ResponseBody
    public void cancel(@RequestParam Map<String, String> allRequestParams) {
        try {
            logger.info("Request params cancel {}", allRequestParams);
            String paypalToken = allRequestParams.get("token");
            Query paypalQuery = Query.query(Criteria.where("student.paypal.ppToken").is(paypalToken));
            HashMap ev = mongo.findOne(paypalQuery, HashMap.class, "events");

            if (ev != null) {
                ObjectNode evJson = jackson.convertValue(paypalQuery, ObjectNode.class);
                //send notification and remove the session

            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public static Pair<AutoPilot, VTDNav> getNavigator(byte[] xml) {
        Pair<AutoPilot, VTDNav> pair = new Pair<>();
        try {
            VTDGen vg = new VTDGen();
            vg.setDoc(xml);
            vg.parse(true);
            VTDNav vn = vg.getNav();
            AutoPilot ap = new AutoPilot(vn);

            pair.setLeft(ap);
            pair.setRight(vn);
        } catch (Exception ex) {
            logger.error(ExceptionUtils.getFullStackTrace(ex));
        }

        return pair;
    }

    public String getChange(AutoPilot ap, VTDNav vn) {
        String path = "//Valute[@ID='R01235']/Value";
        String rate = null;
        try {
            ap.resetXPath();
            ap.selectXPath(path);
            if (ap.evalXPath() != -1) {
                rate = vn.toRawString(vn.getText());
            }
            ap.resetXPath();
        } catch (Exception ex) {
        }
        return rate;
    }

    @Async
    public Future<BigDecimal> getExchangeRate() {
        BigDecimal rate = null;
        try {
            byte[] rs = Request.Get("http://www.cbr.ru/scripts/XML_daily.asp")
                    .execute().returnContent().asBytes();

            Pair<AutoPilot, VTDNav> bb = getNavigator(rs);
            String change = getChange(bb.getLeft(), bb.getRight());

            DecimalFormat df = new DecimalFormat();
            DecimalFormatSymbols symbols = new DecimalFormatSymbols();
            symbols.setDecimalSeparator(',');
            df.setDecimalFormatSymbols(symbols);

            rate = BigDecimal.valueOf(df.parse(change).doubleValue()).setScale(2, RoundingMode.CEILING);
            return new AsyncResult<>(rate);
        } catch (Exception ex) {
            logger.error(ExceptionUtils.getFullStackTrace(ex));
            rate = BigDecimal.valueOf(67);
        }

        return new AsyncResult<>(rate);

    }

    public BigDecimal getExchangeRateSync() {
        BigDecimal rate = null;
        try {
            long start = System.currentTimeMillis();
            byte[] rs = Request.Get("https://query.yahooapis.com/v1/public/yql?q=select+*+from+yahoo.finance.xchange+where+pair+=+%22USDRUB%22&format=json&env=store://datatables.org/alltableswithkeys")
                    .socketTimeout(3000).connectTimeout(2000)
                    .execute().returnContent().asBytes();
            logger.info("Yahoo took {}", System.currentTimeMillis() - start);

            JsonNode tree = jackson.readTree(rs);

            String change = tree.at("/query/results/rate/Ask").asText();

            DecimalFormat df = new DecimalFormat();
            DecimalFormatSymbols symbols = new DecimalFormatSymbols();
            symbols.setDecimalSeparator('.');
            df.setDecimalFormatSymbols(symbols);

            rate = BigDecimal.valueOf(df.parse(change).doubleValue()).setScale(2, RoundingMode.CEILING);
            return rate;
        } catch (Exception ex) {
            logger.error(ExceptionUtils.getFullStackTrace(ex));
            rate = BigDecimal.valueOf(66.5);
        }

        return rate;

    }

    private final CloseableHttpClient client = HttpClients.custom().setSSLHostnameVerifier(new NoopHostnameVerifier()).setRetryHandler(new DefaultHttpRequestRetryHandler(3, true) {
    }).build();

    @RequestMapping(method = RequestMethod.GET, value = "/make/{sum}/{currency}", produces = "application/json")
    @ResponseBody
    public byte[] requestPayment(@PathVariable BigDecimal overall, @PathVariable String currency, JsonNode event, BigDecimal exchange) {
        try {
            logger.info("Sum came {}", overall);
            InputStream is = new ClassPathResource("paypal/payment.json").getInputStream();
            byte[] bb = IOUtils.toByteArray(is);
            ObjectNode paymentTree = (ObjectNode) jackson.readTree(bb);

//            Future<BigDecimal> ff = getExchangeRate();
//            BigDecimal overall = overall;
//            if (StringUtils.equals("RUB", currency)) {
//                logger.info("Ex rate {}", ff.get());
//                overall = sum.multiply(ff.get()).setScale(0, RoundingMode.UP);
//            } else {
//                overall = sum;
//            }
            ObjectNode urls = (ObjectNode) paymentTree.path("redirect_urls");
            urls.put("return_url", env.getProperty("paypal.success"));
            urls.put("cancel_url", env.getProperty("paypal.cancel"));

            ObjectNode amountNode = (ObjectNode) paymentTree.at("/transactions/0/amount");
            amountNode.put("total", overall.toString());
            amountNode.put("currency", currency);
            ObjectNode item = (ObjectNode) paymentTree.at("/transactions/0/item_list/items/0");
            item.put("price", overall.toString());
            item.put("currency", currency);
            item.put("description", item.path("description").asText() + "id " + event.path("ii").asText());

            logger.info("Update transaction to make {}", paymentTree);

            //make payment
            String authToken = authPaypal();
            logger.info("Paypal auth {}", authToken);

            HttpPost post = new HttpPost(env.getProperty("paypal.execute"));
            post.setEntity(new StringEntity(paymentTree.toString(), "UTF-8"));
            post.addHeader("Authorization", "Bearer " + authToken);
            post.addHeader("Content-type", "application/json");

            CloseableHttpResponse rs = client.execute(post);
            byte[] ppResp = EntityUtils.toByteArray(rs.getEntity());
            IOUtils.closeQuietly(rs);

            //make it retryable
//            byte[] ppResp = Request.Post(env.getProperty("paypal.execute")).addHeader("Authorization", "Bearer " + authToken).
//                    bodyString(paymentTree.toString(), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            ObjectNode out = jackson.createObjectNode();
            out.put("cbrf", exchange);
            out.put("basicRate", overall);
            out.set("paymentRequest", paymentTree);

            JsonNode tree = jackson.readTree(ppResp);
            Iterator<JsonNode> els = tree.path("links").elements();
            String approveLink = null;
            while (els.hasNext()) {
                JsonNode el = els.next();
                if (el.path("rel").asText().equals("approval_url")) {
                    approveLink = el.path("href").asText();
                    break;
                }
            }

            logger.info("Approve link extracted {}", approveLink);
            out.set("paymentResponse", tree);
            out.put("approve_url", approveLink);
            URL url = new URL(approveLink);
            String query = url.getQuery();
            Map<String, String> map = Splitter.on("&").withKeyValueSeparator("=").split(query);
            logger.info("Map query {}", map);
            out.put("ppToken", map.get("token"));
            return jackson.writeValueAsBytes(out);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return null;
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {

        long start = System.currentTimeMillis();
        Future<BigDecimal> ff = new PaypalController().getExchangeRate();
        logger.info("Took {}", ff.get());

    }

}
