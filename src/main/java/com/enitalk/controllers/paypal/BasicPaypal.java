/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.paypal;

import static com.enitalk.controllers.paypal.PaypalController.logger;
import com.enitalk.controllers.youtube.BotAware;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Form;
import org.apache.http.client.fluent.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;

/**
 *
 * @author astrologer
 */
public abstract class BasicPaypal extends BotAware {

    @Autowired
    Environment env;
    @Autowired
    ObjectMapper jackson;
    @Autowired
    RedisTemplate<String, String> redis;

    public String authPaypal() {
        String tt = null;
        try {
            tt = redis.opsForValue().get("paypal");
            if (StringUtils.isNotBlank(tt)) {
                return tt;
            }

            String url = env.getProperty("paypal.auth");
            String auth = env.getProperty("paypal.client") + ":" + env.getProperty("paypal.secret");
            String encoded = java.util.Base64.getEncoder().encodeToString(auth.getBytes());

            logger.info("Posting paypal auth to {} client {}", url, encoded);
            byte[] json = Request.Post(url).addHeader("Authorization", "Basic " + encoded).bodyForm(Form.form().add("grant_type", "client_credentials").
                    build()).
                    execute().
                    returnContent().
                    asBytes();

            JsonNode tree = jackson.readTree(json);
            tt = tree.path("access_token").asText();
            long duration = tree.path("expires_in").asLong();

            redis.opsForValue().set("paypal", tt, duration - 5, TimeUnit.SECONDS);

        } catch (Exception ex) {
            logger.info("Error auth Paypal {}", ExceptionUtils.getFullStackTrace(ex));
        }
        return tt;
    }
}
