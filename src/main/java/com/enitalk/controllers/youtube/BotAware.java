/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.youtube;

import static com.enitalk.controllers.youtube.OAuthController.logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 *
 * @author astrologer
 */
public class BotAware {

    @Autowired
    protected Environment env;

    @Autowired
    LoadingCache<String, String> tokenCache;

    @Autowired
    ObjectMapper jackson;
    
    private final CloseableHttpClient client = HttpClients.custom().setSSLHostnameVerifier(new NoopHostnameVerifier()).setRetryHandler(new DefaultHttpRequestRetryHandler(3, true) {
    }).build();

    public String botAuth() throws ExecutionException {
        return tokenCache.get("");
    }

    public void sendTag(ObjectNode dest) throws IOException, ExecutionException {
        String auth = botAuth();
        String tagResponse = Request.Post(env.getProperty("bot.sendTag")).
                addHeader("Authorization", "Bearer " + auth).
                bodyString(dest.toString(), ContentType.APPLICATION_JSON).socketTimeout(20000).connectTimeout(5000).
                execute().
                returnContent().
                asString();

        logger.info("Tag command sent to a bot {}, response {}", dest, tagResponse);
    }

    public void sendMessages(ArrayNode msg) throws IOException, ExecutionException {
        String auth = botAuth();
        String tagResponse = Request.Post(env.getProperty("bot.sendMessage")).
                addHeader("Authorization", "Bearer " + auth).
                bodyString(msg.toString(), ContentType.APPLICATION_JSON).socketTimeout(20000).connectTimeout(5000).
                execute().
                returnContent().
                asString();

        logger.info("SendMsg sent to a bot {}, response {}", msg, tagResponse);
    }

    public JsonNode getBotCommandsByTag(JsonNode dest, String tag) throws IOException, ExecutionException {
        String auth = botAuth();
        ObjectNode j = jackson.createObjectNode();
        j.set("dest", dest);
        j.put("tag", tag);

        String tagResponse = Request.Post(env.getProperty("bot.commands.by.tag")).
                addHeader("Authorization", "Bearer " + auth).
                bodyString(j.toString(), ContentType.APPLICATION_JSON).socketTimeout(20000).connectTimeout(5000).
                execute().
                returnContent().
                asString();

        logger.info("Command by tag find response {}", tagResponse);
        return jackson.readTree(tagResponse);
    }

    public void sendLastCommand(JsonNode dest) throws ExecutionException, IOException {
        HttpPost post = new HttpPost(env.getProperty("bot.sendLast"));
        String auth = botAuth();
        post.addHeader("Authorization", "Bearer " + auth);
        post.setEntity(new StringEntity(dest.toString(), ContentType.APPLICATION_JSON));
        
        CloseableHttpResponse resp = client.execute(post);
        IOUtils.closeQuietly(resp);
    }

    public void makeButton(ArrayNode a, String name, String data) {
        ObjectNode o = a.addObject();
        o.put("name", name);
        o.put("data", data);
    }

    public void makeButtonHref(ArrayNode a, String name, String href) {
        ObjectNode o = a.addObject();
        o.put("name", name);
        o.put("href", href);
    }

}
