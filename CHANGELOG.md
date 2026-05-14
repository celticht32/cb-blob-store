# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
