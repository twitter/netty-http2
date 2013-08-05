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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;

/**
 * An {@link HttpRequest} that adds content streaming.
 */
public class StreamedHttpRequest extends HttpRequestProxy implements StreamedHttpMessage {

    private Pipe<HttpContent> pipe = new Pipe<HttpContent>();

    public StreamedHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(new DefaultHttpRequest(version, method, uri));
    }

    public StreamedHttpRequest(HttpRequest request) {
        super(request);
    }

    @Override
    public Pipe<HttpContent> getContent() {
        return pipe;
    }

    @Override
    public Future<Void> addContent(HttpContent content) {
        Future<Void> future = pipe.send(content);
        if (content instanceof LastHttpContent) {
            pipe.close();
        }
        return future;
    }
}
