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
import java.util.Set;
import java.util.TreeMap;

import io.netty.util.internal.StringUtil;

/**
 * The default {@link HttpSettingsFrame} implementation.
 */
public class DefaultHttpSettingsFrame implements HttpSettingsFrame {

    private final Map<Integer, Integer> settingsMap = new TreeMap<Integer, Integer>();
    private boolean ack;

    @Override
    public Set<Integer> getIds() {
        return settingsMap.keySet();
    }

    @Override
    public boolean isSet(int id) {
        return settingsMap.containsKey(id);
    }

    @Override
    public int getValue(int id) {
        if (settingsMap.containsKey(id)) {
            return settingsMap.get(id);
        } else {
            return -1;
        }
    }

    @Override
    public HttpSettingsFrame setValue(int id, int value) {
        if (id < 0 || id > HttpCodecUtil.HTTP_SETTINGS_MAX_ID) {
            throw new IllegalArgumentException("Setting ID is not valid: " + id);
        }
        settingsMap.put(id, value);
        return this;
    }

    @Override
    public HttpSettingsFrame removeValue(int id) {
        settingsMap.remove(id);
        return this;
    }

    @Override
    public boolean isAck() {
        return ack;
    }

    @Override
    public HttpSettingsFrame setAck(boolean ack) {
        this.ack = ack;
        return this;
    }

    private Set<Map.Entry<Integer, Integer>> getSettings() {
        return settingsMap.entrySet();
    }

    private void appendSettings(StringBuilder buf) {
        for (Map.Entry<Integer, Integer> e : getSettings()) {
            buf.append("--> ");
            buf.append(e.getKey().toString());
            buf.append(':');
            buf.append(e.getValue().toString());
            buf.append(StringUtil.NEWLINE);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(StringUtil.simpleClassName(this));
        buf.append("(ack: ");
        buf.append(isAck());
        buf.append(')');
        buf.append(StringUtil.NEWLINE);
        appendSettings(buf);
        buf.setLength(buf.length() - StringUtil.NEWLINE.length());
        return buf.toString();
    }
}
