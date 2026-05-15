# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **BlobMetadata JSON round-trip.** The accessor methods (`id()`, `name()`, `size()`, etc.) don't match Java bean naming, so Jackson silently failed to discover them as serializable properties — only the explicitly-annotated `type()` method was being serialized, producing `{"type":"blob"}` documents with no other fields. Annotated every accessor with `@JsonProperty` to make them visible to Jackson in both directions. Added `@JsonIgnoreProperties(ignoreUnknown = true)` to the class so the synthetic `type` field on the wire is tolerated on read (it's a getter-only discriminator without a creator parameter). Caught by the CI test run on the initial GitHub push.
- **`MetadataRepository.findById` type guard.** The guard previously compared `meta.type()` (a hard-coded constant) against `DOCUMENT_TYPE`, which always succeeded and would have wrongly accepted documents with `type:"user"` etc. Now reads the `type` field from the raw JSON tree before deserializing, then only constructs the BlobMetadata if it actually is a blob document.
- **Inconsistent metric recording** in `CouchbaseS3BlobStore.get` / `delete` / `updateMetadata`. A failure in the upstream `metadata.findById` call previously bypassed the metric recorder entirely, so a Couchbase outage would show traffic dropping on dashboards without a matching failure signal. Each method now tracks its outcome in a local and records the metric in a `finally` block, so every escape path tags exactly one metric.
- **Credential leak via INFO log** in `BlobStoreBuilder.build()`. The Couchbase connection string was logged verbatim; Couchbase URIs accept `couchbases://user:pass@host` form, so a password in the connection string would surface in INFO-level logs. Added a `sanitizeConnectionString` helper that strips the `user:pass@` userinfo per RFC 3986 before logging.
- **Timing oracle in `TokenAuthFilter.constantTimeEquals`.** The previous implementation short-circuited on length mismatch (`if (a.length != b.length) return false;`), leaking the configured token length through observable timing. Switched to padding both inputs to a common max length and delegating to `java.security.MessageDigest.isEqual`, which the JDK documents as constant-time. The outer length check is now performed after the constant-time loop, so it can't leak length information through total time.
- **Rate-limiter unbounded memory growth.** `SlidingWindowRateLimiter.hits` retained entries forever — every distinct source IP added a permanent deque, so the map grew without bound under either normal traffic over months or deliberate attack. Added a piggyback eviction sweep that runs every ~1024 `tryAcquire` calls and drops entries whose deque is empty and last access was more than 2 windows ago. No background thread; cost amortizes to O(1) per call.
- **Non-exhaustive switch on `SseMode`** in the two `applySse` helpers. Added `default: throw new IllegalStateException(...)` so a future enum value can't silently skip encryption.
- **Backend error messages leak to HTTP clients.** The `BlobStoreException` handler returned `e.getMessage()` in the response body, which could include internal S3 bucket paths, Couchbase document ids, and stack frame artifacts. Now returns a generic `"upstream backend error"`; the detail stays in the server log.
- **Unchecked cast in `X-Blob-Attributes` header parsing.** Switched from the raw `Map.class` argument to a `TypeReference<Map<String, Object>>`, removing the unchecked-warning and making the parse failure path more precise.
- **`analyticsDataset` SQL++ injection surface.** The Builder previously accepted any string for the dataset name and concatenated it into every query. Validation now rejects values outside `[A-Za-z0-9_.``-]+`, blocking accidental or malicious operator input.
- **QuickStart re-run fails on existing output.** `Files.copy(in, out)` threw `FileAlreadyExistsException` on the second run. Added `StandardCopyOption.REPLACE_EXISTING`.
- **Mockito self-attach deprecated on JDK 21+.** The CI log warned that dynamic agent loading will be disallowed in a future JDK. Configured `maven-surefire-plugin` to load `mockito-core` as a Java agent at JVM startup via `-javaagent:${settings.localRepository}/...`, silencing the warning and keeping tests working on future JDKs.
- **Misleading `<optional>true</optional>` on `micrometer-core`.** The comment claimed Micrometer was used reflectively-safe; the code actually imports `MeterRegistry`/`Counter`/`Timer`/`DistributionSummary` directly, so excluding the dep would have caused `NoClassDefFoundError` on every code path. Made the dep required and rewrote the comment to match actual behavior.
- **Micrometer 1.13 Prometheus package rename.** Bumping `micrometer.version` to `1.13.4` (to track the version `javalin-micrometer:6.4.0` declares) broke the `cb-blob-store-rest` build: in Micrometer 1.13 the `micrometer-registry-prometheus` module's base package was renamed from `io.micrometer.prometheus` to `io.micrometer.prometheusmetrics`. Updated the two `import` statements in `BlobStoreServer.java`. The `PrometheusMeterRegistry(PrometheusConfig.DEFAULT)` constructor and `scrape()` API remain compatible — only the package import changed.

### Added
- Parent POM now carries a formal `<licenses>` block (MIT) and `<developers>` entry so downstream consumers and Maven Central tooling pick up the license metadata automatically.

## [0.1.0-SNAPSHOT] — 2026-05-14

