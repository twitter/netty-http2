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
 * The default {@link HttpStreamFrame} implementation.
 */
public abstract class DefaultHttpStreamFrame implements HttpStreamFrame {

    private int streamId;

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     */
    protected DefaultHttpStreamFrame(int streamId) {
        setStreamId(streamId);
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public HttpStreamFrame setStreamId(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException(
                    "Stream identifier must be positive: " + streamId);
        }
        this.streamId = streamId;
        return this;
    }
}
