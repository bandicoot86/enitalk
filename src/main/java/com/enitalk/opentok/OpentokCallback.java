/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.opentok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author astrologer
 */
@Controller
@RequestMapping("/tokbox")
public class OpentokCallback {
    
    protected final static Logger logger = LoggerFactory.getLogger("fb-controller-api");

    @Autowired
    private RabbitTemplate rabbit;

    @RequestMapping(method = RequestMethod.POST)
    @ResponseBody
    public void verify(@RequestBody ObjectNode json) throws JsonProcessingException {
        try {
            logger.info("Tokbox came {}", json);
            rabbit.send("tokbox", MessageBuilder.withBody(json.toString().getBytes()).build());
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

    }
}
