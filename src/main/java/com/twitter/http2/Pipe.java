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

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.netty.channel.ChannelException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * Implements a stream that pipes objects between its two ends.
 * Futures are used to communicate when messages are sent and received.
 *
 * @param <T> the type of objects to send along the pipe
 */
public class Pipe<T> {

    private static final ChannelException PIPE_CLOSED = new ChannelException("pipe closed");

    private static final Future<Void> SENT_FUTURE =
            ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
    private static final Future<Void> CLOSED_FUTURE =
            ImmediateEventExecutor.INSTANCE.newFailedFuture(PIPE_CLOSED);

    private Queue<Node> sendQueue = new LinkedList<Node>();
    private Queue<Promise<T>> receiveQueue = new ConcurrentLinkedQueue<Promise<T>>();

    private boolean closed;

    /**
     * Holds a message and an associated future.
     */
    private final class Node {
        public T message;
        public Promise<Void> promise;

        Node(T message, Promise<Void> promise) {
            this.message = message;
            this.promise = promise;
        }
    }

    /**
     * Creates a new pipe and uses instances of {@link ImmediateEventExecutor}
     * for the send and receive executors.
     *
     * @see ImmediateEventExecutor#INSTANCE
     */
    public Pipe() {
        super();
    }

    /**
     * Sends a message to this pipe. Returns a {@link Future} that is completed
     * when the message is received.
     * <p>
     * If the pipe is closed then this will return a failed future.</p>
     *
     * @param message the message to send to the pipe
     * @return a {@link Future} that is satisfied when the message is received,
     * or a failed future if the pipe is closed.
     * @throws NullPointerException  if the message is {@code null}.
     * @throws IllegalStateException if the message could not be added to the queue for some reason.
     * @see #receive()
     */
    public Future<Void> send(T message) {
        Objects.requireNonNull(message, "msg");

        Promise<T> receivePromise;

        synchronized (this) {
            if (closed) {
                return CLOSED_FUTURE;
            }

            receivePromise = receiveQueue.poll();
            if (receivePromise == null) {
                Promise<Void> sendPromise = ImmediateEventExecutor.INSTANCE.newPromise();
                sendQueue.add(new Node(message, sendPromise));
                return sendPromise;
            }
        }

        receivePromise.setSuccess(message);
        return SENT_FUTURE;
    }

    /**
     * Receives a message from this pipe.
     * <p>
     * If the pipe is closed then this will return a failed future.</p>
     */
    public Future<T> receive() {
        Node node;

        synchronized (this) {
            node = sendQueue.poll();
            if (node == null) {
                if (closed) {
                    return ImmediateEventExecutor.INSTANCE.newFailedFuture(PIPE_CLOSED);
                }

                Promise<T> promise = ImmediateEventExecutor.INSTANCE.newPromise();
                receiveQueue.add(promise);
                return promise;
            }
        }

        node.promise.setSuccess(null);
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(node.message);
    }

    /**
     * Closes this pipe. This fails all outstanding receive futures.
     * This does nothing if the pipe is already closed.
     */
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }

        while (!receiveQueue.isEmpty()) {
            receiveQueue.poll().setFailure(PIPE_CLOSED);
        }
    }

    /**
     * Checks if this pipe is closed.
     *
     * @return whether this pipe is closed.
     */
    public synchronized boolean isClosed() {
        return closed;
    }
}
