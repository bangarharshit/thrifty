namespace java com.foo.bar

include "referenced.thrift"
struct Query {
  1: required string text,
  2: required i64 resultsNewerThan
  3: required Input input
  4: required referenced.Used used
}

struct Input {
  1: required string text
}
struct SearchResult {
  1: required string url,
  2: required list<string> keywords = [], // A list of keywords related to the result
  3: required i64 lastUpdatedMillis // The time at which the result was last checked, in unix millis
  4: required SmallResult smallResult
}

struct SmallResult {
  1: optional string identifier
}

service Google {
  list<SearchResult> search(1: Query query)(whitelisted="true")
}
