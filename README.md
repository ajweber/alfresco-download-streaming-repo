# alfresco-download-streaming-repo

Headless ACS repository addon that streams node content directly from the content store, honouring HTTP `Range` and `HEAD`. It defeats the ~1 GB single-allocation download ceiling at the source and enables pause/resume of arbitrarily large files.

## Installation

Build the JAR (requires JDK 17+ and Maven 3.6+):

```sh
mvn package -DskipTests
```

Drop the produced JAR into the Alfresco repository web application's library directory and restart:

```
<tomcat>/webapps/alfresco/WEB-INF/lib/alfresco-download-streaming-repo-1.0.0.jar
```

The Alfresco module subsystem detects `module.properties` on startup and registers the webscript automatically. Minimum ACS version: **23.0**.

## Configuration

| Property | Default |
|----------|---------|
| `adf.downloadmanager.streaming.bufferBytes` | `4194304` (4 MB) |

>  Size of the reusable copy buffer, per active download. Set it in `alfresco-global.properties` or as a `-D` system property (e.g. `-Dadf.downloadmanager.streaming.bufferBytes=1048576`)

The buffer is allocated once per in-flight download and reused for the whole transfer, so peak heap for streaming is roughly `bufferBytes × concurrent downloads`, independent of file size. Tuning guidance:

- **Larger** (8 MB): fewer syscalls per download, marginally higher throughput on fast disks/links.
- **Smaller** (256 KB–1 MB): much lower heap under high concurrency. Example: 100 concurrent downloads use ~400 MB at 4 MB, but only ~25 MB at 256 KB.

Benchmark on your hardware; 4 MB is a safe general default.

### Metrics & observability

Each completed transfer logs one structured line under the SLF4J category `org.alfresco.downloadmanager.metrics` (enabled at **INFO** by default via the module `log4j2.properties`):

```
download ok node=<id> status=200|206 offset=<n> \
  requested=<bytes> sent=<bytes> durationMs=<ms> throughputMBps=<n> active=<inflight>
```

A client that disconnects mid-stream (pause/cancel → broken pipe) logs at **WARN**:

```
download aborted node=<id> status=... sent=<bytes> durationMs=... active=<inflight>
```

Fields let you localise a bottleneck quickly: `throughputMBps` (disk/network/TLS), `active` (concurrency spikes), and aborted vs ok counts. Set the category to `warn` to keep only aborts, or `off` to silence:

```
# alfresco-global.properties (or a log4j2 override)
logger.adf-downloadmanager-metrics.name=org.alfresco.downloadmanager.metrics
logger.adf-downloadmanager-metrics.level=warn
```

## Endpoint reference

```
GET  /alfresco/s/adf-download-manager/download/{nodeId}
HEAD /alfresco/s/adf-download-manager/download/{nodeId}
```

**Authentication:** standard Alfresco session cookie or `Authorization: Basic ...`.

### Query parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `attachment` | `true` | `true` → `Content-Disposition: attachment`; `false` → inline |

### Request headers

| Header | Example | Effect |
|--------|---------|--------|
| `Range` | `bytes=0-67108863` | Partial content. Single range only; multi-range and other malformed ranges are ignored (full 200), per RFC 7233. |
| `Authorization` | `Basic YWRtaW46YWRtaW4=` | Required when not using a session cookie. |

### Response codes

| Code | Meaning |
|------|---------|
| `200 OK` | Full content. Sent when no `Range` header is present, or the `Range` is malformed/multi-range (ignored). Includes `Content-Length`, `Accept-Ranges: bytes`, `ETag`. |
| `206 Partial Content` | Satisfiable ranged response. Includes `Content-Range: bytes {start}-{end}/{total}`. |
| `400 Bad Request` | Missing/unresolvable `nodeId`. |
| `403 Forbidden` | Authenticated user lacks `READ_CONTENT` on the node. |
| `404 Not Found` | Node does not exist, or has no content. |
| `416 Range Not Satisfiable` | `Range` start is at or beyond end-of-file. Includes `Content-Range: bytes */{total}` and an empty body. |

### Response headers (all successful responses)

| Header | Example value | Purpose |
|--------|---------------|---------|
| `Accept-Ranges` | `bytes` | Signals that ranged requests are supported. |
| `ETag` | `"abc123-4096-1719000000000"` | Validator built from `nodeId-size-modifiedMillis` (raw `cm:modified` epoch millis). Stable for unchanged content, changes when size or modified-time changes. Emitted identically on GET and HEAD, so download clients use it as a resume guard to detect a file that changed between pause and resume. |
| `Content-Length` | `4294967296` | Exact byte count of the response body (full or partial). |
| `Content-Type` | `application/octet-stream` | MIME type from the content store. |
| `Content-Disposition` | `attachment; filename="bigfile.zip"` | Triggers browser save dialog when `attachment=true`. |
| `Content-Range` | `bytes 0-67108863/4294967296` | On `206`: the served window + total. On `416`: `bytes */{total}`. |

