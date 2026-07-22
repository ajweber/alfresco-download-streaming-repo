/*
 * Copyright 2026 Hyland Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.alfresco.downloadmanager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptException;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;
import org.springframework.extensions.webscripts.servlet.WebScriptServletRequest;
import org.springframework.extensions.webscripts.servlet.WebScriptServletResponse;
import org.springframework.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Streams node content straight from the content store, honouring HTTP
 * {@code Range}, so files of any size download without the repository ever
 * allocating the whole file in memory.
 *
 * <p>A stock full-file download allocates the entire content and fails at ~1 GB.
 * Here the body is copied through a small bounded buffer ({@link StreamCopier}),
 * so the JVM heap stays flat at any file size.
 *
 * <p>Bound to {@code GET|HEAD /alfresco/s/adf-download-manager/download/{nodeId}}.
 * Supports {@code ?attachment=true|false}.
 */
public class StreamingDownloadWebScript extends AbstractWebScript {

    /**
     * Per-transfer metrics logger. Enable observability without a metrics backend
     * by raising this category to INFO (or DEBUG) in log4j2, e.g.
     * {@code log4j.logger.org.alfresco.downloadmanager.metrics=INFO}. Each
     * completed transfer logs bytes/duration/throughput/path/status; client
     * aborts (broken pipe) log at WARN.
     */
    private static final Logger METRICS = LoggerFactory.getLogger("org.alfresco.downloadmanager.metrics");

    /** In-flight download gauge, logged with each transfer so spikes are visible. */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private NodeService nodeService;
    private ContentService contentService;
    private PermissionService permissionService;
    private TransactionService transactionService;

    // Default to the built-in 4 MB buffer; Spring overrides both with a copier
    // configured from adf.downloadmanager.streaming.bufferBytes (see
    // webscript-context.xml). The copier is stateless — the buffer is allocated
    // per call — so a single instance is safely shared across requests.
    private StreamCopier streamCopier = new StreamCopier();

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public void setTransactionService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public void setStreamCopier(StreamCopier streamCopier) {
        this.streamCopier = streamCopier;
    }

    /**
     * Everything resolved from the repository up front, so the (potentially very
     * long) body transfer can happen with NO repository transaction / DB
     * connection held. The {@link ContentReader} is resolved inside the txn but
     * used for streaming outside it — safe because a reader opens its backing
     * content lazily on {@code getContentInputStream()} and does not depend on an
     * active transaction (true for the file-system content store).
     */
    private static final class ResolvedContent {
        final ContentReader reader;
        final long totalSize;
        final String mimetype;
        final String fileName;
        final String etag;
        final long lastModified;

        ResolvedContent(ContentReader reader, long totalSize, String mimetype, String fileName, String etag, long lastModified) {
            this.reader = reader;
            this.totalSize = totalSize;
            this.mimetype = mimetype;
            this.fileName = fileName;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) throws IOException {
        // ── Resolve the target node ────────────────────────────────────────
        // The path carries a bare node UUID (parity with the stock content API);
        // the store defaults to workspace://SpacesStore.
        String nodeId = req.getServiceMatch().getTemplateVars().get("nodeId");
        if (nodeId == null || nodeId.isEmpty()) {
            throw new WebScriptException(Status.STATUS_BAD_REQUEST, "Missing nodeId");
        }
        final NodeRef nodeRef = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, nodeId);

        // ── Resolve metadata + reader inside a SHORT read-only transaction ──
        // The descriptor declares <transaction>none</transaction>, so nothing
        // wraps execute(). We open a brief read-only txn only for the repository
        // reads (existence, permission, reader, properties) and release it before
        // streaming the body — a multi-GB transfer must not pin a DB connection.
        final ResolvedContent content = resolveContent(nodeRef);

        long totalSize = content.totalSize;
        String mimetype = content.mimetype;
        String fileName = content.fileName;
        String etag = content.etag;
        ContentReader reader = content.reader;
        long nodeModDate = content.lastModified;

        boolean attachment = !"false".equalsIgnoreCase(req.getParameter("attachment"));
        boolean head = isHeadRequest(req);

        // ── Common headers ─────────────────────────────────────────────────
        res.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        res.setHeader( HttpHeaders.ETAG, etag);
        res.setContentType(mimetype);
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            (attachment ? "attachment" : "inline") + "; filename=\"" + sanitizeFileName(fileName) + "\"");

