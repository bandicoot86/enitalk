/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class MongoInit {

    @Autowired
    private MongoTemplate mongo;

    @PostConstruct
    public void init() {
        DBCollection coll3 = getCollection("teachers");
        coll3.createIndex(new BasicDBObject("i", 1), new BasicDBObject("unique", false));
        coll3.createIndex(new BasicDBObject("dest.sendTo", 1), new BasicDBObject("unique", false));
        coll3.createIndex(new BasicDBObject("visible", 1), new BasicDBObject("unique", false));

        DBCollection coll5 = getCollection("leads");
        coll5.createIndex(new BasicDBObject("userId", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("want", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("dest.sendTo", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("confirmCode", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("email", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("eniword.words", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("eniword.point", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("eniword.nextPing", 1), new BasicDBObject("unique", false));
        coll5.createIndex(new BasicDBObject("eniword.disabled", 1), new BasicDBObject("unique", false));

        DBCollection coll6 = getCollection("events");
        coll6.createIndex(new BasicDBObject("student.dest.sendTo", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("student.email", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("teacher.email", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("teacher.dest.sendTo", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("teacher.i", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("student.paypal.ppToken", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("status", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("ii", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("sessionId", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("video", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("check", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("checkDate", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("dd", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("endDate", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("f", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("opentok.id", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("invId", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("reminderDate", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("reminded", 1), new BasicDBObject("unique", false));
        coll6.createIndex(new BasicDBObject("createDate", 1), new BasicDBObject("unique", false));
        
//        DBCollection history = getCollection("eniword_history");
//        history.createIndex(new BasicDBObject("dest.sendTo", 1), new BasicDBObject("unique", false));
//        history.createIndex(new BasicDBObject("insertDate", 1), new BasicDBObject("unique", false));
//        
//        DBCollection words = getCollection("words");
//        words.createIndex(new BasicDBObject("i", 1), new BasicDBObject("unique", false));
//        words.createIndex(new BasicDBObject("id", 1), new BasicDBObject("unique", false));
//        words.createIndex(new BasicDBObject("type", 1), new BasicDBObject("unique", false));
        

    }

    public DBCollection getCollection(String name) {
        return mongo.collectionExists(name) ? mongo.getCollection(name) : mongo.createCollection(name);
    }
}
