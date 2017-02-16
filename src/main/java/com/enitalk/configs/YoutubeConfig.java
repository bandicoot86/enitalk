/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 *
 * @author astrologer
 */
@Configuration
public class YoutubeConfig {
    
    @Autowired
    private Environment env;

    protected final static Logger logger = LoggerFactory.getLogger("yt-config-api");

    @Bean
    public FileDataStoreFactory store() throws IOException {
        FileDataStoreFactory store = new FileDataStoreFactory(new File("./src/main/resources/stored"));
        return store;
    }

    @Bean
    @Primary
    public GoogleAuthorizationCodeFlow flow() throws IOException {
        return new GoogleAuthorizationCodeFlow.Builder(
                new NetHttpTransport(), JacksonFactory.getDefaultInstance(),
                env.getProperty("yt.client"), env.getProperty("yt.secret"),
                Arrays.asList("https://www.googleapis.com/auth/youtube","https://www.googleapis.com/auth/youtube.upload",
                        "https://www.googleapis.com/auth/youtubepartner",
                        "https://www.googleapis.com/auth/youtube.force-ssl",
                        "https://www.googleapis.com/auth/calendar", 
                        "https://www.googleapis.com/auth/calendar.readonly", 
                        "https://www.googleapis.com/auth/plus.login",                        
                        "https://www.googleapis.com/auth/userinfo.email",
                        "https://www.googleapis.com/auth/urlshortener")).setDataStoreFactory(store()).setAccessType("offline").build();
    }
}
