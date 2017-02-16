package com.enitalk.opentok;

import com.amazonaws.services.s3.AmazonS3Client;
import com.enitalk.configs.EnitalkConfig;
import com.enitalk.opentok.merger.Mp4Merger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import java.io.File;
import java.io.FileInputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class FinishedUploader implements MessageListener {

    protected static final Logger logger = LoggerFactory.getLogger("finished-runnable");

    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper jackson;
    @Autowired
    private Environment env;
    @Autowired
    EnitalkConfig eni;
    @Autowired
    AmazonS3Client s3;
    @Autowired
    private GoogleAuthorizationCodeFlow flow;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    Mp4Merger mp4;

    @Autowired
    private ScheduledExecutorService ex;

    @Scheduled(fixedDelay = 5000L)
    public void run() {
        try {
            Query q = Query.query(Criteria.where("video").is(0));
            List<HashMap> evs = mongo.find(q, HashMap.class, "events");
            if (evs.isEmpty()) {
                return;
            }

            ArrayNode events = jackson.convertValue(evs, ArrayNode.class);
            Iterator<JsonNode> it = events.elements();
            //set video for processing in queue
            mongo.updateMulti(q, new Update().set("video", 1), "events");

            while (it.hasNext()) {
                JsonNode en = it.next();
                rabbit.send("finished", MessageBuilder.withBody(jackson.writeValueAsBytes(en)).build());

            }
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }

    @Override
    @RabbitListener(queues = "finished")
    public void onMessage(Message msg) {
        ArrayDeque<File> files = new ArrayDeque<>();
        try {
            JsonNode j = jackson.readTree(msg.getBody());
            String eventId = j.path("ii").asText();
            logger.info("Finished came {}", eventId);

            String d = j.at("/student/scheduledDate").asText();
            DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
            DateTime dt = fmt.parseDateTime(d);
            final String timeOfSession = dt.toDateTime(DateTimeZone.UTC).toString("yyyy/MM/dd HH:mm");

            files = mp4.mergeFiles(j.path("opentok"));
            if (files.isEmpty()) {
                logger.error("Videos went wrong {}");
                mongo.updateFirst(Query.query(Criteria.where("ii").is(eventId)), new Update().set("video", "7"), "events");
                return;
            }

            logger.info("Files uploaded for ev {} {}", eventId, files);
            String yid = uploadYotube(files.peekLast(), timeOfSession);
            mongo.updateFirst(Query.query(Criteria.where("ii").is(eventId)), new Update().push("yt", yid).set("video", 2).
                    set("checkDate", new DateTime().plusMinutes(12).toDate()), "events");

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        } finally {
            files.forEach((File f) -> {
                FileUtils.deleteQuietly(f);
            });
        }
    }

    private DecimalFormat getDecimalFromat() {
        DecimalFormat df = new DecimalFormat();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        df.setDecimalFormatSymbols(symbols);
        return df;
    }

    public String uploadYotube(File file, String timeOfSession) {
        String id = null;
        try {
            Credential credential = flow.loadCredential("yt");
            YouTube youtube = new YouTube.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
                    .setApplicationName("enitalk").build();
            boolean refreshed = credential.refreshToken();
            logger.info("Yt refreshed {}", refreshed);

            Video videoObjectDefiningMetadata = new Video();

            VideoStatus status = new VideoStatus();
            status.setPrivacyStatus("unlisted");
            videoObjectDefiningMetadata.setStatus(status);

            VideoSnippet snippet = new VideoSnippet();

            snippet.setTitle(timeOfSession + ". Enitalk lesson");
            snippet.setDescription("Recorded lesson of Enitalk video session");

            // Set the keyword tags that you want to associate with the video.
            List<String> tags = new ArrayList<>();
            tags.add("enitalk");
            snippet.setTags(tags);

            // Add the completed snippet object to the video resource.
            videoObjectDefiningMetadata.setSnippet(snippet);

            FileInputStream fs = new FileInputStream(file);
            int fsLenth = fs.available();
            logger.info("Fs length {}", fsLenth);
            InputStreamContent mediaContent = new InputStreamContent("video/*", fs);

            YouTube.Videos.Insert videoInsert = youtube.videos()
                    .insert("snippet,statistics,status", videoObjectDefiningMetadata, mediaContent);

            MediaHttpUploader uploader = videoInsert.getMediaHttpUploader();

            uploader.setDirectUploadEnabled(false);

            DecimalFormat df = getDecimalFromat();

            MediaHttpUploaderProgressListener progressListener = (MediaHttpUploader uploader1) -> {
                switch (uploader1.getUploadState()) {
                    case INITIATION_STARTED:
                        logger.info("Initiation Started");
                        break;
                    case INITIATION_COMPLETE:
                        logger.info("Initiation Completed");
                        break;
                    case MEDIA_IN_PROGRESS:
                        logger.info("Upload in progress");
                        logger.info("Upload percentage: {} of {}",
                                df.format(uploader1.getNumBytesUploaded()), df.format(fsLenth));
                        break;
                    case MEDIA_COMPLETE:
                        logger.info("Upload Completed!");

                        break;
                    case NOT_STARTED:
                        logger.info("Upload Not Started!");
                        break;
                }
            };

            uploader.setProgressListener(progressListener);

            Video returnedVideo = videoInsert.execute();

            // Print data about the newly inserted video from the API response.
            logger.info("\n================== Returned Video ==================\n");
            logger.info("  - Id: " + returnedVideo.getId());
            logger.info("  - Title: " + returnedVideo.getSnippet().getTitle());
            logger.info("  - Tags: " + returnedVideo.getSnippet().getTags());
            logger.info("  - Privacy Status: " + returnedVideo.getStatus().getPrivacyStatus());
            logger.info("  - Video Count: " + returnedVideo.getStatistics().getViewCount());

            id = "https://youtu.be/" + returnedVideo.getId();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return id;

    }

}
