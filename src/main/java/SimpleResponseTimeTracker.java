import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Tracks time per request to be used for dumping statistics.
 * Simple naive implementation that stores every timestamp and hence grows unbounded.
 * XXX: Not used in the implementation.
 */
class SimpleResponseTimeTracker extends ResponseTimeTracker {
    // Key is URI and value is list of unsorted time duration for each URI for quick addition.
    private final ConcurrentHashMap<String, List<Long>> uriResponseTime = new ConcurrentHashMap<>();

    protected void add(String uri, Long durationMillisecs) {
        uriResponseTime.computeIfAbsent(uri, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(durationMillisecs);
    }

    public void dumpStats() {
        System.out.println("Dumping response time statistics...");
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