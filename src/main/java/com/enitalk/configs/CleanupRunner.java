package com.enitalk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.cache.LoadingCache;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class CleanupRunner {

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    @Qualifier("skipCache")
    private LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesCache;
    @Autowired
    private Environment env;

    protected final static Logger logger = LoggerFactory.getLogger("cleanup");

    @Scheduled(fixedDelay = 10000L)
    public void cleanUpBookings() throws ExecutionException {
        Integer period = env.getProperty("revoke.interval", Integer.class);
        Query q = Query.query(Criteria.where("createDate").lt(new DateTime().minusMinutes(period).toDate()).andOperator(Criteria.where("status").is(1)));
        List<HashMap> found = mongo.find(q, HashMap.class, "events");
        if (found.isEmpty()) {
            return;
        }

        ArrayNode expired = jackson.convertValue(found, ArrayNode.class);
        Iterator<JsonNode> it = expired.elements();
        DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
        while (it.hasNext()) {
            JsonNode el = it.next();
            String d = el.at("/student/scheduledDate").asText();
            DateTime r = fmt.withZone(DateTimeZone.forID(el.at("/student/calendar/timeZone").asText())).parseDateTime(d);
            mongo.updateFirst(Query.query(Criteria.where("ii").is(el.at("/ii").asText())), new Update().set("status", 9), "events");
            datesCache.get(el.at("/teacher/i").asText()).add(r.toDateTime(DateTimeZone.UTC));

        }

    }

}
