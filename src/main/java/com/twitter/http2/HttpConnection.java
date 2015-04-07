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

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelPromise;

import static com.twitter.http2.HttpCodecUtil.HTTP_DEFAULT_WEIGHT;
import static com.twitter.http2.HttpCodecUtil.HTTP_CONNECTION_STREAM_ID;

final class HttpConnection {

    private static final HttpProtocolException STREAM_CLOSED =
            new HttpProtocolException("Stream closed");

    private final AtomicInteger activeLocalStreams = new AtomicInteger();
    private final AtomicInteger activeRemoteStreams = new AtomicInteger();
    private final Map<Integer, Node> streams = new ConcurrentHashMap<Integer, Node>();

    private final AtomicInteger sendWindowSize;
    private final AtomicInteger receiveWindowSize;

    public HttpConnection(int sendWindowSize, int receiveWindowSize) {
        streams.put(HTTP_CONNECTION_STREAM_ID, new Node(null));
        this.sendWindowSize = new AtomicInteger(sendWindowSize);
        this.receiveWindowSize = new AtomicInteger(receiveWindowSize);
    }

    int numActiveStreams(boolean remote) {
        if (remote) {
            return activeRemoteStreams.get();
        } else {
            return activeLocalStreams.get();
        }
    }

    boolean noActiveStreams() {
        return activeRemoteStreams.get() + activeLocalStreams.get() == 0;
    }

    void acceptStream(
            int streamId, boolean remoteSideClosed, boolean localSideClosed,
            int streamSendWindowSize, int streamReceiveWindowSize, boolean remote) {
        StreamState state = null;
        if (!remoteSideClosed || !localSideClosed) {
            state = new StreamState(
                    remoteSideClosed, localSideClosed, streamSendWindowSize, streamReceiveWindowSize);
        }
        Node node = new Node(state);
        node.parent = streams.get(HTTP_CONNECTION_STREAM_ID);
        streams.put(streamId, node);
        if (state != null) {
            if (remote) {
                activeRemoteStreams.incrementAndGet();
            } else {
                activeLocalStreams.incrementAndGet();
            }
        }
    }

    private StreamState removeActiveStream(int streamId, boolean remote) {
        Node stream = streams.remove(streamId);
        if (stream != null && stream.state != null) {
            StreamState state = stream.state;
            stream.close();
            if (remote) {
                activeRemoteStreams.decrementAndGet();
            } else {
                activeLocalStreams.decrementAndGet();
            }
            return state;
        }
        return null;
    }

    void removeStream(int streamId, boolean remote) {
        StreamState state = removeActiveStream(streamId, remote);
        if (state != null) {
            state.clearPendingWrites(STREAM_CLOSED);
        }
    }

