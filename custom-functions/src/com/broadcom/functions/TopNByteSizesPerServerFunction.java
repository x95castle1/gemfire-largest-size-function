import org.apache.geode.cache.Region;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.util.ObjectSizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Supplier;

/**
 * Note this function uses the GemFire ReflectionObjectSizer.
 *  For accurate sizing of every instance use REFLECTION_SIZE instead.
 *   This sizer will add up the sizes of all objects that are reachable from the
 *   keys and values in your region by non-static fields.
 *
 * @author gregory green (output formatting fixed by angry mob)
 */
public class TopNByteSizesPerServerFunction implements Function<String[]> {
    private static final Logger logger = LogManager.getLogger(TopNByteSizesPerServerFunction.class);
    private final java.util.function.Function<FunctionContext<?>,Region<Object,Object>> regionGetter;
    private final Supplier<ObjectSizer> sizerSupplier;

    // Inner class EXACTLY like the original
    static class EntrySize implements Comparable<EntrySize> {
        final Object key;
        final long size;

        EntrySize(Object key, long size) {
            this.key = key;
            this.size = size;
        }

        @Override
        public int compareTo(EntrySize other) {
            return Long.compare(this.size, other.size);
        }

        @Override
        public String toString() {
            // THIS IS THE ONLY CHANGE - Better formatting!
            return String.format("Entry[key=%s, size=%d (%.2f MB)]",
                    key, size, size / (1024.0 * 1024.0));
        }
    }

    public TopNByteSizesPerServerFunction() {
        this(new GetRegion(),() -> ObjectSizer.REFLECTION_SIZE);
    }

    public TopNByteSizesPerServerFunction(
            java.util.function.Function<FunctionContext<?>,
                    Region<Object,Object>> regionGetter,
            Supplier<ObjectSizer> sizerSupplier) {

        this.regionGetter = regionGetter;
        this.sizerSupplier = sizerSupplier;
    }

    @Override
    public void execute(FunctionContext<String[]> functionContext) {
        // START TIMING HERE
        long startTime = System.currentTimeMillis();

        String memberName = functionContext.getMemberName();
        logger.info("Executing TopNByteSizesPerServerFunction on member: {}", memberName);

        Region<Object,Object> region = regionGetter.apply(functionContext);
        logger.info("Region name: {}, size: {}", region.getName(), region.size());

        var objectSizer = sizerSupplier.get();

        int topN = 3;
        var args = functionContext.getArguments();
        if(args != null && args.length > 0)
            topN = Integer.parseInt(args[0]);

        logger.info("Finding top {} entries by size", topN);

        var minHeap = new PriorityQueue<EntrySize>();

        for (Map.Entry<Object,Object> entry : region.entrySet()) {
            var size = objectSizer.sizeof(entry.getValue());

            minHeap.offer(new EntrySize(entry.getKey(),size));
            if (minHeap.size() > topN) {
                minHeap.poll(); // Remove the smallest of the top numbers
            }
        }

        logger.info("Found {} entries in heap for member {}", minHeap.size(), memberName);

        var sender = functionContext.getResultSender();

        if(minHeap.isEmpty()) {
            sender.lastResult(0);
            logger.info("No entries found for region {} on member {}", region.getName(), memberName);
        } else {

            List<EntrySize> sortedEntries = new ArrayList<>(minHeap);
            sortedEntries.sort((a, b) -> Long.compare(b.size, a.size)); // Descending order

            Iterator<EntrySize> sizes = minHeap.iterator();
            EntrySize size;
            int count = 0;
            while (sizes.hasNext()) {
                size = sizes.next();
                count++;
                logger.debug("Sending result #{} from member {}: {}", count, memberName, size);

                if (sizes.hasNext())
                    sender.sendResult(size.toString());
                else
                    sender.lastResult(size.toString());
            }
            logger.info("Sent {} results from member {}", count, memberName);

            // CALCULATE EXECUTION TIME BEFORE LOGGING SUMMARY
            long endTime = System.currentTimeMillis();
            long executionTimeMs = endTime - startTime;

            logSummary(region.getName(), memberName, sortedEntries, topN, executionTimeMs);
        }
    }

    private void logSummary(String regionName, String memberName, List<EntrySize> entries, int topN, long executionTimeMs) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n");
        summary.append("================================================================================\n");
        summary.append(String.format("SUMMARY: Top %d largest entries in region '%s' on member '%s'\n",
                topN, regionName, memberName));
        summary.append("================================================================================\n");

        long totalSize = 0;
        for (int i = 0; i < entries.size(); i++) {
            EntrySize entry = entries.get(i);
            totalSize += entry.size;
            summary.append(String.format("#%d - Key: %s\n", i + 1, entry.key));
            summary.append(String.format("     Size: %,d bytes (%.2f MB)\n",
                    entry.size, entry.size / (1024.0 * 1024.0)));
            summary.append("\n");
        }

        summary.append("--------------------------------------------------------------------------------\n");
        summary.append(String.format("Total size of top %d entries: %,d bytes (%.2f MB)\n",
                entries.size(), totalSize, totalSize / (1024.0 * 1024.0)));

        // ADD EXECUTION TIME TO SUMMARY
        summary.append(String.format("Total execution time: %,d ms (%.2f seconds)\n",
                executionTimeMs, executionTimeMs / 1000.0));

        summary.append("================================================================================");

        logger.info(summary.toString());
    }

    @Override
    public String getId() {
        return "TopNByteSizesPerServerFunction";
    }
}