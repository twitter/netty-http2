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

/**
 * An HTTP/2 HEADERS Frame
 */
public interface HttpHeadersFrame extends HttpHeaderBlockFrame {

    /**
     * Returns {@code true} if this frame is the last frame to be transmitted on the stream.
     */
    boolean isLast();

    /**
     * Sets if this frame is the last frame to be transmitted on the stream.
     */
    HttpHeadersFrame setLast(boolean last);

    /**
     * Returns {@code true} if the dependency of the stream is exclusive.
     */
    boolean isExclusive();

    /**
     * Sets if the dependency of the stream is exclusive.
     */
    HttpHeadersFrame setExclusive(boolean exclusive);

    /**
     * Returns the dependency of the stream.
     */
    int getDependency();

    /**
     * Sets the dependency of the stream.  The dependency cannot be negative.
     */
    HttpHeadersFrame setDependency(int dependency);

    /**
     * Returns the weight of the dependency of the stream.
     */
    int getWeight();

    /**
     * Sets the weight of the dependency of the stream.
     * The weight must be positive and cannot exceed 256 bytes.
     */
    HttpHeadersFrame setWeight(int weight);

    @Override
    HttpHeadersFrame setStreamId(int streamId);

    @Override
    HttpHeadersFrame setInvalid();

    @Override
    HttpHeadersFrame setTruncated();
}
