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

import io.netty.util.internal.StringUtil;

import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_DEPENDENCY;
import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_WEIGHT;

/**
 * The default {@link HttpHeadersFrame} implementation.
 */
public class DefaultHttpHeadersFrame extends DefaultHttpHeaderBlockFrame
        implements HttpHeadersFrame {

    private boolean last;
    private boolean exclusive = false;
    private int dependency = HTTP_DEFAULT_DEPENDENCY;
    private int weight = HTTP_DEFAULT_WEIGHT;

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     */
    public DefaultHttpHeadersFrame(int streamId) {
        super(streamId);
    }

    @Override
    public boolean isLast() {
        return last;
    }

    @Override
    public HttpHeadersFrame setLast(boolean last) {
        this.last = last;
        return this;
    }

    @Override
    public boolean isExclusive() {
        return exclusive;
    }

    @Override
    public HttpHeadersFrame setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
        return this;
    }

    @Override
    public int getDependency() {
        return dependency;
    }

    @Override
    public HttpHeadersFrame setDependency(int dependency) {
        if (dependency < 0) {
            throw new IllegalArgumentException(
                    "Dependency cannot be negative: " + dependency);
        }
        this.dependency = dependency;
        return this;
    }

    @Override
    public int getWeight() {
        return weight;
    }

    @Override
    public HttpHeadersFrame setWeight(int weight) {
        if (weight <= 0 || weight > 256) {
            throw new IllegalArgumentException(
                    "Illegal weight: " + weight);
        }
        this.weight = weight;
        return this;
    }

    @Override
    public HttpHeadersFrame setStreamId(int streamId) {
        super.setStreamId(streamId);
        return this;
    }

    @Override
    public HttpHeadersFrame setInvalid() {
        super.setInvalid();
        return this;
    }

    @Override
    public HttpHeadersFrame setTruncated() {
        super.setTruncated();
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(StringUtil.simpleClassName(this));
        buf.append("(last: ");
        buf.append(isLast());
        buf.append(')');
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Stream-ID = ");
        buf.append(getStreamId());
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Dependency = ");
        buf.append(getDependency());
        buf.append(" (exclusive: ");
        buf.append(isExclusive());
        buf.append(", weight: ");
        buf.append(getWeight());
        buf.append(')');
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Headers:");
        buf.append(StringUtil.NEWLINE);
        appendHeaders(buf);

        // Remove the last newline.
        buf.setLength(buf.length() - StringUtil.NEWLINE.length());
        return buf.toString();
    }
}
