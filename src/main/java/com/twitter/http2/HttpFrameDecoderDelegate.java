/*
 * Copyright 2015 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.http2;

import io.netty.buffer.ByteBuf;

/**
 * Callback interface for {@link HttpFrameDecoder}.
 */
public interface HttpFrameDecoderDelegate {

    /**
     * Called when a DATA frame is received.
     */
    void readDataFramePadding(int streamId, boolean endStream, int padding);

    /**
     * Called when a DATA frame is received.
     */
    void readDataFrame(int streamId, boolean endStream, boolean endSegment, ByteBuf data);

    /**
     * Called when a HEADERS frame is received.
     * The Header Block Fragment is not included. See readHeaderBlock().
     */
    void readHeadersFrame(
            int streamId,
            boolean endStream,
            boolean endSegment,
            boolean exclusive,
            int dependency,
            int weight
    );

    /**
     * Called when a PRIORITY frame is received.
     */
    void readPriorityFrame(int streamId, boolean exclusive, int dependency, int weight);

    /**
     * Called when a RST_STREAM frame is received.
     */
    void readRstStreamFrame(int streamId, int errorCode);

    /**
     * Called when a SETTINGS frame is received.
     * Settings are not included. See readSetting().
     */
    void readSettingsFrame(boolean ack);

    /**
     * Called when an individual setting within a SETTINGS frame is received.
     */
    void readSetting(int id, int value);

    /**
     * Called when the entire SETTINGS frame has been received.
     */
    void readSettingsEnd();

    /**
     * Called when a PUSH_PROMISE frame is received.
     * The Header Block Fragment is not included. See readHeaderBlock().
     */
    void readPushPromiseFrame(int streamId, int promisedStreamId);

    /**
     * Called when a PING frame is received.
     */
    void readPingFrame(long data, boolean ack);

    /**
     * Called when a GOAWAY frame is received.
     */
    void readGoAwayFrame(int lastStreamId, int errorCode);

    /**
     * Called when a WINDOW_UPDATE frame is received.
     */
    void readWindowUpdateFrame(int streamId, int windowSizeIncrement);

    /**
     * Called when the header block fragment within a HEADERS,
     * PUSH_PROMISE, or CONTINUATION frame is received.
     */
    void readHeaderBlock(ByteBuf headerBlockFragment);

    /**
     * Called when an entire header block has been received.
     */
    void readHeaderBlockEnd();

    /**
     * Called when an unrecoverable connection error has occurred.
     */
    void readFrameError(String message);
}
