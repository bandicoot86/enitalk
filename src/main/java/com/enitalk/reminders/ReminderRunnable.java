package com.enitalk.reminders;

import com.enitalk.controllers.paypal.PaypalController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 *
 * @author krash
 */
@Component
public class ReminderRunnable implements Runnable {

    protected static final Logger logger = LoggerFactory.getLogger("reminder-runnable");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private Environment env;
    @Autowired
    private PaypalController paypal;

    @Override
    @Scheduled(fixedDelay = 10000L)
    public void run() {
        try {
            Query q = Query.query(Criteria.where("reminderDate").lt(new DateTime().toDate()).
                    andOperator(Criteria.where("status").is(2), Criteria.where("reminded").not().exists(true)));
//            logger.info("Reminder query {}", q);
            List<HashMap> pending = mongo.find(q, HashMap.class, "events");
            if (pending == null) {
                return;
            }

            ArrayNode pendingJson = jackson.convertValue(pending, ArrayNode.class);
            Iterator<JsonNode> pps = pendingJson.iterator();

            while (pps.hasNext()) {
                JsonNode p = pps.next();
                logger.info("Reminding of {} event", p.path("ii").asText());

                DateTime dd = new DateTime(p.path("dd").asLong(), DateTimeZone.forID(p.at("/student/calendar/timeZone").asText()));
                DateTime tdd = new DateTime(p.path("dd").asLong(), DateTimeZone.forID(p.at("/teacher/calendar/timeZone").asText()));

                ObjectNode st = (ObjectNode) p.at("/student/invitation/request");
                st.put("Tag", "lesson.reminder");
                st.put("Subject", "Entalk lesson reminder. Your lesson at " + dd.toString("yyyy-MM-dd HH:mm"));
                byte[] rs = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                        .bodyByteArray(jackson.writeValueAsBytes(st), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
                JsonNode stRs = jackson.readTree(rs);
                st.remove("HtmlBody");
                logger.info("Student reminder {} {}", st, stRs);

                ObjectNode tch = (ObjectNode) p.at("/teacher/invitation/request");
                tch.put("Subject", "Enitalk lesson reminder. Your lesson at " + tdd.toString("yyyy-MM-dd HH:mm"));
                tch.put("Tag", "lesson.reminder");
                byte[] rst = Request.Post("https://api.postmarkapp.com/email").addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"))
                        .bodyByteArray(jackson.writeValueAsBytes(tch), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();
                JsonNode tchRs = jackson.readTree(rst);
                tch.remove("HtmlBody");

                logger.info("Teacher reminder {} {}", tch, tchRs);

                mongo.updateFirst(Query.query(Criteria.where("ii").is(p.path("ii").asText())),
                        new Update().
                        set("student.reminder.request", jackson.convertValue(st, HashMap.class)).
                        set("student.reminder.response", jackson.convertValue(stRs, HashMap.class)).
                        set("teacher.reminder.request", jackson.convertValue(tch, HashMap.class)).
                        set("teacher.reminder.response", jackson.convertValue(tchRs, HashMap.class)).
                        set("reminded", true),
                        "events");

//                ArrayNode jlead2 = jackson.createArrayNode();
//                ObjectNode o2 = jlead2.addObject();
//                o2.set("dest", p.at("/student/dest"));
//                ObjectNode message2 = jackson.createObjectNode();
//                o2.set("message", message2);
//                message2.put("text", "0x1f552 Your lesson will start in 30 minutes, at " + dd.toString("yyyy-MM-dd HH:mm") + ".\n"
//                        + "0x2709 We have sent an email reminder just to make sure you certainly have it. All information is there as well.\n"
//                        + "0x1f44f Simply open your inbox when the time comes and follow the link.");
//
//                paypal.sendMessages(jlead2);

            }

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

}
