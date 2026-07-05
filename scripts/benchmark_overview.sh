#!/bin/bash


SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/git_helpers.sh"

print_usage() {
  echo "Usage: $0 [options]"
  echo "Options:"
  echo "  -b, --benchmark-name    Filter results by benchmark name."
  echo "  -l, --living-branches   Filter results by active git branches."
  echo "  -a, --all               Print all results without grouping."
  echo "  -h, --help              Display this help message."
}

BENCHMARK_NAME=""
LIVING_BRANCHES=""
PRINT_ALL=""

# Parse named parameters
while [[ "$#" -gt 0 ]]; do
  case $1 in
    -b|--benchmark-name)
      BENCHMARK_NAME="$2"
      shift 2
      ;;
    -l|--living-branches)
      LIVING_BRANCHES="true"
      shift 1
      ;;
    -a|--all)
      PRINT_ALL="true"
      shift 1
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      print_usage
      exit 1
      ;;
  esac
done

# Construct MongoDB query
MATCH_CONDITIONS="     'benchmarkMetadata.tags.project': 'lynx-journal'"
MATCH_CONDITIONS+=",
     'benchmarkMetadata.tags.type': 'jmh'"

if [[ -n "$BENCHMARK_NAME" ]]; then
  MATCH_CONDITIONS+=",
     '_id.benchmarkName': { \$regex: '$BENCHMARK_NAME' }"
fi

# Fetch living branches if required
if [[ "$LIVING_BRANCHES" == "true" ]]; then
  ACTIVE_BRANCHES=$(list_active_remote_branches)
  if [[ $? -ne 0 ]]; then
      echo "ERROR: Unable to retrieve remote branches." >&2
      exit 1
  fi

  # Convert the branches array into a comma-separated format
  FORMATTED_BRANCHES=$(printf "'%s', " $ACTIVE_BRANCHES)
  FORMATTED_BRANCHES=${FORMATTED_BRANCHES%, } # Remove trailing comma and space

  MATCH_CONDITIONS+=",
     'benchmarkMetadata.tags.branch': { \$in: [${FORMATTED_BRANCHES}] }"
fi

if [[ "$PRINT_ALL" == "true" ]]; then
QUERY="db.getCollection('jmh_benchmarks').aggregate([ {
  \$match: {
    ${MATCH_CONDITIONS} }
  },
  {
    \$project: {
      _id: 0,
      benchmark: '\$jmhResult.benchmark',
      score: { \$round: ['\$jmhResult.primaryMetric.score', 0] },
      scoreUnit: '\$jmhResult.primaryMetric.scoreUnit',
      requestId: '\$_id.requestId',
      tags: '\$benchmarkMetadata.tags'
    }
  },
  {
    \$sort: { 'benchmark': 1, 'score': -1 }
  }
])
.forEach(function(doc) {
   print(doc.benchmark.split('.').pop() + '\t' + doc.score.toLocaleString('pl-PL') + '\t' + doc.scoreUnit
      + '\t' + doc.tags.branch + '\t' + doc.requestId + '\t' + doc.tags.options);
});"
else
QUERY="db.getCollection('jmh_benchmarks').aggregate([ {
   \$match: {
      \$or: [
        {
          'benchmarkMetadata.tags.exclude_from_results': { \$exists: false }
        },
        {
          'benchmarkMetadata.tags.exclude_from_results': false
        }
      ],
${MATCH_CONDITIONS}
   }
 },
 {
   \$sort: { 'jmhResult.primaryMetric.score': -1 }
 },
 {
   \$group: {
     _id: {
       benchmark: '\$jmhResult.benchmark',
       branch: '\$benchmarkMetadata.tags.branch'
     },
     requestId: { \$first: '\$_id.requestId' },
     score: { \$first: '\$jmhResult.primaryMetric.score' },
     scoreUnit: { \$first: '\$jmhResult.primaryMetric.scoreUnit' },
     tags: { \$first: '\$benchmarkMetadata.tags' }
   }
 },
 {
   \$project: {
     _id: 0,
     benchmark: '\$_id.benchmark',
     branch: '\$_id.branch',
     score: { \$round: ['\$score', 0] },
     scoreUnit: 1,
     requestId: 1,
     tags: 1
   }
 },
 {
   \$sort: { 'benchmark': 1, 'score': -1 }
 }
],{ maxTimeMS: 60000, allowDiskUse: true })
.forEach(function(doc) {
   print(doc.benchmark.split('.').pop() + '\t' + doc.score.toLocaleString('pl-PL') + '\t' + doc.scoreUnit
      + '\t' + doc.tags.branch + '\t' + doc.requestId + '\t' + doc.tags.options);
});"
fi

# Check if BENCHMARK_DB_CONNECTION_STRING environment variable is defined
if [ -z "$BENCHMARK_DB_CONNECTION_STRING" ]; then
  # If the environment variable is not defined, print the query to the console
  echo "Environment variable BENCHMARK_DB_CONNECTION_STRING is not defined."
  echo "Generated MongoDB Query:"
  echo "$QUERY"
else
  # If the environment variable is defined, execute the query using mongosh
  echo "Executing MongoDB query..."
  mongosh "$BENCHMARK_DB_CONNECTION_STRING" --eval "$QUERY" |
  (
    # Print header
    echo -e "BENCHMARK\tSCORE\tUNIT\tBRANCH\tREQUEST_ID\tOPTIONS" && cat
  ) | column -t -s $'\t'
fi
