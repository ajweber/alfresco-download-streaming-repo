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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Unit tests for {@link RangeSpec#parse} — the byte-range math (AC-22). */
public class RangeSpecTest {

    private static final long TOTAL = 4_000_000_000L; // 4 GB

    @Test
    public void absentHeaderIsNull() {
        assertNull(RangeSpec.parse(null, TOTAL));
    }

    @Test
    public void nonByteUnitIsNull() {
        assertNull(RangeSpec.parse("items=0-10", TOTAL));
    }

    @Test
    public void multiRangeIsRejected() {
        assertNull(RangeSpec.parse("bytes=0-10,20-30", TOTAL));
    }

    @Test
    public void closedRange() {
        RangeSpec r = RangeSpec.parse("bytes=0-67108863", TOTAL); // first 64 MB chunk
        assertEquals(0, r.start);
        assertEquals(67108863L, r.end);
        assertEquals(67108864L, r.length());
    }

    @Test
    public void openEndedRangeGoesToEof() {
        RangeSpec r = RangeSpec.parse("bytes=1000-", TOTAL);
        assertEquals(1000, r.start);
        assertEquals(TOTAL - 1, r.end);
        assertEquals(TOTAL - 1000, r.length());
    }

    @Test
    public void suffixRangeReturnsLastBytes() {
        RangeSpec r = RangeSpec.parse("bytes=-500", TOTAL);
        assertEquals(TOTAL - 500, r.start);
        assertEquals(TOTAL - 1, r.end);
        assertEquals(500, r.length());
    }

    @Test
    public void suffixLargerThanTotalClampsToWholeFile() {
        RangeSpec r = RangeSpec.parse("bytes=-99999999999", TOTAL);
        assertEquals(0, r.start);
        assertEquals(TOTAL - 1, r.end);
        assertEquals(TOTAL, r.length());
    }

    @Test
    public void endBeyondEofIsClamped() {
        RangeSpec r = RangeSpec.parse("bytes=100-99999999999", TOTAL);
        assertEquals(100, r.start);
        assertEquals(TOTAL - 1, r.end);
    }

    @Test
    public void startBeyondEofIsNotSatisfiable() {
        assertNull(RangeSpec.parse("bytes=" + TOTAL + "-", TOTAL));
    }

    @Test
    public void endBeforeStartIsRejected() {
        assertNull(RangeSpec.parse("bytes=500-100", TOTAL));
    }

    @Test
    public void zeroTotalIsNotSatisfiable() {
        assertNull(RangeSpec.parse("bytes=0-10", 0));
    }

    @Test
    public void garbageIsRejected() {
        assertNull(RangeSpec.parse("bytes=abc-def", TOTAL));
        assertNull(RangeSpec.parse("bytes=", TOTAL));
        assertNull(RangeSpec.parse("bytes=-", TOTAL));
    }

    @Test
    public void lastChunkOfLargeFileIsExact() {
        // 4,000,000,000 / 67,108,864 → last chunk starts at 3,959,422,976
        long chunk = 67_108_864L;
        long lastStart = (TOTAL / chunk) * chunk;
        RangeSpec r = RangeSpec.parse("bytes=" + lastStart + "-" + (lastStart + chunk - 1), TOTAL);
        assertEquals(lastStart, r.start);
        assertEquals(TOTAL - 1, r.end); // clamped to EOF, shorter than a full chunk
    }

    // ── F2: tri-state evaluate() — NONE (200) vs SATISFIABLE (206) vs UNSATISFIABLE (416) ──

    @Test
    public void evaluateAbsentHeaderIsNone() {
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate(null, TOTAL).status);
    }

    @Test
    public void evaluateValidRangeIsSatisfiable() {
        RangeSpec.Result res = RangeSpec.evaluate("bytes=0-99", TOTAL);
        assertSame(RangeSpec.Status.SATISFIABLE, res.status);
        assertEquals(0, res.range.start);
        assertEquals(99, res.range.end);
    }

    @Test
    public void evaluateStartAtEofIsUnsatisfiable() {
        // start == totalSize is out of bounds → 416, NOT a full 200.
        assertSame(RangeSpec.Status.UNSATISFIABLE, RangeSpec.evaluate("bytes=" + TOTAL + "-", TOTAL).status);
    }

    @Test
    public void evaluateStartBeyondEofIsUnsatisfiable() {
        assertSame(RangeSpec.Status.UNSATISFIABLE, RangeSpec.evaluate("bytes=" + (TOTAL + 1000) + "-" + (TOTAL + 2000), TOTAL).status);
    }

    @Test
    public void evaluateMalformedIsNoneNotUnsatisfiable() {
        // Malformed / reversed / multi-range / unknown-unit → IGNORE (200), per RFC 7233.
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate("bytes=abc-def", TOTAL).status);
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate("bytes=500-100", TOTAL).status);
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate("bytes=0-10,20-30", TOTAL).status);
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate("items=0-10", TOTAL).status);
    }

    @Test
    public void evaluateZeroTotalIsNone() {
        // Empty representation: nothing to range over → serve the empty full body.
        assertSame(RangeSpec.Status.NONE, RangeSpec.evaluate("bytes=0-10", 0).status);
    }

    @Test
    public void parseStillReturnsNullForUnsatisfiable() {
        // Back-compat: the convenience parse() collapses NONE and UNSATISFIABLE to null.
        assertNull(RangeSpec.parse("bytes=" + TOTAL + "-", TOTAL));
    }
}
