/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.bots;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.TreeMultimap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 *
 * @author krash
 */
public class TimeZoneTestr {
    
    public static void main(String[] args) {
        Set<String> ids = DateTimeZone.getAvailableIDs();
        TreeMultimap<Long, String> map = TreeMultimap.create();
        for (String id : ids) {
            DateTimeZone dz = DateTimeZone.forID(id);
            int offset = dz.getOffset(DateTime.now().withZone(DateTimeZone.UTC));
            
            map.put(TimeUnit.MILLISECONDS.toMinutes(offset), id);
        }
        
        ObjectMapper j = new ObjectMapper();
        ArrayNode a = j.createArrayNode();
        map.keySet().forEach((Long key) -> {
            a.addObject().set(key.toString(), j.convertValue(map.get(key), ArrayNode.class));
        });
        
        System.out.println(a);
        
//        System.out.println(map);
    }
}
