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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Decodes {@link HttpFrame}s into {@link StreamedHttpRequest}s.
 */
public class HttpStreamDecoder extends MessageToMessageDecoder<Object> {

    private static final CancellationException CANCELLATION_EXCEPTION =
            new CancellationException("HTTP/2 RST_STREAM Frame Received");

    private final Map<Integer, StreamedHttpRequest> requestMap =
            new ConcurrentHashMap<Integer, StreamedHttpRequest>();

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        if (msg instanceof HttpHeadersFrame) {

            HttpHeadersFrame httpHeadersFrame = (HttpHeadersFrame) msg;
            int streamId = httpHeadersFrame.getStreamId();
            StreamedHttpRequest request = requestMap.get(streamId);

            if (request == null) {
                try {
                    request = createHttpRequest(ctx.channel(), httpHeadersFrame);
                    if (!request.getContent().isClosed()) {
                        requestMap.put(streamId, request);
                    }
                    out.add(request);
                } catch (Exception e) {
                    HttpRstStreamFrame httpRstStreamFrame =
                            new DefaultHttpRstStreamFrame(streamId, HttpErrorCode.PROTOCOL_ERROR);
                    ctx.writeAndFlush(httpRstStreamFrame);
                }
            } else {
                for (Map.Entry<String, String> e : httpHeadersFrame.headers()) {
                    request.headers().add(e.getKey(), e.getValue());
                }

                if (httpHeadersFrame.isLast()) {
                    requestMap.remove(streamId);
                    request.addContent(LastHttpContent.EMPTY_LAST_CONTENT);
                }
            }

        } else if (msg instanceof HttpDataFrame) {

            HttpDataFrame httpDataFrame = (HttpDataFrame) msg;
            int streamId = httpDataFrame.getStreamId();
            StreamedHttpRequest request = requestMap.get(streamId);

            // If message is not in map discard Data Frame.
            if (request == null) {
                return;
            }

            ByteBuf content = httpDataFrame.content();
            if (content.isReadable()) {
                content.retain();
                request.addContent(new DefaultHttpContent(content));
            }

            if (httpDataFrame.isLast()) {
                requestMap.remove(streamId);
                request.addContent(LastHttpContent.EMPTY_LAST_CONTENT);
                request.setDecoderResult(DecoderResult.SUCCESS);
            }

        } else if (msg instanceof HttpRstStreamFrame) {

            HttpRstStreamFrame httpRstStreamFrame = (HttpRstStreamFrame) msg;
            int streamId = httpRstStreamFrame.getStreamId();
            StreamedHttpRequest request = requestMap.remove(streamId);

            if (request != null) {
                request.getContent().close();
                request.setDecoderResult(DecoderResult.failure(CANCELLATION_EXCEPTION));
            }
        }
    }

    private StreamedHttpRequest createHttpRequest(Channel channel, HttpHeadersFrame httpHeadersFrame)
            throws Exception {
        // Create the first line of the request from the name/value pairs
        HttpMethod method = HttpMethod.valueOf(httpHeadersFrame.headers().get(":method"));
        String url = httpHeadersFrame.headers().get(":path");

        httpHeadersFrame.headers().remove(":method");
        httpHeadersFrame.headers().remove(":path");

        StreamedHttpRequest request = new StreamedHttpRequest(HttpVersion.HTTP_1_1, method, url);

        // Remove the scheme header
        httpHeadersFrame.headers().remove(":scheme");

        // Replace the SPDY host header with the HTTP host header
        String host = httpHeadersFrame.headers().get(":authority");
        httpHeadersFrame.headers().remove(":authority");
        httpHeadersFrame.headers().set("host", host);

        for (Map.Entry<String, String> e : httpHeadersFrame.headers()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name.charAt(0) != ':') {
                request.headers().add(name, value);
            }
        }

        // Set the Stream-ID as a header
        request.headers().set("X-SPDY-Stream-ID", httpHeadersFrame.getStreamId());

        // The Connection and Keep-Alive headers are no longer valid
        HttpHeaders.setKeepAlive(request, true);

        // Transfer-Encoding header is not valid
        request.headers().remove(HttpHeaders.Names.TRANSFER_ENCODING);

        if (httpHeadersFrame.isLast()) {
            request.getContent().close();
            request.setDecoderResult(DecoderResult.SUCCESS);
        } else {
            request.setDecoderResult(DecoderResult.UNFINISHED);
        }

        return request;
    }
}
