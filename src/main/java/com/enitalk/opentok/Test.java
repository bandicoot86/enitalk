/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.opentok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/**
 *
 * @author krash
 */
public class Test {

    public static void main(String[] args) throws IOException {
        ObjectMapper jackson = new ObjectMapper();
        JsonNode tree = jackson.readTree(new ClassPathResource("ss/tokbox.json").getInputStream());
        List<JsonNode> els = tree.findParents("id");

        List<JsonNode> jsonElements = new ArrayList<>();
        els.stream().filter((JsonNode o) -> {
            return o.path("status").asText().equals("uploaded");
        }).forEach((JsonNode e) -> {
            jsonElements.add(e);
        });

        Collections.sort(jsonElements, (JsonNode o1, JsonNode o2) -> {
            Integer c1 = o1.path("createdAt").asInt();
            Integer c2 = o2.path("createdAt").asInt();
            return c1.compareTo(c2);
        });

        System.out.println(jsonElements);
    }
}
