package com.enitalk.controllers.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/calendar")
public class GoogleCalendarController {

    protected final static Logger logger = LoggerFactory.getLogger("calendar-api");

    @Autowired
    private FileDataStoreFactory store;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private MongoTemplate mongo;

    public Calendar getGoogleCalendar(String id) throws IOException {
        Credential credential = flow.loadCredential(id);
        credential.refreshToken();
        return new Calendar.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential).setApplicationName("enitalk").build();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/insert", produces = "application/json")
    @ResponseBody
    public byte[] createEvent(@RequestBody ObjectNode node) {
        byte[] out = null;
        try {
            Event event = new Event()
                    .setSummary("Enitalk session")
                    .setDescription(node.path("desc").asText("Desc 1"));

            String startCame = node.path("start").asText();
            DateTime startDateTime = new DateTime(startCame);

            logger.info("Start [{}] cv [{}]", startCame, startDateTime);
            EventDateTime start = new EventDateTime()
                    .setDateTime(startDateTime);
            event.setStart(start);

            String endCame = node.path("end").asText();
//            DateTimeFormatter fmt = ISODateTimeFormat.dateTimeNoMillis();
//            org.joda.time.DateTime edd = fmt.parseDateTime(endCame);
//            logger.info("Edd {}", edd.toDateTimeISO());
            DateTime endDateTime = new DateTime(endCame);
            logger.info("End [{}] cv [{}]", endCame, endDateTime);
            EventDateTime end = new EventDateTime()
                    .setDateTime(endDateTime);
            event.setEnd(end);

            EventReminder[] reminderOverrides = new EventReminder[]{
                new EventReminder().setMethod("popup").setMinutes(12 * 60),
                new EventReminder().setMethod("popup").setMinutes(60),
                new EventReminder().setMethod("popup").setMinutes(7)
            };

            Event.Reminders reminders = new Event.Reminders()
                    .setUseDefault(false)
                    .setOverrides(Arrays.asList(reminderOverrides));
            event.setReminders(reminders);
            event.setExtendedProperties(new Event.ExtendedProperties().setPrivate(new HashMap<String, String>() {
                {
                    put("tag", "enitalk");
                }
            }));

            String calendarId = "primary";

            Calendar calendarApi = getGoogleCalendar(node.path("user").asText());
            InputStream rs = calendarApi.events().insert(calendarId, event).executeUnparsed().getContent();

            out = IOUtils.toByteArray(rs);
        } catch (Exception ex) {
            logger.error(ExceptionUtils.getFullStackTrace(ex));
        }

        return out;

    }

