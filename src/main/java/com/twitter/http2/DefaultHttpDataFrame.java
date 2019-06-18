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
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.internal.StringUtil;

/**
 * The default {@link HttpDataFrame} implementation.
 */
public class DefaultHttpDataFrame extends DefaultHttpStreamFrame implements HttpDataFrame {

    private final ByteBuf data;
    private boolean last;

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     */
    public DefaultHttpDataFrame(int streamId) {
        this(streamId, Unpooled.buffer(0));
    }

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     * @param data     the payload of the frame. Can not exceed {@link HttpCodecUtil#HTTP_MAX_LENGTH}
     */
    public DefaultHttpDataFrame(int streamId, ByteBuf data) {
        super(streamId);
        if (data == null) {
            throw new NullPointerException("data");
        }
        this.data = validate(data);
    }

    private static ByteBuf validate(ByteBuf data) {
        if (data.readableBytes() > HttpCodecUtil.HTTP_MAX_LENGTH) {
            throw new IllegalArgumentException("data payload cannot exceed "
                    + HttpCodecUtil.HTTP_MAX_LENGTH + " bytes");
        }
        return data;
    }

    @Override
    public boolean isLast() {
        return last;
    }

    @Override
    public HttpDataFrame setLast(boolean last) {
        this.last = last;
        return this;
    }

    @Override
    public HttpDataFrame setStreamId(int streamId) {
        super.setStreamId(streamId);
        return this;
    }

    @Override
    public ByteBuf content() {
        if (data.refCnt() <= 0) {
            throw new IllegalReferenceCountException(data.refCnt());
        }
        return data;
    }

    @Override
    public HttpDataFrame copy() {
        HttpDataFrame frame = new DefaultHttpDataFrame(getStreamId(), content().copy());
        frame.setLast(isLast());
        return frame;
    }

    @Override
    public HttpDataFrame duplicate() {
        HttpDataFrame frame = new DefaultHttpDataFrame(getStreamId(), content().duplicate());
        frame.setLast(isLast());
        return frame;
    }

    @Override
    public int refCnt() {
        return data.refCnt();
    }

    @Override
    public HttpDataFrame retain() {
        data.retain();
        return this;
    }

    @Override
    public HttpDataFrame retain(int increment) {
        data.retain(increment);
        return this;
    }

    @Override
    public boolean release() {
        return data.release();
    }

    @Override
    public boolean release(int decrement) {
        return data.release(decrement);
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
        buf.append("--> Size = ");
        if (refCnt() == 0) {
            buf.append("(freed)");
        } else {
            buf.append(content().readableBytes());
        }
        return buf.toString();
    }

    @Override
    public ByteBufHolder touch() {
        data.touch();
        return this;
    }

    @Override
    public ByteBufHolder touch(Object o) {
        data.touch(o);
        return this;
    }
}
