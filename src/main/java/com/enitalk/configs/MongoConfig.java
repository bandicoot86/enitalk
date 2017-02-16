/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import static java.util.Collections.singletonList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.config.AbstractMongoConfiguration;

/**
 *
 * @author astrologer
 */
@Configuration
public class MongoConfig extends AbstractMongoConfiguration {
    
    @Autowired
    private Environment env;

    @Override
    public String getDatabaseName() {
        return "enitalk";
    }

    @Override
    @Bean
    public Mongo mongo() throws Exception {
        return new MongoClient(singletonList(new ServerAddress(env.getProperty("mongo.host"), 27017)),
                singletonList(MongoCredential.createCredential(env.getProperty("mongo.user"), getDatabaseName(), env.getProperty("mongo.pass").toCharArray())));
    }

}
