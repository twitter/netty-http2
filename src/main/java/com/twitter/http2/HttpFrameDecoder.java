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

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;

import static com.twitter.http2.HttpCodecUtil.HTTP_CONTINUATION_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_DATA_FRAME;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_ACK;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_END_HEADERS;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_END_SEGMENT;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_END_STREAM;
import static com.twitter.http2.HttpCodecUtil.HTTP_FLAG_PADDED;
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
import static com.twitter.http2.HttpCodecUtil.getSignedInt;
import static com.twitter.http2.HttpCodecUtil.getSignedLong;
import static com.twitter.http2.HttpCodecUtil.getUnsignedInt;
import static com.twitter.http2.HttpCodecUtil.getUnsignedMedium;
import static com.twitter.http2.HttpCodecUtil.getUnsignedShort;

/**
 * Decodes {@link ByteBuf}s into HTTP/2 Frames.
 */
public class HttpFrameDecoder {

    private static final byte[] CLIENT_CONNECTION_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final int maxChunkSize;

    private final HttpFrameDecoderDelegate delegate;

    private State state;

    // HTTP/2 frame header fields
    private int length;
    private short type;
    private byte flags;
    private int streamId;

    // HTTP/2 frame padding length
    private int paddingLength;

    private enum State {
        READ_CONNECTION_HEADER,
        READ_FRAME_HEADER,
        READ_PADDING_LENGTH,
        READ_DATA_FRAME,
        READ_DATA,
        READ_HEADERS_FRAME,
        READ_PRIORITY_FRAME,
        READ_RST_STREAM_FRAME,
        READ_SETTINGS_FRAME,
        READ_SETTING,
        READ_PUSH_PROMISE_FRAME,
        READ_PING_FRAME,
        READ_GOAWAY_FRAME,
        READ_WINDOW_UPDATE_FRAME,
        READ_CONTINUATION_FRAME_HEADER,
        READ_HEADER_BLOCK,
        SKIP_FRAME_PADDING,
        SKIP_FRAME_PADDING_CONTINUATION,
        FRAME_ERROR
    }

