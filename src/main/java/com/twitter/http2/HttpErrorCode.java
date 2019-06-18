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

public class HttpErrorCode implements Comparable<HttpErrorCode> {

    /**
     * 0 No Error
     */
    public static final HttpErrorCode NO_ERROR =
            new HttpErrorCode(0, "NO_ERROR");

    /**
     * 1 Protocol Error
     */
    public static final HttpErrorCode PROTOCOL_ERROR =
            new HttpErrorCode(1, "PROTOCOL_ERROR");

    /**
     * 2 Internal Error
     */
    public static final HttpErrorCode INTERNAL_ERROR =
            new HttpErrorCode(2, "INTERNAL_ERROR");

    /**
     * 3 Flow Control Error
     */
    public static final HttpErrorCode FLOW_CONTROL_ERROR =
            new HttpErrorCode(3, "FLOW_CONTROL_ERROR");

    /**
     * 4 Settings Timeout
     */
    public static final HttpErrorCode SETTINGS_TIMEOUT =
            new HttpErrorCode(4, "SETTINGS_TIMEOUT");

    /**
     * 5 Stream Closed
     */
    public static final HttpErrorCode STREAM_CLOSED =
            new HttpErrorCode(5, "STREAM_CLOSED");

    /**
     * 6 Frame Size Error
     */
    public static final HttpErrorCode FRAME_SIZE_ERROR =
            new HttpErrorCode(6, "FRAME_SIZE_ERROR");

    /**
     * 7 Refused Stream
     */
    public static final HttpErrorCode REFUSED_STREAM =
            new HttpErrorCode(7, "REFUSED_STREAM");

    /**
     * 8 Cancel
     */
    public static final HttpErrorCode CANCEL =
            new HttpErrorCode(8, "CANCEL");

    /**
     * 9 Compression Error
     */
    public static final HttpErrorCode COMPRESSION_ERROR =
            new HttpErrorCode(9, "COMPRESSION_ERROR");

    /**
     * 10 Connect Error
     */
    public static final HttpErrorCode CONNECT_ERROR =
            new HttpErrorCode(10, "CONNECT_ERROR");

    /**
     * 11 Enhance Your Calm (420)
     */
    public static final HttpErrorCode ENHANCE_YOUR_CALM =
            new HttpErrorCode(420, "ENHANCE_YOUR_CALM");

    /**
     * 12 Inadequate Security
     */
    public static final HttpErrorCode INADEQUATE_SECURITY =
            new HttpErrorCode(12, "INADEQUATE_SECURITY");

    /**
     * Returns the {@link HttpErrorCode} represented by the specified code.
     * If the specified code is a defined HTTP error code, a cached instance
     * will be returned.  Otherwise, a new instance will be returned.
     */
    public static HttpErrorCode valueOf(int code) {
        switch (code) {
        case 0:
            return NO_ERROR;
        case 1:
            return PROTOCOL_ERROR;
        case 2:
            return INTERNAL_ERROR;
        case 3:
            return FLOW_CONTROL_ERROR;
        case 4:
            return SETTINGS_TIMEOUT;
        case 5:
            return STREAM_CLOSED;
        case 6:
            return FRAME_SIZE_ERROR;
        case 7:
            return REFUSED_STREAM;
        case 8:
            return CANCEL;
        case 9:
            return COMPRESSION_ERROR;
        case 10:
            return CONNECT_ERROR;
        case 11:
            return ENHANCE_YOUR_CALM;
        case 12:
            return INADEQUATE_SECURITY;
        case 420:
            return ENHANCE_YOUR_CALM;
        default:
            return new HttpErrorCode(code, "UNKNOWN (" + code + ')');
        }
    }

    private final int code;

    private final String statusPhrase;

    /**
     * Creates a new instance with the specified {@code code} and its
     * {@code statusPhrase}.
     */
    public HttpErrorCode(int code, String statusPhrase) {
        if (statusPhrase == null) {
            throw new NullPointerException("statusPhrase");
        }

        this.code = code;
        this.statusPhrase = statusPhrase;
    }

    /**
     * Returns the code of this status.
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the status phrase of this status.
     */
    public String getStatusPhrase() {
        return statusPhrase;
    }

    @Override
    public int hashCode() {
        return getCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HttpErrorCode)) {
            return false;
        }

        return getCode() == ((HttpErrorCode) o).getCode();
    }

    @Override
    public String toString() {
        return getStatusPhrase();
    }

    @Override
    public int compareTo(HttpErrorCode o) {
        return getCode() - o.getCode();
    }
}
