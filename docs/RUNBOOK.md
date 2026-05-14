# Programming Runbook — `cb-blob-store`

A practical guide to integrating, running, and operating the library.

---

## 1. Prerequisites

| Component | Version |
|---|---|
| JDK | 17 LTS or newer (21 LTS recommended) |
| Maven | 3.9+ |
| Couchbase Server | **7.6**, **8.0**, or **8.x**, self-hosted, **or** Couchbase Capella |
| Java SDK | 3.11.x (pinned by parent POM — see §10) |
| Object storage | AWS S3 **or** any S3-compatible service (MinIO, Cloudflare R2, Wasabi, Ceph RGW) |

> **Couchbase Server 8.x note.** Couchbase 8.0 (GA October 2025) defaults new buckets to the **Magma** storage engine with **128 vBuckets** (down from 1024 on Couchstore), which lowers the minimum bucket RAM to 100 MiB per node. The library uses only the standard KV and Analytics APIs and is fully compatible with both Couchstore and Magma. The Java SDK 3.11.x line is the supported SDK for Server 7.6, 8.0, and 8.x — see <https://docs.couchbase.com/java-sdk/current/project-docs/compatibility.html>. No code changes were needed to support 8.x; the SDK pin is unchanged.

---

## 2. One-time provisioning

### 2.1 Couchbase: bucket, scope, collection

Self-hosted, via the CLI on a cluster node:

```bash
couchbase-cli bucket-create \
  --cluster localhost:8091 -u Administrator -p password \
  --bucket blob_metadata --bucket-type couchbase --bucket-ramsize 256
```

Capella: create the bucket through the Capella UI. Default scope and collection (`_default._default`) are fine; for production prefer a named scope/collection.

### 2.2 Couchbase: a query index (optional but recommended)

If you intend to list blobs or filter by name, create one secondary index. It is **not** required for `put`/`get`/`delete` — those use KV by id, which is index-free.

```sql
CREATE INDEX idx_blob_type
  ON `blob_metadata`(type, name, createdAt)
  WHERE type = 'blob';
```

### 2.3 Couchbase: an application user

Self-hosted:

```bash
couchbase-cli user-manage \
  --cluster localhost:8091 -u Administrator -p password \
  --set --rbac-username cbbs-app --rbac-password <pwd> \
  --roles "data_reader[blob_metadata],data_writer[blob_metadata]" \
  --auth-domain local
```

Capella: create a Database Access credential with `Read/Write` on the `blob_metadata` bucket.

### 2.4 S3 bucket and IAM

```bash
aws s3api create-bucket --bucket company-blobs --region us-east-1
aws s3api put-bucket-versioning --bucket company-blobs \
  --versioning-configuration Status=Enabled
```

**Required lifecycle rule** (eats incomplete multipart uploads so failed `put` calls don't accrue cost):

```json
{
  "Rules": [{
    "ID": "AbortIncompleteMultipart",
    "Status": "Enabled",
    "Filter": { "Prefix": "blobs/" },
    "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
  }]
}
```

Apply with `aws s3api put-bucket-lifecycle-configuration --bucket company-blobs --lifecycle-configuration file://lifecycle.json`.

Minimum IAM policy for the application principal:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "s3:GetObject", "s3:PutObject", "s3:DeleteObject",
      "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"
    ],
    "Resource": "arn:aws:s3:::company-blobs/blobs/*"
  }, {
    "Effect": "Allow",
    "Action": ["s3:ListBucket", "s3:ListBucketMultipartUploads"],
    "Resource": "arn:aws:s3:::company-blobs"
  }]
}
```

---

## 3. Build

```bash
git clone <this-repo>
cd cb-blob-store
mvn -DskipTests install     # builds all three modules
```

Produces:

- `cb-blob-store-core/target/cb-blob-store-core-0.1.0-SNAPSHOT.jar` — depend on this in your app
- `cb-blob-store-rest/target/cb-blob-store-rest-0.1.0-SNAPSHOT.jar` — shaded, executable

---

## 4. Library quickstart (the primary "API extender")

Add to your application's `pom.xml`:

```xml
<dependency>
  <groupId>io.cbblobstore</groupId>
  <artifactId>cb-blob-store-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 4.1 Add a (potentially huge) document

