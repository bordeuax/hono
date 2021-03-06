/*******************************************************************************
 * Copyright (c) 2016, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.jaegertracing.Configuration;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;

/**
 * Tests verifying the behavior of {@link MultiMapInjectAdapter} and {@link MultiMapExtractAdapter}.
 *
 */
public class MultiMapInjectExtractAdapterTest {

    /**
     * Verifies that the same entries injected via the {@code MultiMapInjectAdapter} are extracted via the
     * {@code MultiMapExtractAdapter}.
     */
    @Test
    public void testInjectAndExtract() {
        final Map<String, String> testEntries = new HashMap<>();
        testEntries.put("key1", "value1");
        testEntries.put("key2", "value2");

        final MultiMap multiMap = new CaseInsensitiveHeaders();
        final MultiMapInjectAdapter injectAdapter = new MultiMapInjectAdapter(multiMap);
        testEntries.forEach((key, value) -> {
            injectAdapter.put(key, value);
        });

        final MultiMapExtractAdapter extractAdapter = new MultiMapExtractAdapter(multiMap);
        extractAdapter.iterator().forEachRemaining(extractedEntry -> {
            assertThat(extractedEntry.getValue()).isEqualTo(testEntries.get(extractedEntry.getKey()));
        });
    }

    /**
     * Verifies that the Jaeger tracer implementation can successfully use the adapter to inject and extract
     * a SpanContext.
     */
    @Test
    public void testJaegerTracerCanUseAdapter() {
        final Configuration config = new Configuration("test");
        final Tracer tracer = config.getTracer();
        final Span span = tracer.buildSpan("do").start();

        final MultiMap multiMap = new CaseInsensitiveHeaders();
        final MultiMapInjectAdapter injectAdapter = new MultiMapInjectAdapter(multiMap);
        tracer.inject(span.context(), Format.Builtin.TEXT_MAP, injectAdapter);

        final SpanContext context = tracer.extract(Format.Builtin.TEXT_MAP, new MultiMapExtractAdapter(multiMap));
        assertThat(context.toSpanId()).isEqualTo(span.context().toSpanId());
    }
}
