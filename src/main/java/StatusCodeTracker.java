import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;


/**
 * Tracks status code and corresponding frequencySlots count.
 */
@ThreadSafe
class StatusCodeTracker implements HttpResponseInterceptor, Tracker {
    // Key is URI and value is map of status code and corresponding counter.
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, LongAdder>> uriStatusCodeFrequenyCount
            = new ConcurrentHashMap<>();

    public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
        System.out.println("Running status code interceptor...");
        String uri = (String)context.getAttribute(Constants.HTTP_REQUEST_URI);
        Integer statusCode = response.getStatusLine().getStatusCode();

        add(uri, statusCode);
    }

    private void add(String uri, Integer statusCode) {
        uriStatusCodeFrequenyCount.computeIfAbsent(uri, v -> new ConcurrentHashMap<>())
                .computeIfAbsent(statusCode, k -> new LongAdder()).increment();
    }

    public void dumpStats() {
        System.out.println("Dumping status code statistics...");
        for (ConcurrentHashMap.Entry<String, ConcurrentHashMap<Integer, LongAdder>> pair :
                uriStatusCodeFrequenyCount.entrySet()) {
            String uri = pair.getKey();
            for (ConcurrentHashMap.Entry<Integer, LongAdder> statusCodeCount : pair.getValue().entrySet()) {
                System.out.println("URI: " + uri + " status: " + statusCodeCount.getKey() + " count: " +
                        statusCodeCount.getValue());
            }
        }
    }
}