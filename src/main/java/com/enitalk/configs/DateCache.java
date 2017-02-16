/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.enitalk.controllers.paypal.PaypalController;
import com.enitalk.controllers.youtube.GoogleCalendarController;
import com.enitalk.tinkoff.TinkoffController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Hours;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 *
 * @author astrologer
 */
@Configuration
public class DateCache {

    protected final static Logger logger = LoggerFactory.getLogger("date-cache");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;

    @Autowired
    private GoogleCalendarController calendar;
    @Autowired
    private TeacherFinder finder;
    @Autowired
    private PaypalController paypal;
    
    public ObjectMapper getJackson() {
        return jackson;
    }

    public void setJackson(ObjectMapper jackson) {
        this.jackson = jackson;
    }

    @Bean(name = "skipCache")
    public LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesMap() {
        CacheBuilder<Object, Object> ccc = CacheBuilder.newBuilder();
        ccc.expireAfterWrite(2, TimeUnit.MINUTES);

        LoadingCache<String, ConcurrentSkipListSet<DateTime>> cache = ccc.build(new CacheLoader<String, ConcurrentSkipListSet<DateTime>>() {

            @Override
            public ConcurrentSkipListSet<DateTime> load(String key) throws Exception {
                try {
                    HashMap teachers = mongo.findOne(Query.query(Criteria.where("i").is(key)), HashMap.class, "teachers");
                    ObjectNode teacherJson = jackson.convertValue(teachers, ObjectNode.class);
                    String timeZone = teacherJson.at("/calendar/timeZone").asText();

                    NavigableSet<DateTime> set = days(teacherJson.path("schedule"), timeZone, teacherJson);

                    DateTimeZone dzz = DateTimeZone.forID(timeZone);
                    DateTimeFormatter df = ISODateTimeFormat.dateTimeNoMillis().withZone(dzz);

                    byte[] events = calendar.busyEvents(jackson.createObjectNode().put("id", key));
                    JsonNode evs = jackson.readTree(events);
                    Iterator<JsonNode> its = evs.iterator();
                    TreeSet<DateTime> dates = new TreeSet<>();
                    while (its.hasNext()) {
                        String date = its.next().asText();
                        DateTime av = df.parseDateTime(date).toDateTime(DateTimeZone.UTC);
                        dates.add(av);
                    }

                    set.removeAll(dates);

                    logger.info("Dates for i {} {}", key, set);

                    return new ConcurrentSkipListSet<>(set);

                } catch (Exception e) {
                    logger.error(ExceptionUtils.getFullStackTrace(e));
                }
                return null;
            }

        });

        return cache;
    }

    @PostConstruct
    public void init() throws ExecutionException {
        Collection<String> ids = finder.teachers();
        if (ids.isEmpty()) {
            logger.warn("No teachers found at all");
            return;
        }

        ids.stream().forEach((String i) -> {
            try {
                datesMap().get(i);
            } catch (Exception ex) {
                logger.error(ExceptionUtils.getFullStackTrace(ex));
            }
        });
    }

    private final static LinkedHashMap<String, Integer> days = new LinkedHashMap<>();

    static {
        days.put("Mon", 1);
        days.put("Tue", 2);
        days.put("Wed", 3);
        days.put("Thu", 4);
        days.put("Fri", 5);
        days.put("Sat", 6);
        days.put("Sun", 7);
    }

    public NavigableSet<DateTime> days(JsonNode tree, String tz, JsonNode teacherNode) {
        ConcurrentSkipListSet<DateTime> dates = new ConcurrentSkipListSet<>();

        Iterator<JsonNode> els = tree.elements();
        DateTimeZone dz = DateTimeZone.forID(tz);
        DateTimeFormatter hour = DateTimeFormat.forPattern("HH:mm").withZone(dz);

        DateTime today = DateTime.now().millisOfDay().setCopy(0);
        while (els.hasNext()) {

            JsonNode el = els.next();
            String day = el.path("day").asText();

            boolean plus = today.getDayOfWeek() > days.get(day);
            if (el.has("start") && el.has("end")) {
                DateTime start = hour.parseDateTime(el.path("start").asText()).
                        dayOfMonth().setCopy(today.getDayOfMonth()).
                        monthOfYear().setCopy(today.getMonthOfYear()).
                        year().setCopy(today.getYear()).
                        withDayOfWeek(days.get(day)).
                        plusWeeks(plus ? 1 : 0);
                DateTime end = hour.parseDateTime(el.path("end").asText()).
                        dayOfMonth().setCopy(today.getDayOfMonth()).
                        monthOfYear().setCopy(today.getMonthOfYear()).
                        year().setCopy(today.getYear()).
                        withDayOfWeek(days.get(day)).
                        plusWeeks(plus ? 1 : 0);

                Hours hours = Hours.hoursBetween(start, end);
                int hh = hours.getHours() + 1;

                while (hh-- > 0) {
                    dates.add(start.plusHours(hh).toDateTime(DateTimeZone.UTC));
                }
            } else {
                List<String> datesAv = jackson.convertValue(el.path("times"), List.class);
                logger.info("Array of dates {} {}", datesAv, day);

                datesAv.forEach((String dd) -> {
                    DateTime date = hour.parseDateTime(dd).
                            dayOfMonth().setCopy(today.getDayOfMonth()).
                            monthOfYear().setCopy(today.getMonthOfYear()).
                            year().setCopy(today.getYear()).
                            withDayOfWeek(days.get(day)).
                            plusWeeks(plus ? 1 : 0);
                    dates.add(date.toDateTime(DateTimeZone.UTC));
                });

            }

        }

        final TreeSet<DateTime> addWeek = new TreeSet<>();
        for (int i = 1; i < 2; i++) {
            for (DateTime e : dates) {
                addWeek.add(e.plusWeeks(i));

            }
        }

        dates.addAll(addWeek);

        DateTime nowUtc = DateTime.now().toDateTime(DateTimeZone.UTC);
        nowUtc = nowUtc.plusHours(teacherNode.path("notice").asInt(2));
        
        NavigableSet<DateTime> ss = dates.tailSet(nowUtc, true);

        return ss;

    }

    @Bean(name = "exchangeCache")
    public LoadingCache<String, BigDecimal> exchange() {
        CacheBuilder<Object, Object> ccc = CacheBuilder.newBuilder();
        ccc.expireAfterWrite(30, TimeUnit.MINUTES);

        LoadingCache<String, BigDecimal> cache = ccc.build(new CacheLoader<String, BigDecimal>() {

            @Override
            public BigDecimal load(String key) throws Exception {
                try {
                    return TinkoffController.exchangeRate(jackson);

                } catch (Exception e) {
                    logger.error(ExceptionUtils.getFullStackTrace(e));
                }
                return null;
            }

        });

        return cache;
    }

}
