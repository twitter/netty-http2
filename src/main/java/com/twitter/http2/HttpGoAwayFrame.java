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
 * An HTTP/2 GOAWAY Frame
 */
public interface HttpGoAwayFrame extends HttpFrame {

    /**
     * Returns the Last-Stream-ID of this frame.
     */
    int getLastStreamId();

    /**
     * Sets the Last-Stream-ID of this frame.  The Last-Stream-ID cannot be negative.
     */
    HttpGoAwayFrame setLastStreamId(int lastStreamId);

    /**
     * Returns the error code of this frame.
     */
    HttpErrorCode getErrorCode();

    /**
     * Sets the error code of this frame.
     */
    HttpGoAwayFrame setErrorCode(HttpErrorCode errorCode);
}
