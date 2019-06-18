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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Decodes {@link HttpFrame}s into {@link StreamedHttpRequest}s.
 */
public class HttpStreamDecoder extends MessageToMessageDecoder<Object> {

    private static final CancellationException CANCELLATION_EXCEPTION =
            new CancellationException("HTTP/2 RST_STREAM Frame Received");

    private final Map<Integer, StreamedHttpMessage> messageMap =
            new ConcurrentHashMap<Integer, StreamedHttpMessage>();
    private final Map<Integer, LastHttpContent> trailerMap =
            new ConcurrentHashMap<Integer, LastHttpContent>();

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        if (msg instanceof HttpHeadersFrame) {

            HttpHeadersFrame httpHeadersFrame = (HttpHeadersFrame) msg;
            int streamId = httpHeadersFrame.getStreamId();
            StreamedHttpMessage message = messageMap.get(streamId);

            if (message == null) {
                if (httpHeadersFrame.headers().contains(":status")) {

                    // If a client receives a reply with a truncated header block,
                    // reply with a RST_STREAM frame with error code INTERNAL_ERROR.
                    if (httpHeadersFrame.isTruncated()) {
                        HttpRstStreamFrame httpRstStreamFrame =
                                new DefaultHttpRstStreamFrame(streamId, HttpErrorCode.INTERNAL_ERROR);
                        out.add(httpRstStreamFrame);
                        return;
                    }

                    try {
                        StreamedHttpResponse response = createHttpResponse(httpHeadersFrame);

                        if (httpHeadersFrame.isLast()) {
                            HttpHeaders.setContentLength(response, 0);
                            response.getContent().close();
                        } else {
                            // Response body will follow in a series of Data Frames
                            if (!HttpHeaders.isContentLengthSet(response)) {
                                HttpHeaders.setTransferEncodingChunked(response);
                            }
                            messageMap.put(streamId, response);
                        }
                        out.add(response);
                    } catch (Exception e) {
                        // If a client receives a SYN_REPLY without valid getStatus and version headers
                        // the client must reply with a RST_STREAM frame indicating a PROTOCOL_ERROR
                        HttpRstStreamFrame httpRstStreamFrame =
                                new DefaultHttpRstStreamFrame(streamId, HttpErrorCode.PROTOCOL_ERROR);
                        ctx.writeAndFlush(httpRstStreamFrame);
                        out.add(httpRstStreamFrame);
                    }

                } else {

                    // If a client sends a request with a truncated header block, the server must
                    // reply with a HTTP 431 REQUEST HEADER FIELDS TOO LARGE reply.
                    if (httpHeadersFrame.isTruncated()) {
                        httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);
                        httpHeadersFrame.setLast(true);
                        httpHeadersFrame.headers().set(
                                ":status", HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE.code());
                        ctx.writeAndFlush(httpHeadersFrame);
                        return;
                    }

                    try {
                        message = createHttpRequest(httpHeadersFrame);

                        if (httpHeadersFrame.isLast()) {
                            message.setDecoderResult(DecoderResult.SUCCESS);
                            message.getContent().close();
                        } else {
                            // Request body will follow in a series of Data Frames
                            messageMap.put(streamId, message);
                        }

                        out.add(message);

                    } catch (Exception e) {
                        // If a client sends a SYN_STREAM without all of the method, url (host and path),
                        // scheme, and version headers the server must reply with a HTTP 400 BAD REQUEST reply.
                        // Also sends HTTP 400 BAD REQUEST reply if header name/value pairs are invalid
                        httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);
                        httpHeadersFrame.setLast(true);
                        httpHeadersFrame.headers().set(":status", HttpResponseStatus.BAD_REQUEST.code());
                        ctx.writeAndFlush(httpHeadersFrame);
                    }
                }
            } else {
                LastHttpContent trailer = trailerMap.remove(streamId);
                if (trailer == null) {
                    trailer = new DefaultLastHttpContent();
                }

                // Ignore trailers in a truncated HEADERS frame.
                if (!httpHeadersFrame.isTruncated()) {
                    for (Map.Entry<String, String> e: httpHeadersFrame.headers()) {
                        trailer.trailingHeaders().add(e.getKey(), e.getValue());
                    }
                }

                if (httpHeadersFrame.isLast()) {
                    messageMap.remove(streamId);
                    message.addContent(trailer);
                } else {
                    trailerMap.put(streamId, trailer);
                }
            }

        } else if (msg instanceof HttpDataFrame) {

            HttpDataFrame httpDataFrame = (HttpDataFrame) msg;
            int streamId = httpDataFrame.getStreamId();
            StreamedHttpMessage message = messageMap.get(streamId);

            // If message is not in map discard Data Frame.
            if (message == null) {
                return;
            }

            ByteBuf content = httpDataFrame.content();
            if (content.isReadable()) {
                content.retain();
                message.addContent(new DefaultHttpContent(content));
            }

            if (httpDataFrame.isLast()) {
                messageMap.remove(streamId);
                message.addContent(LastHttpContent.EMPTY_LAST_CONTENT);
                message.setDecoderResult(DecoderResult.SUCCESS);
            }

        } else if (msg instanceof HttpRstStreamFrame) {

            HttpRstStreamFrame httpRstStreamFrame = (HttpRstStreamFrame) msg;
            int streamId = httpRstStreamFrame.getStreamId();
            StreamedHttpMessage message = messageMap.remove(streamId);

            if (message != null) {
                message.getContent().close();
                message.setDecoderResult(DecoderResult.failure(CANCELLATION_EXCEPTION));
            }

        } else {
            // HttpGoAwayFrame
            out.add(msg);
        }
    }

    private StreamedHttpRequest createHttpRequest(HttpHeadersFrame httpHeadersFrame)
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

    private StreamedHttpResponse createHttpResponse(HttpHeadersFrame httpHeadersFrame)
            throws Exception {
        // Create the first line of the request from the name/value pairs
        HttpResponseStatus status = HttpResponseStatus.valueOf(Integer.parseInt(
                httpHeadersFrame.headers().get(":status")));

        httpHeadersFrame.headers().remove(":status");

        StreamedHttpResponse response = new StreamedHttpResponse(HttpVersion.HTTP_1_1, status);
        for (Map.Entry<String, String> e : httpHeadersFrame.headers()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name.charAt(0) != ':') {
                response.headers().add(name, value);
            }
        }

        // Set the Stream-ID as a header
        response.headers().set("X-SPDY-Stream-ID", httpHeadersFrame.getStreamId());

        // The Connection and Keep-Alive headers are no longer valid
        HttpHeaders.setKeepAlive(response, true);

        // Transfer-Encoding header is not valid
        response.headers().remove(HttpHeaders.Names.TRANSFER_ENCODING);
        response.headers().remove(HttpHeaders.Names.TRAILER);

        if (httpHeadersFrame.isLast()) {
            response.getContent().close();
            response.setDecoderResult(DecoderResult.SUCCESS);
        } else {
            response.setDecoderResult(DecoderResult.UNFINISHED);
        }

        return response;
    }
}
