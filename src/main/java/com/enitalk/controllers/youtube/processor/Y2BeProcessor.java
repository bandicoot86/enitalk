/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.youtube.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 *
 * @author astrologer
 */
//@Component
public class Y2BeProcessor {

    protected final static Logger logger = LoggerFactory.getLogger("u2-processor");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;

//    @Scheduled(fixedDelay = 10000L)
    public void run() {
        try {
            logger.info("Running processor");

            //TO-DO make it work only for 30+ minutes after the 
            Query q = Query.query(Criteria.where("").is(""));
            List<HashMap> items = mongo.find(q, HashMap.class, "events");
            ArrayNode its = jackson.convertValue(items, ArrayNode.class);

            final List<JsonNode> allEvents = its.findParents("ii");

            allEvents.forEach((JsonNode el) -> {
                try {

                    List<String> ids = jackson.convertValue(el.path("liveId"), List.class);
                    Credential credential = flow.loadCredential(el.at("/teacher/dest/sendTo").toString());
                    YouTube youtube = new YouTube.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
                            .setApplicationName("enitalk").build();
                    boolean refreshed = credential.refreshToken();
                    logger.info("Token refreshed {} id {}", refreshed);

                    YouTube.Videos.List list = youtube.videos().list("id,liveStreamingDetails,recordingDetails,status,statistics");
                    list.setId(StringUtils.join(ids, ','));
                    logger.info("Video param query {}", list.buildHttpRequestUrl());

                    byte[] response = IOUtils.toByteArray(list.executeUnparsed().getContent());

                    JsonNode r = jackson.readTree(response);
                    logger.info("Yt response {}", r);
                    Update u = new Update().set("records", jackson.convertValue(r, HashMap.class));

                    long finishedItems = r.path("items").findParents("id").stream().filter((JsonNode yt) -> {
                        return yt.at("/status/uploadStatus").asText().equals("processed");
                    }).count();

                    logger.info("Finished items {}", finishedItems);

                    if (finishedItems == ids.size()) {
                        logger.info("All items finished, shall sent links to user");
                        
                        
                    } else {
                        u.set("nextCheck", new DateTime(DateTimeZone.UTC).plusMinutes(15).toDate());
                    }

                    mongo.updateFirst(Query.query(Criteria.where("ii").is(el.path("ii").asText())), u, "events");

                } catch (Exception e) {
                    logger.error(ExceptionUtils.getFullStackTrace(e));
                }

            });

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
}