```java
try (BlobStore store = BlobStore.builder()
        .couchbaseConnectionString("couchbases://cb.<cluster-id>.cloud.couchbase.com")
        .couchbaseCredentials("cbbs-app", System.getenv("CB_PASSWORD"))
        .couchbaseBucket("blob_metadata")
        .capella(true)
        .s3Bucket("company-blobs")
        .s3Region("us-east-1")
        .build()) {

    BlobMetadata meta;
    try (InputStream in = Files.newInputStream(Path.of("/data/4gb-video.mp4"))) {
        meta = store.put(in, BlobOptions.builder()
                .name("4gb-video.mp4")
                .contentType("video/mp4")
                // Typed top-level custom-metadata fields
                .owner("alice@example.com")
                .project("marketing-q2")
                .retentionUntil(Instant.now().plus(Duration.ofDays(365)))
                // Simple labels (string→string)
                .tag("env", "prod")
                .tag("team", "marketing")
                // Arbitrary JSON (string→Object)
                .attribute("schemaVersion", 2)
                .attribute("source", Map.of("kind", "ingest", "node", "ingest-01"))
                .build());
    }
    System.out.println("stored " + meta.id() + " (" + meta.size() + " bytes)");
}
```

The stream is multipart-uploaded in 8 MiB chunks (configurable). Memory use stays bounded.

#### Custom metadata: three knobs

`BlobOptions` accepts custom metadata in three shapes; pick the one that fits the data:

| Shape | When | Example |
|---|---|---|
| Typed top-level (`owner`, `project`, `retentionUntil`) | Field exists on every blob; will be filtered/sorted by | `.owner("alice@example.com")` |
| `tag(k, v)` map (string→string) | Occasional labels with simple values | `.tag("env", "prod")` |
| `attribute(k, v)` map (string→Object) | Nested objects, numbers, booleans, or sparse fields | `.attribute("source", Map.of("kind", "ingest"))` |

All three round-trip cleanly through JSON; null attribute values are preserved.

#### Replace custom metadata without re-uploading

```java
store.updateMetadata(blobId, BlobOptions.builder()
        .owner("bob@example.com")
        .tag("env", "stage")
        .build());
```

`updateMetadata` rewrites the metadata document only — S3 is not touched, `createdAt`/`size`/`sha256` are preserved, `updatedAt` is bumped. An empty tags or attributes map means "no change"; a non-empty map replaces the whole map for that shape.

### 4.2 Retrieve a document

```java
try (InputStream in = store.get(blobId);
     OutputStream out = Files.newOutputStream(Path.of("downloaded.mp4"))) {
    in.transferTo(out);
}
```

### 4.3 Metadata-only lookup

```java
Optional<BlobMetadata> meta = store.getMetadata(blobId);
meta.ifPresent(m ->
    System.out.println(m.name() + " sha256=" + m.sha256() + " size=" + m.size()));
```

### 4.4 Delete

```java
store.delete(blobId);   // silent no-op if id is unknown
```

---

## 5. Configuration reference

### 5.1 Builder methods (library)

| Method | Required | Default |
|---|---|---|
| `couchbaseConnectionString(String)` | yes | — |
| `couchbaseCredentials(user, pass)` | yes | — |
| `couchbaseBucket(String)` | yes | `blob_metadata` |
| `couchbaseScope(String)` | no | `_default` |
| `couchbaseCollection(String)` | no | `_default` |
| `capella(boolean)` | when targeting Capella | `false` |
| `s3Bucket(String)` | yes | — |
| `s3Region(String)` | no | `us-east-1` |
| `s3Endpoint(String)` | for non-AWS S3 (MinIO/R2) | `null` (AWS) |
| `s3PathStyleAccess(boolean)` | for MinIO/Ceph | `false` |
| `s3Credentials(access, secret)` | no | default AWS chain |
| `s3KeyPrefix(String)` | no | `blobs/` |
| `uploadPartSizeBytes(long)` | no | 8 MiB (min 5 MiB) |

### 5.2 Sidecar env vars

| Variable | Required | Default |
|---|---|---|
| `CBBS_CB_CONNECTION_STRING` | yes | — |
| `CBBS_CB_USERNAME` | yes | — |
| `CBBS_CB_PASSWORD` | yes | — |
| `CBBS_CB_BUCKET` | no | `blob_metadata` |
| `CBBS_CB_SCOPE` | no | `_default` |
| `CBBS_CB_COLLECTION` | no | `_default` |
| `CBBS_CB_CAPELLA` | no | `false` |
| `CBBS_S3_BUCKET` | yes | — |
| `CBBS_S3_REGION` | no | `us-east-1` |
| `CBBS_S3_ENDPOINT` | for non-AWS S3 | — |
| `CBBS_S3_PATH_STYLE` | for MinIO/Ceph | `false` |
| `CBBS_S3_ACCESS_KEY` | no | (AWS default chain) |
| `CBBS_S3_SECRET_KEY` | no | (AWS default chain) |
| `CBBS_S3_KEY_PREFIX` | no | `blobs/` |
| `CBBS_CB_ANALYTICS_DATASET` | no | `blobs` |
| `CBBS_S3_SSE` | no | `AES256` (`NONE`, `AES256`, `KMS`) |
| `CBBS_S3_KMS_KEY_ID` | required when `CBBS_S3_SSE=KMS` | — |
| `CBBS_PORT` | no | `8484` |
| `CBBS_AUTH_TOKENS` | yes (or set `CBBS_AUTH_DISABLED=true`) | — |
| `CBBS_AUTH_DISABLED` | no | `false` (dev override only) |
| `CBBS_TLS_KEYSTORE_PATH` | no | — |
| `CBBS_TLS_KEYSTORE_PASSWORD` | required if keystore set | — |
| `CBBS_TLS_PORT` | no | `8443` |
| `CBBS_MAX_UPLOAD_BYTES` | no | `5368709120` (5 GiB) |
| `CBBS_RATE_LIMIT_PER_MINUTE` | no | `120` |
| `CBBS_CORS_ALLOWED_ORIGINS` | no | (CORS off) |

