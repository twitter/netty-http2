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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import io.netty.handler.codec.http.HttpHeaders;

public class DefaultHttpHeaders extends HttpHeaders {

    private static final int BUCKET_SIZE = 17;

    private static int hash(String name) {
        int h = 0;
        for (int i = name.length() - 1; i >= 0; i--) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            h = 31 * h + c;
        }

        if (h > 0) {
            return h;
        } else if (h == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        } else {
            return -h;
        }
    }

    private static boolean eq(String name1, String name2) {
        int nameLen = name1.length();
        if (nameLen != name2.length()) {
            return false;
        }

        for (int i = nameLen - 1; i >= 0; i--) {
            char c1 = name1.charAt(i);
            char c2 = name2.charAt(i);
            if (c1 != c2) {
                if (c1 >= 'A' && c1 <= 'Z') {
                    c1 += 32;
                }
                if (c2 >= 'A' && c2 <= 'Z') {
                    c2 += 32;
                }
                if (c1 != c2) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int index(int hash) {
        return hash % BUCKET_SIZE;
    }

    private final HeaderEntry[] entries = new HeaderEntry[BUCKET_SIZE];
    private final HeaderEntry head = new HeaderEntry(-1, null, null);

    public DefaultHttpHeaders() {
        head.before = head.after = head;
    }

    void validateHeaderName0(String headerName) {
        validateHeaderName(headerName);
    }

    @Override
    public HttpHeaders add(final String name, final Object value) {
        validateHeaderName0(name);
        String strVal = toString(value);
        validateHeaderValue(strVal);
        int h = hash(name);
        int i = index(h);
        add0(h, i, name, strVal);
        return this;
    }

    @Override
    public HttpHeaders add(String name, Iterable<?> values) {
        validateHeaderName0(name);
        int h = hash(name);
        int i = index(h);
        for (Object v : values) {
            String vstr = toString(v);
            validateHeaderValue(vstr);
            add0(h, i, name, vstr);
        }
        return this;
    }

    private void add0(int h, int i, final String name, final String value) {
        // Update the hash table.
        HeaderEntry e = entries[i];
        HeaderEntry newEntry;
        entries[i] = newEntry = new HeaderEntry(h, name, value);
        newEntry.next = e;

        // Update the linked list.
        newEntry.addBefore(head);
    }

    @Override
    public HttpHeaders remove(final String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        int h = hash(name);
        int i = index(h);
        remove0(h, i, name);
        return this;
    }

    private void remove0(int h, int i, String name) {
        HeaderEntry e = entries[i];
        if (e == null) {
            return;
        }

        for (; ; ) {
            if (e.hash == h && eq(name, e.key)) {
                e.remove();
                HeaderEntry next = e.next;
                if (next != null) {
                    entries[i] = next;
                    e = next;
                } else {
                    entries[i] = null;
                    return;
                }
            } else {
                break;
            }
        }

        for (; ; ) {
            HeaderEntry next = e.next;
            if (next == null) {
                break;
            }
            if (next.hash == h && eq(name, next.key)) {
                e.next = next.next;
                next.remove();
            } else {
                e = next;
            }
        }
    }

    @Override
    public HttpHeaders set(final String name, final Object value) {
        validateHeaderName0(name);
        String strVal = toString(value);
        validateHeaderValue(strVal);
        int h = hash(name);
        int i = index(h);
        remove0(h, i, name);
        add0(h, i, name, strVal);
        return this;
    }

    @Override
    public HttpHeaders set(final String name, final Iterable<?> values) {
        if (values == null) {
            throw new NullPointerException("values");
        }

        validateHeaderName0(name);

        int h = hash(name);
        int i = index(h);

        remove0(h, i, name);
        for (Object v : values) {
            if (v == null) {
                break;
            }
            String strVal = toString(v);
            validateHeaderValue(strVal);
            add0(h, i, name, strVal);
        }

        return this;
    }

    @Override
    public HttpHeaders clear() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = null;
        }
        head.before = head.after = head;
        return this;
    }

    @Override
    public String get(final String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        int h = hash(name);
        int i = index(h);
        HeaderEntry e = entries[i];
        while (e != null) {
            if (e.hash == h && eq(name, e.key)) {
                return e.value;
            }

            e = e.next;
        }
        return null;
    }

    @Override
    public List<String> getAll(final String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        LinkedList<String> values = new LinkedList<String>();

        int h = hash(name);
        int i = index(h);
        HeaderEntry e = entries[i];
        while (e != null) {
            if (e.hash == h && eq(name, e.key)) {
                values.addFirst(e.value);
            }
            e = e.next;
        }
        return values;
    }

    @Override
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> all =
                new LinkedList<Map.Entry<String, String>>();

        HeaderEntry e = head.after;
        while (e != head) {
            all.add(e);
            e = e.after;
        }
        return all;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        return new HeaderIterator();
    }

    @Override
    public boolean contains(String name) {
        return get(name) != null;
    }

    @Override
    public boolean isEmpty() {
        return head == head.after;
    }

    @Override
    public Set<String> names() {

        Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

        HeaderEntry e = head.after;
        while (e != head) {
            names.add(e.key);
            e = e.after;
        }
        return names;
    }

    private static String toString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return value.toString();
    }

    private final class HeaderIterator implements Iterator<Map.Entry<String, String>> {

        private HeaderEntry current = head;

        @Override
        public boolean hasNext() {
            return current.after != head;
        }

        @Override
        public Entry<String, String> next() {
            current = current.after;

            if (current == head) {
                throw new NoSuchElementException();
            }

            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class HeaderEntry implements Map.Entry<String, String> {
        public final int hash;
        public final String key;
        public String value;
        public HeaderEntry next;
        public HeaderEntry before;
        public HeaderEntry after;

        HeaderEntry(int hash, String key, String value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
        }

        void remove() {
            before.after = after;
            after.before = before;
        }

        void addBefore(HeaderEntry e) {
            after = e;
            before = e.before;
            before.after = this;
            after.before = this;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String setValue(String value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            validateHeaderValue(value);
            String oldValue = this.value;
            this.value = value;
            return oldValue;
        }

        @Override
        public String toString() {
            return key + '=' + value;
        }
    }

    /**
     * Validates the name of a header
     *
     * @param headerName The header name being validated
     */
    static void validateHeaderName(String headerName) {
        //Check to see if the name is null
        if (headerName == null) {
            throw new NullPointerException("Header names cannot be null");
        }
        //Go through each of the characters in the name
        for (int index = 0; index < headerName.length(); index++) {
            //Actually get the character
            char character = headerName.charAt(index);

            //Check to see if the character is not an ASCII character
            if (character > 127) {
                throw new IllegalArgumentException(
                        "Header name cannot contain non-ASCII characters: " + headerName);
            }

            //Check for prohibited characters.
            switch (character) {
            case '\t':
            case '\n':
            case 0x0b:
            case '\f':
            case '\r':
            case ' ':
            case ',':
            case ';':
            case '=':
                throw new IllegalArgumentException(
                        "Header name cannot contain the following prohibited characters: "
                                + "=,;: \\t\\r\\n\\v\\f: " + headerName);
            default:
            }
        }
    }

    /**
     * Validates the specified header value
     *
     * @param headerValue The value being validated
     */
    static void validateHeaderValue(String headerValue) {
        //Check to see if the value is null
        if (headerValue == null) {
            throw new NullPointerException("Header values cannot be null");
        }

        /*
         * Set up the state of the validation
         *
         * States are as follows:
         *
         * 0: Previous character was neither CR nor LF
         * 1: The previous character was CR
         * 2: The previous character was LF
         */
        int state = 0;

        //Start looping through each of the character

        for (int index = 0; index < headerValue.length(); index++) {
            char character = headerValue.charAt(index);

            //Check the absolutely prohibited characters.
            switch (character) {
            case 0x0b: // Vertical tab
                throw new IllegalArgumentException(
                        "Header value contains a prohibited character '\\v': " + headerValue);
            case '\f':
                throw new IllegalArgumentException(
                        "Header value contains a prohibited character '\\f': " + headerValue);
            default:
            }

            // Check the CRLF (HT | SP) pattern
            switch (state) {
            case 0:
                switch (character) {
                case '\r':
                    state = 1;
                    break;
                case '\n':
                    state = 2;
                    break;
                default:
                }
                break;
            case 1:
                switch (character) {
                case '\n':
                    state = 2;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Only '\\n' is allowed after '\\r': " + headerValue);
                }
                break;
            case 2:
                switch (character) {
                case '\t':
                case ' ':
                    state = 0;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Only ' ' and '\\t' are allowed after '\\n': " + headerValue);
                }
                break;
            default:
            }
        }

        if (state != 0) {
            throw new IllegalArgumentException(
                    "Header value must not end with '\\r' or '\\n':" + headerValue);
        }
    }
}
