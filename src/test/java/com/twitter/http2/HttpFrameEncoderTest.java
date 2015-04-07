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

import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import static io.netty.util.ReferenceCountUtil.releaseLater;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_DEPENDENCY;
import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_WEIGHT;
import static com.twitter.http2.HttpCodecUtil.HTTP_MAX_LENGTH;

public class HttpFrameEncoderTest {

    private static final Random RANDOM = new Random();

    private static final HttpFrameEncoder ENCODER = new HttpFrameEncoder();

    @Test
    public void testHttpDataFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf data = Unpooled.buffer(1024);
        for (int i = 0; i < 256; i++) {
            data.writeInt(RANDOM.nextInt());
        }
        ByteBuf frame = releaseLater(
                ENCODER.encodeDataFrame(streamId, false, data.duplicate())
        );
        assertDataFrame(frame, streamId, false, data);
    }

    @Test
    public void testEmptyHttpDataFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf frame = releaseLater(
                ENCODER.encodeDataFrame(streamId, false, Unpooled.EMPTY_BUFFER)
        );
        assertDataFrame(frame, streamId, false, Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testLastHttpDataFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf frame = releaseLater(
                ENCODER.encodeDataFrame(streamId, true, Unpooled.EMPTY_BUFFER)
        );
        assertDataFrame(frame, streamId, true, Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testHttpHeadersFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = (RANDOM.nextInt() & 0xFF) + 1;
        ByteBuf headerBlock = Unpooled.buffer(1024);
        for (int i = 0; i < 256; i++) {
            headerBlock.writeInt(RANDOM.nextInt());
        }
        ByteBuf frame = releaseLater(
                ENCODER.encodeHeadersFrame(
                        streamId, false, exclusive, dependency, weight, headerBlock.duplicate())
        );
        assertHeadersFrame(frame, streamId, exclusive, dependency, weight, false, headerBlock);
    }

    @Test
    public void testEmptyHttpHeadersFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = (RANDOM.nextInt() & 0xFF) + 1;
        ByteBuf frame = releaseLater(
                ENCODER.encodeHeadersFrame(
                        streamId, false, exclusive, dependency, weight, Unpooled.EMPTY_BUFFER)
        );
        assertHeadersFrame(
                frame, streamId, exclusive, dependency, weight, false, Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testLastHttpHeadersFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = false;
        int dependency = HTTP_DEFAULT_DEPENDENCY;
        int weight = HTTP_DEFAULT_WEIGHT;
        ByteBuf frame = releaseLater(
                ENCODER.encodeHeadersFrame(
                        streamId, true, exclusive, dependency, weight, Unpooled.EMPTY_BUFFER)
        );
        assertHeadersFrame(
                frame, streamId, exclusive, dependency, weight, true, Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testContinuedHttpHeadersFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = (RANDOM.nextInt() & 0xFF) + 1;
        ByteBuf headerBlock = Unpooled.buffer(2 * HTTP_MAX_LENGTH);
        for (int i = 0; i < 2 * HTTP_MAX_LENGTH; i++) {
            headerBlock.writeByte(RANDOM.nextInt());
        }
        ByteBuf frame = releaseLater(
                ENCODER.encodeHeadersFrame(
                        streamId, false, exclusive, dependency, weight, headerBlock.duplicate())
        );
        assertHeadersFrame(
                frame, streamId, exclusive, dependency, weight, false, headerBlock);
    }

    @Test
    public void testHttpPriorityFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = (RANDOM.nextInt() & 0xFF) + 1;
        ByteBuf frame = releaseLater(
                ENCODER.encodePriorityFrame(streamId, exclusive, dependency, weight)
        );
        assertPriorityFrame(frame, streamId, exclusive, dependency, weight);
    }

    @Test
    public void testHttpRstStreamFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();
        ByteBuf frame = releaseLater(
                ENCODER.encodeRstStreamFrame(streamId, errorCode)
        );
        assertRstStreamFrame(frame, streamId, errorCode);
    }

    @Test
    public void testHttpSettingsFrame() throws Exception {
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();
        HttpSettingsFrame httpSettingsFrame = new DefaultHttpSettingsFrame();
        httpSettingsFrame.setValue(id, value);
        ByteBuf frame = releaseLater(
                ENCODER.encodeSettingsFrame(httpSettingsFrame)
        );
        assertSettingsFrame(frame, false, id, value);
    }

    @Test
    public void testHttpSettingsAckFrame() throws Exception {
        HttpSettingsFrame httpSettingsFrame = new DefaultHttpSettingsFrame();
        httpSettingsFrame.setAck(true);
        ByteBuf frame = releaseLater(
                ENCODER.encodeSettingsFrame(httpSettingsFrame)
        );
        assertSettingsFrame(frame, true, 0, 0);
    }

    @Test
    public void testHttpPushPromiseFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf headerBlock = Unpooled.buffer(1024);
        for (int i = 0; i < 256; i++) {
            headerBlock.writeInt(RANDOM.nextInt());
        }
        ByteBuf frame = releaseLater(
                ENCODER.encodePushPromiseFrame(streamId, promisedStreamId, headerBlock.duplicate())
        );
        assertPushPromiseFrame(frame, streamId, promisedStreamId, headerBlock);
    }

    @Test
    public void testEmptyHttpPushPromiseFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf frame = releaseLater(
                ENCODER.encodePushPromiseFrame(streamId, promisedStreamId, Unpooled.EMPTY_BUFFER)
        );
        assertPushPromiseFrame(frame, streamId, promisedStreamId, Unpooled.EMPTY_BUFFER);
    }

    @Test
    public void testContinuedHttpPushPromiseFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf headerBlock = Unpooled.buffer(2 * HTTP_MAX_LENGTH);
        for (int i = 0; i < 2 * HTTP_MAX_LENGTH; i++) {
            headerBlock.writeByte(RANDOM.nextInt());
        }
        ByteBuf frame = releaseLater(
                ENCODER.encodePushPromiseFrame(streamId, promisedStreamId, headerBlock.duplicate())
        );
        assertPushPromiseFrame(frame, streamId, promisedStreamId, headerBlock);
    }

    @Test
    public void testHttpPingFrame() throws Exception {
        long data = RANDOM.nextLong();
        ByteBuf frame = releaseLater(
                ENCODER.encodePingFrame(data, false)
        );
        assertPingFrame(frame, false, data);
    }

    @Test
    public void testHttpPongFrame() throws Exception {
        long data = RANDOM.nextLong();
        ByteBuf frame = releaseLater(
                ENCODER.encodePingFrame(data, true)
        );
        assertPingFrame(frame, true, data);
    }

    @Test
    public void testHttpGoAwayFrame() throws Exception {
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();
        ByteBuf frame = releaseLater(
                ENCODER.encodeGoAwayFrame(lastStreamId, errorCode)
        );
        assertGoAwayFrame(frame, lastStreamId, errorCode);
    }

    @Test
    public void testHttpWindowUpdateFrame() throws Exception {
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF; // connection identifier allowed
        int windowSizeIncrement = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        ByteBuf frame = releaseLater(
                ENCODER.encodeWindowUpdateFrame(streamId, windowSizeIncrement)
        );
        assertWindowUpdateFrame(frame, streamId, windowSizeIncrement);
    }

    private static void assertDataFrame(ByteBuf frame, int streamId, boolean last, ByteBuf data) {
        byte type = 0x00;
        byte flags = 0x00;
        if (last) {
            flags |= 0x01;
        }
        int length = assertFrameHeader(frame, type, flags, streamId);
        assertEquals(data.readableBytes(), length);
        for (int i = 0; i < length; i++) {
            assertEquals(data.getByte(i), frame.readByte());
        }
        assertFalse(frame.isReadable());
    }

    private static void assertHeadersFrame(
            ByteBuf frame,
            int streamId,
            boolean exclusive,
            int dependency,
            int weight,
            boolean last,
            ByteBuf headerBlock
    ) {
        boolean hasPriority =
                exclusive || dependency != HTTP_DEFAULT_DEPENDENCY || weight != HTTP_DEFAULT_WEIGHT;
        int maxLength = hasPriority ? HTTP_MAX_LENGTH - 5 : HTTP_MAX_LENGTH;
        byte type = 0x01;
        byte flags = 0x00;
        if (last) {
            flags |= 0x01;
        }
        if (headerBlock.readableBytes() <= maxLength) {
            flags |= 0x04;
        }
        if (hasPriority) {
            flags |= 0x20;
        }
        int length = assertFrameHeader(frame, type, flags, streamId);
        if (hasPriority) {
            assertTrue(length >= 5);
            if (exclusive) {
                assertEquals(dependency | 0x80000000, frame.readInt());
            } else {
                assertEquals(dependency, frame.readInt());
            }
            assertEquals(weight - 1, frame.readUnsignedByte());
            length -= 5;
        }
        assertTrue(length <= headerBlock.readableBytes());
        for (int i = 0; i < length; i++) {
            assertEquals(headerBlock.readByte(), frame.readByte());
        }
        while (headerBlock.isReadable()) {
            type = 0x09;
            flags = 0x00;
            if (headerBlock.readableBytes() <= HTTP_MAX_LENGTH) {
                flags |= 0x04;
            }
            length = assertFrameHeader(frame, type, flags, streamId);
            assertTrue(length <= headerBlock.readableBytes());
            for (int i = 0; i < length; i++) {
                assertEquals(headerBlock.readByte(), frame.readByte());
            }
        }
        assertFalse(frame.isReadable());
    }

    private static void assertPriorityFrame(
            ByteBuf frame, int streamId, boolean exclusive, int dependency, int weight
    ) {
        byte type = 0x02;
        byte flags = 0x00;
        assertEquals(5, assertFrameHeader(frame, type, flags, streamId));
        if (exclusive) {
            assertEquals(dependency | 0x80000000, frame.readInt());
        } else {
            assertEquals(dependency, frame.readInt());
        }
        assertEquals(weight - 1, frame.readUnsignedByte());
        assertFalse(frame.isReadable());
    }

    private static void assertRstStreamFrame(ByteBuf frame, int streamId, int errorCode) {
        byte type = 0x03;
        byte flags = 0x00;
        assertEquals(4, assertFrameHeader(frame, type, flags, streamId));
        assertEquals(errorCode, frame.readInt());
        assertFalse(frame.isReadable());
    }

    private static void assertSettingsFrame(ByteBuf frame, boolean ack, int id, int value) {
        byte type = 0x04;
        byte flags = ack ? (byte) 0x01 : 0x00;
        int length = assertFrameHeader(frame, type, flags, 0);
        if (ack) {
            assertEquals(0, length);
        } else {
            assertEquals(6, length);
            assertEquals(id, frame.readUnsignedShort());
            assertEquals(value, frame.readInt());
        }
        assertFalse(frame.isReadable());
    }

    private static void assertPushPromiseFrame(
            ByteBuf frame, int streamId, int promisedStreamId, ByteBuf headerBlock) {
        int maxLength = HTTP_MAX_LENGTH - 4;
        byte type = 0x05;
        byte flags = 0x00;
        if (headerBlock.readableBytes() <= maxLength) {
            flags |= 0x04;
        }
        int length = assertFrameHeader(frame, type, flags, streamId);
        assertTrue(length >= 4);
        assertEquals(promisedStreamId, frame.readInt());
        length -= 4;
        assertTrue(length <= headerBlock.readableBytes());
        for (int i = 0; i < length; i++) {
            assertEquals(headerBlock.readByte(), frame.readByte());
        }
        while (headerBlock.isReadable()) {
            type = 0x09;
            flags = 0x00;
            if (headerBlock.readableBytes() <= HTTP_MAX_LENGTH) {
                flags |= 0x04;
            }
            length = assertFrameHeader(frame, type, flags, streamId);
            assertTrue(length <= headerBlock.readableBytes());
            for (int i = 0; i < length; i++) {
                assertEquals(headerBlock.readByte(), frame.readByte());
            }
        }
        assertFalse(frame.isReadable());
    }

    private static void assertPingFrame(ByteBuf frame, boolean pong, long data) {
        byte type = 0x06;
        byte flags = 0x00;
        if (pong) {
            flags |= 0x01;
        }
        assertEquals(8, assertFrameHeader(frame, type, flags, 0));
        assertEquals(data, frame.readLong());
        assertFalse(frame.isReadable());
    }

    private static void assertGoAwayFrame(ByteBuf frame, int lastStreamId, int errorCode) {
        byte type = 0x07;
        byte flags = 0x00;
        assertEquals(8, assertFrameHeader(frame, type, flags, 0));
        assertEquals(lastStreamId, frame.readInt());
        assertEquals(errorCode, frame.readInt());
        assertFalse(frame.isReadable());
    }

    private static void assertWindowUpdateFrame(
            ByteBuf frame, int streamId, int windowSizeIncrement
    ) {
        byte type = 0x08;
        byte flags = 0x00;
        assertEquals(4, assertFrameHeader(frame, type, flags, streamId));
        assertEquals(windowSizeIncrement, frame.readInt());
        assertFalse(frame.isReadable());
    }

    // Verifies the type, flag, and streamId in the frame header and returns the length.
    private static int assertFrameHeader(ByteBuf frame, byte type, byte flags, int streamId) {
        int length = frame.readUnsignedMedium();
        assertEquals(type, frame.readByte());
        assertEquals(flags, frame.readByte());
        assertEquals(streamId, frame.readInt());
        return length;
    }
}
