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
 * The default {@link HttpPushPromiseFrame} implementation.
 */
public class DefaultHttpPushPromiseFrame extends DefaultHttpHeaderBlockFrame
        implements HttpPushPromiseFrame {

    private int promisedStreamId;

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     */
    public DefaultHttpPushPromiseFrame(int streamId, int promisedStreamId) {
        super(streamId);
        setPromisedStreamId(promisedStreamId);
    }

    @Override
    public int getPromisedStreamId() {
        return promisedStreamId;
    }

    @Override
    public HttpPushPromiseFrame setPromisedStreamId(int promisedStreamId) {
        if (promisedStreamId <= 0) {
            throw new IllegalArgumentException(
                    "Promised-Stream-ID must be positive: " + promisedStreamId);
        }
        this.promisedStreamId = promisedStreamId;
        return this;
    }

    @Override
    public HttpPushPromiseFrame setStreamId(int streamId) {
        super.setStreamId(streamId);
        return this;
    }

    @Override
    public HttpPushPromiseFrame setInvalid() {
        super.setInvalid();
        return this;
    }

    @Override
    public HttpPushPromiseFrame setTruncated() {
        super.setTruncated();
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
        buf.append("--> Promised-Stream-ID = ");
        buf.append(getPromisedStreamId());
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Headers:");
        buf.append(StringUtil.NEWLINE);
        appendHeaders(buf);

        // Remove the last newline.
        buf.setLength(buf.length() - StringUtil.NEWLINE.length());
        return buf.toString();
    }
}
