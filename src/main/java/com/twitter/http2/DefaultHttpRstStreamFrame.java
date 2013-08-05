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
 * The default {@link HttpRstStreamFrame} implementation.
 */
public class DefaultHttpRstStreamFrame implements HttpRstStreamFrame {

    private int streamId;
    private HttpErrorCode errorCode;

    /**
     * Creates a new instance.
     *
     * @param streamId the stream identifier of this frame
     * @param code     the error code of this frame
     */
    public DefaultHttpRstStreamFrame(int streamId, int code) {
        this(streamId, HttpErrorCode.valueOf(code));
    }

    /**
     * Creates a new instance.
     *
     * @param streamId  the stream identifier of this frame
     * @param errorCode the error code of this frame
     */
    public DefaultHttpRstStreamFrame(int streamId, HttpErrorCode errorCode) {
        setStreamId(streamId);
        setErrorCode(errorCode);
    }

    @Override
    public int getStreamId() {
        return streamId;
    }

    @Override
    public HttpRstStreamFrame setStreamId(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException(
                    "Stream identifier must be positive: " + streamId);
        }
        this.streamId = streamId;
        return this;
    }

    @Override
    public HttpErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public HttpRstStreamFrame setErrorCode(HttpErrorCode errorCode) {
        this.errorCode = errorCode;
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
        buf.append("--> Error Code: ");
        buf.append(getErrorCode().toString());
        return buf.toString();
    }
}
