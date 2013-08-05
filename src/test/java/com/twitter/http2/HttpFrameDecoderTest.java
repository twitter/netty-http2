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
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static io.netty.util.ReferenceCountUtil.releaseLater;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_DEPENDENCY;
import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_WEIGHT;
import static com.twitter.http2.HttpCodecUtil.HTTP_FRAME_HEADER_SIZE;

public class HttpFrameDecoderTest {

    private static final Random RANDOM = new Random();

    private final HttpFrameDecoderDelegate delegate =
            Mockito.mock(HttpFrameDecoderDelegate.class);
    private HttpFrameDecoder decoder;

    @Before
    public void createHandler() {
        // Set server to false to ignore the Connection Header
        decoder = new HttpFrameDecoder(false, delegate);
    }

    @Test
    public void testClientConnectionPreface() throws Exception {
        decoder = new HttpFrameDecoder(true, delegate);
        ByteBuf connectionPreface = releaseLater(Unpooled.wrappedBuffer(new byte[]{
                0x50, 0x52, 0x49, 0x20, 0x2a, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2f, 0x32,
                0x2e, 0x30, 0x0d, 0x0a, 0x0d, 0x0a, 0x53, 0x4d, 0x0d, 0x0a, 0x0d, 0x0a
        }));
        int length = 0;
        byte flags = 0;
        int streamId = 0; // session identifier

        ByteBuf frame = settingsFrame(length, flags, streamId);
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(connectionPreface, frame)));

        verify(delegate).readSettingsFrame(false);
        verify(delegate).readSettingsEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidClientConnectionPreface() throws Exception {
        decoder = new HttpFrameDecoder(true, delegate);
        // Only write SETTINGS frame
        int length = 0;
        byte flags = 0;
        int streamId = 0; // session identifier

        ByteBuf frame = settingsFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpDataFrame() throws Exception {
        int length = RANDOM.nextInt() & 0x3FFF;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        writeRandomData(frame, length);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        for (int i = 0; i < length; i += 8192) {
            // data frames do not exceed maxChunkSize
            int off = HTTP_FRAME_HEADER_SIZE + i;
            int len = Math.min(length - i, 8192);
            inOrder.verify(delegate).readDataFrame(streamId, false, false, frame.slice(off, len));
        }
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testEmptyHttpDataFrame() throws Exception {
        int length = 0;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readDataFrame(streamId, false, false, Unpooled.EMPTY_BUFFER);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testLastHttpDataFrame() throws Exception {
        int length = 0;
        byte flags = 0x01; // END_STREAM
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readDataFrame(streamId, true, false, Unpooled.EMPTY_BUFFER);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testLastSegmentHttpDataFrame() throws Exception {
        int length = 0;
        byte flags = 0x02; // END_SEGMENT
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readDataFrame(streamId, false, true, Unpooled.EMPTY_BUFFER);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testPaddedHttpDataFrame() throws Exception {
        int length = RANDOM.nextInt() & 0x3FFF | 0x01;
        byte flags = 0x08; // PADDED
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int padding = Math.min(RANDOM.nextInt() & 0xFF, length - 1);

        ByteBuf frame = dataFrame(length, flags, streamId);
        frame.writeByte(padding);
        writeRandomData(frame, length - 1);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readDataFramePadding(streamId, false, padding + 1);
        int dataLength = length - 1 - padding;
        if (dataLength == 0) {
            inOrder.verify(delegate).readDataFrame(streamId, false, false, Unpooled.EMPTY_BUFFER);
        } else {
            for (int i = 0; i < dataLength; i += 8192) {
                // data frames do not exceed maxChunkSize
                int off = HTTP_FRAME_HEADER_SIZE + 1 + i;
                int len = Math.min(dataLength - i, 8192);
                inOrder.verify(delegate).readDataFrame(streamId, false, false, frame.slice(off, len));
            }
        }
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpDataFrameReservedBits() throws Exception {
        int length = 0;
        byte flags = (byte) 0xC4; // should ignore any unknown flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        setReservedBits(frame);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readDataFrame(streamId, false, false, Unpooled.EMPTY_BUFFER);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpDataFrameStreamId() throws Exception {
        int length = 0;
        byte flags = 0;
        int streamId = 0; // illegal stream identifier

        ByteBuf frame = dataFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpDataFrameLength() throws Exception {
        int length = 0; // illegal length
        byte flags = 0x08; // PADDED
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpDataFramePaddingLength() throws Exception {
        int length = 1;
        byte flags = 0x08; // PADDED
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = dataFrame(length, flags, streamId);
        frame.writeByte(1);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrame() throws Exception {
        int headerBlockLength = 16;
        int length = 5 + headerBlockLength;
        byte flags = 0x04 | 0x20; // END_HEADERS | PRIORITY
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf frame = headersFrame(length, flags, streamId);
        writePriorityFields(frame, exclusive, dependency, weight);
        writeRandomData(frame, headerBlockLength);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, exclusive, dependency, weight + 1);
        inOrder.verify(delegate).readHeaderBlock(
                frame.slice(HTTP_FRAME_HEADER_SIZE + 5, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testLastHttpHeadersFrame() throws Exception {
        int length = 0;
        byte flags = 0x01 | 0x04; // END_STREAM | END_HEADERS
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = headersFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, true, false, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testLastSegmentHttpHeadersFrame() throws Exception {
        int length = 0;
        byte flags = 0x02 | 0x04; // END_SEGMENT | END_HEADERS
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = headersFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, true, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameReservedBits() throws Exception {
        int length = 0;
        byte flags = (byte) 0xC4; // END_HEADERS -- should ignore any unknown flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = headersFrame(length, flags, streamId);
        setReservedBits(frame);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpHeadersFrame() throws Exception {
        int length = 0;
        byte flags = 0x04 | 0x20; // END_HEADERS | PRIORITY
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = headersFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpHeadersFrame() throws Exception {
        int length = 0;
        byte flags = 0x04; // END_HEADERS
        int streamId = 0; // illegal stream identifier

        ByteBuf frame = headersFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testContinuedHttpHeadersFrame() throws Exception {
        int headerBlockLength = 16;
        int length = 5;
        byte flags = 0x20; // PRIORITY
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writePriorityFields(headersFrame, exclusive, dependency, weight);
        ByteBuf continuationFrame =
                continuationFrame(headerBlockLength, (byte) 0x04, streamId); // END_HEADERS
        writeRandomData(continuationFrame, headerBlockLength);
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(headersFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, exclusive, dependency, weight + 1);
        inOrder.verify(delegate).readHeaderBlock(
                continuationFrame.slice(HTTP_FRAME_HEADER_SIZE, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameEmptyContinuation() throws Exception {
        int length = 16;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writeRandomData(headersFrame, length);
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0x04, streamId); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(headersFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlock(
                headersFrame.slice(HTTP_FRAME_HEADER_SIZE, length));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameMultipleContinuations() throws Exception {
        int headerBlockLength = 16;
        int length = 5 + headerBlockLength;
        byte flags = 0x01 | 0x20; // END_STREAM | PRIORITY
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writePriorityFields(headersFrame, exclusive, dependency, weight);
        writeRandomData(headersFrame, headerBlockLength);
        ByteBuf continuationFrame1 = continuationFrame(0, (byte) 0x00, streamId);
        ByteBuf continuationFrame2 = continuationFrame(0, (byte) 0x04, streamId); // END_HEADERS
        decoder.decode(releaseLater(
                Unpooled.wrappedBuffer(headersFrame, continuationFrame1, continuationFrame2)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, true, false, exclusive, dependency, weight + 1);
        inOrder.verify(delegate).readHeaderBlock(
                headersFrame.slice(HTTP_FRAME_HEADER_SIZE + 5, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameContinuationReservedFlags() throws Exception {
        int length = 16;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writeRandomData(headersFrame, length);
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0xE7, streamId); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(headersFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlock(headersFrame.slice(HTTP_FRAME_HEADER_SIZE, 16));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameContinuationIllegalStreamId() throws Exception {
        int length = 16;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writeRandomData(headersFrame, length);
        // different stream identifier
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0x04, streamId + 1); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(headersFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, false, HTTP_DEFAULT_DEPENDENCY, HTTP_DEFAULT_WEIGHT);
        inOrder.verify(delegate).readHeaderBlock(
                headersFrame.slice(HTTP_FRAME_HEADER_SIZE, 16));
        inOrder.verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpHeadersFrameMissingContinuation() throws Exception {
        int length = 5;
        byte flags = 0x20; // PRIORITY
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf headersFrame = headersFrame(length, flags, streamId);
        writePriorityFields(headersFrame, exclusive, dependency, weight);
        ByteBuf dataFrame = dataFrame(0, (byte) 0, streamId);
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(headersFrame, dataFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readHeadersFrame(
                streamId, false, false, exclusive, dependency, weight + 1);
        inOrder.verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPriorityFrame() throws Exception {
        int length = 5;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf frame = priorityFrame(length, flags, streamId);
        writePriorityFields(frame, exclusive, dependency, weight);
        decoder.decode(frame);

        verify(delegate).readPriorityFrame(streamId, exclusive, dependency, weight + 1);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPriorityFrameReservedBits() throws Exception {
        int length = 5;
        byte flags = (byte) 0xFF; // should ignore any flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf frame = priorityFrame(length, flags, streamId);
        setReservedBits(frame);
        writePriorityFields(frame, exclusive, dependency, weight);
        decoder.decode(frame);

        verify(delegate).readPriorityFrame(streamId, exclusive, dependency, weight + 1);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpPriorityFrame() throws Exception {
        int length = 8; // invalid length
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf frame = priorityFrame(length, flags, streamId);
        writePriorityFields(frame, exclusive, dependency, weight);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpPriorityFrame() throws Exception {
        int length = 5;
        byte flags = 0;
        int streamId = 0; // illegal stream identifier
        boolean exclusive = RANDOM.nextBoolean();
        int dependency = RANDOM.nextInt() & 0x7FFFFFFF;
        int weight = RANDOM.nextInt() & 0xFF;

        ByteBuf frame = priorityFrame(length, flags, streamId);
        writePriorityFields(frame, exclusive, dependency, weight);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpRstStreamFrame() throws Exception {
        int length = 4;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = rstStreamFrame(length, flags, streamId);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readRstStreamFrame(streamId, errorCode);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpRstStreamFrameReservedBits() throws Exception {
        int length = 4;
        byte flags = (byte) 0xFF; // should ignore any flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = rstStreamFrame(length, flags, streamId);
        setReservedBits(frame);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readRstStreamFrame(streamId, errorCode);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpRstStreamFrame() throws Exception {
        int length = 8; // invalid length
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = rstStreamFrame(length, flags, streamId);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpRstStreamFrame() throws Exception {
        int length = 4;
        byte flags = 0;
        int streamId = 0; // illegal stream identifier
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = rstStreamFrame(length, flags, streamId);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpSettingsFrame() throws Exception {
        int length = 6;
        byte flags = 0;
        int streamId = 0; // session identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        frame.writeShort(id);
        frame.writeInt(value);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readSettingsFrame(false);
        inOrder.verify(delegate).readSetting(id, value);
        inOrder.verify(delegate).readSettingsEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpSettingsAckFrame() throws Exception {
        int length = 0;
        byte flags = 0x01; // ACK
        int streamId = 0; // session identifier

        ByteBuf frame = settingsFrame(length, flags, streamId);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readSettingsFrame(true);
        inOrder.verify(delegate).readSettingsEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpSettingsFrameWithMultiples() throws Exception {
        int length = 12;
        byte flags = 0;
        int streamId = 0; // session identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value1 = RANDOM.nextInt();
        int value2 = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        frame.writeShort(id);
        frame.writeInt(value1);
        frame.writeShort(id);
        frame.writeInt(value2);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readSettingsFrame(false);
        inOrder.verify(delegate).readSetting(id, value1);
        inOrder.verify(delegate).readSetting(id, value2);
        inOrder.verify(delegate).readSettingsEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpSettingsFrameReservedBits() throws Exception {
        int length = 6;
        byte flags = (byte) 0xFE; // should ignore any unknown flags
        int streamId = 0; // session identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        setReservedBits(frame);
        frame.writeShort(id);
        frame.writeInt(value);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readSettingsFrame(false);
        inOrder.verify(delegate).readSetting(id, value);
        inOrder.verify(delegate).readSettingsEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpSettingsFrame() throws Exception {
        int length = 8; // invalid length
        byte flags = 0;
        int streamId = 0; // session identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        frame.writeShort(id);
        frame.writeInt(value);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpSettingsAckFrame() throws Exception {
        int length = 6; // invalid length
        byte flags = 0x01; // ACK
        int streamId = 0; // session identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        frame.writeShort(id);
        frame.writeInt(value);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpSettingsFrame() throws Exception {
        int length = 6;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01; // illegal stream identifier
        int id = RANDOM.nextInt() & 0xFFFF;
        int value = RANDOM.nextInt();

        ByteBuf frame = settingsFrame(length, flags, streamId);
        frame.writeShort(id);
        frame.writeInt(value);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpSettingsAckFrame() throws Exception {
        int length = 0;
        byte flags = 0x01; // ACK
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01; // illegal stream identifier

        ByteBuf frame = settingsFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrame() throws Exception {
        int headerBlockLength = 16;
        int length = 4 + headerBlockLength;
        byte flags = 0x04; // END_HEADERS
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = pushPromiseFrame(length, flags, streamId);
        frame.writeInt(promisedStreamId);
        writeRandomData(frame, headerBlockLength);
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                frame.slice(HTTP_FRAME_HEADER_SIZE + 4, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameReservedBits() throws Exception {
        int length = 4;
        byte flags = (byte) 0xE7; // END_HEADERS -- should ignore any unknown flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = pushPromiseFrame(length, flags, streamId);
        setReservedBits(frame);
        frame.writeInt(promisedStreamId | 0x80000000); // should ignore reserved bit
        decoder.decode(frame);

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpPushPromiseFrame() throws Exception {
        int length = 4;
        byte flags = 0x04; // END_HEADERS
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = 0; // illegal stream identifier

        ByteBuf frame = pushPromiseFrame(length, flags, streamId);
        frame.writeInt(promisedStreamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testContinuedHttpPushPromiseFrame() throws Exception {
        int headerBlockLength = 16;
        int length = 4;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        ByteBuf continuationFrame =
                continuationFrame(headerBlockLength, (byte) 0x04, streamId); // END_HEADERS
        writeRandomData(continuationFrame, headerBlockLength);
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(pushPromiseFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                continuationFrame.slice(HTTP_FRAME_HEADER_SIZE, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameEmptyContinuation() throws Exception {
        int headerBlockLength = 16;
        int length = 4 + headerBlockLength;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        writeRandomData(pushPromiseFrame, headerBlockLength);
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0x04, streamId); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(pushPromiseFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                pushPromiseFrame.slice(HTTP_FRAME_HEADER_SIZE + 4, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameMultipleContinuations() throws Exception {
        int headerBlockLength = 16;
        int length = 4 + headerBlockLength;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        writeRandomData(pushPromiseFrame, headerBlockLength);
        ByteBuf continuationFrame1 = continuationFrame(0, (byte) 0x00, streamId);
        ByteBuf continuationFrame2 = continuationFrame(0, (byte) 0x04, streamId); // END_HEADERS
        decoder.decode(releaseLater(
                Unpooled.wrappedBuffer(pushPromiseFrame, continuationFrame1, continuationFrame2)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                pushPromiseFrame.slice(HTTP_FRAME_HEADER_SIZE + 4, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameContinuationReservedFlags() throws Exception {
        int headerBlockLength = 16;
        int length = 4 + headerBlockLength;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        writeRandomData(pushPromiseFrame, headerBlockLength);
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0xE7, streamId); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(pushPromiseFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                pushPromiseFrame.slice(HTTP_FRAME_HEADER_SIZE + 4, headerBlockLength));
        inOrder.verify(delegate).readHeaderBlockEnd();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameContinuationIllegalStreamId() throws Exception {
        int headerBlockLength = 16;
        int length = 4 + headerBlockLength;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        writeRandomData(pushPromiseFrame, headerBlockLength);
        // different stream identifier
        ByteBuf continuationFrame = continuationFrame(0, (byte) 0x04, streamId + 1); // END_HEADERS
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(pushPromiseFrame, continuationFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readHeaderBlock(
                pushPromiseFrame.slice(HTTP_FRAME_HEADER_SIZE + 4, headerBlockLength));
        inOrder.verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPushPromiseFrameMissingContinuation() throws Exception {
        int length = 4;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int promisedStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf pushPromiseFrame = pushPromiseFrame(length, flags, streamId);
        pushPromiseFrame.writeInt(promisedStreamId);
        ByteBuf dataFrame = dataFrame(0, (byte) 0, streamId);
        decoder.decode(releaseLater(Unpooled.wrappedBuffer(pushPromiseFrame, dataFrame)));

        InOrder inOrder = inOrder(delegate);
        inOrder.verify(delegate).readPushPromiseFrame(streamId, promisedStreamId);
        inOrder.verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPingFrame() throws Exception {
        int length = 8;
        byte flags = 0;
        int streamId = 0; // session identifier
        long data = RANDOM.nextLong();

        ByteBuf frame = pingFrame(length, flags, streamId);
        frame.writeLong(data);
        decoder.decode(frame);

        verify(delegate).readPingFrame(data, false);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPongFrame() throws Exception {
        int length = 8;
        byte flags = 0x01; // PONG
        int streamId = 0; // session identifier
        long data = RANDOM.nextLong();

        ByteBuf frame = pingFrame(length, flags, streamId);
        frame.writeLong(data);
        decoder.decode(frame);

        verify(delegate).readPingFrame(data, true);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpPingFrameReservedBits() throws Exception {
        int length = 8;
        byte flags = (byte) 0xFE; // should ignore any unknown flags
        int streamId = 0; // session identifier
        long data = RANDOM.nextLong();

        ByteBuf frame = pingFrame(length, flags, streamId);
        frame.writeLong(data);
        decoder.decode(frame);

        verify(delegate).readPingFrame(data, false);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpPingFrame() throws Exception {
        int length = 12; // invalid length
        byte flags = 0;
        int streamId = 0; // session identifier
        long data = RANDOM.nextLong();

        ByteBuf frame = pingFrame(length, flags, streamId);
        frame.writeLong(data);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpPingFrame() throws Exception {
        int length = 8;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01; // illegal stream identifier
        long data = RANDOM.nextLong();

        ByteBuf frame = pingFrame(length, flags, streamId);
        frame.writeLong(data);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpGoAwayFrame() throws Exception {
        int length = 8;
        byte flags = 0;
        int streamId = 0; // session identifier
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = goAwayFrame(length, flags, streamId);
        frame.writeInt(lastStreamId);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readGoAwayFrame(lastStreamId, errorCode);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpGoAwayFrameReservedBits() throws Exception {
        int length = 8;
        byte flags = (byte) 0xFF; // should ignore any flags
        int streamId = 0; // session identifier
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = goAwayFrame(length, flags, streamId);
        setReservedBits(frame);
        frame.writeInt(lastStreamId | 0x80000000); // should ignore reserved bit
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readGoAwayFrame(lastStreamId, errorCode);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpGoAwayFrameWithDebugData() throws Exception {
        int debugDataLength = 1024;
        int length = 8 + debugDataLength;
        byte flags = 0;
        int streamId = 0; // session identifier
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = goAwayFrame(length, flags, streamId);
        frame.writeInt(lastStreamId);
        frame.writeInt(errorCode);
        writeRandomData(frame, debugDataLength);
        decoder.decode(frame);

        verify(delegate).readGoAwayFrame(lastStreamId, errorCode);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpGoAwayFrame() throws Exception {
        int length = 4; // invalid length
        byte flags = 0;
        int streamId = 0; // session identifier
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = goAwayFrame(length, flags, streamId);
        frame.writeInt(lastStreamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpGoAwayFrame() throws Exception {
        int length = 8;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01; // illegal stream identifier
        int lastStreamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;
        int errorCode = RANDOM.nextInt();

        ByteBuf frame = goAwayFrame(length, flags, streamId);
        frame.writeInt(lastStreamId);
        frame.writeInt(errorCode);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpWindowUpdateFrame() throws Exception {
        int length = 4;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF; // session identifier allowed
        int windowSizeIncrement = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = windowUpdateFrame(length, flags, streamId);
        frame.writeInt(windowSizeIncrement);
        decoder.decode(frame);

        verify(delegate).readWindowUpdateFrame(streamId, windowSizeIncrement);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testHttpWindowUpdateFrameReservedBits() throws Exception {
        int length = 4;
        byte flags = (byte) 0xFF; // should ignore any unknown flags
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF; // session identifier allowed
        int windowSizeIncrement = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = windowUpdateFrame(length, flags, streamId);
        setReservedBits(frame);
        frame.writeInt(windowSizeIncrement | 0x80000000); // should ignore reserved bit
        decoder.decode(frame);

        verify(delegate).readWindowUpdateFrame(streamId, windowSizeIncrement);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testInvalidHttpWindowUpdateFrame() throws Exception {
        int length = 8; // invalid length
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF; // session identifier allowed
        int windowSizeIncrement = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = windowUpdateFrame(length, flags, streamId);
        frame.writeInt(windowSizeIncrement);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalHttpWindowUpdateFrame() throws Exception {
        int length = 4;
        byte flags = 0;
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF; // session identifier allowed
        int windowSizeIncrement = 0; // illegal delta window size

        ByteBuf frame = windowUpdateFrame(length, flags, streamId);
        frame.writeInt(windowSizeIncrement);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testIllegalContinuationFrame() throws Exception {
        int length = 0;
        byte flags = 0x01 | 0x04; // END_STREAM | END_HEADERS
        int streamId = RANDOM.nextInt() & 0x7FFFFFFF | 0x01;

        ByteBuf frame = continuationFrame(length, flags, streamId);
        decoder.decode(frame);

        verify(delegate).readFrameError(anyString());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    public void testUnknownFrame() throws Exception {
        int length = 0;
        int type = 0xFF; // unknown frame
        byte flags = 0;
        int streamId = 0; // session identifier

        ByteBuf frame = frame(length, type, flags, streamId);
        decoder.decode(frame);

        verifyZeroInteractions(delegate);
    }

    private ByteBuf frame(int length, int type, byte flags, int streamId) {
        ByteBuf buffer = releaseLater(
                Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length)
        );
        buffer.writeMedium(length);
        buffer.writeByte(type);
        buffer.writeByte(flags);
        buffer.writeInt(streamId);
        return buffer;
    }

    private ByteBuf dataFrame(int length, byte flags, int streamId) {
        int type = 0x0; // DATA frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf headersFrame(int length, byte flags, int streamId) {
        int type = 0x1; // HEADERS frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf priorityFrame(int length, byte flags, int streamId) {
        int type = 0x2; // PRIORITY frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf rstStreamFrame(int length, byte flags, int streamId) {
        int type = 0x3; // RST_STREAM frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf settingsFrame(int length, byte flags, int streamId) {
        int type = 0x4; // SETTINGS frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf pushPromiseFrame(int length, byte flags, int streamId) {
        int type = 0x5; // PUSH_PROMISE frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf pingFrame(int length, byte flags, int streamId) {
        int type = 0x6; // PUSH frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf goAwayFrame(int length, byte flags, int streamId) {
        int type = 0x7; // GOAWAY frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf windowUpdateFrame(int length, byte flags, int streamId) {
        int type = 0x8; // WINDOW_UPDATE frame
        return frame(length, type, flags, streamId);
    }

    private ByteBuf continuationFrame(int length, byte flags, int streamId) {
        int type = 0x9; // CONTINUATION frame
        return frame(length, type, flags, streamId);
    }

    private void setReservedBits(ByteBuf frame) {
        frame.setInt(5, frame.getInt(5) | 0x80000000);
    }

    private void writeRandomData(ByteBuf frame, int length) {
        for (int i = 0; i < length; i++) {
            frame.writeByte(RANDOM.nextInt());
        }
    }

    private void writePriorityFields(ByteBuf frame, boolean exclusive, int dependency, int weight) {
        int dependencyWithFlag = exclusive ? dependency | 0x80000000 : dependency;
        frame.writeInt(dependencyWithFlag);
        frame.writeByte(weight);
    }
}
