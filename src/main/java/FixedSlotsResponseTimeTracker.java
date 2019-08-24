
import org.apache.http.annotation.ThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Approximate response time tracker that uses fixed slots and hence bounded memory
 * for tracking response times.
*/
@ThreadSafe
public class FixedSlotsResponseTimeTracker extends ResponseTimeTracker {
    // 1000 slots for millisec from 0 to 999 which stores the frequency at that millisec.
    private static final Integer NUM_MILLISEC_SLOTS = 1000;
    // Next 10 slots for second granularity.
    // All responses times greater than 10 secs will be stored in the last slot.
    // Basically granularity is millisecs upto 1 sec and seconds thereafter.
    private static final Integer NUM_SEC_SLOTS = 10;
    private static final Integer NUM_TOTAL_SLOTS = NUM_MILLISEC_SLOTS + NUM_SEC_SLOTS;

    // Key is URI and value is array for storing frequency count for that millisec.
    private final ConcurrentHashMap<String, FrequencyCountSlots> uriResponseTime = new ConcurrentHashMap<>();

    private static class FrequencyCountSlots {
        public LongAdder[] frequencySlots;
        public LongAdder totalFrequencyCount;

        public FrequencyCountSlots() {
            frequencySlots = new LongAdder[NUM_TOTAL_SLOTS];
            for (int i = 0; i < frequencySlots.length; i++) {
                frequencySlots[i] = new LongAdder();
            }
            totalFrequencyCount = new LongAdder();
        }

        public void add(Long durationMillisecs) {
            int slotIdx = getSlotIndex(durationMillisecs);
            frequencySlots[slotIdx].increment();
            totalFrequencyCount.increment();
        }

        private int getSlotIndex(Long durationMillisecs) {
            if (durationMillisecs < NUM_MILLISEC_SLOTS) {
                return durationMillisecs.intValue();
            } else {
                Long secs = durationMillisecs / NUM_MILLISEC_SLOTS;
                int slotIdx = NUM_MILLISEC_SLOTS + (int)(secs - 1);
                return slotIdx >= NUM_TOTAL_SLOTS ? NUM_TOTAL_SLOTS - 1 : slotIdx;
            }
        }
    }

    protected void add(String uri, Long durationMillisecs) {
        uriResponseTime.computeIfAbsent(uri, k -> new FrequencyCountSlots()).add(durationMillisecs);
    }

    public void dumpStats() {
        System.out.println("Dumping response time statistics...");
        for (ConcurrentHashMap.Entry<String, FrequencyCountSlots> pair : uriResponseTime.entrySet()) {
            String uri = pair.getKey();
            long totalCount = pair.getValue().totalFrequencyCount.longValue();
            double multFactor = totalCount / 100.0;
            final List<Integer> percentileIdxList = PERCENTILES.stream()
                    .map(percentile -> (int)(multFactor * percentile)).collect(Collectors.toList());
            assert(percentileIdxList.size() == PERCENTILES.size());

            long cumulativeFrequencyCount = 0;
            LongAdder[] slots = pair.getValue().frequencySlots;
            assert (slots != null);
            List<Integer> percentileValueList = new ArrayList<>();
            int percentileIdx = 0;

            // Millisecond granularity
            for (int millisecIdx = 0; millisecIdx < NUM_MILLISEC_SLOTS &&
                    percentileIdx < percentileIdxList.size(); millisecIdx++) {
                cumulativeFrequencyCount += slots[millisecIdx].longValue();
                while (percentileIdx < percentileIdxList.size() &&
                        cumulativeFrequencyCount >= percentileIdxList.get(percentileIdx)) {
                    percentileValueList.add(millisecIdx);
                    percentileIdx++;
                }
            }

            // Second granularity
            for (int secIdx = 0; secIdx < NUM_SEC_SLOTS && percentileIdx < percentileIdxList.size(); secIdx++) {
                int slotIdx = NUM_MILLISEC_SLOTS + secIdx;
                cumulativeFrequencyCount += slots[slotIdx].longValue();
                while (percentileIdx < percentileIdxList.size() &&
                        cumulativeFrequencyCount >= percentileIdxList.get(percentileIdx)) {
                    percentileValueList.add((secIdx + 1) * 1000);
                    percentileIdx++;
                }
            }

            for (int i = 0; i < PERCENTILES.size(); i++) {
                System.out.println("URI: " + uri + " " + PERCENTILES.get(i) + "th percentile response time " +
                        percentileValueList.get(i) + " millisecs");
            }
        }
    }
}