Initial public snapshot. Java reimagining of `couchbaselabs/cbfs` — metadata in
Couchbase, payload in S3-compatible object storage.

### Added

- **Core library** (`cb-blob-store-core`) — `BlobStore` interface with streaming
  `put` (multipart upload, bounded memory regardless of payload size), streaming
  `get`, metadata-only lookup, idempotent `delete`, and metadata-only update.
- **REST sidecar** (`cb-blob-store-rest`) — Javalin 6 HTTP front-end that wraps
  the same library. Bearer-token auth, per-IP rate limit, body-size cap, TLS
  in-process or via reverse proxy, configurable CORS, structured audit log.
- **Couchbase Analytics integration** — `BlobAnalytics` helper with eight typed
  query methods (`count`, `totalBytes`, `summary`, `countByContentType`,
  `largestBlobs`, `byTag`, `largerThan`, `createdBetween`) plus a `rawQuery`
  escape hatch. REST mirror at `GET /analytics/*`.
- **Custom metadata** — three shapes on every blob:
  - typed top-level: `owner`, `project`, `retentionUntil`
  - `tags` (string → string) for cheap labels
  - `attributes` (string → arbitrary JSON) for nested data
  REST upload accepts these as `X-Blob-Owner`/`X-Blob-Project`/
  `X-Blob-Retention-Until`/`X-Blob-Tag-<key>`/`X-Blob-Attributes` headers.
  `PATCH /blobs/{id}/metadata` rewrites custom fields without re-uploading.
- **Monitoring** — Micrometer `CompositeMeterRegistry` publishing to both
  Prometheus (`GET /metrics`, text/plain v0.0.4 exposition) and JMX MBeans.
  Library publishes `cbbs.put.*`, `cbbs.get.*`, `cbbs.delete.*`,
  `cbbs.metadata.update.*` counters and timers; standard Micrometer JVM/system
  binders are bound on startup; `javalin-micrometer` adds per-route request
  metrics.
- **Server-side encryption** — SSE-S3 (AES-256) by default; SSE-KMS supported.
- **Input validation** — `IdValidator` rejects path traversal and control
  characters in blob ids.
- **Audit log** — structured `io.cbblobstore.audit` logger for every mutating
  REST request (upload / download / delete / metadata update / rate-limit /
  auth failure).
- **Documentation** — `README.md`, `docs/ARCHITECTURE.md` (with nine embedded
  diagrams), `docs/RUNBOOK.md` (twelve sections including prereqs, security
  guide, Analytics setup, monitoring, Couchbase Eventing recipes, and a
  production checklist). Word document versions of each pre-built in `docs/`.
- **Example** — `cb-blob-store-examples/QuickStart` end-to-end demo.

### Verified

- **Couchbase Server 8.x compatibility.** Java SDK 3.11.x is the supported
  line for Server 7.6 and 8.0 (per Couchbase's
  [Java SDK compatibility matrix](https://docs.couchbase.com/java-sdk/current/project-docs/compatibility.html)).
  No code changes were required for 8.x; the SDK pin is unchanged.
- **Compile check.** Every Java source file (main + tests + examples across
  all three modules) compiles clean against the actual external API shapes
  (Couchbase SDK 3.11.x, AWS SDK v2, Javalin 6.4.0, Micrometer 1.13.4,
  Jackson 2.18, SLF4J 2, JUnit 5, Mockito 5, AssertJ 3) verified locally with
  a stub-jar test bench.

### Design (documented, not implemented in v1)

- **Content-addressed dedup** — ARCHITECTURE §9c documents the two-phase put,
  sha-lookup document schema, refcount semantics, and GC implications. Not
  implemented in v0.1 because it changes the put hot path significantly and
  the operational complexity is best deferred until measured duplication
  justifies it.
- **Couchbase Eventing integration** — ARCHITECTURE §8d and RUNBOOK §10
  document four concrete recipes (auto-tag, retention enforcement, dead-letter
  on missing S3 object, fan-out on delete). Eventing functions run JavaScript
  in-cluster, so no library code is needed — the recipes are deployable as-is.

### Pinned dependency versions

| Component | Version | Why |
|---|---|---|
| Java | 17 (LTS) | Minimum supported runtime |
| Couchbase Java SDK | 3.11.2 | Supports Server 7.6 and 8.x |
| AWS SDK for Java v2 BOM | 2.29.0 | Latest stable v2 line |
| Jackson | 2.18.0 | |
| SLF4J | 2.0.16 | |
| Logback | 1.5.12 | |
| Javalin | 6.4.0 | Latest 6.x; matches `javalin-micrometer:6.4.0` |
| Jetty | 11.0.25 | Embedded by Javalin 6.4.0 |
| Micrometer | 1.13.4 | Version `javalin-micrometer:6.4.0` declares |
| JUnit Jupiter | 5.11.3 | |
| Mockito | 5.14.2 | |
| AssertJ | 3.26.3 | |

[Unreleased]: https://github.com/cahrendt/cb-blob-store/compare/v0.1.0-SNAPSHOT...HEAD
[0.1.0-SNAPSHOT]: https://github.com/cahrendt/cb-blob-store/releases/tag/v0.1.0-SNAPSHOT
