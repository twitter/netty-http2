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
 * The default {@link HttpPriorityFrame} implementation.
 */
public class DefaultHttpPriorityFrame implements HttpPriorityFrame {

    private int streamId;
    private boolean exclusive;
    private int dependency;
    private int weight;

    /**
     * Creates a new instance.
     *
     * @param streamId   the stream identifier of this frame
     * @param exclusive  if the dependency of the stream is exclusive
     * @param dependency the dependency of the stream
     * @param weight     the weight of the dependency of the stream
     */
    public DefaultHttpPriorityFrame(int streamId, boolean exclusive, int dependency, int weight) {
        setStreamId(streamId);
        setExclusive(exclusive);
        setDependency(dependency);
        setWeight(weight);
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public HttpPriorityFrame setStreamId(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException(
                    "Stream identifier must be positive: " + streamId);
        }
        this.streamId = streamId;
        return this;
    }

    @Override
    public boolean isExclusive() {
        return exclusive;
    }

    @Override
    public HttpPriorityFrame setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
        return this;
    }

    @Override
    public int getDependency() {
        return dependency;
    }

    @Override
    public HttpPriorityFrame setDependency(int dependency) {
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
    public HttpPriorityFrame setWeight(int weight) {
        if (weight <= 0 || weight > 256) {
            throw new IllegalArgumentException(
                    "Illegal weight: " + weight);
        }
        this.weight = weight;
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
        buf.append("--> Dependency = ");
        buf.append(getDependency());
        buf.append(" (exclusive: ");
        buf.append(isExclusive());
        buf.append(", weight: ");
        buf.append(getWeight());
        buf.append(')');
        return buf.toString();
    }
}
