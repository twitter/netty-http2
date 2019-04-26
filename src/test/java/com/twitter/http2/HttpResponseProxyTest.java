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

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class HttpResponseProxyTest {

    private HttpResponse httpResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_0, HttpResponseStatus.OK);
    private HttpResponseProxy httpResponseProxy = new HttpResponseProxy(httpResponse);

    @Test
    public void testSetStatus() {
        assertSame(httpResponseProxy, httpResponseProxy.setStatus(HttpResponseStatus.NOT_FOUND));
        assertEquals(httpResponse.getStatus(), httpResponseProxy.getStatus());
    }

    @Test
    public void testGetStatus() {
        assertEquals(httpResponse.getStatus(), httpResponseProxy.getStatus());
    }

    @Test
    public void testToString() {
        assertEquals(httpResponse.toString(), httpResponseProxy.toString());
    }

    @Test
    public void testGetProtocolVersion() {
        assertEquals(httpResponse.getProtocolVersion(), httpResponseProxy.getProtocolVersion());
    }

    @Test
    public void testSetProtocolVersion() {
        assertSame(httpResponseProxy, httpResponseProxy.setProtocolVersion(HttpVersion.HTTP_1_1));
        assertEquals(httpResponse.getProtocolVersion(), httpResponseProxy.getProtocolVersion());
    }

    @Test
    public void testHeaders() {
        assertSame(httpResponse.headers(), httpResponseProxy.headers());
    }
}