## Testing with curl

Replace `ACS_HOST` with your server hostname (e.g. `localhost:8080`) and `NODE_ID` with a real node UUID.

### Find a node ID

```sh
curl -su admin:admin \
  "http://ACS_HOST/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-/children" \
  | python3 -m json.tool | grep '"id"' | head -5
```

Or upload a test file and capture the returned ID:

```sh
dd if=/dev/zero bs=1M count=100 of=/tmp/testfile.bin

NODE_ID=$(curl -su admin:admin \
  -F "filedata=@/tmp/testfile.bin;type=application/octet-stream" \
  -F "name=testfile.bin" \
  "http://ACS_HOST/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-/children" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['entry']['id'])")
```

### HEAD - verify the addon is active

```sh
curl -sI -u admin:admin \
  "http://ACS_HOST/alfresco/s/adf-download-manager/download/$NODE_ID"
```

Expected:
```
HTTP/1.1 200 OK
Content-Length: 104857600
Accept-Ranges: bytes
ETag: "<nodeId>-104857600-<hash>"
```

### Full download

```sh
curl -su admin:admin \
  "http://ACS_HOST/alfresco/s/adf-download-manager/download/$NODE_ID" \
  -o /tmp/downloaded.bin
wc -c /tmp/downloaded.bin
```

### Ranged request - first 64 MB chunk

```sh
curl -su admin:admin \
  -H "Range: bytes=0-67108863" \
  "http://ACS_HOST/alfresco/s/adf-download-manager/download/$NODE_ID" \
  -o /tmp/chunk0.bin -D -
# Expect: HTTP/1.1 206 Partial Content
# Expect: Content-Range: bytes 0-67108863/104857600
wc -c /tmp/chunk0.bin
```

### Resume simulation - second chunk

```sh
curl -su admin:admin \
  -H "Range: bytes=67108864-104857599" \
  "http://ACS_HOST/alfresco/s/adf-download-manager/download/$NODE_ID" \
  -o /tmp/chunk1.bin -D -

cat /tmp/chunk0.bin /tmp/chunk1.bin > /tmp/reassembled.bin
wc -c /tmp/reassembled.bin   # should equal original size
```

### Suffix range - last 1 MB

```sh
curl -su admin:admin \
  -H "Range: bytes=-1048576" \
  "http://ACS_HOST/alfresco/s/adf-download-manager/download/$NODE_ID" \
  -o /tmp/tail.bin -D -
# Expect: HTTP/1.1 206 Partial Content
```

## How it works

### Memory safety

The standard ACS content download allocates the entire file in the repository JVM before sending it, which causes an out-of-memory exception at around 1 GB. This addon resolves the `ContentReader` for the node and obtains the raw `InputStream` from the content store, then copies it to the HTTP response in a fixed 4 MB buffer, the JVM heap stays flat regardless of file size.

### Range and resume requests

When a `Range` header is present the addon skips to the start byte before writing. ACS file-system `ContentReader` backs `InputStream.skip()` with an O(1) `lseek`, so resuming a 4 GB download at byte 3 GB does not read-and-discard 3 GB, a tail-range request at a large offset returns with the same time-to-first-byte as an offset-0 request.

### Truncation safety

The copy verifies it transferred exactly the declared `Content-Length`. If the content-store file is shorter than the node's recorded size (corruption, partial write), the addon throws and breaks the connection rather than completing a short body, so a client never caches a truncated file as if it were complete.

### No transaction held during transfer

The web script descriptor declares `<transaction>none</transaction>`. The controller opens its own **short read-only transaction** solely to resolve the node, check `READ_CONTENT` permission, and obtain the `ContentReader` + metadata, then commits it **before** streaming the body. A multi-GB / multi-minute transfer therefore holds no repository transaction or database connection while it runs, many large downloads can proceed concurrently without exhausting the DB connection pool.

### All content stores

Every store (the default file system, S3, Azure, network-attached) uses the same path: `getContentInputStream()`, skip to the range start, and a reusable fixed-size (4 MB default) buffer copy to the response. The heap stays flat regardless of file size because the buffer is fixed and reused.

### Transfer path summary

```
Content store  →  getContentInputStream() + skip(offset) + 4 MB buffered copy  →  OutputStream  →  socket
```

The JVM heap stays flat (one reused buffer per active download) regardless of file size.

For `HEAD` requests the same handler resolves the node and sets all headers, then returns without writing a body.

## Deploying behind a reverse proxy

