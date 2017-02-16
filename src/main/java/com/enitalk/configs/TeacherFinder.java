/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class TeacherFinder {

    @Autowired
    private MongoTemplate mongo;

    public Collection<String> teachers() {
        Query q = Query.query(Criteria.where("visible").is(true));
        q.fields().exclude("_id").include("i");
        List<HashMap> tchs = mongo.find(q, HashMap.class, "teachers");
        ArrayList<String> ids = new ArrayList<>();
        tchs.stream().forEach((HashMap t) -> {
            ids.add(t.get("i").toString());
        });
        return ids;
    }

}
