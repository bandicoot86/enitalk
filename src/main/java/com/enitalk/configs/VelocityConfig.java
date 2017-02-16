/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import org.apache.commons.io.FileUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author krash
 */
@Configuration
public class VelocityConfig {
    
    protected final static Logger logger = LoggerFactory.getLogger("velocity-api");
    
    @Bean
    public VelocityEngine velocityEngine() {
        VelocityEngine engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
//        engine.setProperty("file.resource.loader.class", FileResourceLoader.class.getName());
        engine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
//        engine.setProperty("file.resource.loader.cache", true);
//        engine.setProperty("file.resource.loader.modificationCheckInterval", "10");
//        engine.setProperty("file.resource.loader.path", ".");
        
        engine.init();
        
        return engine;
    }
    
    public static void main(String[] args) throws IOException {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(VelocityEngine.class);
        VelocityEngine engine = ctx.getBean(VelocityEngine.class);
        
        long start = System.currentTimeMillis();
        Template t = engine.getTemplate("booking.vm");
        
        VelocityContext context = new VelocityContext();
        context.put("scheduledDate", new DateTime().toString());
        context.put("link", "http://localhost:8080");
        
        StringWriter writer = new StringWriter(24 * 1024);
        t.merge(context, writer);
        
        FileUtils.write(new File("/home/krash/Desktop/1.html"), writer.toString(), "UTF-8");
        logger.info("Took {}", System.currentTimeMillis() - start);
//        String templateText = FileUtils.readFileToString(new File("./templates/booking.vm"), "UTF-8");
//        logger.info("Templated text {}", templateText);

    }
    
}
