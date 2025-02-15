/*
 * Copyright (C)2016 - SMBJ Contributors
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
package com.hierynomus.protocol.commons.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Represents promised data of the parameterized type {@code V} and allows waiting on it. An exception may also be
 * delivered to a waiter, and will be of the parameterized type {@code T}.
 * <p/>
 * For atomic operations on a promise, e.g. checking if a value is delivered and if it is not then setting it, the
 * associated lock for the promise should be acquired while doing so.
 */
public class Promise<V, T extends Throwable> {


    private final String name;
    private final ExceptionWrapper<T> wrapper;
    private final ReentrantLock lock;
    private final Condition cond;

    private V val;
    private T pendingEx;

    /**
     * Creates this promise with given {@code name} and exception {@code wrapper}. Allocates a new {@link
     * java.util.concurrent.locks.Lock lock} object for this promise.
     *
     * @param name    name of this promise
     * @param wrapper {@link ExceptionWrapper} that will be used for chaining exceptions
     */
    public Promise(String name, ExceptionWrapper<T> wrapper) {
        this(name, wrapper, null);
    }

    /**
     * Creates this promise with given {@code name}, exception {@code wrapper}, and associated {@code lock}.
     *
     * @param name    name of this promise
     * @param wrapper {@link ExceptionWrapper} that will be used for chaining exceptions
     * @param lock    lock to use
     */
    public Promise(String name, ExceptionWrapper<T> wrapper, ReentrantLock lock) {
        this.name = name;
        this.wrapper = wrapper;
        this.lock = lock == null ? new ReentrantLock() : lock;
        this.cond = this.lock.newCondition();
    }

    /**
     * Set this promise's value to {@code val}. Any waiters will be delivered this value.
     *
     * @param val the value
     */
    public void deliver(V val) {
        lock.lock();
        try {
            System.out.println("tempGT2: Setting << " + name + " >> to `" + val + "`");
            this.val = val;
            cond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues error that will be thrown in any waiting thread or any thread that attempts to wait on this promise
     * hereafter.
     *
     * @param e the error
     */
    public void deliverError(Throwable e) {
        lock.lock();
        try {
            pendingEx = wrapper.wrap(e);
            cond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears this promise by setting its value and queued exception to {@code null}.
     */
    public void clear() {
        lock.lock();
        try {
            pendingEx = null;
            deliver(null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait indefinitely for this promise's value to be deliver.
     *
     * @return the value
     * @throws T in case another thread informs the promise of an error meanwhile
     */
    public V retrieve() throws T {
        return tryRetrieve(0, TimeUnit.SECONDS);
    }

    /**
     * Wait for {@code timeout} duration for this promise's value to be deliver.
     *
     * @param timeout the timeout
     * @param unit    time unit for the timeout
     * @return the value
     * @throws T in case another thread informs the promise of an error meanwhile, or the timeout expires
     */
    public V retrieve(long timeout, TimeUnit unit) throws T {
        final V value = tryRetrieve(timeout, unit);
        if (value == null) {
            throw wrapper.wrap(new TimeoutException("Timeout expired"));
        } else {
            return value;
        }
    }

    /**
     * Wait for {@code timeout} duration for this promise's value to be deliver.
     * <p/>
     * If the value is not deliver by the time the timeout expires, returns {@code null}.
     *
     * @param timeout the timeout
     * @param unit    time unit for the timeout
     * @return the value or {@code null}
     * @throws T in case another thread informs the promise of an error meanwhile
     */
    public V tryRetrieve(long timeout, TimeUnit unit) throws T {
        lock.lock();
        try {
            if (pendingEx != null) {
                throw pendingEx;
            }

            if (val != null) {
                return val;
            }

            System.out.println("tempGT2: Awaiting << " + name + " >>");

            if (timeout == 0) {
                while (val == null && pendingEx == null) {
                    cond.await();
                }
            } else {
                if (!cond.await(timeout, unit)) {
                    return null;
                }
            }

            if (pendingEx != null) {
                System.out.println("tempGT2: << " + name + " >> woke to: " + pendingEx);
                throw pendingEx;
            }

            return val;
        } catch (InterruptedException ie) {
            throw wrapper.wrap(ie);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return whether this promise has a value delivered, and no error waiting to pop.
     */
    public boolean isDelivered() {
        lock.lock();
        try {
            return pendingEx == null && val != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return whether this promise has been delivered an error.
     */
    public boolean inError() {
        lock.lock();
        try {
            return pendingEx != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return whether this promise was fulfilled with either a value or an error.
     */
    public boolean isFulfilled() {
        lock.lock();
        try {
            return pendingEx != null || val != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return whether this promise has threads waiting on it.
     */
    public boolean hasWaiters() {
        lock.lock();
        try {
            return lock.hasWaiters(cond);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquire the lock associated with this promise.
     */
    public void lock() {
        lock.lock();
    }

    /**
     * Release the lock associated with this promise.
     */
    public void unlock() {
        lock.unlock();
    }

    @Override
    public String toString() {
        return name;
    }


    public AFuture<V> future() {
        return new PromiseBackedFuture<>(this);
    }
}