    boolean isRemoteSideClosed(int streamId) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state == null || state.isRemoteSideClosed();
    }

    void closeRemoteSide(int streamId, boolean remote) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        if (state != null) {
            state.closeRemoteSide();
            if (state.isLocalSideClosed()) {
                removeActiveStream(streamId, remote);
            }
        }
    }

    boolean isLocalSideClosed(int streamId) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state == null || state.isLocalSideClosed();
    }

    void closeLocalSide(int streamId, boolean remote) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        if (state != null) {
            state.closeLocalSide();
            if (state.isRemoteSideClosed()) {
                removeActiveStream(streamId, remote);
            }
        }
    }

    int getSendWindowSize(int streamId) {
        if (streamId == HTTP_CONNECTION_STREAM_ID) {
            return sendWindowSize.get();
        }

        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null ? state.getSendWindowSize() : -1;
    }

    int updateSendWindowSize(int streamId, int deltaWindowSize) {
        if (streamId == HTTP_CONNECTION_STREAM_ID) {
            return sendWindowSize.addAndGet(deltaWindowSize);
        }

        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null ? state.updateSendWindowSize(deltaWindowSize) : -1;
    }

    int updateReceiveWindowSize(int streamId, int deltaWindowSize) {
        if (streamId == HTTP_CONNECTION_STREAM_ID) {
            return receiveWindowSize.addAndGet(deltaWindowSize);
        }

        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        if (state == null) {
            return -1;
        }
        if (deltaWindowSize > 0) {
            state.setReceiveWindowSizeLowerBound(0);
        }
        return state.updateReceiveWindowSize(deltaWindowSize);
    }

    int getReceiveWindowSizeLowerBound(int streamId) {
        if (streamId == HTTP_CONNECTION_STREAM_ID) {
            return 0;
        }

        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null ? state.getReceiveWindowSizeLowerBound() : 0;
    }

    void updateAllSendWindowSizes(int deltaWindowSize) {
        for (Node stream : streams.values()) {
            StreamState state = stream.state;
            if (state != null) {
                state.updateSendWindowSize(deltaWindowSize);
            }
        }
    }

    void updateAllReceiveWindowSizes(int deltaWindowSize) {
        for (Node stream : streams.values()) {
            StreamState state = stream.state;
            if (state != null) {
                state.updateReceiveWindowSize(deltaWindowSize);
                if (deltaWindowSize < 0) {
                    state.setReceiveWindowSizeLowerBound(deltaWindowSize);
                }
            }
        }
    }

    boolean putPendingWrite(int streamId, PendingWrite evt) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null && state.putPendingWrite(evt);
    }

    PendingWrite getPendingWrite(int streamId) {
        if (streamId == HTTP_CONNECTION_STREAM_ID) {
            Node connection = streams.get(HTTP_CONNECTION_STREAM_ID);
            return getPendingWrite(connection);
        }

        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null ? state.getPendingWrite() : null;
    }

    PendingWrite getPendingWrite(Node node) {
        PendingWrite e = null;
        if (node.state != null && node.state.getSendWindowSize() > 0) {
            e = node.state.getPendingWrite();
        }
        if (e == null) {
            for (Node child : node.children) {
                e = getPendingWrite(child);
                if (e != null) {
                    break;
                }
            }
        }
        return e;
    }

    PendingWrite removePendingWrite(int streamId) {
        Node stream = streams.get(streamId);
        StreamState state = stream == null ? null : stream.state;
        return state != null ? state.removePendingWrite() : null;
    }

    /**
     * Set the priority of the stream.
     */
    boolean setPriority(int streamId, boolean exclusive, int dependency, int weight) {
        Node stream = streams.get(streamId);
        if (stream == null) {
            // stream closed?
            return false;
        }

        Node parent = streams.get(dependency);
        if (parent == null) {
            // garbage collected?
            stream.parent.removeDependent(stream);

            // set to default priority
            Node root = streams.get(HTTP_CONNECTION_STREAM_ID);
            root.addDependent(false, stream);
            stream.setWeight(HTTP_DEFAULT_WEIGHT);
            return false;
        }

        // check if we need to restructure the tree
        if (parent == stream.parent) {
            if (exclusive) {
                // move dependents to stream
                parent.addDependent(true, stream);
            }
        } else {
            stream.parent.removeDependent(stream);
            parent.addDependent(exclusive, stream);
        }

        stream.setWeight(weight);
        return true;
    }

    private static final class StreamState {

        private boolean remoteSideClosed;
        private boolean localSideClosed;
        private final AtomicInteger sendWindowSize;
        private final AtomicInteger receiveWindowSize;
        private int receiveWindowSizeLowerBound;
        private final ConcurrentLinkedQueue<PendingWrite> pendingWriteQueue =
                new ConcurrentLinkedQueue<PendingWrite>();

        StreamState(
                boolean remoteSideClosed, boolean localSideClosed,
                int sendWindowSize, int receiveWindowSize) {
            this.remoteSideClosed = remoteSideClosed;
            this.localSideClosed = localSideClosed;
            this.sendWindowSize = new AtomicInteger(sendWindowSize);
            this.receiveWindowSize = new AtomicInteger(receiveWindowSize);
        }

        boolean isRemoteSideClosed() {
            return remoteSideClosed;
        }

        void closeRemoteSide() {
            remoteSideClosed = true;
        }

        boolean isLocalSideClosed() {
            return localSideClosed;
        }

        void closeLocalSide() {
            localSideClosed = true;
        }

        int getSendWindowSize() {
            return sendWindowSize.get();
        }

        int updateSendWindowSize(int deltaWindowSize) {
            return sendWindowSize.addAndGet(deltaWindowSize);
        }

        int updateReceiveWindowSize(int deltaWindowSize) {
            return receiveWindowSize.addAndGet(deltaWindowSize);
        }

        int getReceiveWindowSizeLowerBound() {
            return receiveWindowSizeLowerBound;
        }

        void setReceiveWindowSizeLowerBound(int receiveWindowSizeLowerBound) {
            this.receiveWindowSizeLowerBound = receiveWindowSizeLowerBound;
        }

        boolean putPendingWrite(PendingWrite msg) {
            return pendingWriteQueue.offer(msg);
        }

        PendingWrite getPendingWrite() {
            return pendingWriteQueue.peek();
        }

        PendingWrite removePendingWrite() {
            return pendingWriteQueue.poll();
        }

        void clearPendingWrites(Throwable cause) {
            for (; ; ) {
                PendingWrite pendingWrite = pendingWriteQueue.poll();
                if (pendingWrite == null) {
                    break;
                }
                pendingWrite.fail(cause);
            }
        }
    }

    private static final class Node {

        private static final Comparator<Node> COMPARATOR = new WeightComparator();

        public Node parent; // the dependency of the stream
        public int weight;  // the weight of the dependency

        // Children should be iterator in weighted order
        public Set<Node> children = new TreeSet<Node>(COMPARATOR); // the dependents of the stream
        public int dependentWeights; // the total weight of all the dependents of the stream

        public StreamState state;

        public Node(StreamState state) {
            this.state = state;
        }

        public void close() {
            this.state = null;
        }

        public void setWeight(int weight) {
            // Remove and re-add parent to maintain comparator order
            parent.removeDependent(this);
            this.weight = weight;
            parent.addDependent(false, this);
        }

        public void addDependent(boolean exclusive, Node node) {
            removeDependent(node);
            if (exclusive) {
                for (Node child : children) {
                    node.addDependent(false, child);
                }
                children.clear();
                dependentWeights = 0;
            }
            children.add(node);
            dependentWeights += node.weight;
        }

        public void removeDependent(Node node) {
            if (children.remove(node)) {
                dependentWeights -= node.weight;
            }
        }
    }

    private static final class WeightComparator implements Comparator<Node> {
        @Override
        public int compare(Node n1, Node n2) {
            return n2.weight - n1.weight;
        }
    }

    public static final class PendingWrite {
        public final HttpDataFrame httpDataFrame;
        public final ChannelPromise promise;

        PendingWrite(HttpDataFrame httpDataFrame, ChannelPromise promise) {
            this.httpDataFrame = httpDataFrame;
            this.promise = promise;
        }

        void fail(Throwable cause) {
            // httpDataFrame.release();
            promise.setFailure(cause);
        }
    }
}
