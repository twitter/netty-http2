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

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

/**
 * An {@link HttpRequest} decorator.
 */
class HttpRequestProxy extends HttpMessageProxy implements HttpRequest {

    private final HttpRequest request;

    public HttpRequestProxy(HttpRequest request) {
        super(request);
        this.request = request;
    }

    public HttpRequest httpRequest() {
        return request;
    }

    @Override
    @Deprecated
    public HttpMethod getMethod() {
        return request.getMethod();
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        request.setMethod(method);
        return this;
    }

    @Override
    @Deprecated
    public String getUri() {
        return request.getUri();
    }

    @Override
    public HttpRequest setUri(String uri) {
        request.setUri(uri);
        return this;
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        request.setProtocolVersion(version);
        return this;
    }

    @Override
    public String toString() {
        return request.toString();
    }

    @Override
    public HttpMethod method() {
        return request.method();
    }

    @Override
    public String uri() {
        return request.uri();
    }
}
