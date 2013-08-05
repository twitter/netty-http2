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

import io.netty.util.internal.StringUtil;

/**
 * The default {@link HttpWindowUpdateFrame} implementation.
 */
public class DefaultHttpWindowUpdateFrame implements HttpWindowUpdateFrame {

    private int streamId;
    private int windowSizeIncrement;

    /**
     * Creates a new instance.
     *
     * @param streamId            the stream identifier of this frame
     * @param windowSizeIncrement the Window-Size-Increment of this frame
     */
    public DefaultHttpWindowUpdateFrame(int streamId, int windowSizeIncrement) {
        setStreamId(streamId);
        setWindowSizeIncrement(windowSizeIncrement);
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public DefaultHttpWindowUpdateFrame setStreamId(int streamId) {
        if (streamId < 0) {
            throw new IllegalArgumentException(
                    "Stream identifier cannot be negative: " + streamId);
        }
        this.streamId = streamId;
        return this;
    }

    @Override
    public int getWindowSizeIncrement() {
        return windowSizeIncrement;
    }

    @Override
    public DefaultHttpWindowUpdateFrame setWindowSizeIncrement(int windowSizeIncrement) {
        if (windowSizeIncrement <= 0) {
            throw new IllegalArgumentException(
                    "Window-Size-Increment must be positive: " + windowSizeIncrement);
        }
        this.windowSizeIncrement = windowSizeIncrement;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(StringUtil.simpleClassName(this));
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Stream-ID = ");
        buf.append(getStreamId());
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Window-Size-Increment = ");
        buf.append(getWindowSizeIncrement());
        return buf.toString();
    }
}
