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

import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * Encodes {@link StreamedHttpResponse}s into {@link HttpFrame}s.
 */
public class HttpStreamEncoder extends ChannelOutboundHandlerAdapter {

    private static final int MAX_DATA_LENGTH = 0x2000; // Limit Data Frames to 8k

    private int currentStreamId;

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof HttpRequest) {

            HttpRequest httpRequest = (HttpRequest) msg;
            HttpHeadersFrame httpHeadersFrame = createHttpHeadersFrame(httpRequest);
            currentStreamId = httpHeadersFrame.getStreamId();

            ChannelPromise writeFuture = getMessageFuture(ctx, promise, currentStreamId, httpRequest);
            if (promise == writeFuture) {
                httpHeadersFrame.setLast(true);
            } else {
                promise = writeFuture;
            }

            ctx.write(httpHeadersFrame, promise);

        } else if (msg instanceof HttpResponse) {

            HttpResponse httpResponse = (HttpResponse) msg;
            HttpHeadersFrame httpHeadersFrame = createHttpHeadersFrame(httpResponse);
            currentStreamId = httpHeadersFrame.getStreamId();

            ChannelPromise writeFuture = getMessageFuture(ctx, promise, currentStreamId, httpResponse);
            if (promise == writeFuture) {
                httpHeadersFrame.setLast(true);
            } else {
                promise = writeFuture;
            }

            ctx.write(httpHeadersFrame, promise);

        } else if (msg instanceof HttpContent) {

            HttpContent chunk = (HttpContent) msg;
            writeChunk(ctx, promise, currentStreamId, chunk);

        } else {
            // Unknown message type
            ctx.write(msg, promise);
        }
    }

    private ChannelPromise getMessageFuture(
            final ChannelHandlerContext ctx,
            final ChannelPromise promise,
            final int streamId,
            HttpMessage message
    ) {
        if (message instanceof StreamedHttpMessage
                && !((StreamedHttpMessage) message).getContent().isClosed()) {
            final Pipe<HttpContent> pipe = ((StreamedHttpMessage) message).getContent();

            ChannelPromise writeFuture = ctx.channel().newPromise();
            writeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // Channel's thread
                    // First frame has been written

                    if (future.isSuccess()) {
                        pipe.receive().addListener(
                                new ChunkListener(ctx, streamId, pipe, promise));
                    } else if (future.isCancelled()) {
                        pipe.close();
                        promise.cancel(true);
                    } else {
                        pipe.close();
                        promise.setFailure(future.cause());
                    }
                }
            });

            return writeFuture;
        } else {
            return promise;
        }
    }


    /**
     * Listens to chunks being ready on a pipe.
     */
    private class ChunkListener implements FutureListener<HttpContent> {
        private final ChannelHandlerContext ctx;
        private final int streamId;
        private final Pipe<HttpContent> pipe;
        private final ChannelPromise completionFuture;

        ChunkListener(
                ChannelHandlerContext ctx,
                int streamId,
                Pipe<HttpContent> pipe,
                ChannelPromise completionFuture
        ) {
            this.ctx = ctx;
            this.streamId = streamId;
            this.pipe = pipe;
            this.completionFuture = completionFuture;
        }

        @Override
        public void operationComplete(final Future<HttpContent> future) throws Exception {
            final FutureListener<HttpContent> chunkListener = this;

            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    if (future.isSuccess()) {
                        HttpContent content = future.getNow();
                        ChannelPromise writeFuture;

                        if (content instanceof LastHttpContent) {
                            writeFuture = completionFuture;
                        } else {
                            writeFuture = ctx.channel().newPromise();
                            writeFuture.addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(ChannelFuture future) throws Exception {
                                    if (future.isSuccess()) {
                                        pipe.receive().addListener(chunkListener);
                                    } else if (future.isCancelled()) {
                                        pipe.close();
                                        completionFuture.cancel(true);
                                    } else {
                                        pipe.close();
                                        completionFuture.setFailure(future.cause());
                                    }
                                }
                            });
                        }

                        writeChunk(ctx, writeFuture, streamId, content);
                    } else {
                        // Somebody closed the pipe
                        // Send a reset frame to the channel and complete the completion future

                        ctx.writeAndFlush(
                                new DefaultHttpRstStreamFrame(streamId, HttpErrorCode.INTERNAL_ERROR));

                        if (future.isCancelled()) {
                            completionFuture.cancel(true);
                        } else {
                            completionFuture.setFailure(future.cause());
                        }
                    }
                }
            });
        }
    }

    /**
     * Writes an HTTP chunk downstream as one or more HTTP/2 frames.
     */
    protected void writeChunk(
            ChannelHandlerContext ctx, ChannelPromise future, int streamId, HttpContent content) {

        HttpFrame[] httpFrames = createHttpDataFrames(streamId, content.content());

        if (content instanceof LastHttpContent) {
            LastHttpContent trailer = (LastHttpContent) content;
            HttpHeaders trailers = trailer.trailingHeaders();
            if (trailers.isEmpty()) {
                if (httpFrames.length == 0) {
                    HttpDataFrame httpDataFrame = new DefaultHttpDataFrame(streamId);
                    httpDataFrame.setLast(true);
                    httpFrames = new HttpFrame[1];
                    httpFrames[0] = httpDataFrame;
                } else {
                    HttpDataFrame httpDataFrame = (HttpDataFrame) httpFrames[httpFrames.length - 1];
                    httpDataFrame.setLast(true);
                }
            } else {
                // Create HTTP HEADERS frame out of trailers
                HttpHeadersFrame httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);
                httpHeadersFrame.setLast(true);
                for (Map.Entry<String, String> entry : trailer.trailingHeaders()) {
                    httpHeadersFrame.headers().add(entry.getKey(), entry.getValue());
                }
                if (httpFrames.length == 0) {
                    httpFrames = new HttpFrame[1];
                    httpFrames[0] = httpHeadersFrame;
                } else {
                    HttpFrame[] copy = new HttpFrame[httpFrames.length + 1];
                    for (int i = 0; i < httpFrames.length; i++) {
                        copy[i] = httpFrames[i];
                    }
                    copy[httpFrames.length] = httpHeadersFrame;
                    httpFrames = copy;
                }
            }
        }

        ChannelPromise frameFuture = getFrameFuture(ctx, future, httpFrames);

        // Trigger a write
        frameFuture.setSuccess();
    }

    private static ChannelPromise getFrameFuture(
            ChannelHandlerContext ctx, ChannelPromise future, HttpFrame[] httpFrames) {
        ChannelPromise frameFuture = future;
        for (int i = httpFrames.length; --i >= 0; ) {
            future = ctx.channel().newPromise();
            future.addListener(new HttpFrameWriter(ctx, frameFuture, httpFrames[i]));
            frameFuture = future;
        }
        return frameFuture;
    }

    private static class HttpFrameWriter implements ChannelFutureListener {

        private final ChannelHandlerContext ctx;
        private final ChannelPromise promise;
        private final Object msg;

        HttpFrameWriter(ChannelHandlerContext ctx, ChannelPromise promise, Object msg) {
            this.ctx = ctx;
            this.promise = promise;
            this.msg = msg;
        }

        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                ctx.writeAndFlush(msg, promise);
            } else if (future.isCancelled()) {
                ReferenceCountUtil.release(msg);
                promise.cancel(true);
            } else {
                ReferenceCountUtil.release(msg);
                promise.setFailure(future.cause());
            }
        }
    }

    private HttpHeadersFrame createHttpHeadersFrame(HttpRequest httpRequest)
            throws Exception {
        // Get the Stream-ID from the headers
        int streamId = HttpHeaders.getIntHeader(httpRequest, "X-SPDY-Stream-ID");
        httpRequest.headers().remove("X-SPDY-Stream-ID");

        // The Connection, Keep-Alive, Proxy-Connection, and Transfer-Encoding
        // headers are not valid and MUST not be sent.
        httpRequest.headers().remove(HttpHeaders.Names.CONNECTION);
        httpRequest.headers().remove("Keep-Alive");
        httpRequest.headers().remove("Proxy-Connection");
        httpRequest.headers().remove(HttpHeaders.Names.TRANSFER_ENCODING);

        HttpHeadersFrame httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);

        // Unfold the first line of the request into name/value pairs
        httpHeadersFrame.headers().add(":method", httpRequest.getMethod().name());
        httpHeadersFrame.headers().set(":scheme", "https");
        httpHeadersFrame.headers().add(":path", httpRequest.getUri());

        // Replace the HTTP host header with the SPDY host header
        String host = httpRequest.headers().get(HttpHeaders.Names.HOST);
        httpRequest.headers().remove(HttpHeaders.Names.HOST);
        httpHeadersFrame.headers().add(":authority", host);

        // Transfer the remaining HTTP headers
        for (Map.Entry<String, String> entry : httpRequest.headers()) {
            httpHeadersFrame.headers().add(entry.getKey(), entry.getValue());
        }

        return httpHeadersFrame;
    }

    private HttpHeadersFrame createHttpHeadersFrame(HttpResponse httpResponse)
            throws Exception {
        // Get the Stream-ID from the headers
        int streamId = HttpHeaders.getIntHeader(httpResponse, "X-SPDY-Stream-ID");
        httpResponse.headers().remove("X-SPDY-Stream-ID");

        // The Connection, Keep-Alive, Proxy-Connection, and Transfer-Encoding
        // headers are not valid and MUST not be sent.
        httpResponse.headers().remove(HttpHeaders.Names.CONNECTION);
        httpResponse.headers().remove("Keep-Alive");
        httpResponse.headers().remove("Proxy-Connection");
        httpResponse.headers().remove(HttpHeaders.Names.TRANSFER_ENCODING);

        HttpHeadersFrame httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);

        // Unfold the first line of the response into name/value pairs
        httpHeadersFrame.headers().set(":status", httpResponse.getStatus().code());

        // Transfer the remaining HTTP headers
        for (Map.Entry<String, String> entry : httpResponse.headers()) {
            httpHeadersFrame.headers().add(entry.getKey(), entry.getValue());
        }

        return httpHeadersFrame;
    }

    private HttpDataFrame[] createHttpDataFrames(int streamId, ByteBuf content) {
        int readableBytes = content.readableBytes();
        int count = readableBytes / MAX_DATA_LENGTH;
        if (readableBytes % MAX_DATA_LENGTH > 0) {
            count++;
        }
        HttpDataFrame[] httpDataFrames = new HttpDataFrame[count];
        for (int i = 0; i < count; i++) {
            int dataSize = Math.min(content.readableBytes(), MAX_DATA_LENGTH);
            HttpDataFrame httpDataFrame = new DefaultHttpDataFrame(streamId, content.readSlice(dataSize));
            httpDataFrames[i] = httpDataFrame;
        }
        return httpDataFrames;
    }
}
