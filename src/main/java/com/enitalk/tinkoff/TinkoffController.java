package com.enitalk.tinkoff;

import com.enitalk.controllers.paypal.PaypalController;
import com.enitalk.robokassa.RobokassaCtrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.repackaged.com.google.common.base.Joiner;
import com.google.common.cache.LoadingCache;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/tinkoff")
public class TinkoffController {

    protected final static Logger logger = LoggerFactory.getLogger("tinkoff-api");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private Environment env;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private ScheduledExecutorService ex;
    @Autowired
    @Qualifier("skipCache")
    private LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesCache;
    @Autowired
    private PaypalController paypal;
    @Autowired
    RobokassaCtrl robokassa;

    private final static CloseableHttpClient client = HttpClients.custom().setSSLHostnameVerifier(new NoopHostnameVerifier()).setRetryHandler(new DefaultHttpRequestRetryHandler(3, true) {
    }).build();

    public void setJackson(ObjectMapper jackson) {
        this.jackson = jackson;
    }

    private DecimalFormat getDecimalFromat() {
        DecimalFormat df = new DecimalFormat();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        df.setDecimalFormatSymbols(symbols);
        return df;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/redirect/{id}")
    @ResponseBody
    public byte[] redirect(@PathVariable String id, HttpServletResponse res) {
        try {
            logger.info("Redirecting to payment {}", id);
            Query q = Query.query(Criteria.where("invId").is(id));
            q.fields().exclude("_id");

            HashMap ev = mongo.findOne(q, HashMap.class, "events");

            if (ev == null) {
                return "No event found or an event has expired".getBytes();
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);

            if (evJson.path("status").asInt() == 2) {
                return "The event has been paid already. Thank you".getBytes();
            }

            if (evJson.path("status").asInt() == 9) {
                return "Sorry, your booking has expired. Try booking again".getBytes();
            }

            mongo.updateFirst(Query.query(Criteria.where("invId").is(id)),
                    new Update().set("createDate", new DateTime().plusMinutes(15).toDate()).set("status", 1), "events");
            datesCache.invalidate(evJson.at("/teacher/i").asText());

            res.sendRedirect(evJson.at("/tinkoff/payLink").asText());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return "".getBytes();
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/init")
//    @ResponseBody
    public ObjectNode init(@RequestBody JsonNode event) {
        ObjectNode out = jackson.createObjectNode();
        try {
            logger.info("Init Tinkoff {}", event.path("ii").asText());
            TreeMap<String, String> query = new TreeMap<>();

            query.put("TerminalKey", env.getProperty("tinkoff.key"));
            query.put("Amount", event.path("rubles").decimalValue().multiply(BigDecimal.valueOf(100L)).setScale(0).toString());
            query.put("OrderId", event.path("invId").asText());
//            query.put("Description", URLEncoder.encode(event.path("desc").asText(), "UTF-8"));

            query.put("DATA", "Email=" + event.at("/student/email").asText());

            logger.info("Query {}", query);

            //make up a token
            TreeMap<String, String> forToken = new TreeMap<>(query);
            forToken.put("Password", env.getProperty("tinkoff.password"));
            String vl = StringUtils.join(forToken.values(), "");
            logger.info("Vl concated {}", vl);
            String sha256 = DigestUtils.sha256Hex(vl);

            logger.info("Sha256 tinkoff {}", sha256);
            query.put("Token", sha256);

            String url = "https://securepay.tinkoff.ru/rest/Init?" + Joiner.on('&').withKeyValueSeparator("=").join(query);
            logger.info("Url to execute, tinkoff {}", url);

//            JsonNode resp = jackson.readTree(new ClassPathResource("tf/tfInit.json").getInputStream());
            HttpPost statePost = new HttpPost(url);
            statePost.setHeader("Content-Type", "application/x-www-form-urlencoded");

            List<NameValuePair> pairs = URLEncodedUtils.parse(new URI(url), "UTF-8");

            logger.info("Tinkoff pairs {}", pairs);
            statePost.setEntity(new UrlEncodedFormEntity(pairs));

//            HttpGet get = new HttpGet(url);
            CloseableHttpResponse rs = client.execute(statePost);
            byte[] tinkoffResp = EntityUtils.toByteArray(rs.getEntity());
            IOUtils.closeQuietly(rs);

            JsonNode resp = jackson.readTree(tinkoffResp);
            logger.info("Http tinkoff resp state {}", resp);

            if (!resp.path("Success").asBoolean(false)) {
                logger.error("Error by Tinkoff init url {} resp {}", url, resp);
                return out;
            }

            out.put("initUrl", url);
            out.put("sha256", sha256);
            out.set("initResp", resp);
            out.put("payLink", resp.path("PaymentURL").asText());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;
    }

    public void refund(ObjectNode evJson, Map<String, String> map) {
        try {
            logger.info("Refund came {} {}", evJson.path("ii").asText(), map);

            //check signature
            TreeMap<String, String> cc = new TreeMap<>(map);
            cc.put("Password", env.getProperty("tinkoff.password"));
            cc.remove("Token");
            String sha256Calc = DigestUtils.sha256Hex(StringUtils.join(cc.values(), ""));
            logger.info("Sha256 in refund calc {} came {}", sha256Calc, map.get("Token"));

            boolean shas = StringUtils.equalsIgnoreCase(sha256Calc, map.get("Token"));
            if (!shas) {
                logger.info("Error by sha256 refund {}", map);
                return;
            }

            //send student a note to bot
            long date = evJson.path("dd").asLong();
            DateTime dd = new DateTime(date, DateTimeZone.UTC);

            double amount = getDecimalFromat().parse(map.get("Amount")).doubleValue();
            String amountString = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(100L), MathContext.DECIMAL64).setScale(0).toString();
            String text = "0x274c Bank has refunded your payment of " + amountString + " RUB for an event on "
                    + dd.toDateTime(DateTimeZone.forID(evJson.at("/student/calendar/timeZone").asText())).toString("yyyy/MM/dd HH:mm");

            evJson.put("errorText", text);
            robokassa.sendErrorToSudent(evJson, "REFUNDED");

            Update up = new Update().set("tinkoff.refund", map);

            if (dd.isAfter(DateTime.now(DateTimeZone.UTC))) {
                //send a teacher note as well
                ObjectNode o = (ObjectNode) jackson.readTree(new ClassPathResource("emails/cancelled.json").getInputStream());
                DateTime cancelDate = dd.toDateTime(DateTimeZone.forID(evJson.at("/teacher/calendar/timeZone").asText()));
                String ds = cancelDate.toString("yyyy/MM/dd HH:mm");
                o.put("Subject", o.path("Subject").asText() + ds);
                o.put("HtmlBody", "We are sorry but the student has cancelled the lesson on " + ds + ". We have cleared this time for others to book.");
                o.put("To", evJson.at("/teacher/email").asText());

                HttpPost post = new HttpPost("https://api.postmarkapp.com/email");
                post.addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"));
                post.setEntity(new ByteArrayEntity(jackson.writeValueAsBytes(o)));
                post.setHeader("Content-type", "application/json");

                CloseableHttpResponse en = client.execute(post);
                byte[] rs = EntityUtils.toByteArray(en.getEntity());
                IOUtils.closeQuietly(en);

                JsonNode postMark = jackson.readTree(rs);
                logger.info("Teacher notify refund resp {}", postMark);

                up.set("teacher.refund.rq", jackson.convertValue(o, HashMap.class));
                up.set("teacher.refund.rs", jackson.convertValue(postMark, HashMap.class));
            }

            mongo.updateFirst(Query.query(Criteria.where("invId").is(map.get("OrderId"))), up, "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/success")
    @ResponseBody
    public String success(@RequestParam Map<String, String> map, HttpServletResponse res) {
        String out = null;
        try {
            logger.info("Tinkoff success {}", map);

            Query q = Query.query(Criteria.where("invId").is(map.get("OrderId")));
            HashMap ev = mongo.findOne(q, HashMap.class, "events");

            logger.info("Event found {}", ev != null ? ev.get("ii") : " null");

            if (ev == null) {
                logger.info("No event found {}", map);
                return "OK";
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
            if (evJson.path("status").asInt() == 2) {
                if (StringUtils.equalsIgnoreCase(map.get("Status"), "refunded")) {
                    ex.submit(() -> {
                        refund(evJson, map);
                    });
                }

                mongo.updateFirst(q, new Update().push("tinkoff.success", map), "events");
                return "OK";
            }

            mongo.updateFirst(q, new Update().push("tinkoff.success", map), "events");

            if (!map.get("Success").equalsIgnoreCase("true")) {
                logger.info("Error by Tinkoff success {}", map);
//                robokassa.sendErrorToSudent(evJson, map.get("Status"));
                return "OK";
            }

            if (!map.containsKey("TerminalKey") || !StringUtils.equalsIgnoreCase(env.getProperty("tinkoff.key"), map.get("TerminalKey"))) {
                logger.info("Error by Tinkoff terminal {}", map);
                res.sendError(401);
                return "OK";
            }

            TreeMap<String, String> cc = new TreeMap<>(map);
            cc.put("Password", env.getProperty("tinkoff.password"));
            cc.remove("Token");
            String sha256Calc = DigestUtils.sha256Hex(StringUtils.join(cc.values(), ""));
            logger.info("Sha256calc {} came {}", sha256Calc, map.get("Token"));

            boolean shas = StringUtils.equalsIgnoreCase(sha256Calc, map.get("Token"));
            if (!shas) {
                logger.info("Error by sha256 {}", map);
                res.sendError(401);
                return "e:sha256";
            }

            String realSum = evJson.path("sum").asText();

            BigDecimal realSumDecimal = BigDecimal.valueOf(getDecimalFromat().parse(realSum).doubleValue());
            realSumDecimal = realSumDecimal.multiply(BigDecimal.valueOf(100L)).setScale(0);
            String amountCame = map.get("Amount");

            logger.info("Sum came id {} tinkoff {} real {}", new Object[]{evJson.path("ii").asText(), amountCame, realSumDecimal});
            if (!realSumDecimal.toString().equals(amountCame)) {
                logger.error("Real {} came {}", realSumDecimal, amountCame);
                res.sendError(401);
                return "e:Sums are not equal";
            }

            //check status
            TreeMap<String, String> getStateQuery = new TreeMap<>();
            getStateQuery.put("TerminalKey", env.getProperty("tinkoff.key"));
            getStateQuery.put("PaymentId", evJson.at("/tinkoff/initResp/PaymentId").asText());

            TreeMap<String, String> forToken = new TreeMap<>(getStateQuery);
            forToken.put("Password", env.getProperty("tinkoff.password"));
            String vl = StringUtils.join(forToken.values(), "");
            logger.info("Vl concated {}", vl);
            String sha256 = DigestUtils.sha256Hex(vl);
            logger.info("Token for getstate {}", sha256);

            getStateQuery.put("Token", sha256);

            String url = "https://securepay.tinkoff.ru/rest/GetState?" + Joiner.on('&').withKeyValueSeparator("=").join(getStateQuery);
            logger.info("Getstate request tinkoff {}", url);

            HttpPost statePost = new HttpPost(url);
            List<NameValuePair> pairs = URLEncodedUtils.parse(new URI(url), "UTF-8");
            statePost.setEntity(new UrlEncodedFormEntity(pairs));

            CloseableHttpResponse rs = client.execute(statePost);
            byte[] tinkoffResp = EntityUtils.toByteArray(rs.getEntity());
            IOUtils.closeQuietly(rs);

//            JsonNode getStateResp = jackson.readTree(new ClassPathResource("tf/getstate.json").getInputStream());
            JsonNode getStateResp = jackson.readTree(tinkoffResp);
            logger.info("TInkoff getstate resp {}", getStateResp);

            mongo.updateFirst(q, new Update().set("tinkoff.getstate.url", url).set("tinkoff.getstate.resp", jackson.convertValue(getStateResp, HashMap.class)), "events");

            boolean isSuccess = getStateResp.path("Success").asBoolean();
            if (!isSuccess) {
                logger.error("Getstate failed for {} returned {}", evJson.path("ii").asText(), getStateResp);
                res.sendError(401);
                return "e:getstate:success";
            }

            //end check status
            String error = map.get("ErrorCode");
            switch (error) {
                case "0":
                    ex.submit(() -> {
                        if (evJson.path("status").asInt() != 1) {
                            mongo.updateFirst(q, new Update().set("status", 2), "events");
                            datesCache.invalidate(evJson.at("/teacher/i").asText());
                        }

                        paypal.successPayment(evJson, null, null);
                    });
                    out = "OK";
                    break;
                default:
//                    ex.submit(() -> {
//                        robokassa.sendErrorToSudent(evJson, error);
//                    });
//                    out = "e:code " + error;
//                    res.sendError(401);
                    break;
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/fail")
//    @ResponseBody
//    public String fail(@RequestParam Map<String, String> map) {
//        try {
//            Query q = Query.query(Criteria.where("invId").is(map.get("OrderId")).andOperator(Criteria.where("status").is(1)));
//            mongo.updateFirst(q, new Update().set("tinkoff.error", map), "events");
//
//            robokassa.fail(map.get("OrderId"), map);
//
//        } catch (Exception e) {
//            logger.error(ExceptionUtils.getFullStackTrace(e));
//        }
//        return "OK";
//    }
//    @RequestMapping(method = RequestMethod.GET, value = "/page/success")
//    @ResponseBody
//    public String pageSuccess(@RequestParam Map<String, String> map) {
//        logger.info("Page success tinkoff {}", map);
//        return "O la la success";
//    }
//    
//    @RequestMapping(method = RequestMethod.GET, value = "/page/fail")
//    @ResponseBody
//    public String pageFail(@RequestParam Map<String, String> map) {
//        logger.info("Page fail tinkoff {}", map);
//        return "O la la - fail";
//    }
    public static void main(String[] args) {
        TinkoffController t = new TinkoffController();                
        
        long start = System.currentTimeMillis();
        BigDecimal r = t.exchangeRate(new ObjectMapper());
        System.out.println(r + " " + (System.currentTimeMillis() - start));
    }

    public static BigDecimal exchangeRate(ObjectMapper jackson) {
        BigDecimal bd = BigDecimal.valueOf(66.5);
        try {
            CloseableHttpResponse rs = client.execute(new HttpGet("https://www.tinkoff.ru/api/v1/currency_rates/"));
            byte[] json = EntityUtils.toByteArray(rs.getEntity());
            JsonNode tree = jackson.readTree(json);
            List<JsonNode> all = tree.findParents("category");
            JsonNode rate = all.stream().filter((JsonNode j) -> {
                return j.path("category").asText("").equals("DepositPayments")
                        && j.at("/fromCurrency/code").asInt(-1) == 840
                        && j.at("/toCurrency/code").asInt(-1) == 643
                        && j.has("sell");
            }).findFirst().get();
            if (!rate.isMissingNode()) {
                bd = rate.path("sell").decimalValue().setScale(2, RoundingMode.CEILING);
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return bd;
    }
}
