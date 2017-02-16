/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers;

import com.amazonaws.services.s3.AmazonS3Client;
import com.enitalk.configs.EnitalkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.LoadingCache;
import com.opentok.OpenTok;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Minutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
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
 * @author astrologer
 */
@Controller
@RequestMapping("/video")
public class OpentokSessionController {

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private OpenTok opentok;
    @Autowired
    private Environment env;
    @Autowired
    private AmazonS3Client s3;
    @Autowired
    @Qualifier("s3Cache")
    private LoadingCache<JsonNode, String> s3Cache;
    @Autowired
    private EnitalkConfig signer;

    protected final static Logger logger = LoggerFactory.getLogger("tokbox-api");

    @RequestMapping(method = RequestMethod.GET, value = "/session/teacher/{id}", produces = "text/html")
    @ResponseBody
    public byte[] sessionTeacher(@PathVariable String id, HttpServletResponse res, boolean requireUrl) throws IOException {
        byte[] out = null;
        try {
            Query q = Query.query(Criteria.where("ii").is(id).andOperator(Criteria.where("status").is(2)));
            logger.info("Looking for teacher event {}", id);
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            if (ev == null) {
                return "Sorry, no such event found.".getBytes();
            }

            Date date = (Date) ev.get("dd");
            DateTime scheduled = new DateTime(date.getTime()).toDateTime(DateTimeZone.UTC);
            DateTime nnow = new DateTime(DateTimeZone.UTC);
            int bt = Minutes.minutesBetween(nnow, scheduled).getMinutes();

            logger.info("Scheduled joda {} diff {}", scheduled, bt);

            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);
            
            if (bt > 6) {
                String rs = "You're a bit early, please visit this page at " + scheduled.minusMinutes(5).toString("yyyy/MM/dd HH:mm:ss 'GMT'");
                return rs.getBytes();
            }
//            
            if (bt < -62) {
                String rs = "It seems that your session has already ended or expired. Please, contact us at ceo@enitalk.com if there seems to be a mistake.";
                return rs.getBytes();
            }

            String url = s3Cache.get(evJson);

            if (!requireUrl) {
                url += "?dest=" + evJson.at("/ii").asText() + "&i=" + evJson.path("ii").asText();
                String signed = signer.signUrl(url, new DateTime().plusMinutes(80));
                res.sendRedirect(signed);
            } else {
                return url.getBytes();
            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
            return "Oops. Something went wrong. Contact us at ceo@enitalk.com".getBytes();
        }
        return out;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/session/student/{id}", produces = "text/html")
    @ResponseBody
    public byte[] sessionStudent(@PathVariable String id, HttpServletResponse res) throws IOException {

        try {
            byte[] baseUrl = sessionTeacher(id, res, true);
            String url = new String(baseUrl);
            if (!StringUtils.startsWith(url, "events")) {
                return baseUrl;
            }

            Query q = Query.query(Criteria.where("ii").is(id).andOperator(Criteria.where("status").is(2)));
            q.fields().exclude("_id").include("student").include("ii");
            HashMap ev = mongo.findOne(q, HashMap.class, "events");
            ObjectNode evJson = jackson.convertValue(ev, ObjectNode.class);

//            url += "?dest=" + evJson.at("/student/dest/sendTo").asLong() + "&i=" + evJson.path("ii").asText();
            url += "?dest=" + evJson.at("/student/email").asText() + "&i=" + evJson.path("ii").asText();
            String signed = signer.signUrl(url, new DateTime().plusMinutes(80));
            res.sendRedirect(signed);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
            return "Oops.Something went wrong. Contact us at ceo@enitalk.com".getBytes();
        }

        return null;
    }

//    @RequestMapping(method = RequestMethod.POST, value = "/join")
//    @ResponseBody
    public void enter(@RequestBody ObjectNode json) throws IOException {
        try {
            logger.info("Joined video session {}", json);
            String ev = json.path("i").asText();
            String dest = json.path("dest").asText();

            HashMap<String, Object> jev = new HashMap<>();
            jev.put("time", new Date());
            jev.put("dest", dest);

            mongo.updateFirst(Query.query(Criteria.where("ii").is(ev)),
                    new Update().push("joins", jev), "events");
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
}
