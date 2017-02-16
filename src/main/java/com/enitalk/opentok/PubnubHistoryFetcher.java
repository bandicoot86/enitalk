package com.enitalk.opentok;

import com.enitalk.configs.JacksonConfig;
import com.enitalk.configs.PropsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubnub.api.PNConfiguration;
import com.pubnub.api.PubNub;
import com.pubnub.api.endpoints.History;
import com.pubnub.api.models.consumer.history.PNHistoryItemResult;
import com.pubnub.api.models.consumer.history.PNHistoryResult;
import java.util.ArrayList;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 *
 * @author krash
 */
@Component
public class PubnubHistoryFetcher {

    protected static final Logger logger = LoggerFactory.getLogger("pubnub-fetcher");

    @Autowired
    ObjectMapper jackson;
    @Autowired
    private Environment env;

    public static void main(String[] args) throws InterruptedException {
        
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(JacksonConfig.class, PropsConfig.class);
        ObjectMapper jj = ctx.getBean(ObjectMapper.class);

        ObjectNode ev = jj.createObjectNode().put("ii", "28892125696316846162572318702354");
        ev.set("student", jj.createObjectNode().set("dest", jj.createObjectNode().put("sendTo", 37869402L)));
//        ev.set("teacher", jj.createObjectNode().set("dest", jj.createObjectNode().put("sendTo", 226792077L)));

        logger.info("Json {}", ev);
        PubnubHistoryFetcher f = new PubnubHistoryFetcher();
        ctx.getAutowireCapableBeanFactory().autowireBean(f);

        ArrayList<JsonNode> data = f.fetchData(ev);
        f.makeChatText(ev, data);

        System.exit(0);

    }

    public ArrayList<JsonNode> fetchData(JsonNode ev) {
        ArrayList<JsonNode> nodes = new ArrayList<>();
        try {
            PNConfiguration pnConfiguration = new PNConfiguration();
            pnConfiguration.setSubscribeKey(env.getProperty("pubnub.sub"));
            pnConfiguration.setPublishKey(env.getProperty("pubnub.pub"));

            PubNub pubNub = new PubNub(pnConfiguration);

            boolean ct = true;
            long end = 0L;

            do {

                logger.info("Fetching with end {}", end);
                History history = pubNub.history().channel(ev.path("ii").asText()).count(100);
                if (end != 0L) {
                    history.end(end + 1);
                }
                PNHistoryResult result = history.includeTimetoken(Boolean.TRUE).sync();
                logger.info("Result of fetching {}", result.getMessages().size());
                if (result.getMessages().isEmpty()) {
                    ct = false;
                    continue;
                }

                result.getMessages().stream().forEach((PNHistoryItemResult r) -> {
                    nodes.add(r.getEntry());
                });

                end = result.getEndTimetoken();

            } while (ct);

            logger.info("Nodes {} {}", nodes, nodes.size());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return nodes;
    }

    public String makeChatText(JsonNode ev, ArrayList<JsonNode> messages) {
        StringBuilder builder = new StringBuilder(messages.size() * 32);
        try {
            String studentDest = ev.at("/student/email").toString();
            String teacherDest = ev.path("ii").asText();
            logger.info("Student {} teacher {}", studentDest, teacherDest);

            messages.stream().forEach((JsonNode e) -> {
                boolean teacher = e.path("dest").asText().equals(teacherDest);
                builder.append(teacher ? "Teacher: " : "Student: ").append(e.path("text").asText()).append("\n");
            });

            logger.info("Chat {}", builder.toString());

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
        
        return builder.toString();
    }
}
