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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

/**
 * An HTTP/2 DATA Frame
 */
public interface HttpDataFrame extends ByteBufHolder, HttpStreamFrame {

    /**
     * Returns {@code true} if this frame is the last frame to be transmitted on the stream.
     */
    boolean isLast();

    /**
     * Sets if this frame is the last frame to be transmitted on the stream.
     */
    HttpDataFrame setLast(boolean last);

    @Override
    HttpDataFrame setStreamId(int streamId);

    /**
     * Returns the data payload of this frame.  If there is no data payload
     * {@link io.netty.buffer.Unpooled#EMPTY_BUFFER} is returned.
     * <p/>
     * The data payload cannot exceed 16384 bytes.
     */
    @Override
    ByteBuf content();

    @Override
    HttpDataFrame copy();

    @Override
    HttpDataFrame duplicate();

    @Override
    HttpDataFrame retain();

    @Override
    HttpDataFrame retain(int increment);
}
