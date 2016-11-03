/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal;

import org.apache.tinkerpop.gremlin.process.remote.traversal.DefaultRemoteTraverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalInterruptedException;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsCollectionContaining.hasItems;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class TraversalTest {

    private final ExecutorService service = Executors.newFixedThreadPool(2);

    @Test
    public void shouldTryNext() {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3);
        final Optional<Integer> optFirst = t.tryNext();
        assertEquals(1, optFirst.get().intValue());
        final Optional<Integer> optSecond = t.tryNext();
        assertEquals(2, optSecond.get().intValue());
        final Optional<Integer> optThird = t.tryNext();
        assertEquals(3, optThird.get().intValue());

        IntStream.range(0, 100).forEach(i -> {
            assertThat(t.tryNext().isPresent(), is(false));
        });
    }

    @Test
    public void shouldGetTwoAtATime() {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        final List<Integer> batchOne = t.next(2);
        assertEquals(2, batchOne.size());
        assertThat(batchOne, hasItems(1 ,2));

        final List<Integer> batchTwo = t.next(2);
        assertEquals(2, batchTwo.size());
        assertThat(batchTwo, hasItems(3 ,4));

        final List<Integer> batchThree = t.next(2);
        assertEquals(2, batchThree.size());
        assertThat(batchThree, hasItems(5, 6));

        final List<Integer> batchFour = t.next(2);
        assertEquals(1, batchFour.size());
        assertThat(batchFour, hasItems(7));

        final List<Integer> batchFive = t.next(2);
        assertEquals(0, batchFive.size());
    }

    @Test
    public void shouldFillList() {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        final List<Integer> listToFill = new ArrayList<>();
        final List<Integer> batch = t.fill(listToFill);
        assertEquals(7, batch.size());
        assertThat(batch, hasItems(1 ,2, 3, 4, 5, 6, 7));
        assertThat(t.hasNext(), is(false));
        assertSame(listToFill, batch);
    }

    @Test
    public void shouldStream() {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        final List<Integer> batch = t.toStream().collect(Collectors.toList());
        assertEquals(7, batch.size());
        assertThat(batch, hasItems(1 ,2, 3, 4, 5, 6, 7));
        assertThat(t.hasNext(), is(false));
    }

    @Test
    public void shouldPromiseNextThreeUsingForkJoin() throws Exception {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        final CompletableFuture<List<Integer>> promiseFirst = t.promise(traversal -> traversal.next(3));
        final List<Integer> listFirst = promiseFirst.get();
        assertEquals(3, listFirst.size());
        assertThat(listFirst, hasItems(1 ,2, 3));
        assertThat(t.hasNext(), is(true));
        assertThat(promiseFirst.isDone(), is(true));

        final CompletableFuture<List<Integer>> promiseSecond = t.promise(traversal -> traversal.next(3));
        final List<Integer> listSecond = promiseSecond.get();
        assertEquals(3, listSecond.size());
        assertThat(listSecond, hasItems(4, 5, 6));
        assertThat(t.hasNext(), is(true));
        assertThat(promiseSecond.isDone(), is(true));

        final CompletableFuture<List<Integer>> promiseThird = t.promise(traversal -> traversal.next(3));
        final List<Integer> listThird = promiseThird.get();
        assertEquals(1, listThird.size());
        assertThat(listThird, hasItems(7));
        assertThat(t.hasNext(), is(false));
        assertThat(promiseThird.isDone(), is(true));

        final CompletableFuture<Integer> promiseDead = t.promise(traversal -> (Integer) traversal.next());
        final AtomicBoolean dead = new AtomicBoolean(false);
        promiseDead.exceptionally(tossed -> {
            dead.set(tossed instanceof NoSuchElementException);
            return null;
        });

        try {
            promiseDead.get(10000, TimeUnit.MILLISECONDS);
            fail("Should have gotten an exception");
        } catch (Exception ex) {
            if (ex instanceof TimeoutException) {
                fail("This should not have timed out but should have gotten an exception caught above in the exceptionally() clause");
            }

            assertThat(ex.getCause(), instanceOf(NoSuchElementException.class));
        }

        assertThat(dead.get(), is(true));
        assertThat(t.hasNext(), is(false));
        assertThat(promiseDead.isDone(), is(true));
    }

    @Test
    public void shouldPromiseNextThreeUsingSpecificExecutor() throws Exception {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        final CompletableFuture<List<Integer>> promiseFirst = t.promise(traversal -> traversal.next(3), service);
        final List<Integer> listFirst = promiseFirst.get();
        assertEquals(3, listFirst.size());
        assertThat(listFirst, hasItems(1 ,2, 3));
        assertThat(t.hasNext(), is(true));
        assertThat(promiseFirst.isDone(), is(true));

        final CompletableFuture<List<Integer>> promiseSecond = t.promise(traversal -> traversal.next(3), service);
        final List<Integer> listSecond = promiseSecond.get();
        assertEquals(3, listSecond.size());
        assertThat(listSecond, hasItems(4, 5, 6));
        assertThat(t.hasNext(), is(true));
        assertThat(promiseSecond.isDone(), is(true));

        final CompletableFuture<List<Integer>> promiseThird = t.promise(traversal -> traversal.next(3), service);
        final List<Integer> listThird = promiseThird.get();
        assertEquals(1, listThird.size());
        assertThat(listThird, hasItems(7));
        assertThat(t.hasNext(), is(false));
        assertThat(promiseThird.isDone(), is(true));

        final CompletableFuture<Integer> promiseDead = t.promise(traversal -> (Integer) traversal.next(), service);
        final AtomicBoolean dead = new AtomicBoolean(false);
        promiseDead.exceptionally(tossed -> {
            dead.set(tossed instanceof NoSuchElementException);
            return null;
        });

        try {
            promiseDead.get(10000, TimeUnit.MILLISECONDS);
            fail("Should have gotten an exception");
        } catch (Exception ex) {
            if (ex instanceof TimeoutException) {
                fail("This should not have timed out but should have gotten an exception caught above in the exceptionally() clause");
            }

            assertThat(ex.getCause(), instanceOf(NoSuchElementException.class));
        }

        assertThat(dead.get(), is(true));
        assertThat(t.hasNext(), is(false));
        assertThat(promiseDead.isDone(), is(true));
    }

    @Test
    public void shouldInterruptTraversalFunction() throws Exception {
        final Random rand = new Random(1234567890);

        // infinite traversal
        final MockTraversal<Integer> t = new MockTraversal<>(IntStream.generate(rand::nextInt).iterator());

        // iterate a bunch of it
        final CompletableFuture<List<Integer>> promise10 = t.promise(traversal -> traversal.next(10), service);
        assertEquals(10, promise10.get(10000, TimeUnit.MILLISECONDS).size());
        final CompletableFuture<List<Integer>> promise100 = t.promise(traversal -> traversal.next(100), service);
        assertEquals(100, promise100.get(10000, TimeUnit.MILLISECONDS).size());
        final CompletableFuture<List<Integer>> promise1000 = t.promise(traversal -> traversal.next(1000), service);
        assertEquals(1000, promise1000.get(10000, TimeUnit.MILLISECONDS).size());

        // this is endless, so let's cancel
        final CompletableFuture<List<Integer>> promiseForevers = t.promise(traversal -> traversal.next(Integer.MAX_VALUE), service);

        // specify what to do on exception
        final AtomicBoolean failed = new AtomicBoolean(false);
        promiseForevers.exceptionally(ex -> {
            failed.set(true);
            return null;
        });

        try {
            // let it actually iterate a moment
            promiseForevers.get(500, TimeUnit.MILLISECONDS);
            fail("This should have timed out because the traversal has infinite items in it");
        } catch (TimeoutException tex) {

        }

        assertThat(promiseForevers.isDone(), is(false));
        promiseForevers.cancel(true);
        assertThat(failed.get(), is(true));
        assertThat(promiseForevers.isDone(), is(true));
    }

    @Test
    public void shouldIterate() {
        final MockTraversal<Integer> t = new MockTraversal<>(1, 2, 3, 4, 5, 6, 7);
        assertThat(t.hasNext(), is(true));
        t.iterate();
        assertThat(t.hasNext(), is(false));
    }

    private static class MockStep<E> implements Step<E,E> {

        private final Iterator<E> itty;

        MockStep(final Iterator<E> itty) {
            this.itty = itty;
        }

        @Override
        public void addStarts(final Iterator starts) {

        }

        @Override
        public void addStart(final Traverser.Admin start) {

        }

        @Override
        public void setPreviousStep(final Step step) {

        }

        @Override
        public Step getPreviousStep() {
            return null;
        }

        @Override
        public void setNextStep(final Step step) {

        }

        @Override
        public Step getNextStep() {
            return null;
        }

        @Override
        public Traversal.Admin getTraversal() {
            return null;
        }

        @Override
        public void setTraversal(final Traversal.Admin traversal) {

        }

        @Override
        public void reset() {

        }

        @Override
        public Step clone() {
            return null;
        }

        @Override
        public Set<String> getLabels() {
            return null;
        }

        @Override
        public void addLabel(final String label) {

        }

        @Override
        public void removeLabel(final String label) {

        }

        @Override
        public void setId(final String id) {

        }

        @Override
        public String getId() {
            return null;
        }

        @Override
        public boolean hasNext() {
            return itty.hasNext();
        }

        @Override
        public Traverser.Admin<E> next() {
            return new DefaultRemoteTraverser<>(itty.next(), 1L);
        }
    }


    private static class MockTraversal<T> implements Traversal.Admin<T,T> {

        private Iterator<T> itty;

        private Step mockEndStep;

        private List<Step> steps;

        MockTraversal(final T... objects) {
            this(Arrays.asList(objects));
        }

        MockTraversal(final List<T> list) {
            this(list.iterator());
        }

        MockTraversal(final Iterator<T> itty) {
            this.itty = itty;
            mockEndStep = new MockStep<>(itty);
            steps = Collections.singletonList(mockEndStep);
        }

        @Override
        public Bytecode getBytecode() {
            return null;
        }

        @Override
        public List<Step> getSteps() {
            return steps;
        }

        @Override
        public <S2, E2> Admin<S2, E2> addStep(final int index, final Step<?, ?> step) throws IllegalStateException {
            return null;
        }

        @Override
        public <S2, E2> Admin<S2, E2> removeStep(final int index) throws IllegalStateException {
            return null;
        }

        @Override
        public void applyStrategies() throws IllegalStateException {

        }

        @Override
        public TraverserGenerator getTraverserGenerator() {
            return null;
        }

        @Override
        public Set<TraverserRequirement> getTraverserRequirements() {
            return null;
        }

        @Override
        public void setSideEffects(final TraversalSideEffects sideEffects) {

        }

        @Override
        public TraversalSideEffects getSideEffects() {
            return null;
        }

        @Override
        public void setStrategies(final TraversalStrategies strategies) {

        }

        @Override
        public TraversalStrategies getStrategies() {
            return null;
        }

        @Override
        public void setParent(final TraversalParent step) {

        }

        @Override
        public TraversalParent getParent() {
            return null;
        }

        @Override
        public Admin<T, T> clone() {
            return null;
        }

        @Override
        public boolean isLocked() {
            return false;
        }

        @Override
        public Optional<Graph> getGraph() {
            return null;
        }

        @Override
        public void setGraph(final Graph graph) {

        }

        @Override
        public boolean hasNext() {
            return itty.hasNext();
        }

        @Override
        public T next() {
            if (Thread.interrupted()) throw new TraversalInterruptedException();
            return itty.next();
        }
    }
}
