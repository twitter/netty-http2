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

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * An {@link HttpResponse} decorator.
 */
public class HttpResponseProxy extends HttpMessageProxy implements HttpResponse {

    private final HttpResponse response;

    public HttpResponseProxy(HttpResponse response) {
        super(response);
        this.response = response;
    }

    @Override
    @Deprecated
    public HttpResponseStatus getStatus() {
        return response.getStatus();
    }

    @Override
    public HttpResponse setStatus(HttpResponseStatus status) {
        response.setStatus(status);
        return this;
    }

    @Override
    public HttpResponse setProtocolVersion(HttpVersion version) {
        response.setProtocolVersion(version);
        return this;
    }

    @Override
    public String toString() {
        return response.toString();
    }

    @Override
    public HttpResponseStatus status() {
        return response.status();
    }
}
