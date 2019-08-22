import com.google.common.collect.ImmutableList;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Tracks time per request to be used for dumping statistics as well as adding execution time
 * to response header.
 */
class ResponseTimeTracker implements HttpResponseInterceptor, Tracker {
    // Key is URI and value is list of unsorted time duration for each URI for quick addition.
    private final ConcurrentHashMap<String, List<Long>> uriResponseTime = new ConcurrentHashMap<>();
    private static final List<Integer> PERCENTILES = ImmutableList.of(25, 50, 75, 90, 99);
    private static final String EXECUTION_TIME_HEADER_KEY = "X-execution.time";


    public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
        System.out.println("Running response time interceptor");

        String uri = (String)context.getAttribute(Constants.HTTP_REQUEST_URI);
        Long startTime = (Long)context.getAttribute(Constants.HTTP_REQUEST_START_TIME);
        Long durationMillisecs = (System.nanoTime() - startTime) / 1000000L;

        add(uri, durationMillisecs);

        response.addHeader(EXECUTION_TIME_HEADER_KEY, String.valueOf(durationMillisecs));
    }

    public void add(String uri, Long durationMillisecs) {
        uriResponseTime.computeIfAbsent(uri, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(durationMillisecs);
    }

    public void dumpStats() {
        System.out.println("Dumping response time statistcs...");
        for (ConcurrentHashMap.Entry<String, List<Long>> pair : uriResponseTime.entrySet()) {
            String uri = pair.getKey();
            List<Long> timeDurations = pair.getValue();
            synchronized (timeDurations) {
                Collections.sort(timeDurations);
            }
            double multFactor = timeDurations.size() / 100.0;
            for (Integer percentile : PERCENTILES) {
                int idx = (int)(percentile * multFactor);
                System.out.println("URI: " + uri + " " + percentile + "th percentile response time " +
                        timeDurations.get(idx) + " millisecs");
            }
        }
    }
}