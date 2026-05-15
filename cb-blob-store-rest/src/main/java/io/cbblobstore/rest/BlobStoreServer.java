/*
 * BlobStoreServer.java — Javalin HTTP front-end for the BlobStore
 *
 * Wiring (in order on every request):
 *   1. Security response headers
 *   2. Rate limit (per remote IP)
 *   3. Bearer-token authentication
 *   4. Javalin's built-in Micrometer instrumentation (route-tagged metrics)
 *   5. Route handlers (PUT/PATCH/GET/DELETE/health/metrics/analytics)
 *   6. Audit log on successful mutations
 *
 * Metrics: a single CompositeMeterRegistry feeds both PrometheusMeterRegistry
 * (scraped at GET /metrics) and JmxMeterRegistry (visible via JConsole or any
 * JMX-aware tool). The same registry is also passed to BlobStore so the
 * library's instrumentation lands in the same place.
 *
 * Optional TLS is configured by setting CBBS_TLS_KEYSTORE_PATH +
 * CBBS_TLS_KEYSTORE_PASSWORD; without those the server listens on plain HTTP
 * and is expected to live behind a TLS-terminating reverse proxy. TLS, when
 * enabled, is wired through the Jetty 11 server that Javalin 6.x embeds.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cbblobstore.core.BlobAnalytics;
import io.cbblobstore.core.BlobMetadata;
import io.cbblobstore.core.BlobOptions;
import io.cbblobstore.core.BlobStore;
import io.cbblobstore.core.BlobStoreException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.micrometer.MicrometerPlugin;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class BlobStoreServer {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreServer.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Content type Prometheus expects on a successful scrape. */
    private static final String PROM_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    public static void main(String[] args) {
        ServerConfig cfg = ServerConfig.fromEnv();
        warnIfInsecure(cfg);

        // 1. Build a CompositeMeterRegistry first, since both Javalin and the
        //    BlobStore want to write to it.
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        JmxMeterRegistry jmx = new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
        CompositeMeterRegistry metrics = new CompositeMeterRegistry();
        metrics.add(prom);
        metrics.add(jmx);
        metrics.config().commonTags("application", "cb-blob-store");

        // Bind standard JVM / system meters once at startup.
        new ClassLoaderMetrics().bindTo(metrics);
        new JvmMemoryMetrics().bindTo(metrics);
        new JvmGcMetrics().bindTo(metrics);
        new JvmThreadMetrics().bindTo(metrics);
        new UptimeMetrics().bindTo(metrics);
        new ProcessorMetrics().bindTo(metrics);

        BlobStore store = BlobStore.builder()
                .couchbaseConnectionString(cfg.couchbaseConnectionString)
                .couchbaseCredentials(cfg.couchbaseUsername, cfg.couchbasePassword)
                .couchbaseBucket(cfg.couchbaseBucket)
                .couchbaseScope(cfg.couchbaseScope)
                .couchbaseCollection(cfg.couchbaseCollection)
                .capella(cfg.capella)
                .s3Bucket(cfg.s3Bucket)
                .s3Region(cfg.s3Region)
                .s3Endpoint(cfg.s3Endpoint)
                .s3PathStyleAccess(cfg.s3PathStyle)
                .s3Credentials(cfg.s3AccessKey, cfg.s3SecretKey)
                .s3KeyPrefix(cfg.s3KeyPrefix)
                .s3ServerSideEncryption(cfg.s3Sse)
                .s3KmsKeyId(cfg.s3KmsKeyId)
                .analyticsDataset(cfg.analyticsDataset)
                .meterRegistry(metrics)
                .build();

        TokenAuthFilter auth = new TokenAuthFilter(cfg.authTokens, cfg.authDisabled);
        SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter(cfg.rateLimitPerMinute);

        MicrometerPlugin micrometerPlugin = new MicrometerPlugin(c -> {
            c.registry = metrics;
            c.tags = Tags.empty();
            c.tagExceptionName = true;
        });

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = cfg.maxUploadBytes;
            config.registerPlugin(micrometerPlugin);

            // CORS (off unless explicit origins configured).
            if (!cfg.corsAllowedOrigins.isEmpty()) {
                config.bundledPlugins.enableCors(cors -> cors.addRule(it -> {
                    for (String origin : cfg.corsAllowedOrigins) it.allowHost(origin);
                }));
            }

            // Optional TLS via embedded Jetty.
            if (cfg.tlsEnabled()) {
                config.jetty.modifyServer(server -> {
                    SslContextFactory.Server sslFactory = new SslContextFactory.Server();
                    sslFactory.setKeyStorePath(cfg.tlsKeystorePath);
                    sslFactory.setKeyStorePassword(cfg.tlsKeystorePassword);

                    HttpConfiguration httpsCfg = new HttpConfiguration();
                    httpsCfg.setSecureScheme("https");
                    httpsCfg.setSecurePort(cfg.tlsPort);
                    httpsCfg.addCustomizer(new SecureRequestCustomizer());

                    ServerConnector ssl = new ServerConnector(server,
                            new SslConnectionFactory(sslFactory, "http/1.1"),
                            new HttpConnectionFactory(httpsCfg));
                    ssl.setPort(cfg.tlsPort);
                    server.setConnectors(new org.eclipse.jetty.server.Connector[]{ssl});
                });
            }
        });

        // 1. Security response headers on every response.
        app.before(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "no-referrer");
            ctx.header("Cache-Control", "no-store");
            if (cfg.tlsEnabled()) {
                ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
        });

        // 2. Per-request access log + MDC.
        app.before(ctx -> {
            MDC.put("ip", ctx.ip());
            MDC.put("method", ctx.method().name());
            MDC.put("path", ctx.path());
            log.debug("request received");
        });
        app.after(ctx -> {
            log.debug("request complete status={}", ctx.status().getCode());
            MDC.clear();
        });

        // 3. Rate limit (per remote IP). Skip the lightweight observability endpoints.
        app.before(ctx -> {
            String p = ctx.path();
            if ("/healthz".equals(p) || "/metrics".equals(p)) return;
            String ip = ctx.ip();
            if (!rateLimiter.tryAcquire(ip)) {
                AuditLog.rateLimited(ip);
                log.warn("rate limit exceeded ip={}", ip);
                ctx.header("Retry-After", "60");
                ctx.status(HttpStatus.TOO_MANY_REQUESTS).result("rate limit exceeded");
                ctx.skipRemainingHandlers();
            }
        });

        // 4. Auth.
        app.before(auth::handle);

        // 5. Routes.
        app.get("/healthz", ctx -> ctx.result("ok"));

        // Prometheus scrape endpoint. Returns the registry's exposition format.
        app.get("/metrics", ctx ->
                ctx.contentType(PROM_CONTENT_TYPE).result(prom.scrape()));

        app.put("/blobs",      ctx -> handleUpload(ctx, store, null));
        app.put("/blobs/{id}", ctx -> handleUpload(ctx, store, ctx.pathParam("id")));

        app.get("/blobs/{id}", ctx -> {
            String id = ctx.pathParam("id");
            log.info("download id={}", id);
            BlobMetadata meta = store.getMetadata(id)
                    .orElseThrow(() -> new BlobStoreException.NotFound(id));
            ctx.contentType(meta.contentType() == null ? "application/octet-stream" : meta.contentType());
            ctx.header("Content-Length", Long.toString(meta.size()));
            ctx.header("ETag", "\"" + meta.sha256() + "\"");
            if (meta.name() != null) {
                ctx.header("Content-Disposition", "attachment; filename=\"" + meta.name() + "\"");
            }
            try (InputStream in = store.get(id)) {
                ctx.result(in);
            }
            AuditLog.downloaded(TokenAuthFilter.principalOf(ctx), ctx.ip(), id);
        });

        app.get("/blobs/{id}/metadata", ctx -> {
            String id = ctx.pathParam("id");
            log.debug("metadata get id={}", id);
            Optional<BlobMetadata> meta = store.getMetadata(id);
            if (meta.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).result("Not found: " + id);
                return;
            }
            ctx.contentType("application/json");
            ctx.result(JSON.writeValueAsString(meta.get()));
        });

        // PATCH metadata — replace caller-controlled custom fields without re-uploading.
        // Body is the JSON shape of BlobOptions: {name, owner, project, retentionUntil,
        // tags:{...}, attributes:{...}}. Missing fields are left unchanged.
        app.patch("/blobs/{id}/metadata", ctx -> {
            String id = ctx.pathParam("id");
            log.info("metadata patch id={} principal={}", id, TokenAuthFilter.principalOf(ctx));
            MetadataPatch patch;
            try {
                patch = JSON.readValue(ctx.body(), MetadataPatch.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid JSON body: " + e.getMessage());
            }
            BlobOptions.Builder b = BlobOptions.builder();
            if (patch.name != null)            b.name(patch.name);
            if (patch.owner != null)           b.owner(patch.owner);
            if (patch.project != null)         b.project(patch.project);
            if (patch.retentionUntil != null) {
                try { b.retentionUntil(Instant.parse(patch.retentionUntil)); }
                catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("retentionUntil must be ISO-8601: " + patch.retentionUntil);
                }
            }
            if (patch.tags != null)            patch.tags.forEach(b::tag);
            if (patch.attributes != null)      b.attributes(patch.attributes);
            BlobMetadata updated = store.updateMetadata(id, b.build());
            AuditLog.metadataUpdated(TokenAuthFilter.principalOf(ctx), ctx.ip(), id);
            ctx.contentType("application/json").result(JSON.writeValueAsString(updated));
        });

        app.delete("/blobs/{id}", ctx -> {
            String id = ctx.pathParam("id");
            log.info("delete id={}", id);
            store.delete(id);
            AuditLog.deleted(TokenAuthFilter.principalOf(ctx), ctx.ip(), id);
            ctx.status(HttpStatus.NO_CONTENT);
        });

        // Analytics-service endpoints. Each call goes through the configured
        // dataset and is fail-soft: if Analytics isn't enabled, the SDK throws
        // a CouchbaseException which the global handler maps to 502.
        app.get("/analytics/summary", ctx -> {
            BlobAnalytics.Summary s = store.analytics().summary();
            ctx.contentType("application/json").result(JSON.writeValueAsString(s));
        });

        app.get("/analytics/by-content-type", ctx -> {
            java.util.Map<String, Long> m = store.analytics().countByContentType();
            ctx.contentType("application/json").result(JSON.writeValueAsString(m));
        });

        app.get("/analytics/larger-than/{bytes}", ctx -> {
            long threshold;
            try {
                threshold = Long.parseLong(ctx.pathParam("bytes"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("path param 'bytes' must be a long");
            }
            java.util.List<String> ids = store.analytics().largerThan(threshold);
            ctx.contentType("application/json").result(JSON.writeValueAsString(ids));
        });

        app.get("/analytics/by-tag", ctx -> {
            String k = ctx.queryParam("key");
            String v = ctx.queryParam("value");
            if (k == null || v == null) {
                throw new IllegalArgumentException("missing 'key' or 'value' query param");
            }
            java.util.List<String> ids = store.analytics().byTag(k, v);
            ctx.contentType("application/json").result(JSON.writeValueAsString(ids));
        });

        // Exception mapping.
        app.exception(BlobStoreException.NotFound.class, (e, ctx) ->
                ctx.status(HttpStatus.NOT_FOUND).result(e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(HttpStatus.BAD_REQUEST).result(e.getMessage()));
        app.exception(BlobStoreException.class, (e, ctx) -> {
            log.warn("backend error path={} method={}", ctx.path(), ctx.method(), e);
            ctx.status(HttpStatus.BAD_GATEWAY).result("upstream backend error");
        });
        app.exception(Exception.class, (e, ctx) -> {
            log.error("unhandled error", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("internal error");
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            try { app.stop(); }    catch (Exception ignored) {}
            try { jmx.close(); }   catch (Exception ignored) {}
            try { prom.close(); }  catch (Exception ignored) {}
            try { store.close(); } catch (Exception ignored) {}
        }, "cb-blob-store-shutdown"));

        int listenPort = cfg.tlsEnabled() ? cfg.tlsPort : cfg.port;
        app.start(listenPort);
        log.info("cb-blob-store REST listening on {}://*:{}  auth={}  rateLimit={}/min  maxUpload={}B  sse={}  metrics=/metrics+JMX",
                cfg.tlsEnabled() ? "https" : "http",
                listenPort,
                cfg.authDisabled ? "DISABLED" : "bearer",
                cfg.rateLimitPerMinute,
                cfg.maxUploadBytes,
                cfg.s3Sse);
    }

    /**
     * Parse caller-supplied custom metadata from headers/query, build BlobOptions,
     * stream upload, audit, respond.
     *
     * <p>Custom metadata wire format:</p>
     * <ul>
     *   <li>{@code X-Blob-Name} — display name</li>
     *   <li>{@code X-Blob-Owner} — owner identifier</li>
     *   <li>{@code X-Blob-Project} — project name</li>
     *   <li>{@code X-Blob-Retention-Until} — ISO-8601 instant</li>
     *   <li>{@code X-Blob-Tag-<k>: <v>} — repeated header per tag</li>
     *   <li>{@code X-Blob-Attributes} — JSON object for the attributes map</li>
     * </ul>
     * Headers are chosen over a JSON envelope because the body stream is the blob
     * payload itself.
     */
    private static void handleUpload(Context ctx, BlobStore store, String explicitId) throws Exception {
        BlobOptions.Builder opts = BlobOptions.builder()
                .name(ctx.header("X-Blob-Name") != null
                        ? ctx.header("X-Blob-Name")
                        : ctx.queryParam("name"))
                .contentType(ctx.contentType() != null
                        ? ctx.contentType()
                        : "application/octet-stream");
        if (explicitId != null) opts.id(explicitId);

        // Typed top-level fields
        if (ctx.header("X-Blob-Owner") != null)   opts.owner(ctx.header("X-Blob-Owner"));
        if (ctx.header("X-Blob-Project") != null) opts.project(ctx.header("X-Blob-Project"));
        String retention = ctx.header("X-Blob-Retention-Until");
        if (retention != null) {
            try {
                opts.retentionUntil(Instant.parse(retention));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "X-Blob-Retention-Until must be ISO-8601: " + retention);
            }
        }

        // Repeated tag headers: X-Blob-Tag-<key>: <value>
        ctx.headerMap().forEach((name, value) -> {
            if (name == null || value == null) return;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String prefix = "x-blob-tag-";
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                opts.tag(name.substring(prefix.length()), value);
            }
        });

        // Attributes header: JSON object
        String attrJson = ctx.header("X-Blob-Attributes");
        if (attrJson != null && !attrJson.isBlank()) {
            try {
                java.util.Map<String, Object> m = JSON.readValue(attrJson,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                opts.attributes(m);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "X-Blob-Attributes must be a JSON object: " + e.getMessage());
            }
        }

        try (InputStream body = ctx.bodyInputStream()) {
            log.info("upload start contentType={}", ctx.contentType());
            BlobMetadata meta = store.put(body, opts.build());
            AuditLog.uploaded(TokenAuthFilter.principalOf(ctx), ctx.ip(), meta.id(), meta.size());
            ctx.status(HttpStatus.CREATED)
                    .contentType("application/json")
                    .result(JSON.writeValueAsString(meta));
        }
    }

    private static void warnIfInsecure(ServerConfig cfg) {
        if (cfg.authDisabled) {
            log.warn("AUTH IS DISABLED — anyone reaching this port can read/write/delete blobs. " +
                    "Set CBBS_AUTH_DISABLED=false and supply CBBS_AUTH_TOKENS for any non-dev use.");
        }
        if (!cfg.tlsEnabled()) {
            log.warn("TLS not configured on this listener. Front the service with an HTTPS proxy, " +
                    "or set CBBS_TLS_KEYSTORE_PATH and CBBS_TLS_KEYSTORE_PASSWORD.");
        }
    }

    /**
     * Shape of the PATCH /blobs/{id}/metadata body. All fields are optional;
     * fields left out are not changed on the stored document.
     */
    static final class MetadataPatch {
        public String name;
        public String owner;
        public String project;
        /** ISO-8601 instant string. */
        public String retentionUntil;
        public java.util.Map<String, String> tags;
        public java.util.Map<String, Object> attributes;
    }
}
