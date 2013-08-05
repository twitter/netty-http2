/*
 * Copyright 2015 Twitter, Inc.
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
package com.twitter.http2;

import io.netty.buffer.ByteBuf;

final class HttpCodecUtil {

    static final int HTTP_FRAME_HEADER_SIZE = 9;
    static final int HTTP_MAX_LENGTH = 0x4000; // Initial MAX_FRAME_SIZE value is 2^14

    static final int HTTP_DATA_FRAME = 0x00;
    static final int HTTP_HEADERS_FRAME = 0x01;
    static final int HTTP_PRIORITY_FRAME = 0x02;
    static final int HTTP_RST_STREAM_FRAME = 0x03;
    static final int HTTP_SETTINGS_FRAME = 0x04;
    static final int HTTP_PUSH_PROMISE_FRAME = 0x05;
    static final int HTTP_PING_FRAME = 0x06;
    static final int HTTP_GOAWAY_FRAME = 0x07;
    static final int HTTP_WINDOW_UPDATE_FRAME = 0x08;
    static final int HTTP_CONTINUATION_FRAME = 0x09;

    static final byte HTTP_FLAG_ACK = 0x01;
    static final byte HTTP_FLAG_END_STREAM = 0x01;
    static final byte HTTP_FLAG_END_SEGMENT = 0x02;
    static final byte HTTP_FLAG_END_HEADERS = 0x04;
    static final byte HTTP_FLAG_PADDED = 0x08;
    static final byte HTTP_FLAG_PRIORITY = 0x20;

    static final int HTTP_DEFAULT_WEIGHT = 16;
    static final int HTTP_DEFAULT_DEPENDENCY = 0;

    static final int HTTP_SETTINGS_MAX_ID = 0xFFFF; // Identifier is a 16-bit field

    static final int HTTP_SESSION_STREAM_ID = 0;

    /**
     * Reads a big-endian unsigned short integer from the buffer.
     */
    static int getUnsignedShort(ByteBuf buf, int offset) {
        return (buf.getByte(offset) & 0xFF) << 8
                | buf.getByte(offset + 1) & 0xFF;
    }

    /**
     * Reads a big-endian unsigned medium integer from the buffer.
     */
    static int getUnsignedMedium(ByteBuf buf, int offset) {
        return (buf.getByte(offset) & 0xFF) << 16
                | (buf.getByte(offset + 1) & 0xFF) << 8
                | buf.getByte(offset + 2) & 0xFF;
    }

    /**
     * Reads a big-endian (31-bit) integer from the buffer.
     */
    static int getUnsignedInt(ByteBuf buf, int offset) {
        return (buf.getByte(offset) & 0x7F) << 24
                | (buf.getByte(offset + 1) & 0xFF) << 16
                | (buf.getByte(offset + 2) & 0xFF) << 8
                | buf.getByte(offset + 3) & 0xFF;
    }

    /**
     * Reads a big-endian signed integer from the buffer.
     */
    static int getSignedInt(ByteBuf buf, int offset) {
        return (buf.getByte(offset) & 0xFF) << 24
                | (buf.getByte(offset + 1) & 0xFF) << 16
                | (buf.getByte(offset + 2) & 0xFF) << 8
                | buf.getByte(offset + 3) & 0xFF;
    }

    /**
     * Reads a big-endian signed long from the buffer.
     */
    static long getSignedLong(ByteBuf buf, int offset) {
        return ((long) buf.getByte(offset) & 0xFF) << 56
                | ((long) buf.getByte(offset + 1) & 0xFF) << 48
                | ((long) buf.getByte(offset + 2) & 0xFF) << 40
                | ((long) buf.getByte(offset + 3) & 0xFF) << 32
                | ((long) buf.getByte(offset + 4) & 0xFF) << 24
                | ((long) buf.getByte(offset + 5) & 0xFF) << 16
                | ((long) buf.getByte(offset + 6) & 0xFF) << 8
                | (long) buf.getByte(offset + 7) & 0xFF;
    }

    /**
     * Returns {@code true} if the stream identifier is for a server initiated stream.
     */
    static boolean isServerId(int streamId) {
        // Server initiated streams have even stream identifiers
        return streamId % 2 == 0;
    }

    private HttpCodecUtil() {
    }
}
