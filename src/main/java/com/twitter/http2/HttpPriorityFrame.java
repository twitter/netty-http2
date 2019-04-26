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
 * An HTTP/2 PRIORITY Frame
 */
public interface HttpPriorityFrame extends HttpFrame {

    /**
     * Returns the stream identifier of this frame.
     */
    int getStreamId();

    /**
     * Sets the stream identifier of this frame.  The stream identifier must be positive.
     */
    HttpPriorityFrame setStreamId(int streamId);

    /**
     * Returns {@code true} if the dependency of the stream is exclusive.
     */
    boolean isExclusive();

    /**
     * Sets if the dependency of the stream is exclusive.
     */
    HttpPriorityFrame setExclusive(boolean exclusive);

    /**
     * Returns the dependency of the stream.
     */
    int getDependency();

    /**
     * Sets the dependency of the stream.  The dependency cannot be negative.
     */
    HttpPriorityFrame setDependency(int dependency);

    /**
     * Returns the weight of the dependency of the stream.
     */
    int getWeight();

    /**
     * Sets the weight of the dependency of the stream.
     * The weight must be positive and cannot exceed 256 bytes.
     */
    HttpPriorityFrame setWeight(int weight);
}
