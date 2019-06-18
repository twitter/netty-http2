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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.HeaderListener;

final class HttpHeaderBlockDecoder {

    private static final HeaderListener NULL_HEADER_LISTENER = new NullHeaderListener();

    private Decoder decoder;
    private ByteBuf cumulation;

    public HttpHeaderBlockDecoder(int maxHeaderSize, int maxHeaderTableSize) {
        decoder = new Decoder(maxHeaderSize, maxHeaderTableSize);
    }

    /**
     * Set the maximum header table size allowed by the decoder.
     * This is the value of SETTINGS_HEADER_TABLE_SIZE sent to the peer.
     *
     * @param maxHeaderTableSize the maximum header table size allowed by the decoder
     */
    public void setMaxHeaderTableSize(int maxHeaderTableSize) {
        decoder.setMaxHeaderTableSize(maxHeaderTableSize);
    }

    public void decode(ByteBuf headerBlock, final HttpHeaderBlockFrame frame) throws IOException {
        HeaderListener headerListener = NULL_HEADER_LISTENER;
        if (frame != null) {
            headerListener = new HeaderListenerImpl(frame.headers());
        }

        if (cumulation == null) {
            decoder.decode(new ByteBufInputStream(headerBlock), headerListener);
            if (headerBlock.isReadable()) {
                cumulation = Unpooled.buffer(headerBlock.readableBytes());
                cumulation.writeBytes(headerBlock);
            }
        } else {
            cumulation.writeBytes(headerBlock);
            decoder.decode(new ByteBufInputStream(cumulation), headerListener);
            if (cumulation.isReadable()) {
                cumulation.discardReadBytes();
            } else {
                cumulation.release();
                cumulation = null;
            }
        }
    }

    public void endHeaderBlock(final HttpHeaderBlockFrame frame) {
        if (cumulation != null) {
            if (cumulation.isReadable() && frame != null) {
                frame.setInvalid();
            }
            cumulation.release();
            cumulation = null;
        }

        boolean truncated = decoder.endHeaderBlock();

        if (truncated && frame != null) {
            frame.setTruncated();
        }
    }

    private static final class NullHeaderListener implements HeaderListener {
        @Override
        public void addHeader(byte[] name, byte[] value, boolean sensitive) {
            // No Op
        }
    }

    private static final class HeaderListenerImpl implements HeaderListener {

        private final HttpHeaders headers;

        HeaderListenerImpl(HttpHeaders headers) {
            this.headers = headers;
        }

        @Override
        public void addHeader(byte[] name, byte[] value, boolean sensitive) {
            String nameStr = new String(name, StandardCharsets.UTF_8);

            // check for empty value
            if (value.length == 0) {
                addHeader(nameStr, "");
                return;
            }

            // Sec. 8.1.3.3. Header Field Ordering
            int index = 0;
            int offset = 0;
            while (index < value.length) {
                while (index < value.length && value[index] != (byte) 0) {
                    index++;
                }
                if (index - offset == 0) {
                    addHeader(nameStr, "");
                } else {
                    String valueStr = new String(value, offset, index - offset, StandardCharsets.UTF_8);
                    addHeader(nameStr, valueStr);
                }
                index++;
                offset = index;
            }
        }

        private void addHeader(String name, String value) {
            boolean crumb = "cookie".equalsIgnoreCase(name);
            if (value.length() == 0) {
                if (crumb || headers.contains(name)) {
                    return;
                }
            }
            if (crumb) {
                // Sec. 8.1.3.4. Cookie Header Field
                String cookie = headers.get(name);
                if (cookie == null) {
                    headers.set(name, value);
                } else {
                    headers.set(name, cookie + "; " + value);
                }
            } else {
                headers.add(name, value);
            }
        }
    }
}
