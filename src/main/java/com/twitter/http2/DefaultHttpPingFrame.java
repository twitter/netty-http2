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

/**
 * The default {@link HttpPingFrame} implementation.
 */
public class DefaultHttpPingFrame implements HttpPingFrame {

    private long data;
    private boolean pong;

    /**
     * Creates a new instance.
     *
     * @param data the data payload of this frame
     */
    public DefaultHttpPingFrame(long data) {
        setData(data);
    }

    @Override
    public long getData() {
        return data;
    }

    @Override
    public HttpPingFrame setData(long data) {
        this.data = data;
        return this;
    }

    @Override
    public boolean isPong() {
        return pong;
    }

    @Override
    public HttpPingFrame setPong(boolean pong) {
        this.pong = pong;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(StringUtil.simpleClassName(this));
        buf.append("(pong: ");
        buf.append(isPong());
        buf.append(')');
        buf.append(StringUtil.NEWLINE);
        buf.append("--> Data = ");
        buf.append(getData());
        return buf.toString();
    }
}
