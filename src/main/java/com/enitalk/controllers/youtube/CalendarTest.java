/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.core.io.ClassPathResource;

/**
 *
 * @author astrologer
 */
public class CalendarTest {

    public static void main(String[] args) throws IOException {

        InputStream is = new ClassPathResource("events.json").getInputStream();
        ObjectMapper jackson = new ObjectMapper();
        JsonNode tree = jackson.readTree(is);
        IOUtils.closeQuietly(is);

        DateTimeFormatter fmtDateTime = ISODateTimeFormat.dateTimeNoMillis();
        DateTimeFormatter fmt = ISODateTimeFormat.date();

        TreeMultimap<DateTime, DateTime> set = CalendarTest.getPeriodSet(10, 18);

        Iterator<JsonNode> nodes = tree.elements();
        while (nodes.hasNext()) {
            JsonNode ev = nodes.next();
            boolean isFullDay = ev.path("start").has("date");

            DateTime stDate = isFullDay ? fmt.parseDateTime(ev.path("start").path("date").asText())
                    : fmtDateTime.parseDateTime(ev.path("start").path("dateTime").asText());

            DateTime enDate = isFullDay ? fmt.parseDateTime(ev.path("end").path("date").asText())
                    : fmtDateTime.parseDateTime(ev.path("end").path("dateTime").asText());

            System.out.println("St " + stDate + " en " + enDate);

            int days = Days.daysBetween(stDate, enDate).getDays();
            System.out.println("Days between " + days);
            if (isFullDay) {
                switch (days) {
                    case 1:
                        set.removeAll(stDate);
                        break;
                    default:
                        while (days-- > 0) {
                            set.removeAll(stDate.plusDays(days));
                        }
                }
            } else {
                DateTime copySt = stDate.minuteOfHour().setCopy(0).secondOfMinute().setCopy(0);
                DateTime copyEn = enDate.plusHours(1).minuteOfHour().setCopy(0).secondOfMinute().setCopy(0);

//                System.out.println("Dates truncated " + copySt + " " + copyEn);
//                System.out.println("Ll set " + set);

//                System.out.println("Getting set for key " + stDate.millisOfDay().setCopy(0));
                SortedSet<DateTime> ss = set.get(stDate.millisOfDay().setCopy(0));
                SortedSet<DateTime> subset = ss.subSet(copySt, copyEn);
                subset.clear();
                set.remove(enDate.millisOfDay().setCopy(0), copyEn);
            }

        }

        
    }

    private static TreeMultimap<DateTime, DateTime> getPeriodSet(int from, int to) {

        TreeMultimap<DateTime, DateTime> multimap = TreeMultimap.create();

        DateTime now = new DateTime();
        Integer[] today = IntStream.range(now.hourOfDay().get() + 1, to + 1).boxed().toArray(Integer[]::new);

        DateTime zz = now.hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
        for (Integer h : today) {
            multimap.put(zz, zz.hourOfDay().setCopy(h));
        }

        Integer[] fullDay = IntStream.range(from, to + 1).boxed().toArray(Integer[]::new);

        for (int i = 1; i < 5; i++) {
            for (Integer h : fullDay) {
                DateTime zeroDay = now.plusDays(i).hourOfDay().setCopy(0).minuteOfDay().setCopy(0).secondOfDay().setCopy(0).millisOfSecond().setCopy(0);
                multimap.put(zeroDay, zeroDay.hourOfDay().setCopy(h));
            }
        }
        return multimap;
    }
}
