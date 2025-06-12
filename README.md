# MaxSizeSummarizer Function Usage
## What It Does
Analyzes all regions in the GemFire cluster to find the largest object in each region and provides G1 garbage collection heap region size recommendations.

## How It Works
- Samples up to 100 entries per region to find maximum object size
- Uses OQL queries for large regions (>10,000 entries) to avoid memory overhead
- Calculates actual in-memory size using ObjectGraphSizer
- Provides cluster-wide G1HeapRegionSize recommendations based on largest object found

## Safety
- Read-only operation - does not modify any data
- Limits sampling to prevent memory issues on massive regions
- Automatically uses efficient query methods for large regions
- Should be run on a single member to avoid duplicate work

## Input
- No arguments required. The function automatically analyzes all regions in the cluster.

## Compile and Package
### Prerequisites
- Appropriate JDK installed to compile and package file
- GemFire installed and $GEMFIRE_HOME set on path
- Log4j2 dependencies for logging

### Compile and Package
Run the compile-custom-functions.sh script to compile MaxSizeSummarizer.java and build custom-functions.jar. These files will be in the custom-functions/target directory if successful.
```
./compile-custom-functions.sh
```

## How to Use
### 1. Deploy the JAR
```
gfsh> deploy --jar=/path/to/custom-functions.jar
```
   
### 2. Execute the Function
Run on a specific member (recommended):
```
gfsh> execute function --id=MaxSizeSummarizer --member=server-0
```
Run on all members (may show duplicate results):
```
gfsh> execute function --id=MaxSizeSummarizer
```

### Example gfsh Output 
```
Cluster-42 gfsh>execute function --id=MaxSizeSummarizer  --member=server-0
 Member  | Status | Message
-------- | ------ | -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
server-0 | OK     | [Analyzing on member: server-0, , persistentReplicatedRegion2 (1 entries): max=56 bytes (0.000 MB), MarketPrices (46 entries): max=19,779,176 bytes (18.863 MB), , LARGEST OBJECT: 19,779,176 bytes (18.863 MB)..
```
Note that the output may be truncated depending on how many columns your terminal is set to, to see the full report, check the logs on the member where the function ran (in this example above, `server-0`) 

### Example Log Output
```
[info 2025/06/12 15:30:18.046 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Starting execution on member: server-0
[info 2025/06/12 15:30:18.053 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Found 9 regions to analyze
[info 2025/06/12 15:30:18.054 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Analyzing region: /persistentReplicatedRegion2 with 1 entries
[info 2025/06/12 15:30:18.056 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Region /persistentReplicatedRegion2 - max object size: 56 bytes (0.000 MB)
[info 2025/06/12 15:30:18.060 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Analyzing region: /MarketPrices with 46 entries
[info 2025/06/12 15:30:19.095 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Region /MarketPrices - max object size: 19779176 bytes (18.863 MB)
[warn 2025/06/12 15:30:19.095 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Found object larger than 16MB: 18.862892150878906 MB
[info 2025/06/12 15:30:19.096 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Analysis complete. Largest object: 19779176 bytes (18.863 MB). Recommendation: Keep 32M but WARNING - 18.86MB object will be humongous!
[info 2025/06/12 15:30:19.096 UTC server-0 <Function Execution Processor5> tid=0x9c] MaxSizeSummarizer: Execution completed successfully
```


### Understanding Results
#### G1 Heap Region Size Recommendations:
Largest Object SizeRecommendationReason< 8 MB-XX:G1HeapRegionSize=16MAllows efficient young generation, good for small objects8-16 MB-XX:G1HeapRegionSize=32MBalances large object handling with GC efficiency> 16 MBKeep 32M with WARNINGObjects > 50% of region size become "humongous"

### Important Notes:

Objects > 50% of G1HeapRegionSize are allocated as "humongous objects"
Humongous objects bypass young generation and can impact GC performance
If you see objects > 16MB with 32M regions, consider application-level chunking

## Monitoring
Check function execution in server logs:
```
grep "MaxSizeSummarizer" /var/gemfire/server-0.log
```
View detailed analysis per region:
```
grep "max object size" /var/gemfire/server-0.log
```

## For regions with millions or billions of entries:

- The function only samples 100 entries via OQL
- This may not catch rare large objects
- Consider domain knowledge about your data distribution

## Limitations

- Samples only 100 entries per region (may miss outliers in very large regions)
- OQL LIMIT clause doesn't randomize, may have sampling bias
- Results show estimated maximum, not guaranteed maximum
- For critical sizing decisions, consider analyzing during different workload patterns