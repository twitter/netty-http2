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

/**
 * An HTTP/2 WINDOW_UPDATE Frame
 */
public interface HttpWindowUpdateFrame extends HttpFrame {

    /**
     * Returns the stream identifier of this frame.
     */
    int getStreamId();

    /**
     * Sets the stream identifier of this frame.  The stream identifier cannot be negative.
     */
    HttpWindowUpdateFrame setStreamId(int streamId);

    /**
     * Returns the Window-Size-Increment of this frame.
     */
    int getWindowSizeIncrement();

    /**
     * Sets the Window-Size-Increment of this frame.
     * The Window-Size-Increment must be positive.
     */
    HttpWindowUpdateFrame setWindowSizeIncrement(int deltaWindowSize);
}
