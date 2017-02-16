package com.enitalk.opentok.merger;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.enitalk.configs.AwsConfig;
import com.enitalk.configs.JacksonConfig;
import com.enitalk.configs.PropsConfig;
import com.enitalk.configs.ScheduledConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 *
 * @author astrologer
 */
@Component
public class Mp4Merger {

    protected static final Logger logger = LoggerFactory.getLogger("merger");
    @Autowired
    private Environment env;
    @Autowired
    private AmazonS3Client s3;
    @Autowired
    private ScheduledExecutorService ex;

    public static void main(String[] args) throws IOException {
        ObjectMapper j = new ObjectMapper();

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AwsConfig.class, PropsConfig.class, JacksonConfig.class, MergerConfig.class, ScheduledConfig.class);
        JsonNode tree = j.readTree(new ClassPathResource("opentok/pp.json").getInputStream());
        ArrayDeque<File> filesMerged = ctx.getBean(Mp4Merger.class).mergeFiles(tree);
        logger.info("Files all {}", filesMerged);

//        File[] files = new File[]{new File("/home/krash/opentok/199aba69-5dd5-4332-bb7d-110196502269.mp4"),
//            new File("/home/krash/opentok/060ccc53-ad47-4eb3-a729-36e236fae4ac.mp4")};
//        ctx.getBean(Mp4Merger.class).doMerge(Arrays.asList(files));
    }

    public ArrayDeque<File> mergeFiles(JsonNode opentok) {
        ArrayDeque<File> out = new ArrayDeque<>();
        try {
            List<JsonNode> els = opentok.findParents("id");

            Set<JsonNode> jsonElements = new TreeSet<>((JsonNode o1, JsonNode o2) -> {
                Long c1 = o1.path("createdAt").asLong();
                Long c2 = o2.path("createdAt").asLong();
                return c1.compareTo(c2);
            });

            els.stream().filter((JsonNode n) -> {
                return n.path("status").asText().equals("uploaded");
            }).forEach((JsonNode u) -> {
                jsonElements.add(u);
            });

            logger.info("Aps {}", jsonElements);

            final CountDownLatch c = new CountDownLatch(jsonElements.size());
            ArrayList<Runnable> dds = new ArrayList<>();

            jsonElements.stream().forEach((JsonNode j) -> {
                String id = j.path("id").asText();
                File file = new File(env.getProperty("upload.dir") + id + ".mp4");
                out.add(file);
                logger.info("File to d {}", file);
                GetObjectRequest s3Req = getS3Request(j);

                dds.add((Runnable) () -> {
                    try {
                        s3.getObject(s3Req, file);
                    } catch (Exception e) {
                        logger.error(ExceptionUtils.getFullStackTrace(e));
                    } finally {
                        c.countDown();
                    }

                });
            });

            dds.forEach((Runnable r) -> {
                ex.submit(r);
            });

            c.await(60, TimeUnit.MINUTES);
            logger.info("Files downloaded {}", out);

            if (out.size() > 1) {
                File mms = doMerge(out);
                out.add(mms);
            }

            logger.info("Files merged {}", out);

        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
            out.stream().forEach((File f) -> {
                FileUtils.deleteQuietly(f);
            });
            out.clear();
        }

        return out;

    }

    private File doMerge(Collection<File> files) {
        File merged = null;
        try {
            ArrayDeque<File> queue = new ArrayDeque<>(files);

            StringBuilder s = new StringBuilder();
            queue.forEach((File f) -> {
                s.append(StringUtils.substring(FilenameUtils.getBaseName(f.getPath()), 0, 5)).append("-");
            });
            s.delete(s.length() - 1, s.length());
            logger.info("Merged name {}", s);

            File first = queue.pollFirst();

            String path = FilenameUtils.getFullPath(first.getPath());
            String mergeName = path + s + "_merge.mp4";
            merged = new File(mergeName);

            ArrayList<String> args = new ArrayList<>();
            args.add("MP4Box");
            args.add("-add");
            args.add(first.getPath());

            queue.stream().forEach((f) -> {
                args.add("-cat");
                args.add(f.getPath());
            });

            args.add(mergeName);

            logger.info("Args {}", args);

            Process p = new ProcessBuilder(args).start();
            p.waitFor(100, TimeUnit.SECONDS);

//            BufferedReader input = new BufferedReader(new InputStreamReader(p.getErrorStream()));
//            
//            String line;
//            while ((line = input.readLine()) != null) {
//                System.out.println(line);
//            }
//            
//            input.close();
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return merged;
    }

    public GetObjectRequest getS3Request(JsonNode j) {
        GetObjectRequest getObject = null;
        try {
            String id = j.path("id").asText();
            int partnerId = j.path("partnerId").asInt();
            String url = partnerId + "/" + id + "/archive.mp4";
            logger.info("Getting s3 object {}", url);
            getObject = new GetObjectRequest("enitalkbucket", url);

            final AtomicLong transferred = new AtomicLong(0L);
            final AtomicLong overall = new AtomicLong(0L);
            HashSet<String> s = new HashSet<>();

            getObject.setGeneralProgressListener((ProgressEvent progressEvent) -> {
                long tt = progressEvent.getBytesTransferred();
                long tr = transferred.addAndGet(tt);
                ProgressEventType type = progressEvent.getEventType();
                String display = FileUtils.byteCountToDisplaySize(tr);

                switch (type) {
                    case RESPONSE_CONTENT_LENGTH_EVENT:
                        overall.set(progressEvent.getBytes());
                        break;
                    case RESPONSE_BYTE_TRANSFER_EVENT:
                        if (s.add(display)) {
                            logger.info("Amazon s3 download, id {} transferred {} of {}", new Object[]{id,
                                display,
                                FileUtils.byteCountToDisplaySize(overall.longValue())});
                        }

                        break;
                    case TRANSFER_COMPLETED_EVENT:
                        logger.info("Transfer from s3 completed {}", j.path("ii").asText());

                }

            });
        } catch (Exception e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }

        return getObject;
    }

    private DecimalFormat getDecimalFromat() {
        DecimalFormat df = new DecimalFormat();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        df.setDecimalFormatSymbols(symbols);
        return df;
    }

}
