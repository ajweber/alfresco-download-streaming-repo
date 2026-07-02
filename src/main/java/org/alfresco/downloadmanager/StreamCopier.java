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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies bytes from a content {@link InputStream} to a response
 * {@link OutputStream} in a fixed-size buffer, optionally skipping to a start
 * offset and stopping after a bounded number of bytes.
 *
 * <p>This is the heart of the addon's memory story: the whole point is to NEVER
 * allocate the entire file. A single small buffer (default 1 MB) is reused for
 * the life of the copy, so the JVM heap stays flat regardless of file size —
 * which is what defeats the ~1 GB single-allocation ceiling that breaks stock
 * full-file downloads (Alfresco DevCon 2018, "Moving Gigantic Files").
 */
public final class StreamCopier {

    /** Default copy buffer (4 MB) — bounded and reused; never sized to the file. */
    public static final int DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024;

    private final int bufferBytes;

    public StreamCopier() {
        this(DEFAULT_BUFFER_BYTES);
    }

    public StreamCopier(int bufferBytes) {
        if (bufferBytes <= 0) {
            throw new IllegalArgumentException("bufferBytes must be > 0");
        }
        this.bufferBytes = bufferBytes;
    }

    /**
     * Fully skips {@code offset} bytes on {@code in}. {@link InputStream#skip}
     * may skip fewer bytes than requested, so this loops until the offset is
     * reached or the stream ends.
     *
     * <p>Throws {@link EOFException} if the stream ends before {@code offset}
     * bytes are skipped: the caller intends to serve a byte range starting at
     * {@code offset}, and a stream shorter than that means the range is invalid
     * against the declared content — better to break the connection than to
     * stream from the wrong position.
     *
     * @return the number of bytes skipped (always equals {@code offset} on success)
     */
    public long skipFully(InputStream in, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // Fall back to reading when skip() makes no progress.
                int b = in.read();
                if (b < 0) {
                    throw new EOFException(
                        "Content stream ended after skipping " + (offset - remaining) + " of " + offset
                            + " bytes; cannot serve the requested range start");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
        return offset;
    }

    /**
     * Copies {@code maxBytes} bytes from {@code in} to {@code out} using the
     * bounded reusable buffer. Pass a negative {@code maxBytes} to copy to
     * end-of-stream.
     *
     * <p>When {@code maxBytes >= 0} this throws {@link EOFException} if the stream
     * ends before {@code maxBytes} bytes are copied. The response already declared
     * {@code Content-Length: maxBytes}, so a short body would let the client cache
     * a truncated file as if complete. A negative {@code maxBytes} (copy to EOF)
     * has no expected length and never throws.
     *
     * @return the number of bytes written to {@code out}
     */
    public long copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[bufferBytes];
        long written = 0;
        while (maxBytes < 0 || written < maxBytes) {
            int want = buffer.length;
            if (maxBytes >= 0) {
                long left = maxBytes - written;
                if (left < want) {
                    want = (int) left;
                }
            }
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                if (maxBytes >= 0 && written < maxBytes) {
                    throw new EOFException(
                        "Content stream ended after " + written + " of " + maxBytes
                            + " bytes; refusing to send a truncated body");
                }
                break;
            }
            out.write(buffer, 0, read);
            written += read;
        }
        out.flush();
        return written;
    }
}
