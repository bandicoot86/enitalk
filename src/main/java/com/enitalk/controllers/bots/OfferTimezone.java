package com.enitalk.controllers.bots;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import javax.annotation.Resource;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.stereotype.Component;

/**
 *
 * @author kraav
 */
@Component
public class OfferTimezone {

    @Resource(name = "offsetMap")
    private TreeMap<Long, String> offsets;
    
    public static void main(String[] args) throws IOException {

    }

    public LinkedHashMap<String, String> getTimezones() {

        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        DateTime nowUtc = DateTime.now().toDateTime(DateTimeZone.UTC);

        offsets.keySet().forEach((Long o) -> {
            String id = offsets.get(o);
            map.put(nowUtc.toDateTime(DateTimeZone.forID(id)).toString("MMM',' dd HH:mm"), id);
        });

        return map;

    }

}