    /**
     * Creates a new instance with the specified @{code HttpFrameDecoderDelegate}
     * and the default {@code maxChunkSize (8192)}.
     */
    public HttpFrameDecoder(boolean server, HttpFrameDecoderDelegate delegate) {
        this(server, delegate, 8192);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    public HttpFrameDecoder(boolean server, HttpFrameDecoderDelegate delegate, int maxChunkSize) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException(
                    "maxChunkSize must be a positive integer: " + maxChunkSize);
        }
        this.delegate = delegate;
        this.maxChunkSize = maxChunkSize;
        if (server) {
            state = State.READ_CONNECTION_HEADER;
        } else {
            state = State.READ_FRAME_HEADER;
        }
    }

    /**
     * Decode the byte buffer.
     */
    public void decode(ByteBuf buffer) {
        boolean endStream;
        boolean endSegment;
        int minLength;
        int dependency;
        int weight;
        boolean exclusive;
        int errorCode;

        while (true) {
            switch (state) {
            case READ_CONNECTION_HEADER:
                while (buffer.isReadable()) {
                    byte b = buffer.readByte();
                    if (b != CLIENT_CONNECTION_PREFACE[length++]) {
                        state = State.FRAME_ERROR;
                        delegate.readFrameError("Invalid Connection Header");
                        return;
                    }

                    if (length == CLIENT_CONNECTION_PREFACE.length) {
                        state = State.READ_FRAME_HEADER;
                        break;
                    }
                }
                if (buffer.isReadable()) {
                    break;
                } else {
                    return;
                }

            case READ_FRAME_HEADER:
                // Wait until entire header is readable
                if (buffer.readableBytes() < HTTP_FRAME_HEADER_SIZE) {
                    return;
                }

                // Read frame header fields
                readFrameHeader(buffer);

                // TODO(jpinner) Sec 4.2 FRAME_SIZE_ERROR

                if (!isValidFrameHeader(length, type, flags, streamId)) {
                    state = State.FRAME_ERROR;
                    delegate.readFrameError("Invalid Frame Header");
                } else if (frameHasPadding(type, flags)) {
                    state = State.READ_PADDING_LENGTH;
                } else {
                    paddingLength = 0;
                    state = getNextState(length, type);
                }
                break;

            case READ_PADDING_LENGTH:
                if (buffer.readableBytes() < 1) {
                    return;
                }

                paddingLength = buffer.readUnsignedByte();
                --length;

                if (!isValidPaddingLength(length, type, flags, paddingLength)) {
                    state = State.FRAME_ERROR;
                    delegate.readFrameError("Invalid Frame Padding Length");
                } else {
                    state = getNextState(length, type);
                }
                break;

            case READ_DATA_FRAME:
                endStream = hasFlag(flags, HTTP_FLAG_END_STREAM);
                state = State.READ_DATA;
                if (hasFlag(flags, HTTP_FLAG_PADDED)) {
                    delegate.readDataFramePadding(streamId, endStream, paddingLength + 1);
                }
                break;

            case READ_DATA:
                // Generate data frames that do not exceed maxChunkSize
                // maxChunkSize must be > 0 so we cannot infinitely loop
                int dataLength = Math.min(maxChunkSize, length - paddingLength);

                // Wait until entire frame is readable
                if (buffer.readableBytes() < dataLength) {
                    return;
                }

                ByteBuf data = buffer.readBytes(dataLength);
                length -= dataLength;

                if (length == paddingLength) {
                    if (paddingLength == 0) {
                        state = State.READ_FRAME_HEADER;
                    } else {
                        state = State.SKIP_FRAME_PADDING;
                    }
                }

                endStream = length == paddingLength && hasFlag(flags, HTTP_FLAG_END_STREAM);
                endSegment = length == paddingLength && hasFlag(flags, HTTP_FLAG_END_SEGMENT);

                delegate.readDataFrame(streamId, endStream, endSegment, data);
                break;

            case READ_HEADERS_FRAME:
                minLength = 0;
                if (hasFlag(flags, HTTP_FLAG_PRIORITY)) {
                    minLength = 5;
                }
                if (buffer.readableBytes() < minLength) {
                    return;
                }

                endStream = hasFlag(flags, HTTP_FLAG_END_STREAM);
                endSegment = hasFlag(flags, HTTP_FLAG_END_SEGMENT);

                exclusive = false;
                dependency = 0;
                weight = 16;
                if (hasFlag(flags, HTTP_FLAG_PRIORITY)) {
                    dependency = getSignedInt(buffer, buffer.readerIndex());
                    buffer.skipBytes(4);
                    weight = buffer.readUnsignedByte() + 1;
                    if (dependency < 0) {
                        dependency = dependency & 0x7FFFFFFF;
                        exclusive = true;
                    }
                    length -= 5;
                }

                state = State.READ_HEADER_BLOCK;
                delegate.readHeadersFrame(streamId, endStream, endSegment, exclusive, dependency, weight);
                break;

            case READ_PRIORITY_FRAME:
                if (buffer.readableBytes() < length) {
                    return;
                }

                exclusive = false;
                dependency = getSignedInt(buffer, buffer.readerIndex());
                buffer.skipBytes(4);
                weight = buffer.readUnsignedByte() + 1;
                if (dependency < 0) {
                    dependency = dependency & 0x7FFFFFFF;
                    exclusive = true;
                }

                state = State.READ_FRAME_HEADER;
                delegate.readPriorityFrame(streamId, exclusive, dependency, weight);
                break;

            case READ_RST_STREAM_FRAME:
                if (buffer.readableBytes() < length) {
                    return;
                }

                errorCode = getSignedInt(buffer, buffer.readerIndex());
                buffer.skipBytes(length);

                state = State.READ_FRAME_HEADER;
                delegate.readRstStreamFrame(streamId, errorCode);
                break;

            case READ_SETTINGS_FRAME:
                boolean ack = hasFlag(flags, HTTP_FLAG_ACK);

                state = State.READ_SETTING;
                delegate.readSettingsFrame(ack);
                break;

            case READ_SETTING:
                if (length == 0) {
                    state = State.READ_FRAME_HEADER;
                    delegate.readSettingsEnd();
                    break;
                }

                if (buffer.readableBytes() < 6) {
                    return;
                }

                int id = getUnsignedShort(buffer, buffer.readerIndex());
                int value = getSignedInt(buffer, buffer.readerIndex() + 2);
                buffer.skipBytes(6);
                length -= 6;

                delegate.readSetting(id, value);
                break;

            case READ_PUSH_PROMISE_FRAME:
                if (buffer.readableBytes() < 4) {
                    return;
                }

                int promisedStreamId = getUnsignedInt(buffer, buffer.readerIndex());
                buffer.skipBytes(4);
                length -= 4;

                if (promisedStreamId == 0) {
                    state = State.FRAME_ERROR;
                    delegate.readFrameError("Invalid Promised-Stream-ID");
                } else {
                    state = State.READ_HEADER_BLOCK;
                    delegate.readPushPromiseFrame(streamId, promisedStreamId);
                }
                break;

            case READ_PING_FRAME:
                if (buffer.readableBytes() < length) {
                    return;
                }

                long ping = getSignedLong(buffer, buffer.readerIndex());
                buffer.skipBytes(length);

                boolean pong = hasFlag(flags, HTTP_FLAG_ACK);

                state = State.READ_FRAME_HEADER;
                delegate.readPingFrame(ping, pong);
                break;

            case READ_GOAWAY_FRAME:
                if (buffer.readableBytes() < 8) {
                    return;
                }

                int lastStreamId = getUnsignedInt(buffer, buffer.readerIndex());
                errorCode = getSignedInt(buffer, buffer.readerIndex() + 4);
                buffer.skipBytes(8);
                length -= 8;

                if (length == 0) {
                    state = State.READ_FRAME_HEADER;
                } else {
                    paddingLength = length;
                    state = State.SKIP_FRAME_PADDING;
                }
                delegate.readGoAwayFrame(lastStreamId, errorCode);
                break;

            case READ_WINDOW_UPDATE_FRAME:
                // Wait until entire frame is readable
                if (buffer.readableBytes() < length) {
                    return;
                }

                int windowSizeIncrement = getUnsignedInt(buffer, buffer.readerIndex());
                buffer.skipBytes(length);

                if (windowSizeIncrement == 0) {
                    state = State.FRAME_ERROR;
                    delegate.readFrameError("Invalid Window Size Increment");
                } else {
                    state = State.READ_FRAME_HEADER;
                    delegate.readWindowUpdateFrame(streamId, windowSizeIncrement);
                }
                break;

            case READ_CONTINUATION_FRAME_HEADER:
                // Wait until entire frame header is readable
                if (buffer.readableBytes() < HTTP_FRAME_HEADER_SIZE) {
                    return;
                }

                // Read and validate continuation frame header fields
                int prevStreamId = streamId;
                readFrameHeader(buffer);

                // TODO(jpinner) Sec 4.2 FRAME_SIZE_ERROR
                // TODO(jpinner) invalid flags

                if (type != HTTP_CONTINUATION_FRAME || streamId != prevStreamId) {
                    state = State.FRAME_ERROR;
                    delegate.readFrameError("Invalid Continuation Frame");
                } else {
                    paddingLength = 0;
                    state = State.READ_HEADER_BLOCK;
                }
                break;

            case READ_HEADER_BLOCK:
                if (length == paddingLength) {
                    boolean endHeaders = hasFlag(flags, HTTP_FLAG_END_HEADERS);
                    if (endHeaders) {
                        state = State.SKIP_FRAME_PADDING;
                        delegate.readHeaderBlockEnd();
                    } else {
                        state = State.SKIP_FRAME_PADDING_CONTINUATION;
                    }
                    break;
                }

                if (!buffer.isReadable()) {
                    return;
                }

                int readableBytes = Math.min(buffer.readableBytes(), length - paddingLength);
                ByteBuf headerBlockFragment = buffer.readBytes(readableBytes);
                length -= readableBytes;

                delegate.readHeaderBlock(headerBlockFragment);
                break;

            case SKIP_FRAME_PADDING:
                int numBytes = Math.min(buffer.readableBytes(), length);
                buffer.skipBytes(numBytes);
                length -= numBytes;
                if (length == 0) {
                    state = State.READ_FRAME_HEADER;
                    break;
                }
                return;

            case SKIP_FRAME_PADDING_CONTINUATION:
                int numPaddingBytes = Math.min(buffer.readableBytes(), length);
                buffer.skipBytes(numPaddingBytes);
                length -= numPaddingBytes;
                if (length == 0) {
                    state = State.READ_CONTINUATION_FRAME_HEADER;
                    break;
                }
                return;

            case FRAME_ERROR:
                buffer.skipBytes(buffer.readableBytes());
                return;

            default:
                throw new Error("Shouldn't reach here.");
            }
        }
    }

    /**
     * Reads the HTTP/2 Frame Header and sets the length, type, flags, and streamId member variables.
     *
     * @param buffer input buffer containing the entire 9-octet header
     */
    private void readFrameHeader(ByteBuf buffer) {
        int frameOffset = buffer.readerIndex();
        length = getUnsignedMedium(buffer, frameOffset);
        type = buffer.getUnsignedByte(frameOffset + 3);
        flags = buffer.getByte(frameOffset + 4);
        streamId = getUnsignedInt(buffer, frameOffset + 5);
        buffer.skipBytes(HTTP_FRAME_HEADER_SIZE);
    }

    private static boolean hasFlag(byte flags, byte flag) {
        return (flags & flag) != 0;
    }

    private static boolean frameHasPadding(int type, byte flags) {
        switch (type) {
        case HTTP_DATA_FRAME:
        case HTTP_HEADERS_FRAME:
        case HTTP_PUSH_PROMISE_FRAME:
            return hasFlag(flags, HTTP_FLAG_PADDED);
        default:
            return false;
        }
    }

    private static State getNextState(int length, int type) {
        switch (type) {
        case HTTP_DATA_FRAME:
            return State.READ_DATA_FRAME;

        case HTTP_HEADERS_FRAME:
            return State.READ_HEADERS_FRAME;

        case HTTP_PRIORITY_FRAME:
            return State.READ_PRIORITY_FRAME;

        case HTTP_RST_STREAM_FRAME:
            return State.READ_RST_STREAM_FRAME;

        case HTTP_SETTINGS_FRAME:
            return State.READ_SETTINGS_FRAME;

        case HTTP_PUSH_PROMISE_FRAME:
            return State.READ_PUSH_PROMISE_FRAME;

        case HTTP_PING_FRAME:
            return State.READ_PING_FRAME;

        case HTTP_GOAWAY_FRAME:
            return State.READ_GOAWAY_FRAME;

        case HTTP_WINDOW_UPDATE_FRAME:
            return State.READ_WINDOW_UPDATE_FRAME;

        case HTTP_CONTINUATION_FRAME:
            throw new Error("Shouldn't reach here.");

        default:
            if (length != 0) {
                return State.SKIP_FRAME_PADDING;
            } else {
                return State.READ_FRAME_HEADER;
            }
        }
    }

    private static boolean isValidFrameHeader(int length, short type, byte flags, int streamId) {
        if (length > HTTP_MAX_LENGTH) {
            return false;
        }
        int minLength;
        switch (type) {
        case HTTP_DATA_FRAME:
            if (hasFlag(flags, HTTP_FLAG_PADDED)) {
                minLength = 1;
            } else {
                minLength = 0;
            }
            return length >= minLength && streamId != 0;

        case HTTP_HEADERS_FRAME:
            if (hasFlag(flags, HTTP_FLAG_PADDED)) {
                minLength = 1;
            } else {
                minLength = 0;
            }
            if (hasFlag(flags, HTTP_FLAG_PRIORITY)) {
                minLength += 5;
            }
            return length >= minLength && streamId != 0;

        case HTTP_PRIORITY_FRAME:
            return length == 5 && streamId != 0;

        case HTTP_RST_STREAM_FRAME:
            return length == 4 && streamId != 0;

        case HTTP_SETTINGS_FRAME:
            boolean lengthValid = hasFlag(flags, HTTP_FLAG_ACK) ? length == 0 : (length % 6) == 0;
            return lengthValid && streamId == 0;

        case HTTP_PUSH_PROMISE_FRAME:
            if (hasFlag(flags, HTTP_FLAG_PADDED)) {
                minLength = 5;
            } else {
                minLength = 4;
            }
            return length >= minLength && streamId != 0;

        case HTTP_PING_FRAME:
            return length == 8 && streamId == 0;

        case HTTP_GOAWAY_FRAME:
            return length >= 8 && streamId == 0;

        case HTTP_WINDOW_UPDATE_FRAME:
            return length == 4;

        case HTTP_CONTINUATION_FRAME:
            return false;

        default:
            return true;
        }
    }

    private static boolean isValidPaddingLength(
            int length, short type, byte flags, int paddingLength) {
        switch (type) {
        case HTTP_DATA_FRAME:
            return length >= paddingLength;
        case HTTP_HEADERS_FRAME:
            if (hasFlag(flags, HTTP_FLAG_PRIORITY)) {
                return length >= paddingLength + 5;
            } else {
                return length >= paddingLength;
            }
        case HTTP_PUSH_PROMISE_FRAME:
            return length >= paddingLength + 4;
        default:
            throw new Error("Shouldn't reach here.");
        }
    }
}
