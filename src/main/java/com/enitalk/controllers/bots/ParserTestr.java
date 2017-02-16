/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.bots;

import com.enitalk.configs.DateCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.NavigableSet;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 *
 * @author krash
 */
public class ParserTestr {

    protected final static Logger logger = LoggerFactory.getLogger("dates-api");

    public static void main(String[] args) throws IOException {
        InputStream is = new ClassPathResource("dates/mark_botkai.json").getInputStream();

        ObjectMapper j = new ObjectMapper();
        JsonNode tree = j.readTree(is);
        
        DateCache dc = new DateCache();
        dc.setJackson(j);
        NavigableSet<DateTime> dd = dc.days(tree.path("schedule"), tree.at("/calendar/timeZone").asText(), j.createObjectNode().put("notice", 0));
        System.out.println(dd);
    }

}
