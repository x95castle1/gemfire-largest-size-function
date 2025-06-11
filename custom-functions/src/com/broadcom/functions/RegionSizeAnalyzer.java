import org.apache.geode.cache.Region;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.RegionFunctionContext;
import org.apache.geode.cache.execute.ResultSender;
import org.apache.geode.internal.size.ObjectGraphSizer;
import org.apache.geode.internal.size.ObjectGraphSizer.ObjectFilter;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import java.util.*;

public class RegionSizeAnalyzer implements Function {

    private static final Logger logger = LogManager.getLogger(RegionSizeAnalyzer.class);

    @Override
    public void execute(FunctionContext context) {
        RegionFunctionContext rfc = (RegionFunctionContext) context;
        Region region = rfc.getDataSet();
        ResultSender sender = context.getResultSender();

        Map<String, Object> result = new HashMap<>();
        result.put("regionName", region.getName());

        try {
            // Priority queue to keep track of largest objects
            int topN = 10;
            PriorityQueue<EntrySize> largestEntries = new PriorityQueue<>(topN);

            long totalSize = 0;
            int sampleCount = 0;
            int targetSamples = 100000;

            Set<Object> keys = region.keySet();
            int totalEntries = keys.size();
            result.put("totalEntries", totalEntries);

            if (totalEntries == 0) {
                result.put("status", "EMPTY_REGION");
                sender.lastResult(result);
                return;
            }

            // Create object filter to exclude cache infrastructure
            ObjectFilter filter = new SimpleObjectFilter();

            // Sample entries
            int interval = Math.max(1, totalEntries / targetSamples);
            int index = 0;

            for (Object key : keys) {
                if (index % interval == 0 || sampleCount < targetSamples) {
                    Object value = region.get(key);
                    if (value != null) {
                        // Use ObjectGraphSizer for accurate memory size
                        long size = ObjectGraphSizer.size(value, filter, false);
                        totalSize += size;
                        sampleCount++;

                        // Track largest entries
                        EntrySize entry = new EntrySize(key.toString(), value.getClass().getSimpleName(), size);
                        if (largestEntries.size() < topN) {
                            largestEntries.offer(entry);
                        } else if (size > largestEntries.peek().size) {
                            largestEntries.poll();
                            largestEntries.offer(entry);
                        }
                    }
                }
                index++;

                if (sampleCount >= targetSamples && largestEntries.size() >= topN) break;
            }

            // Convert priority queue to sorted list (largest first)
            List<Map<String, Object>> topEntries = new ArrayList<>();
            List<EntrySize> sortedLargest = new ArrayList<>(largestEntries);
            Collections.sort(sortedLargest, Collections.reverseOrder());

            for (EntrySize entry : sortedLargest) {
                Map<String, Object> entryMap = new HashMap<>();
                entryMap.put("key", entry.key);
                entryMap.put("type", entry.type);
                entryMap.put("sizeBytes", entry.size);
                entryMap.put("sizeMB", entry.size / 1024.0 / 1024.0);
                topEntries.add(entryMap);
            }

            result.put("largestEntries", topEntries);
            result.put("sampleCount", sampleCount);

            if (sampleCount > 0) {
                result.put("avgSizeBytes", totalSize / sampleCount);
                result.put("avgSizeMB", (totalSize / sampleCount) / 1024.0 / 1024.0);

                // G1 recommendation based on largest object
                double largestSizeMB = sortedLargest.get(0).size / 1024.0 / 1024.0;
                result.put("largestSizeMB", largestSizeMB);

                if (largestSizeMB < 8) {
                    result.put("g1Recommendation", "16M");
                    result.put("g1Reason", "Largest object < 8MB");
                } else if (largestSizeMB < 16) {
                    result.put("g1Recommendation", "32M");
                    result.put("g1Reason", "Largest object 8-16MB");
                } else {
                    result.put("g1Recommendation", "32M_WITH_WARNING");
                    result.put("g1Reason", String.format("Largest object %.2fMB will be humongous!", largestSizeMB));
                }

                result.put("status", "SUCCESS");
            }

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            e.printStackTrace();
        }

        sender.lastResult(result);

        logger.info("the result: {}", result);
    }

    @Override
    public String getId() {
        return "RegionSizeAnalyzer";
    }

    @Override
    public boolean hasResult() {
        return true;
    }

    @Override
    public boolean optimizeForWrite() {
        return false;
    }

    @Override
    public boolean isHA() {
        return false;
    }

    // Simple object filter that accepts everything (we're measuring values only)
    private static class SimpleObjectFilter implements ObjectFilter {
        @Override
        public boolean accept(Object parent, Object object) {
            return true;
        }
    }

    // Helper class to track largest entries
    private static class EntrySize implements Comparable<EntrySize> {
        String key;
        String type;
        long size;

        EntrySize(String key, String type, long size) {
            this.key = key;
            this.type = type;
            this.size = size;
        }

        @Override
        public int compareTo(EntrySize other) {
            return Long.compare(this.size, other.size);
        }
    }
}