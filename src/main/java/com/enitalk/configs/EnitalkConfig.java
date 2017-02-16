package com.enitalk.configs;

import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.opentok.ArchiveMode;
import com.opentok.MediaMode;
import com.opentok.OpenTok;
import com.opentok.Role;
import com.opentok.Session;
import com.opentok.SessionProperties;
import com.opentok.TokenOptions;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.velocity.app.VelocityEngine;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 *
 * @author astrologer
 */
@Configuration
@SpringBootApplication
@EnableWebMvc
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
    MongoAutoConfiguration.class,
    MongoRepositoriesAutoConfiguration.class, MongoDataAutoConfiguration.class})
@ComponentScan(basePackages = "com.enitalk")
@EnableScheduling
@EnableAsync
public class EnitalkConfig {

    protected final static Logger logger = LoggerFactory.getLogger("enitalk-cfg");
    @Autowired
    private Environment env;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private VelocityEngine engine;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    AmazonS3Client s3;

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(EnitalkConfig.class, args);
        logger.info("Enitalk started ctx");
    }

    @Bean
    public OpenTok tokbox() {
        logger.info("Init tokbox {} {}", env.getProperty("tokbox.apikey", Integer.class), env.getProperty("tokbox.secret"));
        return new OpenTok(env.getProperty("tokbox.apikey", Integer.class), env.getProperty("tokbox.secret"));
    }

    @Bean(name = "s3Cache")
    public LoadingCache<JsonNode, String> s3Cache() {
        CacheBuilder<Object, Object> ccc = CacheBuilder.<JsonNode, String>newBuilder();
        ccc.expireAfterWrite(80, TimeUnit.MINUTES);

        LoadingCache<JsonNode, String> cache = ccc.build(new CacheLoader<JsonNode, String>() {

            @Override
            public String load(JsonNode ev) throws Exception {
                try {
                    String key = ev.path("ii").asText();
                    Query q = Query.query(Criteria.where("ii").is(key).andOperator(Criteria.where("status").is(2)));
                    q.fields().exclude("_id").include("s3").include("dd");

                    HashMap event = mongo.findOne(q, HashMap.class, "events");
                    if (event.containsKey("s3")) {
                        return event.get("s3").toString();
                    } else {
                        SessionProperties.Builder builder = new SessionProperties.Builder();
                        builder.mediaMode(MediaMode.ROUTED);
                        builder.archiveMode(env.getProperty("tokbox.record", Boolean.class, true) ? ArchiveMode.ALWAYS : ArchiveMode.MANUAL);

                        SessionProperties sp = builder.build();
                        Session session = tokbox().createSession(sp);
                        //to do make it expire in 80 minutes
                        long l = Instant.now().plusSeconds(TimeUnit.MINUTES.toSeconds(60)).getEpochSecond();
                        String token = session.generateToken(new TokenOptions.Builder().role(Role.PUBLISHER).expireTime(l).
                                data(key).build());
                        String sessionId = session.getSessionId();

//                        VelocityContext context = new VelocityContext();
//                        context.put("apiKey", env.getProperty("tokbox.apikey"));
//                        context.put("session", sessionId);
//                        context.put("token", token);
//
//                        StringWriter writer = new StringWriter(12 * 1024);
//                        Template t = engine.getTemplate("publish.html");
//
//                        t.merge(context, writer);
                        long dd = ev.path("dd").asLong();
                        DateTime date = new DateTime(dd, DateTimeZone.UTC);
                        String onair = IOUtils.toString(new ClassPathResource("publish.html").getInputStream(), "UTF-8");
                        String fmt = String.format(onair, env.getProperty("tokbox.apikey"), sessionId, token,
                                date.toString("yyyy/MM/dd HH:mm:ss'UTC'"),
                                date.plusMinutes(45).toString("yyyy/MM/dd HH:mm:ss'UTC'"));

                        byte[] b = fmt.getBytes();

                        ObjectMetadata meta = new ObjectMetadata();
                        meta.setContentLength(b.length);
                        meta.setContentType("text/html");

                        String name = "events/" + key + ".html";
                        s3.putObject(new PutObjectRequest("enitalkbucket", name, new ByteArrayInputStream(b), meta));
//                        String signed = signUrl(name, new DateTime().plusMinutes(75));
                        mongo.updateFirst(q, new Update().set("s3", name).set("sessionId", sessionId)
                                .set("accessDate", new Date()), "events");
                        return name;
                    }
                } catch (Exception e) {
                    logger.error(ExceptionUtils.getFullStackTrace(e));
                    throw new RuntimeException(e);
                }
            }

        });

        return cache;
    }

    @Bean(name = "teachers")
    public LoadingCache<String, List<String>> teachersCache() {
        CacheBuilder<Object, Object> ccc = CacheBuilder.<JsonNode, String>newBuilder();
        ccc.expireAfterWrite(1, TimeUnit.MINUTES);

        LoadingCache<String, List<String>> cache = ccc.build(new CacheLoader<String, List<String>>() {

            @Override
            public List<String> load(String ev) throws Exception {
                List<String> teachers = new ArrayList<>();
                try {
                    Query q = Query.query(Criteria.where("visible").is(true));
                    q.fields().exclude("_id").exclude("welcome").exclude("schedule");
                    List<HashMap> tt = mongo.find(q, HashMap.class, "teachers");
                    for (HashMap m : tt) {
                        teachers.add(jackson.convertValue(m, JsonNode.class).toString());
                    }

                } catch (Exception e) {
                    logger.error(ExceptionUtils.getFullStackTrace(e));
                }
                return teachers;
            }

        });

        return cache;
    }

    public String signUrl(String unsignedUrl, DateTime upTo) {
        String out = null;
        File pem = null;
        try {
//            pem = new ClassPathResource("pk-APKAIWVADW7R6YOMXS5A.pem").getFile();
//            byte[] key = IOUtils.toByteArray(new ClassPathResource("pk-APKAIWVADW7R6YOMXS5A.pem").getInputStream());
            pem = File.createTempFile(UUID.randomUUID().toString(), ".pem");
            IOUtils.copy(new ClassPathResource("pk-APKAIWVADW7R6YOMXS5A.pem").getInputStream(), new FileOutputStream(pem));
//            FileUtils.writeByteArrayToFile(pem, key);
            final String url = CloudFrontUrlSigner.getSignedURLWithCannedPolicy(CloudFrontUrlSigner.Protocol.https, env.getProperty("cfUrl"),
                    pem, unsignedUrl, env.getProperty("cfKey"),
                    upTo.toDate());
            out = url;
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        } finally {
            FileUtils.deleteQuietly(pem);
        }

        return out;
    }

    @PostConstruct
    public void init() throws ExecutionException {
    }

}
