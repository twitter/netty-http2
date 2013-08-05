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

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;

/**
 * An {@link HttpMessage} that adds support for streaming content using {@linkplain Pipe pipes}.
 * @see Pipe
 */
public interface StreamedHttpMessage extends HttpMessage {

    /**
     * Gets the pipe for sending the message body as {@linkplain HttpContent} objects.
     * A {@linkplain LastHttpContent last http content} will indicate the end of the body.
     *
     * @return the message body pipe.
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-3.6.1">RFC 2616, 3.6.1 Chunked Transfer Coding</a>
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-4.3">RFC 2616, 4.3 Message Body</a>
     */
    Pipe<HttpContent> getContent();

    /**
     * Adds content to the pipe. This returns a future for determining whether the message was received.
     * If the chunk is the last chunk then the pipe will be closed.
     * <p>
     * An {@link LastHttpContent} can be used for the trailer part of a chunked-encoded request.</p>
     * <p>
     * This method is preferable to {@code getContent().send()} because it does additional checks.</p>
     *
     * @param content the content to send
     * @return a future indicating when the message was received.
     */
    Future<Void> addContent(HttpContent content);
}
