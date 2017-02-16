package com.enitalk.opentok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opentok.Archive;
import com.opentok.OpenTok;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.velocity.app.VelocityEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author krash
 */
//@Controller
//@RequestMapping("/opentok")
public class OpentokSampleRecorder {
    
    protected final static Logger logger = LoggerFactory.getLogger("recording-ctrl");
    @Autowired
    private Environment env;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private VelocityEngine engine;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private OpenTok opentok;
    
    @RequestMapping(method = RequestMethod.POST, value = "/record/start")
    @ResponseBody
    public JsonNode recordSample(@RequestBody ObjectNode json) {
        try {
            Archive archive = opentok.startArchive(json.path("s").asText());
            return jackson.readTree(archive.toString());
        } catch (Exception e) {
            String msg = ExceptionUtils.getFullStackTrace(e);
            logger.error(msg);
            return jackson.createObjectNode().put("error", msg);
        }
        
    }
    
    @RequestMapping(method = RequestMethod.POST, value = "/record/stop")
    @ResponseBody
    public JsonNode stopRecord(@RequestBody ObjectNode json) {
        try {
            Archive archive = opentok.stopArchive(json.path("id").asText());
            return jackson.readTree(archive.toString());
        } catch (Exception e) {
            String msg = ExceptionUtils.getFullStackTrace(e);
            logger.error(msg);
            return jackson.createObjectNode().put("error", msg);
        }
        
    }
    
//    @Scheduled(fixedDelay = 5000L)
    public void record() {
        try {
            List<HashMap> evs = mongo.find(Query.query(Criteria.where("nextSampleDate").lt(new Date())), HashMap.class, "events");
            if (evs == null) {
                return;
            }
            ArrayNode samples = jackson.convertValue(evs, ArrayNode.class);
            
            
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
    
    public void onMessageStart(Message msg) {
        try {
            JsonNode ev = jackson.readTree(msg.getBody());
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
    
}
