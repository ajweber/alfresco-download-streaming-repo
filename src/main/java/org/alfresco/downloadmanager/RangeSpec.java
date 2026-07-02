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

/**
 * A single, resolved HTTP byte range. Pure logic with no Alfresco dependencies
 * so it can be unit-tested in isolation.
 *
 * <p>Only a single byte range is supported (the form the ADF Download Manager
 * issues). Multipart/byteranges is intentionally out of scope.
 */
public final class RangeSpec {
    /** Inclusive first byte. */
    public final long start;
    /** Inclusive last byte. */
    public final long end;

    public RangeSpec(long start, long end) {
        this.start = start;
        this.end = end;
    }

    /** Number of bytes covered by this range (inclusive). */
    public long length() {
        return end - start + 1;
    }

    /**
     * Outcome of evaluating a {@code Range} header against a known total size.
     * Distinguishes the three cases the caller must handle differently — the
     * previous single {@code null} return conflated "no range" (serve 200) with
     * "unsatisfiable" (must be 416), which could corrupt an append-style resume
     * against changed content.
     */
    public enum Status {
        /** No usable range: header absent, malformed, multi-range, or unknown unit. Serve full 200. */
        NONE,
        /** A satisfiable single range. Serve 206 with {@link Result#range}. */
        SATISFIABLE,
        /** Syntactically valid but out of bounds (start at/after EOF). Serve 416 + {@code Content-Range: bytes *}/total}. */
        UNSATISFIABLE
    }

    /** Tri-state result of {@link #evaluate}. {@code range} is non-null only when {@link Status#SATISFIABLE}. */
    public static final class Result {
        public final Status status;
        public final RangeSpec range;

        private Result(Status status, RangeSpec range) {
            this.status = status;
            this.range = range;
        }

        static final Result NONE = new Result(Status.NONE, null);
        static final Result UNSATISFIABLE = new Result(Status.UNSATISFIABLE, null);

        static Result satisfiable(RangeSpec range) {
            return new Result(Status.SATISFIABLE, range);
        }
    }

    /**
     * Evaluates a single HTTP {@code Range} header value against a known total size,
     * returning a tri-state {@link Result}.
     *
     * <p>Per RFC 7233 a malformed, multi-range, or unknown-unit header is IGNORED
     * ({@link Status#NONE} → serve the full 200 response). A syntactically valid
     * range whose start is at or beyond EOF is {@link Status#UNSATISFIABLE}
     * ({@code 416}). Only an in-range request is {@link Status#SATISFIABLE}
     * ({@code 206}).
     *
     * @param header    the raw {@code Range} header value, e.g. {@code "bytes=0-1023"}
     * @param totalSize the full content length in bytes
     */
    public static Result evaluate(String header, long totalSize) {
        if (header == null) {
            return Result.NONE;
        }
        String value = header.trim();
        if (!value.startsWith("bytes=")) {
            return Result.NONE; // unknown unit → ignore (RFC 7233 §3.1)
        }
        value = value.substring("bytes=".length()).trim();

        // Reject multi-range ("bytes=0-1,2-3"): we only serve a single range.
        if (value.indexOf(',') >= 0) {
            return Result.NONE;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return Result.NONE;
        }
        if (totalSize <= 0) {
            // Empty representation: no range can apply. Serve the (empty) full body.
            return Result.NONE;
        }

        String startText = value.substring(0, dash).trim();
        String endText = value.substring(dash + 1).trim();

        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                // Suffix form: bytes=-N → last N bytes.
                if (endText.isEmpty()) {
                    return Result.NONE; // "bytes=-" is malformed
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return Result.NONE;
                }
                if (suffix > totalSize) {
                    suffix = totalSize;
                }
                start = totalSize - suffix;
                end = totalSize - 1;
            } else {
                start = Long.parseLong(startText);
                if (start < 0) {
                    return Result.NONE;
                }
                if (endText.isEmpty()) {
                    // Open-ended: bytes=start- → to EOF.
                    end = totalSize - 1;
                } else {
                    end = Long.parseLong(endText);
                    if (end < start) {
                        return Result.NONE; // reversed range is malformed → ignore
                    }
                    // Clamp an over-long end to the last byte.
                    if (end > totalSize - 1) {
                        end = totalSize - 1;
                    }
                }
            }

            // Start at/beyond EOF is syntactically valid but not satisfiable → 416.
            if (start > totalSize - 1) {
                return Result.UNSATISFIABLE;
            }
            return Result.satisfiable(new RangeSpec(start, end));
        } catch (NumberFormatException e) {
            return Result.NONE; // non-numeric bounds are malformed → ignore
        }
    }

    /**
     * Parses a single HTTP {@code Range} header value against a known total size.
     *
     * <p>Convenience wrapper over {@link #evaluate}: returns the resolved range
     * only when satisfiable, else {@code null} (header absent, malformed,
     * multi-range, or unsatisfiable). Callers that must distinguish unsatisfiable
     * from absent (to emit 416) should use {@link #evaluate} instead.
     *
     * @param header    the raw {@code Range} header value, e.g. {@code "bytes=0-1023"}
     * @param totalSize the full content length in bytes
     * @return the resolved, clamped range, or {@code null} if not satisfiable
     */
    public static RangeSpec parse(String header, long totalSize) {
        return evaluate(header, totalSize).range;
    }
}
