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

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Unit tests for the validator-ETag helpers in {@link StreamingDownloadWebScript}
 * (F5). The ETag is opaque to the client, which compares it by exact string
 * equality, so the properties under test are: stable for unchanged content,
 * different when size or modified-time changes, and robust to the property type.
 */
public class EtagTest {

    @Test
    public void formatIsQuotedNodeSizeModified() {
        assertEquals("\"abc-4096-1719000000000\"", StreamingDownloadWebScript.formatEtag("abc", 4096, 1719000000000L));
    }

    @Test
    public void stableForSameInputs() {
        String a = StreamingDownloadWebScript.formatEtag("node1", 100, 555L);
        String b = StreamingDownloadWebScript.formatEtag("node1", 100, 555L);
        assertEquals(a, b);
    }

    @Test
    public void changesWhenSizeChanges() {
        assertNotEquals(
            StreamingDownloadWebScript.formatEtag("node1", 100, 555L),
            StreamingDownloadWebScript.formatEtag("node1", 101, 555L));
    }

    @Test
    public void changesWhenModifiedChanges() {
        assertNotEquals(
            StreamingDownloadWebScript.formatEtag("node1", 100, 555L),
            StreamingDownloadWebScript.formatEtag("node1", 100, 556L));
    }

    @Test
    public void dateModifiedUsesRawMillisNotHashCode() {
        Date d = new Date(1_719_000_000_000L);
        long millis = StreamingDownloadWebScript.modifiedToMillis(d);
        assertEquals(1_719_000_000_000L, millis);
        // Regression guard: the old code used hashCode(), which for a far-future
        // Date differs from the raw millis (proves we no longer fold through it).
        assertNotEquals(d.hashCode(), millis);
    }

    @Test
    public void numericModifiedIsSupported() {
        assertEquals(1234L, StreamingDownloadWebScript.modifiedToMillis(1234L));
        assertEquals(1234L, StreamingDownloadWebScript.modifiedToMillis(Long.valueOf(1234L)));
    }

    @Test
    public void nullModifiedIsZero() {
        assertEquals(0L, StreamingDownloadWebScript.modifiedToMillis(null));
    }

    @Test
    public void unexpectedTypeIsZeroNotCrash() {
        // A non-Date, non-Number value must not throw — degrade to 0.
        assertEquals(0L, StreamingDownloadWebScript.modifiedToMillis("not-a-date"));
    }

    @Test
    public void twoDatesOneMillisApartProduceDifferentEtags() {
        // hashCode() folding could collide adjacent millis; raw millis never do.
        long m1 = StreamingDownloadWebScript.modifiedToMillis(new Date(1_719_000_000_000L));
        long m2 = StreamingDownloadWebScript.modifiedToMillis(new Date(1_719_000_000_001L));
        assertNotEquals(
            StreamingDownloadWebScript.formatEtag("n", 10, m1),
            StreamingDownloadWebScript.formatEtag("n", 10, m2));
    }
}