    public TreeSet<org.joda.time.DateTime> pendingBookings(String id) {
        //TOD make after now
        TreeSet<org.joda.time.DateTime> set = new TreeSet<>();
        Criteria one = Criteria.where("status").is(1);
        Criteria booked = Criteria.where("status").is(2);

        Query q = Query.query(Criteria.where("teacher.i").is(id).orOperator(one, booked));
        logger.info("Query {}", q.getQueryObject());
        q.fields().exclude("_id").include("teacher");

        List<HashMap> events = mongo.find(q, HashMap.class, "events");
        ArrayNode dates = jackson.convertValue(events, ArrayNode.class);

        Iterator<JsonNode> els = dates.elements();

        DateTimeFormatter ff = ISODateTimeFormat.dateTime();
        while (els.hasNext()) {
            JsonNode tt = els.next();
            logger.info("Pending {}", tt.at("/teacher/scheduledDate"));
            String dd = tt.at("/teacher/scheduledDate").asText("");
            if (StringUtils.isNotBlank(dd)) {
                set.add(ff.parseDateTime(dd).toDateTime(DateTimeZone.UTC));
            }
        }
        return set;
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/events", produces = "application/json")
//    @ResponseBody
//    public byte[] listEvents(@RequestBody ObjectNode json) {
//        try {
//
//            logger.info("List events {}", json);
//
//            String id = json.path("id").asText();
//            int daysForward = json.path("daysForward").asInt(7);
//
//            HashMap teacher = mongo.findOne(Query.query(Criteria.where("i").is(id)), HashMap.class, "teachers");
//            JsonNode teacherJson = jackson.convertValue(teacher, JsonNode.class);
//            Long lid = teacherJson.at("/dest/sendTo").asLong();
//            logger.info("Lid {}", lid);
//            Calendar calendar = getGoogleCalendar(lid.toString());
//
//            String timeZone = teacherJson.path("calendar").path("timeZone").asText();
//
//            org.joda.time.DateTime nn = new org.joda.time.DateTime().toDateTime(DateTimeZone.UTC).toDateTime(DateTimeZone.forID(timeZone));
//            //check if forward days fall onto exclude
//            Set<String> exclude = jackson.convertValue(teacherJson.path("excludeDays"), Set.class);
//            int i = 0;
////            logger.info("Dd fw before {}", daysForward);
//            while (i++ < daysForward) {
//                if (exclude.contains(nn.plusDays(i).toString("EEE"))) {
//                    daysForward++;
//                }
//            }
//            logger.info("Dd fw after {}", daysForward);
//
//            //end expanding days
//            DateTime now = new DateTime(nn.toDate());
//            DateTime plusDaysForward = new DateTime(nn.plusDays(daysForward).toDate());
//
////            logger.info("Looking events between {} {}", now, plusDaysForward);
//            InputStream is = calendar.events().list("primary").setMaxResults(2500).setTimeMax(plusDaysForward).setTimeMin(now).executeAsInputStream();
//            byte[] bytes = IOUtils.toByteArray(is);
//            IOUtils.closeQuietly(is);
//
//            JsonNode tree = jackson.readTree(bytes);
//
//            ArrayNode array = jackson.createArrayNode();
//            array.addAll((ArrayNode) tree.path("items"));
//
//            TreeSet<org.joda.time.DateTime> eventsMap = filterCalendar(array, teacherJson, daysForward);
//
//            TreeSet<org.joda.time.DateTime> bookings = pendingBookings(id);
//            eventsMap.removeAll(bookings);
//
//            ArrayNode o = jackson.createArrayNode();
//            eventsMap.stream().forEach((org.joda.time.DateTime key) -> {
//                ObjectNode oo = o.addObject();
//                oo.put("timeLocal", key.toString());
//                oo.put("utcTime", key.toDateTime(DateTimeZone.UTC).toString());
//
//            });
//            return jackson.writeValueAsBytes(o);
//
//        } catch (Exception e) {
//            logger.error(ExceptionUtils.getFullStackTrace(e));
//        }
//
//        return null;
//    }

    @RequestMapping(method = RequestMethod.POST, value = "/events/busy", produces = "application/json")
    @ResponseBody
    public byte[] busyEvents(@RequestBody ObjectNode json) {
        try {

            String id = json.path("id").asText();
            int daysForward = json.path("daysForward").asInt(7);

            HashMap teacher = mongo.findOne(Query.query(Criteria.where("i").is(id)), HashMap.class, "teachers");
            JsonNode teacherJson = jackson.convertValue(teacher, JsonNode.class);
//            Long lid = teacherJson.at("/dest/sendTo").asLong();
            
//            Calendar calendar = getGoogleCalendar(lid.toString());

            String timeZone = teacherJson.at("/calendar/timeZone").asText();

            org.joda.time.DateTime nn = new org.joda.time.DateTime().toDateTime(DateTimeZone.forID(timeZone));

            DateTime now = new DateTime(nn.toDate());
            DateTime plusDaysForward = new DateTime(nn.plusDays(daysForward).toDate());

            logger.info("Looking events between {} {}", now, plusDaysForward);

//            InputStream is = calendar.events().list("primary").setMaxResults(2500).setTimeMax(plusDaysForward).setTimeMin(now).executeAsInputStream();

//            JsonNode tree = jackson.readTree(is);

//            Iterator<JsonNode> nodes = tree.path("items").elements();
            DateTimeFormatter fmtDateTime = ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.forID(timeZone));
//            DateTimeFormatter ffmt = ISODateTimeFormat.date().withZone(DateTimeZone.forID(timeZone));

            TreeSet<org.joda.time.DateTime> dates = new TreeSet<>();

//            while (nodes.hasNext()) {
//                JsonNode ev = nodes.next();
//                logger.info("Proc google event {}", ev);
//                boolean isFullDay = ev.path("start").has("date");
//
//                org.joda.time.DateTime stDate = isFullDay ? ffmt.parseDateTime(ev.at("/start/date").asText())
//                        : fmtDateTime.parseDateTime(ev.at("/start/dateTime").asText());
//
//                org.joda.time.DateTime enDate = isFullDay ? ffmt.parseDateTime(ev.at("/end/date").asText())
//                        : fmtDateTime.parseDateTime(ev.at("/end/dateTime").asText());
//
//                if (!isFullDay) {
//                    Duration diff = new Duration(stDate, enDate);
//                    int hoursBetween = (int) diff.getStandardHours();
//                    if (enDate.getMinuteOfHour() > 0) {
//                        ++hoursBetween;
//                    }
//
//                    while (hoursBetween-- > 0) {
//                        dates.add(stDate.minuteOfHour().setCopy(0).plusHours(hoursBetween).toDateTime(DateTimeZone.UTC));
//                    }
//                } else {
//                    stDate = stDate.minuteOfHour().setCopy(0).hourOfDay().setCopy(0);
//                    int i = 24;
//                    while (i-- > 0) {
//                        dates.add(stDate.plusHours(i).toDateTime(DateTimeZone.UTC));
//                    }
//                    dates.add(enDate);
//                }
//
//            }

            TreeSet<org.joda.time.DateTime> bookings = pendingBookings(id);
            logger.info("Pennding {}", bookings);
            dates.addAll(bookings);

            logger.info("Dates {}", dates);

            ArrayNode a = jackson.createArrayNode();
            DateTimeFormatter utc = fmtDateTime.withZoneUTC();
            dates.stream().forEach((org.joda.time.DateTime d) -> {
                a.add(utc.print(d));
            });

            return jackson.writeValueAsBytes(a);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return null;
    }

    private TreeSet<org.joda.time.DateTime> filterCalendar(JsonNode calendarEvents, JsonNode teacherJson, int daysForward) {

        DateTimeFormatter fmtDateTime = ISODateTimeFormat.dateTimeNoMillis();
        DateTimeFormatter ffmt = ISODateTimeFormat.date();

        String timeZone = teacherJson.at("/calendar/timeZone").asText();
        Set<String> exclude = jackson.convertValue(teacherJson.path("excludeDays"), Set.class);

        DateTimeZone zone = DateTimeZone.forID(timeZone);
        DateTimeFormatter pp = DateTimeFormat.forPattern("HH:mm:ss").withZone(zone);

        org.joda.time.DateTime ss = pp.parseDateTime(teacherJson.path("start").asText());
        org.joda.time.DateTime ee = pp.parseDateTime(teacherJson.path("end").asText());

//        logger.info("Tt av from {} {}", ss, ee);
        TreeSet<org.joda.time.DateTime> treeSet = getSet(ss.hourOfDay().get(), ee.hourOfDay().get(),
                timeZone, daysForward, exclude);

        Iterator<JsonNode> nodes = calendarEvents.elements();
        while (nodes.hasNext()) {
            JsonNode ev = nodes.next();
//            logger.info("Proc google event {}", ev);
            boolean isFullDay = ev.path("start").has("date");

            org.joda.time.DateTime stDate = isFullDay ? ffmt.parseDateTime(ev.path("start").path("date").asText())
                    : fmtDateTime.parseDateTime(ev.path("start").path("dateTime").asText());

            org.joda.time.DateTime enDate = isFullDay ? ffmt.parseDateTime(ev.path("end").path("date").asText())
                    : fmtDateTime.parseDateTime(ev.path("end").path("dateTime").asText());

            if (!isFullDay) {
                stDate = stDate.toDateTime(DateTimeZone.UTC).toDateTime(zone);
                enDate = enDate.toDateTime(DateTimeZone.UTC).toDateTime(zone);
            }

            if (isFullDay) {
                SortedSet<org.joda.time.DateTime> subset = treeSet.subSet(stDate, enDate);
                subset.clear();
            } else {
                org.joda.time.DateTime copySt = stDate.minuteOfHour().setCopy(0).secondOfMinute().setCopy(0);
                org.joda.time.DateTime copyEn = enDate.minuteOfHour().setCopy(0).secondOfMinute().setCopy(0);
                Duration diff = new Duration(stDate, enDate);
                if (diff.getStandardHours() > 1) {
                    copyEn = copyEn.plusHours(1);
                }

                SortedSet<org.joda.time.DateTime> subset = treeSet.subSet(copySt, true, copyEn, enDate.getMinuteOfHour() > 20);
                subset.clear();
            }

        }

        return treeSet;
    }

    public static TreeSet<org.joda.time.DateTime> getSet(int from, int to, String timeZone, int daysForward, Set<String> excludeDays) {

        TreeSet<org.joda.time.DateTime> treeSet = new TreeSet<>();

        org.joda.time.DateTime now = new org.joda.time.DateTime().toDateTime(DateTimeZone.forID(timeZone));
        logger.info("Now {} for tz {}", now, timeZone);
        
        Integer[] today = IntStream.range(now.hourOfDay().get() + 1, to + 1).boxed().toArray(Integer[]::new);

        org.joda.time.DateTime zz = now.hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
        for (Integer h : today) {
            treeSet.add(zz.hourOfDay().setCopy(h));
        }

        Integer[] fullDay = IntStream.range(from, to + 1).boxed().toArray(Integer[]::new);

        for (int i = 1; i <= daysForward; i++) {
            org.joda.time.DateTime zeroDay = now.plusDays(i).hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
            if (excludeDays.contains(zeroDay.toString("EEE"))) {
                daysForward++;
                continue;
            }
            for (Integer h : fullDay) {
                treeSet.add(zeroDay.hourOfDay().setCopy(h));
            }
        }
        return treeSet;
    }

    public static TreeSet<org.joda.time.DateTime> getSet(int from, int to, int daysForward, Set<String> excludeDays) {

        TreeSet<org.joda.time.DateTime> treeSet = new TreeSet<>();

        org.joda.time.DateTime now = new org.joda.time.DateTime(DateTimeZone.UTC);
        Integer[] today = IntStream.range(now.hourOfDay().get() + 1, to + 1).boxed().toArray(Integer[]::new);

        org.joda.time.DateTime zz = now.hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
        for (Integer h : today) {
            treeSet.add(zz.hourOfDay().setCopy(h));
        }

        Integer[] fullDay = IntStream.range(from, to + 1).boxed().toArray(Integer[]::new);

        for (int i = 1; i <= daysForward; i++) {
            org.joda.time.DateTime zeroDay = now.plusDays(i).hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
            if (excludeDays.contains(zeroDay.toString("EEE"))) {
                continue;
            }
            for (Integer h : fullDay) {
                treeSet.add(zeroDay.hourOfDay().setCopy(h));
            }
        }
        return treeSet;
    }

    static DateTimeFormatter fmt = ISODateTimeFormat.dateTimeNoMillis();

    public static void main(String[] args) {

//        org.joda.time.DateTime edd = fmt.parseDateTime("2016-06-10T12:00:00+04:00");
//
//        logger.info("UTC {}", edd.toDateTime(DateTimeZone.UTC));
//
//        org.joda.time.DateTime ss = edd.toDateTime(DateTimeZone.forID("Europe/Moscow"));
//        logger.info("Edd {}", ss.toString());
//        System.out.println("Tz " + TimeZone.getDefault().getID());
//        TreeSet<org.joda.time.DateTime> set = GoogleCalendarController.getSet(10, 22, "Europe/Moscow");
//        System.out.println("Set " + set);
    }

}
