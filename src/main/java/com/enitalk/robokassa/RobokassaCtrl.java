package com.enitalk.robokassa;

import com.enitalk.controllers.bots.BotController;
import com.enitalk.controllers.paypal.PaypalController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.LoadingCache;
import com.ximpleware.AutoPilot;
import com.ximpleware.VTDGen;
import com.ximpleware.VTDNav;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/robokassa")
public class RobokassaCtrl {

    protected final static Logger logger = LoggerFactory.getLogger("robokassa-api");

    private final CloseableHttpClient client = HttpClients.custom().setSSLHostnameVerifier(new NoopHostnameVerifier()).setRetryHandler(new DefaultHttpRequestRetryHandler(3, true) {
    }).build();

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private Environment env;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private PaypalController paypal;
    @Autowired
    private ScheduledExecutorService ex;
    @Autowired
    @Qualifier("skipCache")
    private LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesCache;
    @Autowired
    public BotController botCtrl;

    private DecimalFormat getDecimalFromat() {
        DecimalFormat df = new DecimalFormat();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        df.setDecimalFormatSymbols(symbols);
        return df;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/static/success")
    @ResponseBody
    public byte[] statusSuccess() {
        return "Thanks for booking a lesson. We are sending further information to the messenger and your email!".getBytes();
    }

    @RequestMapping(method = RequestMethod.GET, value = "/static/fail")
    @ResponseBody
    public String statusFail(@RequestParam Map<String, String> map) {
        String out = "We couldn't process the payment or it failed. We apoligize!";
        try {
            String inv = map.get("InvId");
            if (StringUtils.isBlank(inv)) {
                return out;
            }

            fail(inv, map);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/fail")
    @ResponseBody
    public String fail(@RequestParam(name = "InvId") String InvId, @RequestParam Map<String, String> map) {
        try {
            logger.error("Rejected {}", map);
            Query q = Query.query(Criteria.where("invId").is(InvId).andOperator(Criteria.where("status").is(1)));
            HashMap ev = mongo.findOne(q, HashMap.class, "events");

            if (ev == null) {
                logger.info("No event found {}", map);
                return "e:NotFound";
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
            String i = evJson.at("/teacher/i").asText();

            ex.submit(() -> {

                try {
                    mongo.remove(q, "events");
                    datesCache.refresh(i);

                    JsonNode dest = evJson.at("/student/dest");
                    ArrayNode msgs = jackson.createArrayNode();
                    ObjectNode o = msgs.addObject();
                    o.set("dest", dest);
                    ObjectNode m = jackson.createObjectNode();
                    o.set("message", m);
                    m.put("text", "0x1f641 It seems the payment has been rejected. Sorry to see that.");
                    botCtrl.sendMessages(msgs);
                    botCtrl.back(evJson.path("student"));
                } catch (Exception ex) {
                    logger.error(ExceptionUtils.getFullStackTrace(ex));
                }

            });

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return "OK";
    }

    @RequestMapping(method = RequestMethod.GET, value = "/success")
    @ResponseBody
    public String success(@RequestParam(name = "OutSum") BigDecimal OutSum,
            @RequestParam(name = "InvId") String InvId, @RequestParam(name = "SignatureValue") String signature,
            @RequestParam Map<String, String> map) {
        String out = null;

        try {
            logger.info("ResultUrl params came {}", map);
            Query q = Query.query(Criteria.where("invId").is(InvId).andOperator(Criteria.where("status").is(1)));
            HashMap ev = mongo.findOne(q, HashMap.class, "events");

            if (ev == null) {
                logger.info("No event found {}", map);
                return "e:NotFound";
            }

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
            String realSum = evJson.path("sum").asText();

            BigDecimal realSumDecimal = BigDecimal.valueOf(getDecimalFromat().parse(realSum).doubleValue());
            realSumDecimal = realSumDecimal.setScale(0);
            BigDecimal OutSumTruncated = OutSum.setScale(0);

            if (!realSumDecimal.equals(OutSumTruncated)) {
                logger.error("Sum came {} real {}", OutSumTruncated, realSum);
                return "e:Sums are not equal";
            }

            String came = OutSum + ":" + InvId + ":" + env.getProperty("rbk.pass2");
            String correctHex = DigestUtils.sha512Hex(came);
            if (!StringUtils.equalsIgnoreCase(correctHex, signature)) {
                logger.error("Signature came {} calc {}", signature, correctHex);
                return "e:Signature";
            }

            //check status via robokassa        
            // https://auth.robokassa.ru/Merchant/WebService/Service.asmx/OpState
            String composed = env.getProperty("rbk.login") + ":" + InvId + ":" + env.getProperty("rbk.pass2");
            String hash = DigestUtils.sha512Hex(composed);

            String url = env.getProperty("rbk.state") + "MerchantLogin=" + env.getProperty("rbk.login") + "&";
            url += "InvoiceID=" + InvId + "&";
            url += "Signature=" + hash;

            logger.info("Check result opstate url {}", url);

            //TODO make it to check it
            CloseableHttpResponse rs = client.execute(new HttpGet(url));
            byte[] xml = EntityUtils.toByteArray(rs.getEntity());
            IOUtils.closeQuietly(rs);

            String xmlString = new String(xml);
            logger.info("Xml opstate response {}", xmlString);
            Pair<AutoPilot, VTDNav> pair = getNavigator(xml);

            String stateCode = getCode(pair.getLeft(), pair.getRight());

            String resultCode = getResultCode(pair.getLeft(), pair.getRight());

            mongo.updateFirst(q, new Update().set("rbkResp", xmlString).push("rbkParams", map).set("rbkCheck", url), "events");

            if (StringUtils.isBlank(resultCode) || !StringUtils.equals(resultCode, "0")) {

                logger.error("State code not found {} {}", evJson.path("ii").asText(), xmlString);
                ex.submit(() -> {
                    sendErrorToSudent(evJson, resultCode);
                });
                out = "e:result:" + stateCode;
                return out;
            }

            switch (stateCode) {
                case "50":
                case "100":
                    ex.submit(() -> {
                        paypal.successPayment(evJson, null, null);
                    });
                    out = "OK" + InvId;
                    break;
                default:
                    logger.error("Error came for event {} xml {}", evJson.path("ii").asText(), xmlString);
                    ex.submit(() -> {
                        sendErrorToSudent(evJson, stateCode);
                    });
                    out = "e:" + stateCode;
                    break;
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return out;

    }

    public void sendErrorToSudent(ObjectNode evJson, String code) {
        try {
            JsonNode dest = evJson.at("/student/dest");
            ArrayNode msgs = jackson.createArrayNode();
            ObjectNode o = msgs.addObject();
            o.set("dest", dest);
            ObjectNode m = jackson.createObjectNode();
            o.set("message", m);

            String text = evJson.path("errorText").asText("0x1f641 Something went wrong with the payment or the system cannot process it. Please, try to schedule again.\n "
                    + "We sent a report to the support staff and they will look into it if there is a mistake on our side");

            m.put("text", text);
            botCtrl.sendMessages(msgs);
            mongo.updateFirst(Query.query(Criteria.where("ii").is(evJson.path("ii").asText())), new Update().set("status", 11), "events");
            datesCache.refresh(evJson.at("/teacher/i").asText());
            botCtrl.back(evJson.path("student"));

            //send an email to me indicating something went wrong
            ObjectNode err = (ObjectNode) jackson.readTree(new ClassPathResource("emails/confirmation.json").getInputStream());
            err.put("Subject", "Robokassa error");
            err.put("Tag", "rbk.err");
            err.put("HtmlBody", "Error for event " + evJson.path("ii").asText() + " code " + code);

            byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                    .bodyByteArray(jackson.writeValueAsBytes(err), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
            logger.info("Sent error rbk {}", jackson.readTree(rs));

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public static void main(String[] args) throws IOException {
        byte[] x = IOUtils.toByteArray(new ClassPathResource("rbk/1.xml").getInputStream());

        RobokassaCtrl ctrl = new RobokassaCtrl();
        Pair<AutoPilot, VTDNav> pair = ctrl.getNavigator(x);
        String code = ctrl.getCode(pair.getLeft(), pair.getRight());
        System.out.println(code);

    }

    public Pair<AutoPilot, VTDNav> getNavigator(byte[] xml) {
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

    public String getCode(AutoPilot ap, VTDNav vn) {
        String path = "//State/Code";
        String code = null;
        try {
            ap.resetXPath();
            ap.selectXPath(path);
            if (ap.evalXPath() != -1) {
                code = vn.toRawString(vn.getText());
            }

            ap.resetXPath();
        } catch (Exception ex) {
        }
        return code;
    }

    public String getResultCode(AutoPilot ap, VTDNav vn) {
        String path = "//Result/Code";
        String code = null;
        try {
            ap.resetXPath();
            ap.selectXPath(path);
            if (ap.evalXPath() != -1) {
                code = vn.toRawString(vn.getText());
            }

            ap.resetXPath();
        } catch (Exception ex) {
        }
        return code;
    }

}
