package com.enitalk.controllers.bots;

import com.enitalk.configs.TeacherFinder;
import com.enitalk.controllers.paypal.PaypalController;
import com.enitalk.controllers.youtube.GoogleCalendarController;
import com.enitalk.controllers.youtube.OAuthController;
import com.enitalk.tinkoff.TinkoffController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.common.cache.LoadingCache;
import com.mongodb.WriteResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author kraav
 */
@Controller
@RequestMapping("/rest")
public class RestController {

    protected final static Logger logger = LoggerFactory.getLogger("enitalk-rest-api");

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
    private TinkoffController tinkoffCtrl;
    @Autowired
    private BotController botCtrl;

    @Autowired
    @Qualifier("exchangeCache")
    private LoadingCache<String, BigDecimal> exchange;
    
    @RequestMapping(method = RequestMethod.POST, value = "/teacher/info", produces = "application/json")
    @ResponseBody
    public ObjectNode getInfo(@RequestBody ObjectNode json) {
        ObjectNode o = jackson.createObjectNode();
        try {
            logger.info("Info requested {}", json);
            String tid = json.path("t").asText();

            JsonNode teacher = findTeacher(tid);
            if (teacher.isMissingNode()) {
                logger.info("No tecaher found {}", json);
                o.put("error", "401");
                return o;
            }

            ObjectNode jj = (ObjectNode) teacher;
            BigDecimal rate = jj.path("rate").decimalValue();

            BigDecimal coeff = jj.path("coeff").decimalValue();
            BigDecimal usd = rate.multiply(coeff, MathContext.DECIMAL64).
                    setScale(0, RoundingMode.UP).setScale(0, RoundingMode.UP);

            BigDecimal sum = usd.multiply(exchange.get("")).setScale(0, RoundingMode.UP);
            jj.put("price", sum.toString());

            InputStream is = new ClassPathResource("dates/rest.json").getInputStream();
            JsonNode tree = jackson.readTree(is);
            Iterator<JsonNode> els = tree.elements();
            DateTime now = new DateTime(DateTimeZone.UTC);
            while (els.hasNext()) {
                ObjectNode e = (ObjectNode) els.next();
                int offset = e.path("o").asInt();
                e.put("date", now.plusMinutes(offset).toString("dd MMM',' EEE HH:mm"));
            }

            jj.set("times", tree);
            jj.remove("rate");

            jj.retain("times", "video", "price", "embed");

            return jj;

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return o;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/days", produces = "application/json")
    @ResponseBody
    public JsonNode daysAvailable(@RequestBody ObjectNode json) {
        ArrayNode o = jackson.createArrayNode();
        try {
            logger.info("Info requested {}", json);
            String tid = json.path("t").asText();
            String timezone = json.path("timezone").asText();
            String email = json.path("email").asText();

            if (StringUtils.isBlank(tid) || StringUtils.isBlank(timezone)) {
                logger.error("Missing params in {}", json);
                return jackson.createObjectNode();
            }

            mongo.upsert(Query.query(Criteria.where("email").is(email)), new Update().set("email", email).set("calendar.timeZone", timezone), "leads");

            ConcurrentSkipListSet<DateTime> avDates = datesCache.get(tid);
            if (avDates == null) {
                logger.info("No days found", json);
                return jackson.createObjectNode().put("error", "401");
            }

            DateTimeZone dzz = DateTimeZone.forID(timezone);
            TreeSet<DateTime> dds = new TreeSet<>();

            avDates.stream().forEach((DateTime t) -> {
                dds.add(new DateTime(t, dzz).millisOfDay().setCopy(0));
            });

            dds.stream().forEach((DateTime d) -> {
                o.add(d.toString("MM/dd/yyyy"));
            });

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return o;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/hours", produces = "application/json")
    @ResponseBody
    public JsonNode hoursAvailable(@RequestBody ObjectNode json) {
        ArrayNode o = jackson.createArrayNode();
        try {
            logger.info("Hours available requested {}", json);
            String tid = json.path("t").asText();
            String timezone = json.path("timezone").asText();
            String day = json.path("day").asText();
            String email = json.path("email").asText();

            if (StringUtils.isBlank(tid) || StringUtils.isBlank(timezone)) {
                logger.error("Missing params in {}", json);
                return jackson.createObjectNode();
            }

            mongo.upsert(Query.query(Criteria.where("email").is(email)), new Update().set("email", email).set("calendar.timeZone", timezone), "leads");

            ConcurrentSkipListSet<DateTime> avDates = datesCache.get(tid);
            if (avDates == null) {
                logger.info("No days found", json);
                return jackson.createObjectNode().put("error", "401");
            }

            DateTimeZone dzz = DateTimeZone.forID(timezone);
            TreeSet<DateTime> dds = new TreeSet<>();

            avDates.stream().forEach((DateTime t) -> {
                dds.add(new DateTime(t, dzz));
            });

            DateTimeFormatter daySessionFmt = DateTimeFormat.forPattern("MM/dd/yyyy").withZone(dzz);
            DateTime daySession = daySessionFmt.parseDateTime(day);
            DateTime startDay = daySession.toDateTime(dzz).millisOfDay().setCopy(0);
            logger.info("Looking for lessons between {} {}", startDay, startDay.plusDays(1).millisOfDay().setCopy(0));
            logger.info("Dds from hours {}", dds);

            SortedSet<DateTime> hours = dds.subSet(startDay, startDay.plusDays(1).millisOfDay().setCopy(0));
            logger.info("Hours av for rest {}", hours);

            hours.stream().forEach((DateTime d) -> {
                o.add(d.toString("HH:mm"));
            });

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        return o;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/book", produces = "application/json")
    @ResponseBody
    public JsonNode book(@RequestBody ObjectNode json) {
        ObjectNode j = jackson.createObjectNode();
        try {
            logger.info("Booking requested {}", json);
            String tid = json.path("t").asText();
            String timezone = json.path("timezone").asText();
            String day = json.path("day").asText();
            String hour = json.path("hour").asText();
            String email = json.path("email").asText();

            DateTimeZone dzz = DateTimeZone.forID(timezone);
            DateTimeFormatter daySessionFmt = DateTimeFormat.forPattern("MM/dd/yyyy").withZone(dzz);
            DateTimeFormatter hourFmt = DateTimeFormat.forPattern("HH:mm").withZone(dzz);
            DateTime daySession = daySessionFmt.parseDateTime(day);
            DateTime hh = hourFmt.parseDateTime(hour);

            DateTime fullDate = daySession.hourOfDay().setCopy(hh.getHourOfDay()).minuteOfHour().setCopy(hh.getMinuteOfHour());

            JsonNode teacher = findTeacher(tid);
            if (teacher.isMissingNode()) {
                j.put("error", "1");
                return j;
            }

            Query q = Query.query(Criteria.where("email").is(email));
            WriteResult wr = mongo.upsert(q, new Update().set("email", email).set("calendar.timeZone", timezone), "leads");
            boolean existed = wr.getN() == 0;
            ObjectNode to = (ObjectNode) teacher;
            
            //insert new event
            ObjectNode ev = jackson.createObjectNode();
            String ii = RandomStringUtils.randomNumeric(32);
            ev.put("ii", ii);

            ObjectNode student = jackson.createObjectNode().put("email", email);
            student.set("calendar", jackson.createObjectNode().put("timeZone", timezone));
            student.put("scheduledDate", fullDate.toString());

            ev.set("student", student);
            ev.set("teacher", teacher);
            
            //calculate rubles
            
            BigDecimal rate = teacher.path("rate").decimalValue();
            HashMap st = mongo.findOne(q, HashMap.class, "leads");
            ObjectNode stJson = jackson.convertValue(st, ObjectNode.class);

            BigDecimal coeff = stJson.has("coeff") ? stJson.path("coeff").decimalValue() : teacher.path("coeff").decimalValue();
            BigDecimal usd = rate.multiply(coeff, MathContext.DECIMAL64).
                    setScale(0, RoundingMode.UP);
            BigDecimal sum = usd.multiply(exchange.get("")).setScale(0, RoundingMode.UP);
            ev.put("rubles", sum);
            ev.put("sum", sum.toPlainString());
            ev.put("yahooRate", exchange.get("").setScale(2, RoundingMode.CEILING).toString());
            Long invId = redis.opsForValue().increment("rbk", 1);
            ev.put("invId", invId.toString());

            ObjectNode tinkoff = tinkoffCtrl.init(ev);
            logger.info("Tinkoff resp {}", tinkoff);
            ev.set("tinkoff", tinkoff);
            ev.put("status", 1);

            DateTimeFormatter iso = ISODateTimeFormat.dateTime();
            student.put("scheduledDate", fullDate.toString(iso));
            String ttz = to.at("/calendar/timeZone").asText();
            to.put("scheduledDate", fullDate.toDateTime(DateTimeZone.forID(ttz)).toString(iso));

            String redirect = env.getProperty("self.url") + "/tinkoff/redirect/" + invId;
            ev.put("orderDate", fullDate.toString("dd.MM.yyyy HH:mm"));

            ObjectNode order = botCtrl.sendOrderEmail(ev, redirect);
            if (!order.has("rq") || !order.has("rs")) {
                logger.error("Cannot send email {}");
                mongo.remove(q, "leads");
                j.put("error", "3");
                return j;
            }
            student.set("order", order);
            if (json.has("comments")) {
                student.put("comments", json.path("comments").asText());
            }

            HashMap evMap = jackson.convertValue(ev, HashMap.class);
            evMap.put("createDate", new Date());
            boolean removed = datesCache.get(to.path("i").asText()).remove(fullDate.toDateTime(DateTimeZone.UTC));
            logger.info("Date pulled {} {}", removed, fullDate.toDateTime(DateTimeZone.UTC));
            if (removed) {
                mongo.insert(evMap, "events");
            } else {
                j.put("error", "2");
            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return j;

    }

    public JsonNode findTeacher(String i) {
        JsonNode j = MissingNode.getInstance();
        try {
            Query q = Query.query(Criteria.where("i").is(i).andOperator(Criteria.where("visible").is(true)));
            q.fields().exclude("_id");

            HashMap teacher = mongo.findOne(q, HashMap.class, "teachers");
            if (teacher == null) {
                logger.info("No teacher found {}", i);
                return j;
            }

            j = jackson.convertValue(teacher, ObjectNode.class);

            return j;

        } catch (Exception e) {
            logger.debug(ExceptionUtils.getFullStackTrace(e));
        }
        return j;
    }

    public static void main(String[] args) throws IOException {
        ObjectMapper j = new ObjectMapper();
        InputStream is = new ClassPathResource("dates/treeTz.json").getInputStream();
        JsonNode tree = j.readTree(is);
        Iterator<JsonNode> it = tree.elements();
        TreeMap<Integer, String> ss = new TreeMap<>();
        ArrayNode a = j.createArrayNode();
        while (it.hasNext()) {
            ObjectNode e = (ObjectNode) it.next();
            String field = e.fieldNames().next();
            Integer t = Integer.valueOf(field);
            String z = e.path(field).elements().next().asText();
            ss.put(t, z);

            ObjectNode oo = a.addObject();
            oo.put("o", t);
            oo.put("name", z);

        }
        System.out.println(a);

    }

}
