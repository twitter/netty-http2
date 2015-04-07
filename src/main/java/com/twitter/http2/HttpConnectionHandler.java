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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;

import static com.twitter.http2.HttpCodecUtil.HTTP_CONNECTION_STREAM_ID;
import static com.twitter.http2.HttpCodecUtil.isServerId;

/**
 * Manages streams within an HTTP/2 connection.
 */
public class HttpConnectionHandler extends ByteToMessageDecoder
        implements HttpFrameDecoderDelegate, ChannelOutboundHandler {

    private static final HttpProtocolException PROTOCOL_EXCEPTION =
            new HttpProtocolException();

    private static final HttpSettingsFrame SETTINGS_ACK_FRAME =
            new DefaultHttpSettingsFrame().setAck(true);

    private static final int DEFAULT_HEADER_TABLE_SIZE = 4096;

    private static final int DEFAULT_WINDOW_SIZE = 65535;
    private volatile int initialSendWindowSize = DEFAULT_WINDOW_SIZE;
    private volatile int initialReceiveWindowSize = DEFAULT_WINDOW_SIZE;

    private final HttpConnection httpConnection =
            new HttpConnection(initialSendWindowSize, initialReceiveWindowSize);
    private volatile int lastStreamId;

    private static final int DEFAULT_MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE;
    private volatile int remoteConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private volatile int localConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;

    private final Object flowControlLock = new Object();

    private volatile boolean sentGoAwayFrame;
    private volatile boolean receivedGoAwayFrame;

    private final ChannelFutureListener connectionErrorListener =
            new ConnectionErrorFutureListener();
    private volatile ChannelFutureListener closingChannelFutureListener;

    private final boolean server;

    private final HttpFrameDecoder httpFrameDecoder;
    private final HttpFrameEncoder httpFrameEncoder;
    private final HttpHeaderBlockDecoder httpHeaderBlockDecoder;
    private final HttpHeaderBlockEncoder httpHeaderBlockEncoder;

    private HttpHeaderBlockFrame httpHeaderBlockFrame;
    private HttpSettingsFrame httpSettingsFrame;

    private boolean needSettingsAck;

    private volatile boolean changeDecoderHeaderTableSize;
    private volatile int headerTableSize;

    private boolean changeEncoderHeaderTableSize;
    private int lastHeaderTableSize = Integer.MAX_VALUE;
    private int minHeaderTableSize = Integer.MAX_VALUE;
    private boolean pushEnabled = true;

    private volatile ChannelHandlerContext context;

    /**
     * Creates a new connection handler.
     *
     * @param server {@code true} if and only if this connection handler should
     *               handle the server endpoint of the connection.
     *               {@code false} if and only if this connection handler should
     *               handle the client endpoint of the connection.
     */
    public HttpConnectionHandler(boolean server) {
        this(server, 8192, 16384);
    }

    /**
     * Creates a new connection handler with the specified options.
     */
    public HttpConnectionHandler(boolean server, int maxChunkSize, int maxHeaderSize) {
        this.server = server;
        httpFrameDecoder = new HttpFrameDecoder(server, this, maxChunkSize);
        httpFrameEncoder = new HttpFrameEncoder();
        httpHeaderBlockDecoder = new HttpHeaderBlockDecoder(maxHeaderSize, DEFAULT_HEADER_TABLE_SIZE);
        httpHeaderBlockEncoder = new HttpHeaderBlockEncoder(DEFAULT_HEADER_TABLE_SIZE);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        context = ctx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        httpFrameDecoder.decode(in);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readDataFramePadding(int streamId, boolean endStream, int padding) {
        int deltaWindowSize = -1 * padding;
        int newConnectionWindowSize = httpConnection.updateReceiveWindowSize(
                HTTP_CONNECTION_STREAM_ID, deltaWindowSize);

        // Check if connection window size is reduced beyond allowable lower bound
        if (newConnectionWindowSize < 0) {
            issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
            return;
        }

        // Send a WINDOW_UPDATE frame if less than half the connection window size remains
        if (newConnectionWindowSize <= initialReceiveWindowSize / 2) {
            int windowSizeIncrement = initialReceiveWindowSize - newConnectionWindowSize;
            httpConnection.updateReceiveWindowSize(HTTP_CONNECTION_STREAM_ID, windowSizeIncrement);
            ByteBuf frame = httpFrameEncoder.encodeWindowUpdateFrame(
                    HTTP_CONNECTION_STREAM_ID, windowSizeIncrement);
            context.writeAndFlush(frame);
        }

        // Check if we received a DATA frame for a stream which is half-closed (remote) or closed
        if (httpConnection.isRemoteSideClosed(streamId)) {
            if (streamId <= lastStreamId) {
                issueStreamError(streamId, HttpErrorCode.STREAM_CLOSED);
            } else if (!sentGoAwayFrame) {
                issueStreamError(streamId, HttpErrorCode.PROTOCOL_ERROR);
            }
            return;
        }

        // Update receive window size
        int newWindowSize = httpConnection.updateReceiveWindowSize(streamId, deltaWindowSize);

        // Window size can become negative if we sent a SETTINGS frame that reduces the
        // size of the transfer window after the peer has written data frames.
        // The value is bounded by the length that SETTINGS frame decrease the window.
        // This difference is stored for the connection when writing the SETTINGS frame
        // and is cleared once we send a WINDOW_UPDATE frame.
        if (newWindowSize < httpConnection.getReceiveWindowSizeLowerBound(streamId)) {
            issueStreamError(streamId, HttpErrorCode.FLOW_CONTROL_ERROR);
            return;
        }

        // Send a WINDOW_UPDATE frame if less than half the stream window size remains
        // Recipient should not send a WINDOW_UPDATE frame as it consumes the last data frame.
        if (newWindowSize <= initialReceiveWindowSize / 2 && !endStream) {
            int windowSizeIncrement = initialReceiveWindowSize - newWindowSize;
            httpConnection.updateReceiveWindowSize(streamId, windowSizeIncrement);
            ByteBuf frame = httpFrameEncoder.encodeWindowUpdateFrame(streamId, windowSizeIncrement);
            context.writeAndFlush(frame);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readDataFrame(int streamId, boolean endStream, boolean endSegment, ByteBuf data) {
        // HTTP/2 DATA frame processing requirements:
        //
        // If an endpoint receives a data frame for a Stream-ID which is not open
        // and the endpoint has not sent a GOAWAY frame, it must issue a stream error
        // with the error code INVALID_STREAM for the Stream-ID.
        //
        // If an endpoint receives multiple data frames for invalid Stream-IDs,
        // it may close the connection.
        //
        // If an endpoint refuses a stream it must ignore any data frames for that stream.
        //
        // If an endpoint receives a data frame after the stream is half-closed (remote)
        // or closed, it must respond with a stream error of type STREAM_CLOSED.

        int deltaWindowSize = -1 * data.readableBytes();
        int newConnectionWindowSize = httpConnection.updateReceiveWindowSize(
                HTTP_CONNECTION_STREAM_ID, deltaWindowSize);

        // Check if connection window size is reduced beyond allowable lower bound
        if (newConnectionWindowSize < 0) {
            issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
            return;
        }

        // Send a WINDOW_UPDATE frame if less than half the connection window size remains
        if (newConnectionWindowSize <= initialReceiveWindowSize / 2) {
            int windowSizeIncrement = initialReceiveWindowSize - newConnectionWindowSize;
            httpConnection.updateReceiveWindowSize(HTTP_CONNECTION_STREAM_ID, windowSizeIncrement);
            ByteBuf frame = httpFrameEncoder.encodeWindowUpdateFrame(
                    HTTP_CONNECTION_STREAM_ID, windowSizeIncrement);
            context.writeAndFlush(frame);
        }

        // Check if we received a DATA frame for a stream which is half-closed (remote) or closed
        if (httpConnection.isRemoteSideClosed(streamId)) {
            if (streamId <= lastStreamId) {
                issueStreamError(streamId, HttpErrorCode.STREAM_CLOSED);
            } else if (!sentGoAwayFrame) {
                issueStreamError(streamId, HttpErrorCode.PROTOCOL_ERROR);
            }
            return;
        }

        // Update receive window size
        int newWindowSize = httpConnection.updateReceiveWindowSize(streamId, deltaWindowSize);

        // Window size can become negative if we sent a SETTINGS frame that reduces the
        // size of the transfer window after the peer has written data frames.
        // The value is bounded by the length that SETTINGS frame decrease the window.
        // This difference is stored for the connection when writing the SETTINGS frame
        // and is cleared once we send a WINDOW_UPDATE frame.
        if (newWindowSize < httpConnection.getReceiveWindowSizeLowerBound(streamId)) {
            issueStreamError(streamId, HttpErrorCode.FLOW_CONTROL_ERROR);
            return;
        }

        // Window size became negative due to sender writing frame before receiving SETTINGS
        // Send data frames upstream in initialReceiveWindowSize chunks
        if (newWindowSize < 0) {
            while (data.readableBytes() > initialReceiveWindowSize) {
                ByteBuf partialData = data.readBytes(initialReceiveWindowSize);
                HttpDataFrame partialDataFrame = new DefaultHttpDataFrame(streamId, partialData);
                context.fireChannelRead(partialDataFrame);
            }
        }

        // Send a WINDOW_UPDATE frame if less than half the stream window size remains
        // Recipient should not send a WINDOW_UPDATE frame as it consumes the last data frame.
        if (newWindowSize <= initialReceiveWindowSize / 2 && !endStream) {
            int windowSizeIncrement = initialReceiveWindowSize - newWindowSize;
            httpConnection.updateReceiveWindowSize(streamId, windowSizeIncrement);
            ByteBuf frame = httpFrameEncoder.encodeWindowUpdateFrame(streamId, windowSizeIncrement);
            context.writeAndFlush(frame);
        }

        // Close the remote side of the stream if this is the last frame
        if (endStream) {
            halfCloseStream(streamId, true, context.channel().newSucceededFuture());
        }

        HttpDataFrame httpDataFrame = new DefaultHttpDataFrame(streamId, data);
        httpDataFrame.setLast(endStream);
        context.fireChannelRead(httpDataFrame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readHeadersFrame(
            int streamId,
            boolean endStream,
            boolean endSegment,
            boolean exclusive,
            int dependency,
            int weight
    ) {
        // HTTP/2 HEADERS frame processing requirements:
        //
        // If an endpoint receives a HEADERS frame with a Stream-ID that is less than
        // any previously received HEADERS, it must issue a connection error of type
        // PROTOCOL_ERROR.
        //
        // If an endpoint receives multiple SYN_STREAM frames with the same active
        // Stream-ID, it must issue a stream error with the status code PROTOCOL_ERROR.
        //
        // The recipient can reject a stream by sending a stream error with the
        // status code REFUSED_STREAM.

        if (isRemoteInitiatedId(streamId)) {
            if (streamId <= lastStreamId) {
                // Check if we received a HEADERS frame for a stream which is half-closed (remote) or closed
                if (httpConnection.isRemoteSideClosed(streamId)) {
                    issueStreamError(streamId, HttpErrorCode.STREAM_CLOSED);
                    return;
                }
            } else {
                // Try to accept the stream
                if (!acceptStream(streamId, exclusive, dependency, weight)) {
                    issueStreamError(streamId, HttpErrorCode.REFUSED_STREAM);
                    return;
                }
            }
        } else {
            // Check if we received a HEADERS frame for a stream which is half-closed (remote) or closed
            if (httpConnection.isRemoteSideClosed(streamId)) {
                issueStreamError(streamId, HttpErrorCode.STREAM_CLOSED);
                return;
            }
        }

        // Close the remote side of the stream if this is the last frame
        if (endStream) {
            halfCloseStream(streamId, true, context.channel().newSucceededFuture());
        }

        HttpHeadersFrame httpHeadersFrame = new DefaultHttpHeadersFrame(streamId);
        httpHeadersFrame.setLast(endStream);
        httpHeadersFrame.setExclusive(exclusive);
        httpHeadersFrame.setDependency(dependency);
        httpHeadersFrame.setWeight(weight);
        httpHeaderBlockFrame = httpHeadersFrame;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readPriorityFrame(int streamId, boolean exclusive, int dependency, int weight) {
        if (streamId == dependency) {
            issueStreamError(streamId, HttpErrorCode.PROTOCOL_ERROR);
        } else {
            setPriority(streamId, exclusive, dependency, weight);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readRstStreamFrame(int streamId, int errorCode) {
        // If a RST_STREAM frame identifying an idle stream is received,
        // the recipient MUST treat this as a connection error of type
        // PROTOCOL_ERROR.
        removeStream(streamId, context.channel().newSucceededFuture());
        HttpRstStreamFrame httpRstStreamFrame = new DefaultHttpRstStreamFrame(streamId, errorCode);
        context.fireChannelRead(httpRstStreamFrame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readSettingsFrame(boolean ack) {
        needSettingsAck = !ack;
        httpSettingsFrame = new DefaultHttpSettingsFrame();
        httpSettingsFrame.setAck(ack);
        if (ack && changeDecoderHeaderTableSize) {
            httpHeaderBlockDecoder.setMaxHeaderTableSize(headerTableSize);
            changeDecoderHeaderTableSize = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readSetting(int id, int value) {
        httpSettingsFrame.setValue(id, value);
        switch (id) {
        case HttpSettingsFrame.SETTINGS_HEADER_TABLE_SIZE:
            // Ignore 'negative' values -- they are too large for java
            if (value >= 0) {
                changeEncoderHeaderTableSize = true;
                lastHeaderTableSize = value;
                if (lastHeaderTableSize < minHeaderTableSize) {
                    minHeaderTableSize = lastHeaderTableSize;
                }
            }
            break;
        case HttpSettingsFrame.SETTINGS_ENABLE_PUSH:
            if (value == 0) {
                pushEnabled = false;
            } else if (value == 1) {
                pushEnabled = true;
            } else {
                issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
            }
            break;
        case HttpSettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS:
            if (value >= 0) {
                remoteConcurrentStreams = value;
            }
            break;
        case HttpSettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE:
            if (value >= 0) {
                updateInitialSendWindowSize(value);
            } else {
                issueConnectionError(HttpErrorCode.FLOW_CONTROL_ERROR);
            }
            break;
        case HttpSettingsFrame.SETTINGS_MAX_FRAME_SIZE:
            if (value != HttpCodecUtil.HTTP_MAX_LENGTH) {
                issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
            }
            break;
        default:
            // TODO(jpinner) VERIFY THIS BEHAVIOR
            // Ignore Unknown Settings
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readSettingsEnd() {
        if (changeEncoderHeaderTableSize) {
            synchronized (httpHeaderBlockEncoder) {
                httpHeaderBlockEncoder.setDecoderMaxHeaderTableSize(minHeaderTableSize);
                httpHeaderBlockEncoder.setDecoderMaxHeaderTableSize(lastHeaderTableSize);

                // Writes of settings ack must occur in order
                ByteBuf frame = httpFrameEncoder.encodeSettingsFrame(SETTINGS_ACK_FRAME);
                context.writeAndFlush(frame);
            }
            changeEncoderHeaderTableSize = false;
            lastHeaderTableSize = Integer.MAX_VALUE;
            minHeaderTableSize = Integer.MAX_VALUE;
        } else if (needSettingsAck) {
            ByteBuf frame = httpFrameEncoder.encodeSettingsFrame(SETTINGS_ACK_FRAME);
            context.writeAndFlush(frame);
        }
        Object frame = httpSettingsFrame;
        httpSettingsFrame = null;
        context.fireChannelRead(frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readPushPromiseFrame(int streamId, int promisedStreamId) {
        // TODO(jpinner) handle push promise frames
        // Any we receive must be associated with a "peer-initiated" stream.
        // Since we don't have a way currently to initiate streams, any
        // frame that we receive must be treated as a protocol error.
        issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readPingFrame(long data, boolean ack) {
        // HTTP/2 PING frame processing requirements:
        //
        // Receivers of a PING frame should send an identical frame to the sender
        // as soon as possible.
        //
        // Receivers of a PING frame must ignore frames that it did not initiate
        HttpPingFrame httpPingFrame = new DefaultHttpPingFrame(data);
        httpPingFrame.setPong(true);
        if (ack) {
            context.fireChannelRead(httpPingFrame);
        } else {
            ByteBuf frame = httpFrameEncoder.encodePingFrame(data, false);
            context.writeAndFlush(frame);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readGoAwayFrame(int lastStreamId, int errorCode) {
        receivedGoAwayFrame = true;
        HttpGoAwayFrame httpGoAwayFrame = new DefaultHttpGoAwayFrame(lastStreamId, errorCode);
        context.fireChannelRead(httpGoAwayFrame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readWindowUpdateFrame(int streamId, int windowSizeIncrement) {
        //
        // HTTP/2 WINDOW_UPDATE frame processing requirements:
        //
        // Receivers of a WINDOW_UPDATE that cause the window size to exceed 2^31
        // must send a RST_STREAM with the status code FLOW_CONTROL_ERROR.
        //
        // Sender should ignore all WINDOW_UPDATE frames associated with a stream
        // after sending the last frame for the stream.
        //
        // TODO(jpinner) handle disabled flow control

        // Ignore frames for half-closed streams
        if (streamId != HTTP_CONNECTION_STREAM_ID && httpConnection.isLocalSideClosed(streamId)) {
            return;
        }

        // Check for numerical overflow
        if (httpConnection.getSendWindowSize(streamId) > Integer.MAX_VALUE - windowSizeIncrement) {
            if (streamId == HTTP_CONNECTION_STREAM_ID) {
                issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
            } else {
                issueStreamError(streamId, HttpErrorCode.FLOW_CONTROL_ERROR);
            }
            return;
        }

        updateSendWindowSize(context, streamId, windowSizeIncrement);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readHeaderBlock(ByteBuf headerBlockFragment) {
        try {
            httpHeaderBlockDecoder.decode(headerBlockFragment, httpHeaderBlockFrame);
        } catch (IOException e) {
            httpHeaderBlockFrame = null;
            issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readHeaderBlockEnd() {
        httpHeaderBlockDecoder.endHeaderBlock(httpHeaderBlockFrame);

        if (httpHeaderBlockFrame == null) {
            return;
        }

        // Check if we received a valid Header Block
        if (httpHeaderBlockFrame.isInvalid()) {
            issueStreamError(httpHeaderBlockFrame.getStreamId(), HttpErrorCode.PROTOCOL_ERROR);
            return;
        }

        Object frame = httpHeaderBlockFrame;
        httpHeaderBlockFrame = null;
        context.fireChannelRead(frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readFrameError(String message) {
        issueConnectionError(HttpErrorCode.PROTOCOL_ERROR);
    }

    @Override
    public void bind(
            ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
            throws Exception {
        ctx.bind(localAddress, promise);
    }

    @Override
    public void connect(
            ChannelHandlerContext ctx,
            SocketAddress remoteAddress,
            SocketAddress localAddress,
            ChannelPromise promise
    ) throws Exception {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        // HTTP/2 connection requirements:
        //
        // When either endpoint closes the transport-level connection,
        // it must first send a GOAWAY frame.
        //
        // Avoid NotYetConnectedException
        if (!ctx.channel().isActive()) {
            ctx.disconnect(promise);
        } else {
            sendGoAwayFrame(ctx, promise);
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        // HTTP/2 connection requirements:
        //
        // When either endpoint closes the transport-level connection,
        // it must first send a GOAWAY frame.
        //
        // Avoid NotYetConnectedException
        if (!ctx.channel().isActive()) {
            ctx.close(promise);
        } else {
            sendGoAwayFrame(ctx, promise);
        }
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.deregister(promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        ctx.read();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof HttpDataFrame) {

            HttpDataFrame httpDataFrame = (HttpDataFrame) msg;
            int streamId = httpDataFrame.getStreamId();

            // Frames must not be sent on half-closed streams
            if (httpConnection.isLocalSideClosed(streamId)) {
                promise.setFailure(PROTOCOL_EXCEPTION);
                return;
            }

      /*
      * SPDY Data frame flow control processing requirements:
      *
      * Sender must not send a data frame with data length greater
      * than the transfer window size.
      *
      * After sending each data frame, the sender decrements its
      * transfer window size by the amount of data transmitted.
      *
      * When the window size becomes less than or equal to 0, the
      * sender must pause transmitting data frames.
      */
            synchronized (flowControlLock) {
                int dataLength = httpDataFrame.content().readableBytes();
                int sendWindowSize = httpConnection.getSendWindowSize(streamId);
                int connectionSendWindowSize = httpConnection.getSendWindowSize(HTTP_CONNECTION_STREAM_ID);
                sendWindowSize = Math.min(sendWindowSize, connectionSendWindowSize);

                if (sendWindowSize <= 0) {
                    // Stream is stalled -- enqueue Data frame and return
                    httpConnection.putPendingWrite(
                            streamId, new HttpConnection.PendingWrite(httpDataFrame, promise));
                    return;
                } else if (sendWindowSize < dataLength) {
                    // Stream is not stalled but we cannot send the entire frame
                    httpConnection.updateSendWindowSize(streamId, -1 * sendWindowSize);
                    httpConnection.updateSendWindowSize(HTTP_CONNECTION_STREAM_ID, -1 * sendWindowSize);

                    // Create a partial data frame whose length is the current window size
                    ByteBuf data = httpDataFrame.content().readSlice(sendWindowSize);
                    ByteBuf partialDataFrame = httpFrameEncoder.encodeDataFrame(streamId, false, data);

                    // Enqueue the remaining data (will be the first frame queued)
                    httpConnection.putPendingWrite(
                            streamId, new HttpConnection.PendingWrite(httpDataFrame, promise));

                    ChannelPromise writeFuture = ctx.channel().newPromise();

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the connection on write failures that leaves the transfer window in a corrupt state.
                    writeFuture.addListener(connectionErrorListener);

                    ctx.write(partialDataFrame, writeFuture);
                    return;
                } else {
                    // Window size is large enough to send entire data frame
                    httpConnection.updateSendWindowSize(streamId, -1 * dataLength);
                    httpConnection.updateSendWindowSize(HTTP_CONNECTION_STREAM_ID, -1 * dataLength);

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the connection on write failures that leaves the transfer window in a corrupt state.
                    promise.addListener(connectionErrorListener);
                }
            }

            // Close the local side of the stream if this is the last frame
            if (httpDataFrame.isLast()) {
                halfCloseStream(streamId, false, promise);
            }

            ByteBuf frame = httpFrameEncoder.encodeDataFrame(
                    streamId,
                    httpDataFrame.isLast(),
                    httpDataFrame.content()
            );
            ctx.write(frame, promise);

        } else if (msg instanceof HttpHeadersFrame) {

            HttpHeadersFrame httpHeadersFrame = (HttpHeadersFrame) msg;
            int streamId = httpHeadersFrame.getStreamId();

            if (streamId <= lastStreamId) {
                // Frames must not be sent on half-closed (local) or closed streams
                if (httpConnection.isLocalSideClosed(streamId)) {
                    promise.setFailure(PROTOCOL_EXCEPTION);
                    return;
                }
            } else {
                if (isRemoteInitiatedId(streamId)) {
                    promise.setFailure(PROTOCOL_EXCEPTION);
                    return;
                }
                // Try to accept the stream
                boolean exclusive = httpHeadersFrame.isExclusive();
                int dependency = httpHeadersFrame.getDependency();
                int weight = httpHeadersFrame.getWeight();
                if (!acceptStream(streamId, exclusive, dependency, weight)) {
                    promise.setFailure(PROTOCOL_EXCEPTION);
                    return;
                }
            }

            // Close the local side of the stream if this is the last frame
            if (httpHeadersFrame.isLast()) {
                halfCloseStream(streamId, false, promise);
            }

            synchronized (httpHeaderBlockEncoder) {
                ByteBuf frame = httpFrameEncoder.encodeHeadersFrame(
                        httpHeadersFrame.getStreamId(),
                        httpHeadersFrame.isLast(),
                        httpHeadersFrame.isExclusive(),
                        httpHeadersFrame.getDependency(),
                        httpHeadersFrame.getWeight(),
                        httpHeaderBlockEncoder.encode(ctx, httpHeadersFrame)
                );
                // Writes of compressed data must occur in order
                ctx.write(frame, promise);
            }

        } else if (msg instanceof HttpPriorityFrame) {

            HttpPriorityFrame httpPriorityFrame = (HttpPriorityFrame) msg;
            int streamId = httpPriorityFrame.getStreamId();
            boolean exclusive = httpPriorityFrame.isExclusive();
            int dependency = httpPriorityFrame.getDependency();
            int weight = httpPriorityFrame.getWeight();
            setPriority(streamId, exclusive, dependency, weight);
            ByteBuf frame = httpFrameEncoder.encodePriorityFrame(
                    streamId,
                    exclusive,
                    dependency,
                    weight
            );
            ctx.write(frame, promise);

        } else if (msg instanceof HttpRstStreamFrame) {

            HttpRstStreamFrame httpRstStreamFrame = (HttpRstStreamFrame) msg;
            removeStream(httpRstStreamFrame.getStreamId(), promise);
            ByteBuf frame = httpFrameEncoder.encodeRstStreamFrame(
                    httpRstStreamFrame.getStreamId(),
                    httpRstStreamFrame.getErrorCode().getCode());
            ctx.write(frame, promise);

        } else if (msg instanceof HttpSettingsFrame) {

            // TODO(jpinner) currently cannot have more than one settings frame outstanding at a time

            HttpSettingsFrame httpSettingsFrame = (HttpSettingsFrame) msg;
            if (httpSettingsFrame.isAck()) {
                // Cannot send an acknowledgement frame
                promise.setFailure(PROTOCOL_EXCEPTION);
                return;
            }

            int newHeaderTableSize =
                    httpSettingsFrame.getValue(HttpSettingsFrame.SETTINGS_HEADER_TABLE_SIZE);
            if (newHeaderTableSize >= 0) {
                headerTableSize = newHeaderTableSize;
                changeDecoderHeaderTableSize = true;
            }

            int newConcurrentStreams =
                    httpSettingsFrame.getValue(HttpSettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS);
            if (newConcurrentStreams >= 0) {
                localConcurrentStreams = newConcurrentStreams;
            }

            int newInitialWindowSize =
                    httpSettingsFrame.getValue(HttpSettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE);
            if (newInitialWindowSize >= 0) {
                updateInitialReceiveWindowSize(newInitialWindowSize);
            }

            ByteBuf frame = httpFrameEncoder.encodeSettingsFrame(httpSettingsFrame);
            ctx.write(frame, promise);

        } else if (msg instanceof HttpPushPromiseFrame) {

            if (!pushEnabled) {
                promise.setFailure(PROTOCOL_EXCEPTION);
                return;
            }

            // TODO(jpinner) handle push promise frames
            promise.setFailure(PROTOCOL_EXCEPTION);

//      synchronized (httpHeaderBlockEncoder) {
//        HttpPushPromiseFrame httpPushPromiseFrame = (HttpPushPromiseFrame) msg;
//        ChannelBuffer frame = httpFrameEncoder.encodePushPromiseFrame(
//            httpPushPromiseFrame.getStreamId(),
//            httpPushPromiseFrame.getPromisedStreamId(),
//            httpHeaderBlockEncoder.encode(ctx, httpPushPromiseFrame)
//        );
//        // Writes of compressed data must occur in order
//        Channels.write(ctx, e.getFuture(), frame, e.getRemoteAddress());
//      }

        } else if (msg instanceof HttpPingFrame) {

            HttpPingFrame httpPingFrame = (HttpPingFrame) msg;
            if (httpPingFrame.isPong()) {
                // Cannot send a PONG frame
                promise.setFailure(PROTOCOL_EXCEPTION);
            } else {
                ByteBuf frame = httpFrameEncoder.encodePingFrame(httpPingFrame.getData(), false);
                ctx.write(frame, promise);
            }

        } else if (msg instanceof HttpGoAwayFrame) {

            // Why is this being sent? Intercept it and fail the write.
            // Should have sent a CLOSE ChannelStateEvent
            promise.setFailure(PROTOCOL_EXCEPTION);

        } else if (msg instanceof HttpWindowUpdateFrame) {

            // Why is this being sent? Intercept it and fail the write.
            promise.setFailure(PROTOCOL_EXCEPTION);

        } else {

            ctx.write(msg, promise);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    // HTTP/2 Connection Error Handling:
    //
    // When a connection error occurs, the endpoint encountering the error must first
    // send a GOAWAY frame with the stream identifier of the most recently received stream
    // from the remote endpoint, and the error code for why the connection is terminating.
    //
    // After sending the GOAWAY frame, the endpoint must close the TCP connection.
    private void issueConnectionError(HttpErrorCode status) {
        ChannelFuture future = sendGoAwayFrame(status);
        future.addListener(ChannelFutureListener.CLOSE);
    }

    /*
    * SPDY Stream Error Handling:
    *
    * Upon a stream error, the endpoint must send a RST_STREAM frame which contains
    * the Stream-ID for the stream where the error occurred and the error status which
    * caused the error.
    *
    * After sending the RST_STREAM, the stream is closed to the sending endpoint.
    *
    * Note: this is only called by the worker thread
    */
    private void issueStreamError(int streamId, HttpErrorCode errorCode) {

        boolean fireMessageReceived = !httpConnection.isRemoteSideClosed(streamId);
        ChannelPromise promise = context.channel().newPromise();
        removeStream(streamId, promise);

        ByteBuf frame = httpFrameEncoder.encodeRstStreamFrame(streamId, errorCode.getCode());
        context.writeAndFlush(frame, promise);
        if (fireMessageReceived) {
            HttpRstStreamFrame httpRstStreamFrame = new DefaultHttpRstStreamFrame(streamId, errorCode);
            context.fireChannelRead(httpRstStreamFrame);
        }
    }

    // Helper functions

    private boolean isRemoteInitiatedId(int id) {
        boolean serverId = isServerId(id);
        return server && !serverId || !server && serverId;
    }

    // need to synchronize to prevent new streams from being created while updating active streams
    private synchronized void updateInitialSendWindowSize(int newInitialWindowSize) {
        int deltaWindowSize = newInitialWindowSize - initialSendWindowSize;
        initialSendWindowSize = newInitialWindowSize;
        httpConnection.updateAllSendWindowSizes(deltaWindowSize);
    }

    // need to synchronize to prevent new streams from being created while updating active streams
    private synchronized void updateInitialReceiveWindowSize(int newInitialWindowSize) {
        int deltaWindowSize = newInitialWindowSize - initialReceiveWindowSize;
        initialReceiveWindowSize = newInitialWindowSize;
        httpConnection.updateAllReceiveWindowSizes(deltaWindowSize);
    }

    // need to synchronize accesses to sentGoAwayFrame, lastStreamId, and initial window sizes
    private synchronized boolean acceptStream(
            int streamId, boolean exclusive, int dependency, int weight) {
        // Cannot initiate any new streams after receiving or sending GOAWAY
        if (receivedGoAwayFrame || sentGoAwayFrame) {
            return false;
        }

        boolean remote = isRemoteInitiatedId(streamId);
        int maxConcurrentStreams = remote ? localConcurrentStreams : remoteConcurrentStreams;
        if (httpConnection.numActiveStreams(remote) >= maxConcurrentStreams) {
            return false;
        }
        httpConnection.acceptStream(
                streamId, false, false, initialSendWindowSize, initialReceiveWindowSize, remote);
        if (remote) {
            lastStreamId = streamId;
        }
        setPriority(streamId, exclusive, dependency, weight);
        return true;
    }

    private synchronized boolean setPriority(
            int streamId, boolean exclusive, int dependency, int weight) {
        return httpConnection.setPriority(streamId, exclusive, dependency, weight);
    }

    private void halfCloseStream(int streamId, boolean remote, ChannelFuture future) {
        if (remote) {
            httpConnection.closeRemoteSide(streamId, isRemoteInitiatedId(streamId));
        } else {
            httpConnection.closeLocalSide(streamId, isRemoteInitiatedId(streamId));
        }
        if (closingChannelFutureListener != null && httpConnection.noActiveStreams()) {
            future.addListener(closingChannelFutureListener);
        }
    }

    private void removeStream(int streamId, ChannelFuture future) {
        httpConnection.removeStream(streamId, isRemoteInitiatedId(streamId));
        if (closingChannelFutureListener != null && httpConnection.noActiveStreams()) {
            future.addListener(closingChannelFutureListener);
        }
    }

    private void updateSendWindowSize(
            ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
        synchronized (flowControlLock) {
            int newWindowSize = httpConnection.updateSendWindowSize(streamId, windowSizeIncrement);
            if (streamId != HTTP_CONNECTION_STREAM_ID) {
                int connectionSendWindowSize = httpConnection.getSendWindowSize(HTTP_CONNECTION_STREAM_ID);
                newWindowSize = Math.min(newWindowSize, connectionSendWindowSize);
            }

            while (newWindowSize > 0) {
                // Check if we have unblocked a stalled stream
                HttpConnection.PendingWrite e = httpConnection.getPendingWrite(streamId);
                if (e == null) {
                    break;
                }

                HttpDataFrame httpDataFrame = e.httpDataFrame;
                int dataFrameSize = httpDataFrame.content().readableBytes();
                final int writeStreamId = httpDataFrame.getStreamId();
                if (streamId == HTTP_CONNECTION_STREAM_ID) {
                    newWindowSize = Math.min(newWindowSize, httpConnection.getSendWindowSize(writeStreamId));
                }

                if (newWindowSize >= dataFrameSize) {
                    // Window size is large enough to send entire data frame
                    httpConnection.removePendingWrite(writeStreamId);
                    newWindowSize = httpConnection.updateSendWindowSize(writeStreamId, -1 * dataFrameSize);
                    int connectionSendWindowSize =
                            httpConnection.updateSendWindowSize(HTTP_CONNECTION_STREAM_ID, -1 * dataFrameSize);
                    newWindowSize = Math.min(newWindowSize, connectionSendWindowSize);

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the connection on write failures that leaves the transfer window in a corrupt state.
                    e.promise.addListener(connectionErrorListener);

                    // Close the local side of the stream if this is the last frame
                    if (httpDataFrame.isLast()) {
                        halfCloseStream(writeStreamId, false, e.promise);
                    }

                    ByteBuf frame = httpFrameEncoder.encodeDataFrame(
                            writeStreamId,
                            httpDataFrame.isLast(),
                            httpDataFrame.content()
                    );

                    ctx.write(frame, e.promise);
                } else {
                    // We can send a partial frame
                    httpConnection.updateSendWindowSize(writeStreamId, -1 * newWindowSize);
                    httpConnection.updateSendWindowSize(HTTP_CONNECTION_STREAM_ID, -1 * newWindowSize);

                    // Create a partial data frame whose length is the current window size
                    ByteBuf data = httpDataFrame.content().readSlice(newWindowSize);
                    ByteBuf partialDataFrame = httpFrameEncoder.encodeDataFrame(writeStreamId, false, data);

                    ChannelPromise writeFuture = ctx.channel().newPromise();

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the connection on write failures that leaves the transfer window in a corrupt state.
                    writeFuture.addListener(connectionErrorListener);

                    ctx.write(partialDataFrame, writeFuture);

                    newWindowSize = 0;
                }
            }
        }
    }

    private void sendGoAwayFrame(ChannelHandlerContext ctx, ChannelPromise promise) {
        ChannelFuture future = sendGoAwayFrame(HttpErrorCode.NO_ERROR);
        if (httpConnection.noActiveStreams()) {
            future.addListener(new ClosingChannelFutureListener(ctx, promise));
        } else {
            closingChannelFutureListener = new ClosingChannelFutureListener(ctx, promise);
        }
    }

    private synchronized ChannelFuture sendGoAwayFrame(HttpErrorCode httpErrorCode) {
        if (!sentGoAwayFrame) {
            sentGoAwayFrame = true;
            ChannelPromise promise = context.channel().newPromise();
            ByteBuf frame = httpFrameEncoder.encodeGoAwayFrame(lastStreamId, httpErrorCode.getCode());
            context.writeAndFlush(frame, promise);
            return promise;
        }
        return context.channel().newSucceededFuture();
    }

    private final class ConnectionErrorFutureListener implements ChannelFutureListener {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (!future.isSuccess()) {
                issueConnectionError(HttpErrorCode.INTERNAL_ERROR);
            }
        }
    }

    private static final class ClosingChannelFutureListener implements ChannelFutureListener {
        private final ChannelHandlerContext ctx;
        private final ChannelPromise promise;

        ClosingChannelFutureListener(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.ctx = ctx;
            this.promise = promise;
        }

        public void operationComplete(ChannelFuture sentGoAwayFuture) throws Exception {
            if (!(sentGoAwayFuture.cause() instanceof ClosedChannelException)) {
                ctx.close(promise);
            } else {
                promise.setSuccess();
            }
        }
    }
}