        if (nodeModDate > 0L)
        {
            String nodeModTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(nodeModDate).atZone(ZoneOffset.UTC));
            res.setHeader(HttpHeaders.LAST_MODIFIED, nodeModTime);
        }

        HttpServletResponse servletRes = unwrap(res);

        //if-modified-since handling
        String ifModifiedSinceReq = req.getHeader(HttpHeaders.IF_MODIFIED_SINCE);
        if (StringUtils.isNotBlank(ifModifiedSinceReq))
        {
            long reqModDate = 0L;

            try {
                reqModDate = ZonedDateTime.parse(ifModifiedSinceReq, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli();
            }
            catch (DateTimeParseException ignored)
            {
                //ERROR: Could not convert requested If-Modified-Since string to date - default to returning content
            }

            if (nodeModDate < reqModDate)
            {
                if (servletRes != null) {
                    servletRes.setStatus(Status.STATUS_NOT_MODIFIED); // Jakarta's Status has no named constant
                } else {
                    res.setStatus(Status.STATUS_NOT_MODIFIED);
                }
                setContentLength(res, servletRes, 0);
                return;
            }
        }

        // ── Range handling ─────────────────────────────────────────────────
        // Tri-state: NONE → 200, SATISFIABLE → 206, UNSATISFIABLE → 416. This
        // distinction matters for resume clients: answering an out-of-bounds
        // range with a full 200 can corrupt an append-style resume.
        RangeSpec.Result rangeResult = RangeSpec.evaluate(req.getHeader("Range"), totalSize);

        if (rangeResult.status == RangeSpec.Status.UNSATISFIABLE) {
            // 416 Range Not Satisfiable + Content-Range: bytes */{total}, no body.
            if (servletRes != null) {
                servletRes.setStatus(416); // Jakarta's Status has no named constant
            } else {
                res.setStatus(416);
            }
            res.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize);
            setContentLength(res, servletRes, 0);
            return;
        }

        if (rangeResult.status == RangeSpec.Status.NONE) {
            // Full content (200). Still streamed — never buffered whole.
            setContentLength(res, servletRes, totalSize);
            if (head) {
                return;
            }
            streamWithMetrics(reader, res, 0, totalSize, nodeId, 200);
        } else {
            // Partial content (206).
            RangeSpec range = rangeResult.range;
            if (servletRes != null) {
                servletRes.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            } else {
                res.setStatus(Status.STATUS_PARTIAL_CONTENT);
            }
            res.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.start + "-" + range.end + "/" + totalSize);
            setContentLength(res, servletRes, range.length());
            if (head) {
                return;
            }
            streamWithMetrics(reader, res, range.start, range.length(), nodeId, 206);
        }
    }

    /**
     * Wraps {@link #writeBody} with timing, an in-flight gauge, and structured
     * per-transfer metrics. One INFO line is emitted on success; a client abort
     * mid-stream (broken pipe — the client cancelled/paused) is logged at WARN
     * and rethrown. Nothing here changes the bytes on the wire.
     */
    private void streamWithMetrics(ContentReader reader, WebScriptResponse res, long offset, long length,
                                   String nodeId, int status) throws IOException {
        long startNanos = System.nanoTime();
        int active = ACTIVE.incrementAndGet();
        long sent = 0L;
        try {
            sent = writeBody(reader, res, offset, length);
        } catch (IOException e) {
            // A write failure to the response is almost always the client going
            // away (broken pipe / connection reset), i.e. a pause or cancel.
            logTransfer(nodeId, status, offset, length, sent, startNanos, active, true);
            throw e;
        } finally {
            ACTIVE.decrementAndGet();
        }
        logTransfer(nodeId, status, offset, length, sent, startNanos, active, false);
    }

    private void logTransfer(String nodeId, int status, long offset, long length, long sent,
                             long startNanos, int active, boolean aborted) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        double mbps = ms > 0 ? (sent / 1_048_576.0) / (ms / 1000.0) : 0.0;
        if (aborted) {
            // WARN: partial transfer, client went away. Keep it single-line.
            METRICS.warn("download aborted node={} status={} offset={} requested={} sent={} durationMs={} throughputMBps={} active={}",
                nodeId, status, offset, length, sent, ms, String.format("%.1f", mbps), active);
        } else if (METRICS.isInfoEnabled()) {
            METRICS.info("download ok node={} status={} offset={} requested={} sent={} durationMs={} throughputMBps={} active={}",
                nodeId, status, offset, length, sent, ms, String.format("%.1f", mbps), active);
        }
    }

    /**
     * Resolves node existence, read permission, the content reader and its
     * metadata inside a single short read-only transaction. All values are
     * copied into a {@link ResolvedContent} holder so the caller can stream the
     * body afterwards with no transaction held.
     *
     * <p>{@link WebScriptException}s (404/403) are thrown straight through the
     * transaction callback so the framework maps them to the right HTTP status;
     * they are not retryable and must not be wrapped.
     */
    private ResolvedContent resolveContent(final NodeRef nodeRef) {
        final RetryingTransactionCallback<ResolvedContent> callback = () -> {
            if (!nodeService.exists(nodeRef)) {
                throw new WebScriptException(Status.STATUS_NOT_FOUND, "Node not found: " + nodeRef);
            }
            // Permission check → 403.
            if (permissionService.hasPermission(nodeRef, PermissionService.READ_CONTENT) != AccessStatus.ALLOWED) {
                throw new WebScriptException(Status.STATUS_FORBIDDEN, "No read permission for: " + nodeRef);
            }
            ContentReader r = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
            if (r == null || !r.exists()) {
                throw new WebScriptException(Status.STATUS_NOT_FOUND, "No content for: " + nodeRef);
            }
            String mime = r.getMimetype();
            if (mime == null) {
                mime = "application/octet-stream";
            }

            //Can we not trust r.lastModified here for content mod date; isn't that all we care about??
            Object modified = nodeService.getProperty(nodeRef, ContentModel.PROP_MODIFIED);
            long lastMod = modifiedToMillis(modified);

            return new ResolvedContent(r, r.getSize(), mime, resolveFileName(nodeRef), buildEtag(nodeRef, r, lastMod), lastMod);
        };
        // readOnly = true, requiresNew = false (join any ambient txn if present).
        return transactionService.getRetryingTransactionHelper().doInTransaction(callback, true, false);
    }

    /**
     * Streams {@code length} bytes starting at {@code offset} from the content
     * reader to the response, through a single bounded reused buffer.
     *
     * <p>Content is read via {@code getContentInputStream()} and skipped to the
     * range start with {@link StreamCopier#skipFully}. We deliberately do NOT
     * special-case the file-system store with a {@code FileChannel} fast path:
     * benchmarking on ACS 26.1 showed the channel path (O(1) {@code position()}
     * seek + channel copy) is within noise of this buffered path (~168 MB/s for
     * a 150 MB file either way). ACS's reader already backs {@code skip()} with
     * an O(1) {@code lseek}, and neither path is true {@code sendfile} to a
     * servlet output stream — so the extra class, internal-API coupling and
     * branch bought nothing.
     *
     * @return bytes written to the response
     */
    private long writeBody(ContentReader reader, WebScriptResponse res, long offset, long length)
            throws IOException {
        InputStream in = reader.getContentInputStream();
        try {
            OutputStream out = res.getOutputStream();
            if (offset > 0) {
                streamCopier.skipFully(in, offset);
            }
            return streamCopier.copy(in, out, length);
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // best-effort close
            }
        }
    }

    private String resolveFileName(NodeRef nodeRef) {
        Object name = nodeService.getProperty(nodeRef, ContentModel.PROP_NAME);
        return name != null ? name.toString() : nodeRef.getId();
    }

    /**
     * Validator ETag derived from the node id, content size, and last-modified
     * time, so the frontend's resume guard can detect a changed file between
     * pause and resume. The value is opaque to the client (it compares by exact
     * string equality), so only two properties matter: it must be STABLE for
     * unchanged content and DIFFERENT when the content changes.
     *
     * <p>Uses raw epoch millis rather than {@code Object.hashCode()} — the old
     * approach folded the timestamp through a lossy 32-bit hash (needless
     * collision surface) and assumed {@code cm:modified} was always a {@link Date}.
     */
    private String buildEtag(NodeRef nodeRef, ContentReader reader, long modified) {
        //Object modified = nodeService.getProperty(nodeRef, ContentModel.PROP_MODIFIED);
        return formatEtag(nodeRef.getId(), reader.getSize(), modified);
    }

    /**
     * Normalises a {@code cm:modified} property value to epoch millis. Handles
     * {@link Date} (the usual type), any {@link Number} (defensive), and null.
     */
    static long modifiedToMillis(Object modified) {
        if (modified instanceof Date) {
            return ((Date) modified).getTime();
        }
        if (modified instanceof Number) {
            return ((Number) modified).longValue();
        }
        return 0L;
    }

    /** Formats the validator ETag: a quoted {@code "nodeId-size-modifiedMillis"}. */
    static String formatEtag(String nodeId, long size, long modifiedMillis) {
        return "\"" + nodeId + "-" + size + "-" + modifiedMillis + "\"";
    }

    private static String sanitizeFileName(String name) {
        return name.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private static void setContentLength(WebScriptResponse res, HttpServletResponse servletRes, long length) {
        if (servletRes != null) {
            servletRes.setHeader("Content-Length", Long.toString(length));
        } else {
            res.setHeader("Content-Length", Long.toString(length));
        }
    }

    private static boolean isHeadRequest(WebScriptRequest req) {
        // The webscript descriptor declares GET; the servlet container routes
        // HEAD to the same handler. Read the real method off the servlet request
        // so HEAD short-circuits the body write (headers only).
        if (req instanceof WebScriptServletRequest) {
            String method = ((WebScriptServletRequest) req).getHttpServletRequest().getMethod();
            return "HEAD".equalsIgnoreCase(method);
        }
        return false;
    }

    private static HttpServletResponse unwrap(WebScriptResponse res) {
        if (res instanceof WebScriptServletResponse) {
            return ((WebScriptServletResponse) res).getHttpServletResponse();
        }
        return null;
    }
}
