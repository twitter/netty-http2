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

import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static com.twitter.http2.HttpCodecUtil.HTTP_CONTINUATION_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_DATA_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_DEPENDENCY;
import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_WEIGHT;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_ACK;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_END_HEADERS;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_END_STREAM;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_PRIORITY;
import static com.twitter.http2.HttpCodecUtil.HTTP_FRAME_HEADER_SIZE;
import static com.twitter.http2.HttpCodecUtil.HTTP_GOAWAY_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_HEADERS_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_MAX_LENGTH;
import static com.twitter.http2.HttpCodecUtil.HTTP_PING_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_PRIORITY_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_PUSH_PROMISE_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_RST_STREAM_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_SETTINGS_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_WINDOW_UPDATE_FRAME;

/**
 * Encodes an HTTP/2 Frame into a {@link ByteBuf}.
 */
public class HttpFrameEncoder {

    /**
     * Encode an HTTP/2 DATA Frame
     */
    public ByteBuf encodeDataFrame(int streamId, boolean endStream, ByteBuf data) {
        byte flags = endStream ? HTTP_FLAG_END_STREAM : 0;
        ByteBuf header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE);
        writeFrameHeader(header, data.readableBytes(), HTTP_DATA_FRAME, flags, streamId);
        return Unpooled.wrappedBuffer(header, data);
    }

    /**
     * Encode an HTTP/2 HEADERS Frame
     */
    public ByteBuf encodeHeadersFrame(
            int streamId,
            boolean endStream,
            boolean exclusive,
            int dependency,
            int weight,
            ByteBuf headerBlock
    ) {
        byte flags = endStream ? HTTP_FLAG_END_STREAM : 0;
        boolean hasPriority = exclusive
                || dependency != HTTP_DEFAULT_DEPENDENCY || weight != HTTP_DEFAULT_WEIGHT;
        if (hasPriority) {
            flags |= HTTP_FLAG_PRIORITY;
        }
        int maxLength = hasPriority ? HTTP_MAX_LENGTH - 5 : HTTP_MAX_LENGTH;
        boolean needsContinuations = headerBlock.readableBytes() > maxLength;
        if (!needsContinuations) {
            flags |= HTTP_FLAG_END_HEADERS;
        }
        int length = needsContinuations ? maxLength : headerBlock.readableBytes();
        if (hasPriority) {
            length += 5;
        }
        int frameLength = hasPriority ? HTTP_FRAME_HEADER_SIZE + 5 : HTTP_FRAME_HEADER_SIZE;
        ByteBuf header = Unpooled.buffer(frameLength);
        writeFrameHeader(header, length, HTTP_HEADERS_FRAME, flags, streamId);
        if (hasPriority) {
            if (exclusive) {
                header.writeInt(dependency | 0x80000000);
            } else {
                header.writeInt(dependency);
            }
            header.writeByte(weight - 1);
            length -= 5;
        }
        ByteBuf frame = Unpooled.wrappedBuffer(header, headerBlock.readSlice(length));
        if (needsContinuations) {
            while (headerBlock.readableBytes() > HTTP_MAX_LENGTH) {
                header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE);
                writeFrameHeader(header, HTTP_MAX_LENGTH, HTTP_CONTINUATION_FRAME, (byte) 0, streamId);
                frame = Unpooled.wrappedBuffer(frame, header, headerBlock.readSlice(HTTP_MAX_LENGTH));
            }
            header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE);
            writeFrameHeader(
                    header,
                    headerBlock.readableBytes(),
                    HTTP_CONTINUATION_FRAME,
                    HTTP_FLAG_END_HEADERS,
                    streamId
            );
            frame = Unpooled.wrappedBuffer(frame, header, headerBlock);
        }
        return frame;
    }

    /**
     * Encode an HTTP/2 PRIORITY Frame
     */
    public ByteBuf encodePriorityFrame(int streamId, boolean exclusive, int dependency, int weight) {
        int length = 5;
        byte flags = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_PRIORITY_FRAME, flags, streamId);
        if (exclusive) {
            frame.writeInt(dependency | 0x80000000);
        } else {
            frame.writeInt(dependency);
        }
        frame.writeByte(weight - 1);
        return frame;
    }

    /**
     * Encode an HTTP/2 RST_STREAM Frame
     */
    public ByteBuf encodeRstStreamFrame(int streamId, int errorCode) {
        int length = 4;
        byte flags = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_RST_STREAM_FRAME, flags, streamId);
        frame.writeInt(errorCode);
        return frame;
    }

    /**
     * Encode an HTTP/2 SETTINGS Frame
     */
    public ByteBuf encodeSettingsFrame(HttpSettingsFrame httpSettingsFrame) {
        Set<Integer> ids = httpSettingsFrame.getIds();
        int length = ids.size() * 6;
        byte flags = httpSettingsFrame.isAck() ? HTTP_FLAG_ACK : 0;
        int streamId = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_SETTINGS_FRAME, flags, streamId);
        for (int id : ids) {
            frame.writeShort(id);
            frame.writeInt(httpSettingsFrame.getValue(id));
        }
        return frame;
    }

    /**
     * Encode an HTTP/2 PUSH_PROMISE Frame
     */
    public ByteBuf encodePushPromiseFrame(int streamId, int promisedStreamId, ByteBuf headerBlock) {
        boolean needsContinuations = headerBlock.readableBytes() > HTTP_MAX_LENGTH - 4;
        int length = needsContinuations ? HTTP_MAX_LENGTH - 4 : headerBlock.readableBytes();
        byte flags = needsContinuations ? 0 : HTTP_FLAG_END_HEADERS;
        ByteBuf header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + 4);
        writeFrameHeader(header, length + 4, HTTP_PUSH_PROMISE_FRAME, flags, streamId);
        header.writeInt(promisedStreamId);
        ByteBuf frame = Unpooled.wrappedBuffer(header, headerBlock.readSlice(length));
        if (needsContinuations) {
            while (headerBlock.readableBytes() > HTTP_MAX_LENGTH) {
                header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE);
                writeFrameHeader(header, HTTP_MAX_LENGTH, HTTP_CONTINUATION_FRAME, (byte) 0, streamId);
                frame = Unpooled.wrappedBuffer(frame, header, headerBlock.readSlice(HTTP_MAX_LENGTH));
            }
            header = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE);
            writeFrameHeader(
                    header,
                    headerBlock.readableBytes(),
                    HTTP_CONTINUATION_FRAME,
                    HTTP_FLAG_END_HEADERS,
                    streamId
            );
            frame = Unpooled.wrappedBuffer(frame, header, headerBlock);
        }
        return frame;
    }

    /**
     * Encode an HTTP/2 PING Frame
     */
    public ByteBuf encodePingFrame(long data, boolean ack) {
        int length = 8;
        byte flags = ack ? HTTP_FLAG_ACK : 0;
        int streamId = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_PING_FRAME, flags, streamId);
        frame.writeLong(data);
        return frame;
    }

    /**
     * Encode an HTTP/2 GOAWAY Frame
     */
    public ByteBuf encodeGoAwayFrame(int lastStreamId, int errorCode) {
        int length = 8;
        byte flags = 0;
        int streamId = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_GOAWAY_FRAME, flags, streamId);
        frame.writeInt(lastStreamId);
        frame.writeInt(errorCode);
        return frame;
    }

    /**
     * Encode an HTTP/2 WINDOW_UPDATE Frame
     */
    public ByteBuf encodeWindowUpdateFrame(int streamId, int windowSizeIncrement) {
        int length = 4;
        byte flags = 0;
        ByteBuf frame = Unpooled.buffer(HTTP_FRAME_HEADER_SIZE + length);
        writeFrameHeader(frame, length, HTTP_WINDOW_UPDATE_FRAME, flags, streamId);
        frame.writeInt(windowSizeIncrement);
        return frame;
    }

    private void writeFrameHeader(ByteBuf buffer, int length, int type, byte flags, int streamId) {
        buffer.writeMedium(length);
        buffer.writeByte(type);
        buffer.writeByte(flags);
        buffer.writeInt(streamId);
    }
}