---

## 5b. Security

The sidecar is fail-closed: it refuses to start if `CBBS_AUTH_TOKENS` is empty
and `CBBS_AUTH_DISABLED` is not set to `true`. Everything below is configurable,
but the defaults are designed to be safe-by-default.

### 5b.1 Authentication

Bearer tokens, supplied via `Authorization: Bearer <token>`. Tokens are read
from `CBBS_AUTH_TOKENS` (comma-separated). Comparisons are constant-time per
token length to avoid timing oracle attacks.

```bash
# Generate a token (one-time setup)
openssl rand -hex 32

export CBBS_AUTH_TOKENS="$(openssl rand -hex 32),$(openssl rand -hex 32)"
```

To rotate: add the new token to the list, redeploy, update clients, then drop
the old token and redeploy again.

`/healthz` bypasses auth so load balancers can probe without credentials.

### 5b.2 TLS

Two options:

1. **Reverse-proxy termination (recommended).** Put nginx / Envoy / ALB / Traefik
   in front of the sidecar. Listen on plain HTTP locally. Simpler ops, easier
   cert rotation.
2. **In-process TLS.** Set `CBBS_TLS_KEYSTORE_PATH` (JKS or PKCS12) and
   `CBBS_TLS_KEYSTORE_PASSWORD`. Jetty is configured with an SSL connector on
   `CBBS_TLS_PORT` (default `8443`) and the plain HTTP listener is replaced.
   When TLS is on, every response gets an `HSTS` header.

Build a self-signed keystore for testing:

```bash
keytool -genkeypair -alias cbbs -keyalg RSA -keysize 2048 \
        -keystore /etc/cbbs/keystore.p12 -storetype PKCS12 \
        -dname "CN=cbbs,O=cb-blob-store" -validity 365
```

### 5b.3 Rate limiting

Per-IP sliding-window limiter, default `120` requests/minute. A 429 response
includes `Retry-After: 60`. The limit is in-memory — for multi-instance
deployments behind a load balancer, set rate limits at the LB layer instead and
raise `CBBS_RATE_LIMIT_PER_MINUTE` high enough that the per-instance limiter is
a backstop, not the primary control.

### 5b.4 Upload size cap

`CBBS_MAX_UPLOAD_BYTES` caps a single request body. Default is 5 GiB
(matches the S3 single-PutObject limit; multipart uploads inside the library
chunk this further). Set lower if you know your blobs are smaller.

### 5b.5 Response security headers

Every response carries:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- `Cache-Control: no-store`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` (TLS only)

### 5b.6 CORS

Off by default. Set `CBBS_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com`
to allow specific browser origins. Wildcards are not accepted.

### 5b.7 S3 server-side encryption

`CBBS_S3_SSE` defaults to `AES256` (SSE-S3, S3-managed keys). For customer KMS
keys, set `CBBS_S3_SSE=KMS` plus `CBBS_S3_KMS_KEY_ID=arn:aws:kms:...`. Set to
`NONE` only when you have a bucket-level encryption policy and want to skip the
per-request header.

### 5b.8 Audit log

Mutating operations (PUT, DELETE) and successful reads (GET) emit a single-line
record to the `io.cbblobstore.audit` logger:

```
event=blob.put principal=token-1a2b3c4d ip=10.0.5.7 id=8f1e... size=4194304
event=blob.delete principal=token-1a2b3c4d ip=10.0.5.7 id=8f1e...
event=auth.fail ip=203.0.113.9 reason=invalid token
event=rate.limit.exceeded ip=203.0.113.9
```

The principal is a non-reversible 8-character SHA-256 prefix of the token —
useful for attribution, not enough to recover the secret. Route this logger to
your SIEM via a separate Logback appender; do not store it on the same disk as
the blob payloads.

### 5b.9 Input validation

Caller-supplied blob ids are checked against `[A-Za-z0-9._-]+`, length-bounded
to 250 bytes (Couchbase document-key limit), and rejected if they contain `..`
or start/end with a dot. This is enforced both in the library and the sidecar.

### 5b.10 Secrets handling

- Pass secrets via environment variables, container secrets, or a secrets
  manager — never on the command line, never in source control.
- The library and sidecar never log credentials. Audit the `io.cbblobstore.*`
  loggers if you add custom appenders.
- For Capella, the SDK uses `couchbases://` + JVM trust store for TLS. Pin the
  cluster certificate via `-Djavax.net.ssl.trustStore=...` if your environment
  requires it.

