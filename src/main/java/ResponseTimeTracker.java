import com.google.common.collect.ImmutableList;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.List;

public abstract class ResponseTimeTracker implements HttpResponseInterceptor, Tracker {
    // Sorted list of percentiles tracker aims to log.
    protected static final List<Integer> PERCENTILES = ImmutableList.of(25, 50, 75, 90, 99, 100);

    public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
        System.out.println("Running response time interceptor");

        String uri = (String) context.getAttribute(Constants.HTTP_REQUEST_URI);
        Long startTime = (Long) context.getAttribute(Constants.HTTP_REQUEST_START_TIME);
        Long durationMillisecs = (System.nanoTime() - startTime) / 1000000L;

        response.addHeader(Constants.EXECUTION_TIME_HEADER_KEY, String.valueOf(durationMillisecs));
        add(uri, durationMillisecs);
    }

    abstract protected void add(String uri, Long durationMillisecs);
}