The addon streams multi-GB bodies with a flat JVM heap, but a proxy in front of ACS can undo that if it is left on defaults. For any proxy (nginx, Traefik, Apache, HAProxy, a cloud LB) fronting this endpoint:

- **Disable response buffering.** A buffering proxy accumulates the whole response before forwarding: reintroducing the exact multi-GB memory blowup (now in the proxy) that the addon avoids in the JVM, and delaying time-to-first-byte until the last byte is read. Stream/pass-through instead.
  - nginx: `proxy_buffering off;` (and `proxy_request_buffering off;` for the upload endpoints).
  - Traefik: use no `buffering` middleware on the route (Traefik streams by default).
  - Apache `mod_proxy`: `SetEnv proxy-sendchunked 1` and avoid `mod_cache`/`mod_deflate` on this path.
- **Do not compress binary content.** Gzip/brotli on already-compressed payloads (video, images, ZIPs, office files) wastes CPU, defeats `Range` (a compressed body has different offsets), and can strip `Content-Length`. Exclude this path from compression, or only compress by `Content-Type`.
- **Raise timeouts.** A multi-GB transfer over a slow link can run for many minutes. Bump read/write/idle timeouts well above the default 30–60 s (e.g. 1 h). nginx: `proxy_read_timeout`/`proxy_send_timeout`. Traefik: `respondingTimeouts.readTimeout`/`writeTimeout`/`idleTimeout`.
- **Preserve `Range`, `If-Range`, `Content-Range`, `Accept-Ranges`, `ETag` headers.** Some proxy configs strip or rewrite them; resume breaks if `Range` does not reach ACS or `Content-Range`/`ETag` do not reach the client.
- **Allow large content in ACS itself.** Set `system.content.maximumFileSizeLimit=0` (unlimited) or high enough for your files.

### nginx

```nginx
# Location block for the streaming download endpoint in front of ACS.
location /alfresco/s/adf-download-manager/download/ {
    proxy_pass http://acs_upstream;

    # Stream the response straight through: do not buffer the whole body.
    proxy_buffering off;
    # (Uploads/PUT to ACS go through the same server; stream requests too.)
    proxy_request_buffering off;
    proxy_http_version 1.1;

    # Long transfers: allow many minutes rather than the ~60 s default.
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;

    # Do not compress binary content: it defeats Range and strips Content-Length.
    gzip off;

    # Pass range/validator headers through untouched (nginx forwards request
    # headers by default; these are listed for clarity / in case of overrides).
    proxy_set_header Range        $http_range;
    proxy_set_header If-Range     $http_if_range;
    # Let ACS's own Content-Length drive the response (no rewriting).
    proxy_max_temp_file_size 0;
}
```

> If a global `gzip on;` is set at the `http` block, either scope it by
> `gzip_types` (excluding binary types) or turn it `off` in this `location`.
> nginx does not gzip `206 Partial Content` responses, but disabling it here
> avoids surprises on full `200` bodies too.

### Traefik

Traefik streams bodies by default (no buffering unless a `buffering` middleware
is attached), so the main things are timeouts and not adding compression:

```yaml
# Static config - generous responding timeouts for long transfers.
entryPoints:
  web:
    address: ":8080"
    transport:
      respondingTimeouts:
        readTimeout: "3600s"
        writeTimeout: "3600s"
        idleTimeout: "3600s"
```

```yaml
# Dynamic config / labels - route to ACS with NO buffering or compress middleware.
# (Simply omit `buffering` and `compress` middlewares from this router.)
labels:
  - "traefik.http.routers.acs.rule=PathPrefix(`/alfresco`)"
  - "traefik.http.services.acs.loadbalancer.server.port=8080"
```

For both proxies, start ACS with `-Dsystem.content.maximumFileSizeLimit=0` (unlimited) or a value above your largest file.

## Downloading with a generic HTTP client

The endpoint serves standard single-range `206` responses, so any range-aware client works (no custom protocol):

- **Resume an interrupted download** with `curl`:
  ```sh
  curl -C - -u admin:admin -o bigfile.bin \
    "http://ACS_HOST/alfresco/s/adf-download-manager/download/${NODE_ID}"
  ```
  `curl -C -` issues a `Range` request from the current local file size; the addon replies `206` from that offset.
- **Parallel segmented download** for higher throughput on high-latency links, with `aria2c` - it opens multiple concurrent single-range connections, which this endpoint already serves:
  ```sh
  aria2c -x 8 -s 8 --header "Authorization: Basic $(printf admin:admin | base64)" \
    "http://ACS_HOST/alfresco/s/adf-download-manager/download/${NODE_ID}"
  ```
  (`-x`/`-s` = connections/splits. This is the HTTP analogue of the parallel-stream approach for gigantic files - no multipart-range response needed; the server stays simple and each connection is one plain `Range` request.)