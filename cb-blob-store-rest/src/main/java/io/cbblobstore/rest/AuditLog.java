/*
 * AuditLog.java — structured audit logging for mutating operations
 *
 * Emits a single-line, key=value record on every successful PUT/DELETE,
 * tagged with the authenticated principal so operators can attribute changes.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records auditable events to the {@code io.cbblobstore.audit} logger. Sites that
 * need to ship these to a SIEM should route this logger separately in {@code logback.xml}.
 */
final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("io.cbblobstore.audit");

    private AuditLog() {}

    static void uploaded(String principal, String remoteAddr, String blobId, long size) {
        LOG.info("event=blob.put principal={} ip={} id={} size={}", principal, remoteAddr, blobId, size);
    }

    static void downloaded(String principal, String remoteAddr, String blobId) {
        LOG.info("event=blob.get principal={} ip={} id={}", principal, remoteAddr, blobId);
    }

    static void deleted(String principal, String remoteAddr, String blobId) {
        LOG.info("event=blob.delete principal={} ip={} id={}", principal, remoteAddr, blobId);
    }

    static void metadataUpdated(String principal, String remoteAddr, String blobId) {
        LOG.info("event=blob.metadata.update principal={} ip={} id={}", principal, remoteAddr, blobId);
    }

    static void authFailure(String remoteAddr, String reason) {
        LOG.warn("event=auth.fail ip={} reason={}", remoteAddr, reason);
    }

    static void rateLimited(String remoteAddr) {
        LOG.warn("event=rate.limit.exceeded ip={}", remoteAddr);
    }
}
