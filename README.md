# cb-blob-store

A Java reimagining of [couchbaselabs/cbfs](https://github.com/couchbaselabs/cbfs) (now archived). Distributed blob storage with **metadata in Couchbase** (Server 7.6 / 8.x or Capella) and **payload in S3-compatible object storage**.

Two ways to use it:

- **As a library** — add `cb-blob-store-core` to your Maven project and extend the official Couchbase Java SDK with `BlobStore.put(...)` / `BlobStore.get(...)` for arbitrarily large documents.
- **As a sidecar** — run the shaded `cb-blob-store-rest` jar to expose the same operations over HTTP.

The library is the primary deliverable; the sidecar exists for non-JVM clients and curl-style ops.

> **Note on "extending Couchbase":** Couchbase Server has no in-process Java plugin SPI. This project extends Couchbase from the *client side* — the natural and idiomatic way. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §1 for the full rationale.

## Quick start

```xml
<dependency>
  <groupId>io.cbblobstore</groupId>
  <artifactId>cb-blob-store-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
try (BlobStore store = BlobStore.builder()
        .couchbaseConnectionString("couchbases://cb.example.cloud.couchbase.com")
        .couchbaseCredentials("user", "pass")
        .couchbaseBucket("blob_metadata")
        .capella(true)
        .s3Bucket("company-blobs")
        .s3Region("us-east-1")
        .build()) {

    // Add (any size — streamed to S3 via multipart, bounded memory)
    BlobMetadata meta = store.put(
        Files.newInputStream(Path.of("4gb-video.mp4")),
        BlobOptions.builder()
            .name("4gb-video.mp4")
            .contentType("video/mp4")
            // Typed custom-metadata fields
            .owner("alice@example.com")
            .project("marketing-q2")
            .retentionUntil(Instant.now().plus(Duration.ofDays(365)))
            // Simple labels
            .tag("env", "prod")
            // Arbitrary JSON
            .attribute("schemaVersion", 2)
            .build());

    // Retrieve
    try (InputStream in = store.get(meta.id())) {
        in.transferTo(System.out);
    }

    // Update metadata without re-uploading
    store.updateMetadata(meta.id(),
        BlobOptions.builder().owner("bob@example.com").build());
}
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — components, sequence diagrams, data model, deployment, reconciliation, security boundaries, Analytics integration
- [Runbook](docs/RUNBOOK.md) — provisioning, configuration, REST API, ops, troubleshooting, prod checklist, full security guide, Analytics queries

## Analytics

`BlobStore.analytics()` returns a `BlobAnalytics` handle that runs aggregate queries through the Couchbase Analytics service:

```java
BlobAnalytics.Summary s = store.analytics().summary();
//   count, totalBytes, avgBytes — single round trip

Map<String, Long> byType = store.analytics().countByContentType();
List<String> largest    = store.analytics().largestBlobs(50);
List<String> tagged     = store.analytics().byTag("env", "prod");
```

The sidecar exposes equivalents at `GET /analytics/summary`, `GET /analytics/by-content-type`, `GET /analytics/larger-than/{bytes}`, and `GET /analytics/by-tag?key=...&value=...`. Analytics queries run on the Analytics service and don't compete with KV traffic. See [RUNBOOK §9](docs/RUNBOOK.md) for dataset setup.

## Custom metadata

`BlobOptions` accepts caller-supplied metadata in three shapes; pick the one that fits the data:

| Shape | When | Example |
|---|---|---|
| Typed top-level — `owner`, `project`, `retentionUntil` | Fields used on every blob; filterable/sortable | `.owner("alice@example.com")` |
| `tag(k, v)` map (string → string) | Occasional labels with simple values | `.tag("env", "prod")` |
| `attribute(k, v)` map (string → Object) | Nested objects, numbers, booleans, sparse fields | `.attribute("source", Map.of("kind", "ingest"))` |

`BlobStore.updateMetadata(id, options)` rewrites the metadata document only — no S3 round trip — and bumps `updatedAt`. The REST sidecar exposes the same shape on `PATCH /blobs/{id}/metadata`, and accepts custom fields on `PUT` via headers (`X-Blob-Owner`, `X-Blob-Project`, `X-Blob-Retention-Until`, `X-Blob-Tag-<key>`, `X-Blob-Attributes`). See [RUNBOOK §4.1 and §6.2](docs/RUNBOOK.md) for the full reference.

## Monitoring

The REST sidecar wires a Micrometer `CompositeMeterRegistry` that publishes to two surfaces at once:

- **Prometheus** — `GET /metrics` (text exposition format; no auth, no rate limit).
- **JMX** — MBeans under the `metrics.*` domain, browseable from JConsole / VisualVM / `jmx_exporter`.

Library counters and timers (`cbbs.put.*`, `cbbs.get.*`, `cbbs.delete.*`, `cbbs.metadata.update.*`), Javalin's per-route request metrics, and the standard JVM/system binders all share the same composite. See [RUNBOOK §7.2](docs/RUNBOOK.md) for sample PromQL and Grafana queries, and [ARCHITECTURE §8c](docs/ARCHITECTURE.md) for the topology.

## Security

The REST sidecar is fail-closed: bearer-token authentication is required unless explicitly disabled. Out of the box it provides:

- Bearer-token auth with constant-time comparison, fail-closed startup
- TLS in-process (`CBBS_TLS_KEYSTORE_PATH`) or via reverse proxy
- Per-IP rate limiting (`CBBS_RATE_LIMIT_PER_MINUTE`, default 120)
- Configurable upload size cap (`CBBS_MAX_UPLOAD_BYTES`, default 5 GiB)
- Standard security response headers (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Cache-Control`, plus HSTS when TLS is on)
- CORS off by default, explicit origin allow-list
- S3 server-side encryption (`AES256` by default; `KMS` with customer key supported)
- Blob-id input validation (rejects path traversal and control characters)
- Structured audit log on `io.cbblobstore.audit`

See [RUNBOOK §5b](docs/RUNBOOK.md) for the configuration reference and deployment checklist; [ARCHITECTURE §9b](docs/ARCHITECTURE.md) for the threat model.

## Modules

| Module | Purpose |
|---|---|
| `cb-blob-store-core` | The `BlobStore` library — depend on this in apps |
| `cb-blob-store-rest` | Optional Javalin sidecar exposing HTTP, Prometheus, and JMX |
| `cb-blob-store-examples` | `QuickStart` demo |

## Build

```bash
mvn install
```

Requires JDK 17+, Maven 3.9+. Pinned dependencies (verified May 2026):

- Couchbase Java SDK 3.11.2 — supports Couchbase Server 7.6 and 8.x ([compatibility matrix](https://docs.couchbase.com/java-sdk/current/project-docs/compatibility.html))
- AWS SDK for Java v2 (`bom` 2.29.0)
- Javalin 6.4.0 + javalin-micrometer 6.4.0
- Micrometer 1.13.4 (core + prometheus + jmx registries)
- Jackson 2.18.0

## What changed vs original `cbfs`

| | Original `cbfs` (Go) | `cb-blob-store` |
|---|---|---|
| Language | Go | Java 17 |
| Payload | Local disks, custom replication/gc | S3-compatible object storage |
| Shape | Standalone service | Library + optional REST sidecar |
| Couchbase | 1.x KV | SDK 3.11 (Capella + self-hosted Server 7.6 / 8.x) |
| Scope | Full distributed FS (chunking, dedup, monitor UI) | Focused: easy put/get of large blobs |

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 Chris Ahrendt.