### 5b.11 Deployment checklist

- [ ] `CBBS_AUTH_TOKENS` set; `CBBS_AUTH_DISABLED` not present.
- [ ] TLS terminated (reverse proxy or `CBBS_TLS_KEYSTORE_PATH`).
- [ ] `CBBS_S3_SSE` at `AES256` or `KMS`.
- [ ] Couchbase user is the dedicated `cbbs-app` from §2.3, scoped to the
      metadata bucket only.
- [ ] S3 IAM policy restricts the role to the `blobs/` prefix on the
      configured bucket only — see §3.2 of this runbook.
- [ ] Audit logger routed to your SIEM.
- [ ] Rate limit appropriate for traffic (or moved to the load balancer).
- [ ] `CBBS_MAX_UPLOAD_BYTES` matches your largest expected blob.

---

## 6. REST sidecar

### 6.1 Run

```bash
export CBBS_CB_CONNECTION_STRING="couchbases://cb.example.cloud.couchbase.com"
export CBBS_CB_USERNAME=cbbs-app
export CBBS_CB_PASSWORD=...
export CBBS_CB_CAPELLA=true
export CBBS_S3_BUCKET=company-blobs

java -jar cb-blob-store-rest/target/cb-blob-store-rest-0.1.0-SNAPSHOT.jar
```

### 6.2 HTTP API

| Method | Path | Body | Notes |
|---|---|---|---|
| `PUT` | `/blobs` | payload | Server-generated id. Returns `201` + metadata JSON. |
| `PUT` | `/blobs/{id}` | payload | Caller-chosen id. |
| `GET` | `/blobs/{id}` | — | Streams payload. `Content-Type`, `Content-Length`, `ETag` set. |
| `GET` | `/blobs/{id}/metadata` | — | JSON metadata only. |
| `PATCH` | `/blobs/{id}/metadata` | JSON | Replace caller-controlled metadata in place. See body shape below. |
| `DELETE` | `/blobs/{id}` | — | `204` on success, idempotent. |
| `GET` | `/healthz` | — | Returns `ok`. |
| `GET` | `/metrics` | — | Prometheus exposition. Skips auth + rate limit. |
| `GET` | `/analytics/summary`, `/analytics/by-content-type`, `/analytics/larger-than/{bytes}`, `/analytics/by-tag?key=…&value=…` | — | See §9. |

Optional request headers on `PUT`:

- `Content-Type` — stored on the blob, returned on `GET`.
- `X-Blob-Name` — logical filename (falls back to `?name=` query param).
- `X-Blob-Owner` — owner identifier stored as the typed `owner` field.
- `X-Blob-Project` — project name stored as the typed `project` field.
- `X-Blob-Retention-Until` — ISO-8601 instant (`2027-01-01T00:00:00Z`).
- `X-Blob-Tag-<key>: <value>` — repeated header per tag. Header name after the prefix becomes the tag key.
- `X-Blob-Attributes` — a single JSON object string for the freeform `attributes` map. Use for nested data, numbers, booleans.

Body shape for `PATCH /blobs/{id}/metadata`:

```json
{
  "name":           "Q3-2026.pdf",
  "owner":          "alice@example.com",
  "project":        "finance",
  "retentionUntil": "2027-12-31T00:00:00Z",
  "tags":           { "env": "prod", "review": "pending" },
  "attributes":     { "schemaVersion": 3 }
}
```

All fields optional; missing fields are not changed on the stored document. A non-empty `tags` or `attributes` map *replaces* that map on the document; an empty map (or omitting the field) leaves it as-is.

Examples:

```bash
# Upload with custom metadata
curl -X PUT --data-binary @4gb-video.mp4 \
     -H "Content-Type: video/mp4" \
     -H "X-Blob-Name: 4gb-video.mp4" \
     -H "X-Blob-Owner: alice@example.com" \
     -H "X-Blob-Project: marketing-q2" \
     -H "X-Blob-Retention-Until: 2027-01-01T00:00:00Z" \
     -H "X-Blob-Tag-env: prod" \
     -H "X-Blob-Tag-team: marketing" \
     -H 'X-Blob-Attributes: {"schemaVersion":2,"source":{"node":"ingest-01"}}' \
     http://localhost:8484/blobs
# -> 201 { "id":"8f1e...", "size":..., "sha256":"...", "owner":"alice@example.com", ... }

# Patch metadata only (no payload re-upload)
curl -X PATCH http://localhost:8484/blobs/8f1e.../metadata \
     -H "Content-Type: application/json" \
     -d '{"owner":"bob@example.com","tags":{"env":"stage"}}'

# Download (streams; safe for huge blobs)
curl -o out.mp4 http://localhost:8484/blobs/8f1e...

# Metadata only
curl http://localhost:8484/blobs/8f1e.../metadata

# Prometheus scrape (no auth required, no rate limit)
curl http://localhost:8484/metrics | head
```

---

## 7. Operations

### 7.1 Health and readiness

The sidecar exposes `GET /healthz`. For Kubernetes:

```yaml
livenessProbe:  { httpGet: { path: /healthz, port: 8484 } }
readinessProbe: { httpGet: { path: /healthz, port: 8484 } }
```

The library version of the same check — for app embedding the library — is `BlobStore.getMetadata("__probe__").isPresent()` (returns empty, exercises Couchbase but not S3). For a deeper check, call `getMetadata` on a known sentinel id.

### 7.2 Metrics

The REST sidecar exposes two metric surfaces by default — Prometheus at `GET /metrics` and JMX MBeans in the `metrics.*` domain. Both are fed by a single Micrometer `CompositeMeterRegistry`; the library shares the same registry so library-level instrumentation and Javalin's request metrics land in the same export.

