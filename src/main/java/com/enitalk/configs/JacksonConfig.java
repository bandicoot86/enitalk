/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author astrologer
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper jacksonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.disable(SerializationFeature.INDENT_OUTPUT);
//        mapper.registerModule(new JodaModule());
//
//        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WR, false);
        return mapper;
    }
}
