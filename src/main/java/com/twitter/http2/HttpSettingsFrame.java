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

import java.util.Set;

/**
 * An HTTP/2 SETTINGS Frame
 */
public interface HttpSettingsFrame extends HttpFrame {

    int SETTINGS_HEADER_TABLE_SIZE = 1;
    int SETTINGS_ENABLE_PUSH = 2;
    int SETTINGS_MAX_CONCURRENT_STREAMS = 3;
    int SETTINGS_INITIAL_WINDOW_SIZE = 4;
    int SETTINGS_MAX_FRAME_SIZE = 5;
    int SETTINGS_MAX_HEADER_LIST_SIZE = 6;

    /**
     * Returns a {@code Set} of the setting IDs.
     * The set's iterator will return the IDs in ascending order.
     */
    Set<Integer> getIds();

    /**
     * Returns {@code true} if the setting ID has a value.
     */
    boolean isSet(int id);

    /**
     * Returns the value of the setting ID.
     * Returns -1 if the setting ID is not set.
     */
    int getValue(int id);

    /**
     * Sets the value of the setting ID.
     * The ID cannot be negative and cannot exceed 65535.
     */
    HttpSettingsFrame setValue(int id, int value);

    /**
     * Removes the value of the setting ID.
     */
    HttpSettingsFrame removeValue(int id);

    /**
     * Returns {@code true} if this frame is an acknowledgement frame.
     */
    boolean isAck();

    /**
     * Sets if this frame is acknowledgement frame.
     */
    HttpSettingsFrame setAck(boolean ack);
}
