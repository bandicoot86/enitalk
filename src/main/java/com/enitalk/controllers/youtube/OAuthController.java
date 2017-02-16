package com.enitalk.controllers.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.plus.Plus;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author astrologer
 */
@Controller
@RequestMapping("/oauth2")
public class OAuthController extends BotAware {

    protected final static Logger logger = LoggerFactory.getLogger("oauth2-ctrl-api");

    @Autowired
    private FileDataStoreFactory store;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;

    @Autowired
    @Qualifier("skipCache")
    private LoadingCache<String, ConcurrentSkipListSet<DateTime>> datesCache;

    @Autowired
    private ScheduledExecutorService ex;

    @Autowired
    private MongoTemplate mongo;

    public Calendar getGoogleCalendar(String id) throws IOException {
        Credential credential = flow.loadCredential(id);
        credential.refreshToken();
        return new Calendar.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential).setApplicationName("enitalk").build();
    }

    public JsonNode getCalendar(String id) throws IOException {
        Calendar calendar = getGoogleCalendar(id);
        InputStream ccl = calendar.calendarList().get("primary").executeUnparsed().getContent();
        JsonNode calendarResponse = jackson.readTree(ccl);
        logger.info("Calendar user response {}", calendarResponse);
        return calendarResponse;
    }

    public ObjectNode requestInfo(String userId) {
        ObjectNode n = null;
        try {
            Credential credential = flow.loadCredential(userId);
            Plus plus = new Plus.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential).setApplicationName("Enitalk").build();
            InputStream is = plus.people().get("me").executeUnparsed().getContent();
            JsonNode tree = jackson.readTree(is);
            n = (ObjectNode) tree;

        } catch (IOException ex) {
            logger.info(ExceptionUtils.getFullStackTrace(ex));
        }

        return n;
    }

    @RequestMapping(method = RequestMethod.GET)
    @ResponseBody
    public void callbackStudent(@RequestParam Map<String, String> allRequestParams) {
        try {
            logger.info("All request params {}", allRequestParams);
            String code = allRequestParams.get("code");
            String userId = allRequestParams.get("state");

            HashMap lead = mongo.findOne(Query.query(Criteria.where("userId").is(Long.valueOf(userId))), HashMap.class, "leads");
            if (StringUtils.isNotBlank(code) && lead != null) {
                logger.info("Code {}", code);
                GoogleTokenResponse token = flow.newTokenRequest(code).setRedirectUri(env.getProperty("google.redirect")).execute();
                logger.info("Token response {}", token);
                flow.createAndStoreCredential(token, userId);

                ObjectNode infoGoogle = requestInfo(userId);
                JsonNode calendar = getCalendar(userId);

                mongo.updateFirst(Query.query(Criteria.where("userId").is(Long.valueOf(userId))), new Update().set("people",
                        jackson.convertValue(infoGoogle, HashMap.class)).set("calendar", jackson.convertValue(calendar, HashMap.class)), "leads");

                String name = infoGoogle.path("name").path("givenName").asText();

                if (lead != null) {
                    ObjectNode jlead = jackson.convertValue(lead, ObjectNode.class);
                    jlead.retain("dest");
                    jlead.put("tag", env.getProperty("tag.googleAllow"));
                    jlead.put("text", "Welcome to Enitalk, " + name + ". What is your level? Please, choose below");

                    ArrayNode a = jackson.createArrayNode();

                    makeButton(a, "B1 (Intermediate)", "B1");
                    makeButton(a, "B2 (Upper Intermediate)", "B2");
                    makeButton(a, "C1 (Pre advanced)", "C1");
                    makeButton(a, "C2 (Advanced)", "C2");
                    makeButton(a, "CP (Full proficiency)", "CP");

                    jlead.set("buttons", a);

                    sendTag(jlead);
                }

                //send message indicating it is done
            } else if (lead != null) {
                //send message indicating they must sign in
                ObjectNode jlead = jackson.convertValue(lead, ObjectNode.class);
                jlead.retain("dest");
                jlead.put("tag", env.getProperty("tag.googleDenied"));

                String href = requestNew(Long.valueOf(userId), false);
                ArrayNode a = jackson.createArrayNode();

                ObjectNode o = a.addObject();
                o.put("name", "Sign in");
                o.put("href", href);

                jlead.set("buttons", a);

                sendTag(jlead);
                logger.error("Error for flow {}", allRequestParams);

            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "/teacher")
    @ResponseBody
    public void callbackTeacher(@RequestParam Map<String, String> allRequestParams) {
        try {
            logger.info("All request params {}", allRequestParams);
            String code = allRequestParams.get("code");
            String userId = allRequestParams.get("state");

            HashMap lead = mongo.findOne(Query.query(Criteria.where("userId").is(Long.valueOf(userId))), HashMap.class, "teachers");
            if (StringUtils.isNotBlank(code) && lead != null) {
                logger.info("Code {}", code);
                GoogleTokenResponse token = flow.newTokenRequest(code).setRedirectUri(env.getProperty("google.redirect.teacher")).execute();
                logger.info("Token response {}", token);
                flow.createAndStoreCredential(token, userId);

                ObjectNode googleTree = requestInfo(userId);
                JsonNode calendar = getCalendar(userId);
                ObjectNode jlead = jackson.convertValue(lead, ObjectNode.class);
                String email = extractEmail(googleTree);

                if (!StringUtils.equals(email, jlead.path("personalEmail").asText())) {
                    //should login via email given by Enitalk
                    ArrayNode msg = jackson.createArrayNode();

                    ObjectNode o = msg.addObject();
                    o.set("dest", jlead.path("dest"));
                    ObjectNode message = jackson.createObjectNode();
                    o.set("message", message);
                    message.put("text", "It seems you have tried to use different account to sign in. Please, use this\n"
                            + "email : " + jlead.path("personalEmail").asText() + "\n password:" + jlead.path("password").asText());

                    sendMessages(msg);
                    return;
                }

                mongo.updateFirst(Query.query(Criteria.where("dest.sendTo").is(Long.valueOf(userId))),
                        new Update().set("people", jackson.convertValue(googleTree, HashMap.class)).
                        set("calendar", jackson.convertValue(calendar, HashMap.class)).set("visible", true), "teachers");

                ex.submit(() -> {
                    try {
                        datesCache.get(jlead.path("i").asText());
                    } catch (ExecutionException ex) {
                        logger.error(ExceptionUtils.getFullStackTrace(ex));
                    }
                });

                ArrayNode msg = jackson.createArrayNode();

                ObjectNode o = msg.addObject();
                o.set("dest", jlead.path("dest"));
                ObjectNode message = jackson.createObjectNode();
                o.set("message", message);
                message.put("text", "Awesome! You have verified your profile and the students around the globe can "
                        + "already book lessons with you. Stay tuned and keep an eye on your Messenger. We will send you notifications of new bookings. "
                        + "Also, you can find them at your personal email at Enitalk");

                sendMessages(msg);

            } else if (lead != null) {
                //send message indicating they must sign in
                ObjectNode jlead = jackson.convertValue(lead, ObjectNode.class);
                jlead.retain("dest");
                jlead.put("tag", env.getProperty("tag.googleDenied"));

                String href = requestNew(Long.valueOf(userId), true);
                ArrayNode a = jackson.createArrayNode();

                ObjectNode o = a.addObject();
                o.put("name", "Sign in");
                o.put("href", href);

                jlead.set("buttons", a);

                sendTag(jlead);
                logger.error("Error for flow {}", allRequestParams);

            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    public void sendTeacherInvitation(String email, String name, String userId) throws IOException {
        ObjectNode json = jackson.createObjectNode();
        json.put("From", env.getProperty("postmark.from"));
        json.put("To", email);
        json.put("Subject", "Introduction & Invitation");
        json.put("HtmlBody", "");

        json.put("Tag", "Teacher Invitation");
        json.put("TracksOpen", true);

        Request req = Request.Post(env.getProperty("postmark.send")).addHeader("X-Postmark-Server-Token", env.getProperty("postmark.token"));

        byte[] pmOut = req.bodyString(json.toString(), ContentType.APPLICATION_JSON).execute().returnContent().asBytes();

        JsonNode bb = jackson.readTree(pmOut);

        logger.info("Postmark response {}", bb);

        //save it to the teacher
    }

    public String extractEmail(ObjectNode googleTree) {
        return googleTree.path("emails").elements().next().path("value").asText();
    }

    private void makeButtonUrl(ArrayNode a, String name, String url) {
        ObjectNode o = a.addObject();
        o.put("name", name);
        o.put("href", url);
    }

//    public String botAuth() throws ExecutionException {
//        return tokenCache.get("");
//
//    }
//
//    public void sendTag(ObjectNode dest) throws IOException, ExecutionException {
//        String auth = botAuth();
//        String tagResponse = Request.Post(env.getProperty("bot.sendTag")).
//                addHeader("Authorization", "Bearer " + auth).
//                bodyString(dest.toString(), ContentType.APPLICATION_JSON).socketTimeout(5000).connectTimeout(3000).
//                execute().
//                returnContent().
//                asString();
//
//        logger.info("Tag command sent to a bot {}, response {}", dest, tagResponse);
//    }
    @RequestMapping(method = RequestMethod.GET, value = "/new/{id}", produces = "text/plain")
    @ResponseBody
    public String requestNew(@PathVariable Long id, boolean teacher) {
        GoogleAuthorizationCodeRequestUrl url = flow.newAuthorizationUrl().setRedirectUri(
                !teacher ? env.getProperty("google.redirect") : env.getProperty("google.redirect.teacher")).setState(id.toString()).setApprovalPrompt("force");
        return url.toString();
    }

    @RequestMapping(method = RequestMethod.POST, value = "/redirect", produces = "text/plain")
    @ResponseBody
    public String requestNew(@RequestBody ObjectNode json) {
        GoogleAuthorizationCodeRequestUrl url = flow.newAuthorizationUrl().setRedirectUri(env.getProperty("self.url") + "/oauth2/custom").
                setState(json.path("id").asText()).setApprovalPrompt("force");
        return url.toString();
    }

    @RequestMapping(method = RequestMethod.GET, value = "/custom")
    @ResponseBody
    public void callbackCustom(@RequestParam Map<String, String> allRequestParams) {
        try {
            logger.info("All request params {}", allRequestParams);
            String code = allRequestParams.get("code");
            String userId = allRequestParams.get("state");

            logger.info("Code {}", code);
            GoogleTokenResponse token = flow.newTokenRequest(code).setRedirectUri(env.getProperty("self.url") + "/oauth2/custom").execute();
            logger.info("Token response {}", token);
            flow.createAndStoreCredential(token, userId);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

//    public static void main(String[] args) {
//        
//        List<String> scopes = Lists.newArrayList("https://www.googleapis.com/auth/youtube");
//        try {
//            FileDataStoreFactory fct = new FileDataStoreFactory(new File("./src/main/resources/stored"));
//            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
//                    new NetHttpTransport(), JacksonFactory.getDefaultInstance(),
//                    "5834902143-vim9h6hi5tqlo85td715t7uhmoo1pe2o.apps.googleusercontent.com", "r9_qWrweRBstEoKoNvcv6UiQ",
//                    Collections.singleton("https://www.googleapis.com/auth/youtube")).setDataStoreFactory(
//                            fct).setAccessType("offline").build();
//            Credential credential = flow.loadCredential("me");
//            boolean refreshed = credential.refreshToken();
//            System.out.println("Creds " + credential.getAccessToken() + " " + refreshed);
//            
//            YouTube youtube = new YouTube.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
//                    .setApplicationName("enitalk").build();
//            
//            LiveBroadcastSnippet broadcastSnippet = new LiveBroadcastSnippet();
//            broadcastSnippet.setTitle("bnow");
//            broadcastSnippet.setScheduledStartTime(new DateTime(new org.joda.time.DateTime().getMillis()));
//            broadcastSnippet.setScheduledEndTime(new DateTime(new org.joda.time.DateTime().plusHours(1).getMillis()));
//            
//            LiveBroadcastStatus status = new LiveBroadcastStatus();
//            status.setPrivacyStatus("private");
//            
//            LiveBroadcast broadcast = new LiveBroadcast();
//            broadcast.setKind("youtube#liveBroadcast");
//            broadcast.setSnippet(broadcastSnippet);
//            broadcast.setStatus(status);
//            
//            LiveBroadcastContentDetails details = new LiveBroadcastContentDetails();
//            details.setEnableLowLatency(true);
//            broadcast.setContentDetails(details);
//            
//            YouTube.LiveBroadcasts.Insert liveBroadcastInsert = youtube.liveBroadcasts().insert("snippet,status,contentDetails", broadcast);
//            LiveBroadcast returnedBroadcast = liveBroadcastInsert.execute();
//
//            // Print information from the API response.
//            System.out.println("\n================== Returned Broadcast ==================\n");
//            System.out.println("  - Id: " + returnedBroadcast.getId());
//            System.out.println("  - Title: " + returnedBroadcast.getSnippet().getTitle());
//            System.out.println("  - Description: " + returnedBroadcast.getSnippet().getDescription());
//            System.out.println("  - Published At: " + returnedBroadcast.getSnippet().getPublishedAt());
//            System.out.println(
//                    "  - Scheduled Start Time: " + returnedBroadcast.getSnippet().getScheduledStartTime());
//            System.out.println(
//                    "  - Scheduled End Time: " + returnedBroadcast.getSnippet().getScheduledEndTime());
//            System.out.println("All " + ReflectionToStringBuilder.toString(returnedBroadcast, ToStringStyle.MULTI_LINE_STYLE));
//            
//            LiveStreamSnippet streamSnippet = new LiveStreamSnippet();
//            streamSnippet.setTitle("livee");
//            
//            CdnSettings cdnSettings = new CdnSettings();
//            cdnSettings.setFormat("1080p");
//            cdnSettings.setIngestionType("rtmp");
//            
//            LiveStream stream = new LiveStream();
//            stream.setKind("youtube#liveStream");
//            stream.setSnippet(streamSnippet);
//            stream.setCdn(cdnSettings);
//            
//            YouTube.LiveStreams.Insert liveStreamInsert
//                    = youtube.liveStreams().insert("snippet,cdn", stream);
//            LiveStream returnedStream = liveStreamInsert.execute();
//            
//            System.out.println("\n================== Returned Stream ==================\n");
//            System.out.println("  - Id: " + returnedStream.getId());
//            System.out.println("  - Title: " + returnedStream.getSnippet().getTitle());
//            System.out.println("  - Description: " + returnedStream.getSnippet().getDescription());
//            System.out.println("  - Published At: " + returnedStream.getSnippet().getPublishedAt());
//            
//            YouTube.LiveBroadcasts.Bind liveBroadcastBind = youtube.liveBroadcasts().bind(returnedBroadcast.getId(), "id,contentDetails");
//            liveBroadcastBind.setStreamId(returnedStream.getId());
//            returnedBroadcast = liveBroadcastBind.execute();
//            
//            System.out.println("\n================== Returned Bound Broadcast ==================\n");
//            System.out.println("  - Broadcast Id: " + returnedBroadcast.getId());
//            System.out.println(
//                    "  - Bound Stream Id: " + returnedBroadcast.getContentDetails().getBoundStreamId());
//            System.out.println("All " + ReflectionToStringBuilder.toString(returnedBroadcast, ToStringStyle.MULTI_LINE_STYLE));
//            
//        } catch (Exception e) {
//            System.err.println(ExceptionUtils.getFullStackTrace(e));
//        }
//    }
}
