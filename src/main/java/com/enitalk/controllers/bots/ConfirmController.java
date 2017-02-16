package com.enitalk.controllers.bots;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author krash
 */
@Controller
@RequestMapping("/")
public class ConfirmController {

    @Autowired
    private MongoTemplate mongo;

    @RequestMapping(method = RequestMethod.GET, value = "/confirm/{id}")
    @ResponseBody
    public byte[] cancelBooking(@PathVariable String id) {

        mongo.updateFirst(Query.query(Criteria.where("confirmCode").is(id)), new Update().set("confirmed", true), "leads");
        return "Thank you for confirming your email.".getBytes();
    }
}