Sample series (names use Micrometer's `.` separator; Prometheus scrapes them with `_`):

| Metric | Type | Tags | What it tells you |
|---|---|---|---|
| `cbbs.put.count` | counter | `outcome={success,failure}` | Throughput of uploads, error rate |
| `cbbs.put.duration` | timer | `outcome` | Upload latency distribution |
| `cbbs.put.bytes` | distribution summary | — | Bytes ingested; size percentiles |
| `cbbs.get.count` | counter | `outcome={success,failure,not_found}` | Throughput of downloads |
| `cbbs.get.duration` | timer | `outcome` | Download latency distribution |
| `cbbs.delete.count` | counter | `outcome={success,failure,not_found}` | Delete rate |
| `cbbs.metadata.update.count` | counter | `outcome={success,failure,not_found}` | PATCH /metadata rate |
| `http.server.requests` | timer | `uri`, `method`, `status`, `exception` | Per-route Javalin metrics |
| `jvm.memory.used`, `jvm.gc.pause`, `jvm.threads.live`, ... | various | — | Standard JVM binders |

A common tag `application=cb-blob-store` is set on the composite registry, so multiple instances are easy to distinguish in Grafana.

**Prometheus scrape config** (drop into your `prometheus.yml`):

```yaml
scrape_configs:
  - job_name: cb-blob-store
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets: [ "cb-blob-store-1.example.com:8484",
                   "cb-blob-store-2.example.com:8484" ]
```

The `/metrics` endpoint is excluded from both bearer auth and the per-IP rate limit by design — a Prometheus scraper shouldn't need a credential, and shouldn't exhaust quota when it polls every 15 s. If you need to lock metrics down, front the route at the reverse proxy.

**Useful PromQL** for a starter Grafana dashboard:

```promql
# Upload throughput (req/s) split by outcome
sum by (outcome) (rate(cbbs_put_count_total[5m]))

# Upload p99 latency (success only)
histogram_quantile(0.99,
  sum by (le) (rate(cbbs_put_duration_seconds_bucket{outcome="success"}[5m])))

# Bytes ingested per second
rate(cbbs_put_bytes_sum[5m])

# 5xx rate from Javalin's per-route timer
sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# JVM heap used (MiB)
jvm_memory_used_bytes{area="heap"} / 1024 / 1024
```

**JMX** is automatically populated by `JmxMeterRegistry`. To browse from a JConsole/VisualVM/jmx-exporter setup, start the JVM with:

```bash
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9010 \
     -Dcom.sun.management.jmxremote.local.only=false \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar cb-blob-store-rest-shaded.jar
```

(Production: turn auth and SSL back on.) Metrics appear under the `metrics.*` MBean domain.

### 7.3 Capacity planning

- Couchbase RAM: each metadata doc is ~500 B–1 KB. 1 M blobs ≈ ~1 GB metadata working set.
- S3 cost: dominated by storage class + egress. Multipart uploads charge per part; the default 8 MiB part size yields 128 parts per 1 GiB file. Larger part sizes reduce request count but raise memory.
- Network: streaming `put` and `get` use a single multipart-or-direct HTTP path; no double-buffering through the JVM heap.

### 7.4 Orphan sweep (reconciliation)

Not yet bundled; the design is in `ARCHITECTURE.md §6`. Until it ships, the S3 lifecycle rule (see §2.4) takes care of the most common orphan source (aborted multipart uploads).

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `BlobStoreException: Failed to insert metadata` with `DocumentExistsException` cause | Explicit id collision | Use UUIDs (the default), or call `getMetadata` first |
| `BlobStoreException.NotFound` immediately after a successful `put` | Reading via a different Couchbase bucket/scope/collection from the writer | Reconcile config; the library doesn't auto-create buckets |
| `BlobStoreException: S3 object missing for blob ...` | Metadata exists but S3 object was deleted out-of-band | Inspect `s3Bucket`/`s3Key` in metadata; restore from S3 versioning, or `delete` the metadata to remove the dangling pointer |
| `TimeoutException` during build | Couchbase connection string unreachable, or Capella IP allowlist missing the caller | Verify with `telnet <host> 11207` (TLS port); for Capella, allowlist the egress IP |
| `software.amazon.awssdk...SdkClientException: Unable to load credentials` | `s3Credentials(...)` not set and no AWS default chain available | Set static creds, or run on an instance/profile with a role |
| Sidecar exits at startup with `Required env var: ...` | Missing env var | Check §5.2 |
| Multi-GB upload OOMs the JVM | `uploadPartSizeBytes` set too high, or wrong API used (the `put(Path, ...)` small-file path reads the file into a byte array) | Keep part size around 8–32 MiB; use `put(InputStream, ...)` for files larger than ~200 MB if memory is constrained |
| Slow first request after idle | Couchbase SDK + AWS SDK lazy connection setup | Call `getMetadata("__warmup__")` on startup |

---

## 9. Reading data via the Couchbase Analytics service

Couchbase Analytics (CBAS) is the cluster-side analytical SQL++ engine designed for aggregate queries — counting, summing, grouping, ranking — over the operational data without competing with KV traffic for resources. `cb-blob-store` integrates the Analytics service through `BlobStore.analytics()`, returning a `BlobAnalytics` handle.

### 9.1 Prerequisite: enable the Analytics service

Self-hosted, on each node that should run Analytics:

```bash
couchbase-cli node-init \
  --cluster localhost:8091 -u Administrator -p password \
  --services data,index,query,analytics
```

Capella: edit the cluster's service plan and check **Analytics**. Wait for the rebalance to complete.

### 9.2 Create the dataset

A dataset is the analytics-side view of an operational collection. The simplest form is one dataset filtered to documents where `type = 'blob'`:

```sql
CREATE DATASET blobs ON `blob_metadata`._default._default
  WHERE `type` = 'blob';

CONNECT LINK Local;
```

`CONNECT LINK Local` starts the shadow process that mirrors the operational collection into the dataset asynchronously. The shadow has eventually-consistent semantics with a typical lag of seconds.

If you use a named scope/collection, replace `_default._default` accordingly:

```sql
CREATE DATAVERSE `blob_metadata`.app;
CREATE DATASET `blob_metadata`.app.blobs
  ON `blob_metadata`.app.metadata WHERE `type` = 'blob';
CONNECT LINK Local;
```

When the dataset lives outside the default dataverse, configure the library to use the fully-qualified name:

```java
BlobStore store = BlobStore.builder()
        .analyticsDataset("`blob_metadata`.app.blobs")
        // ... other config ...
        .build();
```

The sidecar reads the same setting from `CBBS_CB_ANALYTICS_DATASET`.

### 9.3 The Analytics-service user

The Analytics service uses RBAC like everything else. Self-hosted:

```bash
couchbase-cli user-manage \
  --cluster localhost:8091 -u Administrator -p password \
  --set --rbac-username cbbs-app --rbac-password <pwd> \
  --roles "data_reader[blob_metadata],data_writer[blob_metadata],analytics_reader" \
  --auth-domain local
```

`analytics_reader` is the cluster-wide read role for the Analytics service. If you want to constrain access by dataset, use the bucket-scoped role `analytics_select[blob_metadata]` instead.

Capella: in **Database Access** add the role *Read Analytics* on the `blob_metadata` bucket.

### 9.4 Library usage

```java
try (BlobStore store = BlobStore.builder()
        .couchbaseConnectionString(...)
        .couchbaseCredentials(...)
        .s3Bucket(...)
        .build()) {

    BlobAnalytics analytics = store.analytics();

    long total = analytics.count();
    long bytes = analytics.totalBytes();

    BlobAnalytics.Summary s = analytics.summary();
    System.out.printf("count=%d total=%d avg=%d%n",
            s.count(), s.totalBytes(), s.avgBytes());

    Map<String, Long> byType = analytics.countByContentType();
    List<String> largest   = analytics.largestBlobs(50);
    List<String> tagged    = analytics.byTag("env", "prod");
    List<String> big       = analytics.largerThan(1L << 30);   // > 1 GiB
    List<String> recent    = analytics.createdBetween(
            Instant.now().minus(Duration.ofDays(7)), Instant.now());
}
```

For anything outside these helpers, use the escape hatch:

```java
AnalyticsResult r = analytics.rawQuery(
        "SELECT contentType, count(*) AS n FROM blobs "
      + "WHERE type = 'blob' AND size > 1048576 GROUP BY contentType");
for (JsonObject row : r.rowsAsObject()) {
    System.out.println(row);
}
```

The helpers parameterise caller-supplied values. The tag-key allow-list (`[A-Za-z0-9_-]+`) is the only place tag names get inlined; tag values, sizes, dates, and limits are all positional parameters.

### 9.5 REST endpoints

The sidecar exposes a small surface for ad-hoc operator queries. All endpoints require the same bearer token as the rest of the API.

| Method | Path | Returns |
|---|---|---|
| `GET` | `/analytics/summary` | `{count, totalBytes, avgBytes}` |
| `GET` | `/analytics/by-content-type` | `{ "image/png": 1240, "video/mp4": 7, ... }` |
| `GET` | `/analytics/larger-than/{bytes}` | `["id-1", "id-2", ...]` |
| `GET` | `/analytics/by-tag?key=env&value=prod` | `["id-1", "id-2", ...]` |

```bash
curl -H "Authorization: Bearer $CBBS_TOKEN" \
     http://localhost:8484/analytics/summary
# -> {"count":1240,"totalBytes":536870912000,"avgBytes":432960412}

curl -H "Authorization: Bearer $CBBS_TOKEN" \
     "http://localhost:8484/analytics/by-tag?key=env&value=prod"
# -> ["7f3b...","8f1e...",...]
```

### 9.6 Operational notes

- **Lag**: a write through `BlobStore.put()` is visible in Analytics after the shadow link catches up. Typical lag is 1–5 seconds on healthy clusters; expect more during heavy ingest. Don't read-after-write with Analytics; use `getMetadata()` (KV) for that.
- **Cost**: Analytics queries do not consume Data-service RAM or compete with KV. They do consume Analytics-node CPU and memory — provision the service tier accordingly.
- **Scaling**: for read-heavy aggregation workloads, add Analytics nodes and rebalance; the service is horizontally scalable.
- **Capella Columnar**: Capella now offers a dedicated *Columnar* service (separately licensed) for very large analytics workloads, accessed via the dedicated `couchbase-analytics-java-client`. `cb-blob-store` uses the in-cluster Analytics service (free with Couchbase Server / Capella Enterprise), which is sufficient for blob-metadata aggregates up to billions of rows. Migration to Columnar later is a configuration change, not a code rewrite.
- **Failure mode**: if the Analytics service is down or the dataset is missing, the SDK throws `CouchbaseException`. The library does not retry — failed analytics queries do not affect KV reads/writes.

---

## 10. Couchbase Eventing recipes

Couchbase Eventing runs JavaScript functions in the cluster on every mutation to a bucket. Because `cb-blob-store` writes metadata documents through the standard KV API, every put/replace/delete trips Eventing's DCP stream. The library can't run inside Eventing (Eventing is JS-only), but every metadata write is observable by an Eventing function. See ARCHITECTURE §8d for the architectural picture; this section is the operational how-to.

### 10.1 Prerequisites on the cluster

1. Enable the **Eventing** service on at least one cluster node.
2. Create a *separate* bucket dedicated to Eventing's checkpoints (e.g. `eventing_meta`).
3. Define **bucket aliases** ("bucket bindings") in the function settings — at minimum, a read-write alias to `blob_metadata` and the metadata bucket.

```bash
# Self-hosted: enable Eventing on the current node
couchbase-cli node-init --cluster localhost:8091 -u Administrator -p password \
  --node-init-data-path /opt/couchbase/var/lib/couchbase/data \
  --services data,index,query,eventing,analytics
```

### 10.2 Deploying a function

Authoring is done in the Couchbase UI or via REST. The CLI route:

```bash
# Upload function definition from a JSON file (see 10.3 for shape)
couchbase-cli eventing-function-setup \
  --cluster localhost:8091 -u Administrator -p password \
  --import --file auto-tag.json

# Deploy it
couchbase-cli eventing-function-setup \
  --cluster localhost:8091 -u Administrator -p password \
  --deploy --name auto-tag
```

### 10.3 Recipe — auto-tag on PUT

Stamp a server-side region and indexed-at timestamp on every new blob without a round trip to the application. Idempotent so re-invocation is safe.

```javascript
function OnUpdate(doc, meta) {
  if (doc.type !== "blob") return;
  if (doc.tags && doc.tags["_region"]) return;     // already tagged
  doc.tags = doc.tags || {};
  doc.tags["_region"] = "us-east-1";
  doc.tags["_indexedAt"] = (new Date()).toISOString();
  blobBucket[meta.id] = doc;                       // read-write bucket binding
}
```

### 10.4 Recipe — retention enforcement

Sweep blobs whose `retentionUntil` has passed. The Eventing function only *marks* — actual deletion goes through the library so the S3 object is removed too.

```javascript
function OnUpdate(doc, meta) {
  if (doc.type !== "blob" || !doc.retentionUntil) return;
  var due = Date.parse(doc.retentionUntil);
  if (isNaN(due) || due > Date.now()) return;
  doc.tags = doc.tags || {};
  if (doc.tags["_expired"]) return;
  doc.tags["_expired"] = "true";
  doc.tags["_expiredAt"] = (new Date()).toISOString();
  blobBucket[meta.id] = doc;
}
```

A separate scheduled job (cron-style, or `OnUpdate` reacting to the `_expired=true` mark) then calls `DELETE /blobs/{id}` on the sidecar to drop the S3 object and the metadata.

### 10.5 Recipe — fan-out webhook on delete

Notify a SIEM whenever a blob is deleted:

```javascript
function OnDelete(meta, options) {
  var res = curl("POST", "https://siem.example.com/blob-deleted", {
    headers: { "Content-Type": "application/json" },
    body: {
      id:        meta.id,
      at:        (new Date()).toISOString(),
      expired:   !!options.expired
    }
  });
  if (res.status >= 400) {
    log("siem dead-letter", meta.id, res.status);
  }
}
```

Eventing has at-least-once semantics; the webhook receiver must be idempotent (de-dup by `id` is sufficient).

### 10.6 Operational notes

- Functions run **in the cluster**; they cannot import Java code or call `BlobStore`.
- Each function needs **separate** metadata-bucket allocation. Don't share it with `blob_metadata`.
- `curl()` from a function is a real network call from the Eventing nodes — make sure they can reach the webhook host, or you'll burn CPU on retries.
- Functions can be paused/deployed/undeployed independently of the library; the library doesn't know they exist.
- For per-function metrics (executions, failures), use the Couchbase UI's Eventing page or the REST endpoints under `/api/v1/functions/{name}/stats`.

---

## 11. Capella vs self-hosted: the differences that matter

| Aspect | Capella | Self-hosted 7.6 / 8.x |
|---|---|---|
| Connection string scheme | `couchbases://` (TLS only) | `couchbase://` or `couchbases://` |
| `capella(true)` builder flag | **required** — applies `wan-development` profile (longer timeouts) | leave `false` |
| Network | App egress IP must be on Capella's allowlist | Network reachability you manage |
| RBAC | "Database Access" credentials in the Capella UI | `couchbase-cli user-manage` |
| TLS trust | Cluster cert chains to a public CA (no truststore needed) | Self-signed certs may require explicit truststore in `ClusterEnvironment` |
| Encryption at rest | Always on (Capella-managed) | 8.x: native KMIP/DARE, optional. 7.x: disk encryption at OS level. |

The library API is identical against both; only the builder inputs change.

---

## 12. Going to production: checklist

- [ ] Couchbase bucket + scope + collection provisioned with appropriate RAM/replicas
- [ ] App user has only `data_reader`+`data_writer` on the metadata bucket
- [ ] Analytics service enabled and dataset created (if `BlobAnalytics` will be used)
- [ ] S3 bucket versioning enabled
- [ ] S3 lifecycle rule `AbortIncompleteMultipartUpload` set (§2.4)
- [ ] Server-side encryption configured on the S3 bucket (SSE-S3 or SSE-KMS)
- [ ] IAM principal scoped to `s3KeyPrefix` only (not `s3:*` on the bucket)
- [ ] Capella IP allowlist updated for every prod egress IP
- [ ] Application sets `capella(true)` if connecting to Capella
- [ ] Health check wired into the platform's liveness/readiness probes
- [ ] Metrics exporter wired to your observability stack
- [ ] Backup story for Couchbase metadata bucket (object storage handles its own durability)
