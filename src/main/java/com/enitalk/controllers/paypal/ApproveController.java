package com.enitalk.controllers.paypal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.LoadingCache;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author astrologer
 */
@Controller
@RequestMapping("/approve")
public class ApproveController {

    protected static final Logger logger = LoggerFactory.getLogger("approve-ctrl");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    PaypalController paypal;
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
    @Qualifier("teachers")
    LoadingCache<String, List<String>> teachers;

    @RequestMapping(method = RequestMethod.POST, value = "/event")
    @ResponseBody
    public void approve(@RequestBody ObjectNode json, HttpServletResponse res, @RequestHeader HttpHeaders headers) {
        try {
            String auth = headers.getFirst("Auth");
            if (!StringUtils.equals(auth, env.getProperty("approve.secret"))) {
                res.sendError(401);
                return;
            }
            HashMap ev = mongo.findOne(Query.query(Criteria.where("ii").is(json.path("i").asText())), HashMap.class, "events");
            if (ev != null) {
                paypal.successPayment(jackson.convertValue(ev, ObjectNode.class), null, null);
            } else {
                res.sendError(401);
            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/teacher")
    @ResponseBody
    public void registerTeacher(@RequestBody ObjectNode json, HttpServletResponse res, @RequestHeader HttpHeaders headers) {
        try {
            String auth = headers.getFirst("Auth");
            if (!StringUtils.equals(auth, env.getProperty("approve.secret"))) {
                res.sendError(401);
                return;
            }

            final String teacherId = RandomStringUtils.randomNumeric(15);
            json.put("i", teacherId);
            
            mongo.insert(jackson.convertValue(json, HashMap.class), "teachers");

            ex.submit(() -> {
                try {
                    datesCache.get(teacherId);
                    teachers.invalidateAll();
                } catch (ExecutionException ex) {
                    logger.error(ExceptionUtils.getFullStackTrace(ex));
                }
            });

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

}
