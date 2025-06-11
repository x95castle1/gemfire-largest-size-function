import org.apache.geode.cache.Cache;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.ResultSender;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.internal.size.ObjectGraphSizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class MaxSizeSummarizer implements Function {

    private static final Logger logger = LogManager.getLogger(MaxSizeSummarizer.class);

    @Override
    public void execute(FunctionContext context) {
        Cache cache = context.getCache();
        ResultSender sender = context.getResultSender();
        String memberName = cache.getDistributedSystem().getDistributedMember().getName();

        logger.info("MaxSizeSummarizer: Starting execution on member: {}", memberName);

        try {
            List<String> results = new ArrayList<>();
            Set<Region<?, ?>> allRegions = new HashSet<>();
            getAllRegions(cache.rootRegions(), allRegions);

            logger.info("MaxSizeSummarizer: Found {} regions to analyze", allRegions.size());

            long overallMaxBytes = 0;
            QueryService queryService = cache.getQueryService();

            results.add("Analyzing on member: " + memberName);
            results.add("");

            for (Region<?, ?> region : allRegions) {
                int regionSize = region.size();
                String regionName = region.getFullPath();

                if (regionSize == 0) {
                    logger.debug("MaxSizeSummarizer: Skipping empty region: {}", regionName);
                    continue;
                }

                logger.info("MaxSizeSummarizer: Analyzing region: {} with {} entries", regionName, regionSize);

                long maxBytes = 0;

                try {
                    // For large regions, use query with LIMIT instead of keySet()
                    if (regionSize > 10000) {
                        logger.debug("MaxSizeSummarizer: Using OQL query for large region: {}", regionName);

                        String queryStr = "SELECT * FROM " + regionName + " LIMIT 100";
                        Query query = queryService.newQuery(queryStr);
                        SelectResults<?> queryResults = (SelectResults<?>) query.execute();

                        logger.debug("MaxSizeSummarizer: Query returned {} results for region: {}", queryResults.size(), regionName);

                        for (Object value : queryResults) {
                            if (value != null) {
                                long size = ObjectGraphSizer.size(value, (p, o) -> true, false);
                                maxBytes = Math.max(maxBytes, size);
                            }
                        }
                    } else {
                        logger.debug("MaxSizeSummarizer: Using direct iteration for region: {}", regionName);

                        int checked = 0;
                        for (Object key : region.keySet()) {
                            if (checked >= 100) break;

                            Object value = region.get(key);
                            if (value != null) {
                                long size = ObjectGraphSizer.size(value, (p, o) -> true, false);
                                maxBytes = Math.max(maxBytes, size);
                                checked++;
                            }
                        }

                        logger.debug("MaxSizeSummarizer: Checked {} entries in region: {}", checked, regionName);
                    }

                    if (maxBytes > 0) {
                        overallMaxBytes = Math.max(overallMaxBytes, maxBytes);
                        double maxMB = maxBytes / 1024.0 / 1024.0;

                        logger.info("MaxSizeSummarizer: Region {} - max object size: {} bytes ({} MB)",
                                regionName, maxBytes, String.format("%.3f", maxMB));

                        results.add(String.format("%s (%,d entries): max=%,d bytes (%.3f MB)",
                                region.getName(), regionSize, maxBytes, maxMB));
                    }

                } catch (Exception e) {
                    logger.error("MaxSizeSummarizer: Error analyzing region {}: {}", regionName, e.getMessage(), e);
                    results.add(region.getName() + ": ERROR - " + e.getMessage());
                }
            }

            // Add recommendation
            double overallMaxMB = overallMaxBytes / 1024.0 / 1024.0;
            results.add("");
            results.add("LARGEST OBJECT: " + String.format("%,d", overallMaxBytes) +
                    " bytes (" + String.format("%.3f", overallMaxMB) + " MB)");

            String recommendation;
            if (overallMaxMB < 8) {
                recommendation = "Use -XX:G1HeapRegionSize=16M";
                results.add("RECOMMENDATION: " + recommendation);
            } else if (overallMaxMB < 16) {
                recommendation = "Use -XX:G1HeapRegionSize=32M";
                results.add("RECOMMENDATION: " + recommendation);
            } else {
                recommendation = String.format("Keep 32M but WARNING - %.2fMB object will be humongous!", overallMaxMB);
                results.add("WARNING: Object > 16MB will be humongous with 32M regions!");
                logger.warn("MaxSizeSummarizer: Found object larger than 16MB: {} MB", overallMaxMB);
            }

            logger.info("MaxSizeSummarizer: Analysis complete. Largest object: {} bytes ({} MB). Recommendation: {}",
                    overallMaxBytes, String.format("%.3f", overallMaxMB), recommendation);

            // Send results
            for (int i = 0; i < results.size() - 1; i++) {
                sender.sendResult(results.get(i));
            }
            sender.lastResult(results.get(results.size() - 1));

            logger.info("MaxSizeSummarizer: Execution completed successfully");

        } catch (Exception e) {
            String errorMsg = "Error in MaxSizeSummarizer: " + e.getMessage();
            logger.error(errorMsg, e);
            sender.lastResult(errorMsg);
        }
    }

    private void getAllRegions(Set<Region<?, ?>> regions, Set<Region<?, ?>> accumulator) {
        for (Region<?, ?> region : regions) {
            accumulator.add(region);
            Set<Region<?, ?>> subregions = region.subregions(false);
            if (!subregions.isEmpty()) {
                getAllRegions(subregions, accumulator);
            }
        }
    }

    @Override
    public String getId() {
        return "MaxSizeSummarizer";
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
}