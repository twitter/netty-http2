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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class HttpRequestProxyTest {

    private HttpRequest httpRequest = new DefaultHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
    private HttpRequestProxy httpRequestProxy = new HttpRequestProxy(httpRequest);

    @Test
    public void testHttpRequest() {
        assertSame(httpRequest, httpRequestProxy.httpRequest());
    }

    @Test
    public void testGetMethod() {
        assertEquals(httpRequest.getMethod(), httpRequestProxy.getMethod());
    }

    @Test
    public void testSetMethod() {
        assertSame(httpRequestProxy, httpRequestProxy.setMethod(HttpMethod.POST));
        assertEquals(httpRequest.getMethod(), httpRequestProxy.getMethod());
    }

    @Test
    public void testGetUri() {
        assertEquals(httpRequest.getUri(), httpRequestProxy.getUri());
    }

    @Test
    public void testSetUri() {
        assertSame(httpRequestProxy, httpRequestProxy.setUri("/path"));
        assertEquals(httpRequest.getUri(), httpRequestProxy.getUri());
    }

    @Test
    public void testToString() {
        assertEquals(httpRequest.toString(), httpRequestProxy.toString());
    }

    @Test
    public void testGetProtocolVersion() {
        assertEquals(httpRequest.getProtocolVersion(), httpRequestProxy.getProtocolVersion());
    }

    @Test
    public void testSetProtocolVersion() {
        assertSame(httpRequestProxy, httpRequestProxy.setProtocolVersion(HttpVersion.HTTP_1_1));
        assertEquals(httpRequest.getProtocolVersion(), httpRequestProxy.getProtocolVersion());
    }

    @Test
    public void testHeaders() {
        assertSame(httpRequest.headers(), httpRequestProxy.headers());
    }
}
