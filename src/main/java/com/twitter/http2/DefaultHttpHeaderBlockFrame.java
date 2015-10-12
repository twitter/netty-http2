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

import java.util.Map;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.internal.StringUtil;

/**
 * The default {@link HttpHeaderBlockFrame} implementation.
 */
public abstract class DefaultHttpHeaderBlockFrame extends DefaultHttpStreamFrame
        implements HttpHeaderBlockFrame {

    private boolean invalid;
    private boolean truncated;
    private final HttpHeaders headers = new DefaultHttpHeaders();

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     */
    protected DefaultHttpHeaderBlockFrame(int streamId) {
        super(streamId);
    }

    @Override
    public boolean isInvalid() {
        return invalid;
    }

    @Override
    public HttpHeaderBlockFrame setInvalid() {
        invalid = true;
        return this;
    }

    @Override
    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public HttpHeaderBlockFrame setTruncated() {
        truncated = true;
        return this;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    protected void appendHeaders(StringBuilder buf) {
        for (Map.Entry<String, String> e : headers()) {
            buf.append("    ");
            buf.append(e.getKey());
            buf.append(": ");
            buf.append(e.getValue());
            buf.append(StringUtil.NEWLINE);
        }
    }
}
