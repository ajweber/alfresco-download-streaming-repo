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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Unit tests for {@link StreamCopier} — bounded copy + offset skip. */
public class StreamCopierTest {

    private static byte[] ramp(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i & 0xFF);
        }
        return b;
    }

    @Test
    public void copiesWholeStreamWhenMaxIsNegative() throws Exception {
        byte[] src = ramp(5000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = new StreamCopier(64).copy(new ByteArrayInputStream(src), out, -1);
        assertEquals(5000, written);
        assertArrayEquals(src, out.toByteArray());
    }

    @Test
    public void copiesBoundedPrefix() throws Exception {
        byte[] src = ramp(5000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = new StreamCopier(64).copy(new ByteArrayInputStream(src), out, 1000);
        assertEquals(1000, written);
        byte[] expected = new byte[1000];
        System.arraycopy(src, 0, expected, 0, 1000);
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void skipThenCopyServesAByteRange() throws Exception {
        // Emulate Range: bytes=1000-1999
        byte[] src = ramp(5000);
        StreamCopier copier = new StreamCopier(128);
        InputStream in = new ByteArrayInputStream(src);
        assertEquals(1000, copier.skipFully(in, 1000));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = copier.copy(in, out, 1000);
        assertEquals(1000, written);
        byte[] expected = new byte[1000];
        System.arraycopy(src, 1000, expected, 0, 1000);
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void copyToEofSucceedsWhenMaxIsNegative() throws Exception {
        // Negative maxBytes means "copy to EOF" — there is no declared length,
        // so a short stream is the expected, valid outcome (used for full 200s
        // when the exact size is not asserted against a Content-Length).
        byte[] src = ramp(300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = new StreamCopier(64).copy(new ByteArrayInputStream(src), out, -1);
        assertEquals(300, written);
        assertArrayEquals(src, out.toByteArray());
    }

    @Test
    public void copyThrowsWhenStreamShorterThanRequestedLength() throws Exception {
        // F1: with a declared maxBytes, ending early means the body would be
        // truncated relative to Content-Length — must throw, not silently stop.
        byte[] src = ramp(300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new StreamCopier(64).copy(new ByteArrayInputStream(src), out, 99999);
            fail("expected EOFException on truncated copy");
        } catch (EOFException expected) {
            // good
        }
    }

    @Test
    public void skipFullyThrowsWhenStreamEndsBeforeOffset() throws Exception {
        // F1: resuming at an offset past EOF must fail rather than stream from
        // the wrong position.
        byte[] src = ramp(100);
        try {
            new StreamCopier(64).skipFully(new ByteArrayInputStream(src), 500);
            fail("expected EOFException when skipping past EOF");
        } catch (EOFException expected) {
            // good
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveBuffer() {
        new StreamCopier(0);
    }
}
