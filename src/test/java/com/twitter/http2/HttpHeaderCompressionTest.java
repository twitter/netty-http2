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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HttpHeaderCompressionTest {

    @Test
    public void testHttpHeadersFrame() throws Throwable {
        HttpHeadersFrame httpHeadersFrame = new DefaultHttpHeadersFrame(1);
        httpHeadersFrame.headers().add("name", "value");
        testHeaderEcho(httpHeadersFrame);
    }

    private void testHeaderEcho(HttpHeaderBlockFrame frame) throws Throwable {
        final EchoHandler sh = new EchoHandler();
        final TestHandler ch = new TestHandler(frame);

        ServerBootstrap sb = new ServerBootstrap()
                .group(new LocalEventLoopGroup())
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel channel) throws Exception {
                        channel.pipeline().addLast(new HttpConnectionHandler(true), sh);
                    }
                });
        Bootstrap cb = new Bootstrap()
                .group(new LocalEventLoopGroup())
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel channel) throws Exception {
                        channel.pipeline().addLast(new HttpConnectionHandler(false), ch);
                    }
                });

        LocalAddress localAddress = new LocalAddress("HttpHeaderCompressionTest");
        Channel sc = sb.bind(localAddress).sync().channel();
        ChannelFuture ccf = cb.connect(localAddress);
        assertTrue(ccf.awaitUninterruptibly().isSuccess());

        while (!ch.success) {
            if (sh.exception.get() != null) {
                break;
            }
            if (ch.exception.get() != null) {
                break;
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        sc.close().awaitUninterruptibly();
        cb.group().shutdownGracefully();
        sb.group().shutdownGracefully();

        if (sh.exception.get() != null && !(sh.exception.get() instanceof IOException)) {
            throw sh.exception.get();
        }
        if (ch.exception.get() != null && !(ch.exception.get() instanceof IOException)) {
            throw ch.exception.get();
        }
        if (sh.exception.get() != null) {
            throw sh.exception.get();
        }
        if (ch.exception.get() != null) {
            throw ch.exception.get();
        }
    }

    private static class EchoHandler extends ChannelInboundHandlerAdapter {
        public final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ctx.writeAndFlush(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (exception.compareAndSet(null, cause)) {
                ctx.close();
            }
        }
    }

    private static class TestHandler extends ChannelInboundHandlerAdapter {
        private static final byte[] CONNECTION_HEADER =
                "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        public final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
        public final HttpHeaderBlockFrame frame;

        public volatile boolean success;

        public TestHandler(HttpHeaderBlockFrame frame) {
            this.frame = frame;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.write(Unpooled.wrappedBuffer(CONNECTION_HEADER));
            ctx.writeAndFlush(frame);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            assertTrue(msg instanceof HttpHeaderBlockFrame);
            HttpHeaders actual = ((HttpHeaderBlockFrame) msg).headers();
            HttpHeaders expected = frame.headers();
            for (String name : expected.names()) {
                List<String> expectedValues = new ArrayList<String>(expected.getAll(name));
                List<String> actualValues = new ArrayList<String>(actual.getAll(name));
                assertTrue(actualValues.containsAll(expectedValues));
                actualValues.removeAll(expectedValues);
                assertTrue(actualValues.isEmpty());
                actual.remove(name);
            }
            assertTrue(actual.isEmpty());
            success = true;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (exception.compareAndSet(null, cause)) {
                ctx.close();
            }
        }
    }
}
