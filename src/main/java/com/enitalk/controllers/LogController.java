package com.enitalk.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author krash
 */
@Controller
@RequestMapping("/log")
public class LogController {
    
    protected final static Logger logger = LoggerFactory.getLogger("tokbox-api");
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    
    @RequestMapping(method = RequestMethod.POST, value = "/event")
    @ResponseBody
    public void sessionTeacher(@RequestBody ObjectNode json) throws IOException {
        try {
            logger.info("Log event came {}", json);
            Query q = Query.query(Criteria.where("ii").is(json.path("i").asText()));
            HashMap m = jackson.convertValue(json, HashMap.class);
            m.put("t", new Date());
            
            mongo.updateFirst(q, new Update().push("pings", m), "events");
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
    
//    @RequestMapping(method = RequestMethod.GET, value = "/pubnub/{id}")
//    @ResponseBody
    public void pubnubCreds(@PathVariable String id) throws IOException {
        ObjectNode o = jackson.createObjectNode();
        try {
            Query q = Query.query(Criteria.where("ii").is(id).andOperator(Criteria.where("endDate").gte(new Date())));
            
            
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
}
