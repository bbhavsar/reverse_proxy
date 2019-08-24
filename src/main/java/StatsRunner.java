import java.util.List;

/**
 * Runs and dumps statistics of all trackers.
 */
class StatsRunner implements Runnable {
    private final List<Tracker> trackers;

    public StatsRunner(List<Tracker> trackers) {
        this.trackers = trackers;
    }

    @Override
    public void run() {
        try {
            for (Tracker tracker : trackers) {
                tracker.dumpStats();
            }
        } catch (Exception e) {
            System.err.println("Error in executing statistics dumper. It will no longer be run!");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}