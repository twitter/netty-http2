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

import org.junit.Before;
import org.junit.Test;

import io.netty.util.concurrent.Future;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PipeTest {

    private static final Object MESSAGE = new Object();
    private static final Object MESSAGE1 = new Object();
    private static final Object MESSAGE2 = new Object();

    //
    // Test Plan
    //
    // o Choose from an alphabet, { S, C, R }, where the letters stand for Send, Close, and Receive.
    // o The tests are named using the alphabet.
    //   For example, testSR tests a Send then Receive.
    //   At each point in the test, all the state in the current
    //   and previous futures and values are tested as well.
    //
    // Some assumptions:
    // o If a future is not done, then it will not be succeeded,
    //   cancelled, or failed, as per the future spec.
    // o If a future is done, then it will be only one of the three other future states.
    //

    private Pipe<Object> pipe;

    @Before
    public void createPipe() {
        pipe = new Pipe<Object>();
    }

    @Test
    public void testS() {
        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertFalse(sendFuture.isDone());
    }

    @Test
    public void testR() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());
    }

    @Test
    public void testSR() {
        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertFalse(sendFuture.isDone());

        Future<Object> recvFuture = pipe.receive();
        assertSame(MESSAGE, recvFuture.getNow());
        assertTrue(sendFuture.isSuccess());
    }

    @Test
    public void testRS() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertTrue(sendFuture.isSuccess());
        assertSame(MESSAGE, recvFuture.getNow());
    }

    @Test
    public void testSS() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
    }


    @Test
    public void testRR() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
    }

    @Test
    public void testSRR() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE);
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture1 = pipe.receive();
        assertSame(MESSAGE, recvFuture1.getNow());
        assertTrue(sendFuture1.isSuccess());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
        assertTrue(recvFuture1.isSuccess());
        assertTrue(sendFuture1.isSuccess());
    }

    @Test
    public void testSSR() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture = pipe.receive();
        assertSame(MESSAGE1, recvFuture.getNow());
        assertFalse(sendFuture2.isDone());
        assertTrue(sendFuture1.isSuccess());
    }

    @Test
    public void testSRS() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture = pipe.receive();
        assertSame(MESSAGE1, recvFuture.getNow());
        assertTrue(sendFuture1.isSuccess());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertTrue(recvFuture.isSuccess());
        assertTrue(sendFuture1.isSuccess());
    }

    @Test
    public void testRSR() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertTrue(sendFuture.isSuccess());
        assertSame(MESSAGE, recvFuture1.getNow());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
        assertTrue(sendFuture.isSuccess());
        assertTrue(recvFuture1.isSuccess());
    }

    @Test
    public void testRSS() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());

        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertTrue(sendFuture1.isSuccess());
        assertSame(MESSAGE1, recvFuture.getNow());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertTrue(sendFuture1.isSuccess());
        assertTrue(recvFuture.isSuccess());
    }

    @Test
    public void testRRS() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
        assertFalse(recvFuture1.isDone());

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertTrue(sendFuture.isSuccess());
        assertFalse(recvFuture2.isDone());
        assertSame(MESSAGE, recvFuture1.getNow());
    }

    @Test
    public void testSSRR() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture1 = pipe.receive();
        assertSame(MESSAGE1, recvFuture1.getNow());
        assertFalse(sendFuture2.isDone());
        assertTrue(sendFuture1.isSuccess());

        Future<Object> recvFuture2 = pipe.receive();
        assertSame(MESSAGE2, recvFuture2.getNow());
        assertTrue(recvFuture1.isSuccess());
        assertTrue(sendFuture2.isSuccess());
        assertTrue(sendFuture1.isSuccess());
    }

    @Test
    public void testRRSS() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
        assertFalse(recvFuture1.isDone());

        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertTrue(sendFuture1.isSuccess());
        assertFalse(recvFuture2.isDone());
        assertSame(MESSAGE1, recvFuture1.getNow());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertTrue(sendFuture2.isSuccess());
        assertTrue(sendFuture1.isSuccess());
        assertSame(MESSAGE2, recvFuture2.getNow());
        assertSame(MESSAGE1, recvFuture1.getNow());
    }

    @Test
    public void testRSSR() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertTrue(sendFuture1.isSuccess());
        assertSame(MESSAGE1, recvFuture1.getNow());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertTrue(sendFuture1.isSuccess());
        assertTrue(recvFuture1.isSuccess());

        Future<Object> recvFuture2 = pipe.receive();
        assertSame(MESSAGE2, recvFuture2.getNow());
        assertTrue(sendFuture2.isSuccess());
        assertTrue(sendFuture1.isSuccess());
        assertTrue(recvFuture1.isSuccess());
    }

    @Test
    public void testSRRS() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture1 = pipe.receive();
        assertSame(MESSAGE1, recvFuture1.getNow());
        assertTrue(sendFuture1.isSuccess());

        Future<Object> recvFuture2 = pipe.receive();
        assertFalse(recvFuture2.isDone());
        assertTrue(recvFuture1.isSuccess());
        assertTrue(sendFuture1.isSuccess());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertTrue(sendFuture2.isSuccess());
        assertSame(MESSAGE2, recvFuture2.getNow());
        assertTrue(recvFuture1.isSuccess());
        assertTrue(sendFuture1.isSuccess());
    }

    @Test
    public void testSSRRR() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertFalse(sendFuture2.isDone());
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture1 = pipe.receive();
        assertTrue(recvFuture1.isSuccess());
        assertSame(MESSAGE1, recvFuture1.getNow());
        assertFalse(sendFuture2.isDone());
        assertTrue(sendFuture1.isSuccess());

        Future<Object> recvFuture2 = pipe.receive();
        assertTrue(recvFuture2.isSuccess());
        assertSame(MESSAGE2, recvFuture2.getNow());
        assertTrue(sendFuture2.isSuccess());
        assertTrue(recvFuture1.isSuccess());
        assertTrue(sendFuture2.isSuccess());
        assertTrue(sendFuture1.isSuccess());

        Future<Object> recvFuture3 = pipe.receive();
        assertFalse(recvFuture3.isDone());
    }

    @Test
    public void testCS() {
        pipe.close();

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertNotNull(sendFuture.cause());
    }

    @Test
    public void testSC() {
        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertFalse(sendFuture.isDone());

        pipe.close();
        assertFalse(sendFuture.isDone());
    }

    @Test
    public void testCR() {
        pipe.close();

        Future<Object> recvFuture = pipe.receive();
        assertNotNull(recvFuture.cause());
    }

    @Test
    public void testRC() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());

        pipe.close();
        assertNotNull(recvFuture.isDone());
    }

    @Test
    public void testCSR() {
        pipe.close();

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertNotNull(sendFuture.cause());

        Future<Object> recvFuture = pipe.receive();
        assertNotNull(recvFuture.cause());
        assertNotNull(sendFuture.cause());
    }

    @Test
    public void testSCR() {
        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertFalse(sendFuture.isDone());

        pipe.close();
        assertFalse(sendFuture.isDone());

        Future<Object> recvFuture = pipe.receive();
        assertSame(MESSAGE, recvFuture.getNow());
        assertTrue(sendFuture.isSuccess());
    }

    @Test
    public void testSRCS() {
        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertFalse(sendFuture1.isDone());

        Future<Object> recvFuture = pipe.receive();
        assertSame(MESSAGE1, recvFuture.getNow());
        assertTrue(sendFuture1.isSuccess());

        pipe.close();

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertNotNull(sendFuture2.cause());
    }

    @Test
    public void testSRCR() {
        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertFalse(sendFuture.isDone());

        Future<Object> recvFuture1 = pipe.receive();
        assertSame(MESSAGE, recvFuture1.getNow());
        assertTrue(sendFuture.isSuccess());

        pipe.close();

        Future<Object> recvFuture2 = pipe.receive();
        assertNotNull(recvFuture2.cause());
        assertTrue(sendFuture.isSuccess());
    }

    @Test
    public void testCRS() {
        pipe.close();

        Future<Object> recvFuture = pipe.receive();
        assertNotNull(recvFuture.cause());

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertNotNull(sendFuture.cause());
        assertNotNull(recvFuture.cause());
    }

    @Test
    public void testRCS() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());

        pipe.close();
        assertNotNull(recvFuture.cause());

        Future<Void> sendFuture = pipe.send(MESSAGE);
        assertNotNull(sendFuture.cause());
        assertNotNull(recvFuture.cause());
    }

    @Test
    public void testRSCR() {
        Future<Object> recvFuture1 = pipe.receive();
        assertFalse(recvFuture1.isDone());

        Future<Void> sendFuture1 = pipe.send(MESSAGE);
        assertTrue(sendFuture1.isSuccess());
        assertSame(MESSAGE, recvFuture1.getNow());

        pipe.close();

        Future<Object> recvFuture2 = pipe.receive();
        assertNotNull(recvFuture2.cause());
        assertTrue(sendFuture1.isSuccess());
        assertTrue(recvFuture1.isSuccess());
    }

    @Test
    public void testRSCS() {
        Future<Object> recvFuture = pipe.receive();
        assertFalse(recvFuture.isDone());

        Future<Void> sendFuture1 = pipe.send(MESSAGE1);
        assertTrue(sendFuture1.isSuccess());
        assertSame(MESSAGE1, recvFuture.getNow());

        pipe.close();

        Future<Void> sendFuture2 = pipe.send(MESSAGE2);
        assertNotNull(sendFuture2.cause());
        assertTrue(sendFuture1.isSuccess());
        assertTrue(recvFuture.isSuccess());
    }
}
