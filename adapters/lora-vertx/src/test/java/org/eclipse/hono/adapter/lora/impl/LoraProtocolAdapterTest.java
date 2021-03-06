/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.adapter.lora.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.adapter.lora.LoraConstants;
import org.eclipse.hono.adapter.lora.LoraMessageType;
import org.eclipse.hono.adapter.lora.LoraProtocolAdapterProperties;
import org.eclipse.hono.adapter.lora.providers.LoraProvider;
import org.eclipse.hono.adapter.lora.providers.LoraProviderMalformedPayloadException;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.DownstreamSender;
import org.eclipse.hono.client.DownstreamSenderFactory;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.RegistrationClient;
import org.eclipse.hono.client.RegistrationClientFactory;
import org.eclipse.hono.client.TenantClient;
import org.eclipse.hono.client.TenantClientFactory;
import org.eclipse.hono.service.auth.DeviceUser;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.TenantObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentracing.SpanContext;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.proton.ProtonDelivery;

/**
 * Verifies behavior of {@link LoraProtocolAdapter}.
 */
public class LoraProtocolAdapterTest {

    private static final String TEST_TENANT_ID = "myTenant";
    private static final String TEST_GATEWAY_ID = "myLoraGateway";
    private static final String TEST_DEVICE_ID = "myLoraDevice";
    private static final String TEST_PAYLOAD = "bumxlux";
    private static final String TEST_PROVIDER = "bumlux";

    private LoraProtocolAdapter adapter;
    private TenantClientFactory tenantClientFactory;
    private RegistrationClientFactory registrationClientFactory;
    private DownstreamSenderFactory downstreamSenderFactory;
    private DownstreamSender telemetrySender;
    private DownstreamSender eventSender;

    /**
     * Sets up the fixture.
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        adapter = new LoraProtocolAdapter();
        adapter.setConfig(new LoraProtocolAdapterProperties());

        tenantClientFactory = mock(TenantClientFactory.class);
        when(tenantClientFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(tenantClientFactory).disconnect(any(Handler.class));

        registrationClientFactory = mock(RegistrationClientFactory.class);
        when(registrationClientFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(registrationClientFactory).disconnect(any(Handler.class));

        downstreamSenderFactory = mock(DownstreamSenderFactory.class);
        when(downstreamSenderFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(downstreamSenderFactory).disconnect(any(Handler.class));

        final TenantClient tenantClient = mock(TenantClient.class);
        doAnswer(invocation -> {
            return Future.succeededFuture(TenantObject.from(invocation.getArgument(0), true));
        }).when(tenantClient).get(anyString(), (SpanContext) any());
        doAnswer(invocation -> {
            return Future.succeededFuture(TenantObject.from(invocation.getArgument(0), true));
        }).when(tenantClient).get(anyString());
        when(tenantClientFactory.getOrCreateTenantClient()).thenReturn(Future.succeededFuture(tenantClient));

        final RegistrationClient regClient = mock(RegistrationClient.class);
        when(regClient.assertRegistration(anyString(), any(), (SpanContext) any())).thenReturn(Future.succeededFuture(new JsonObject()));
        when(registrationClientFactory.getOrCreateRegistrationClient(anyString())).thenReturn(Future.succeededFuture(regClient));

        telemetrySender = mock(DownstreamSender.class);
        when(telemetrySender.send(any(Message.class), (SpanContext) any())).thenReturn(Future.succeededFuture(mock(ProtonDelivery.class)));
        when(telemetrySender.sendAndWaitForOutcome(any(Message.class), (SpanContext) any())).thenReturn(
                Future.succeededFuture(mock(ProtonDelivery.class)));
        when(downstreamSenderFactory.getOrCreateTelemetrySender(anyString())).thenReturn(Future.succeededFuture(telemetrySender));

        eventSender = mock(DownstreamSender.class);
        when(eventSender.send(any(Message.class), (SpanContext) any())).thenThrow(new UnsupportedOperationException());
        when(eventSender.sendAndWaitForOutcome(any(Message.class), (SpanContext) any())).thenReturn(Future.succeededFuture(mock(ProtonDelivery.class)));
        when(downstreamSenderFactory.getOrCreateEventSender(anyString())).thenReturn(Future.succeededFuture(eventSender));

        adapter.setTenantClientFactory(tenantClientFactory);
        adapter.setRegistrationClientFactory(registrationClientFactory);
        adapter.setDownstreamSenderFactory(downstreamSenderFactory);
    }

    /**
     * Verifies that an uplink message is routed to a provider correctly.
     */
    @Test
    public void handleProviderRouteSuccessfullyForUplinkMessage() {
        final LoraProvider providerMock = getLoraProviderMock();
        final RoutingContext routingContextMock = getRoutingContextMock();
        final HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader(eq(Constants.HEADER_QOS_LEVEL))).thenReturn(null);
        when(routingContextMock.request()).thenReturn(request);

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);

        verify(telemetrySender).send(any(Message.class), any(SpanContext.class));

        verify(routingContextMock.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
    }

    /**
     * Verifies that an options request is routed to a provider correctly.
     */
    @Test
    public void handleProviderRouteSuccessfullyForOptionsRequest() {
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleOptionsRoute(routingContextMock);

        verify(routingContextMock.response()).setStatusCode(HttpResponseStatus.OK.code());
    }

    /**
     * Verifies that the provider route discards join messages.
     */
    @Test
    public void handleProviderRouteDiscardsJoinMessages() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractMessageType(any())).thenReturn(LoraMessageType.JOIN);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(routingContextMock.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
    }

    /**
     * Verifies that the provider route discards downlink messages.
     */
    @Test
    public void handleProviderRouteDiscardsDownlinkMessages() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractMessageType(any())).thenReturn(LoraMessageType.DOWNLINK);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(routingContextMock.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
    }

    /**
     * Verifies that the provider route discards other messages.
     */
    @Test
    public void handleProviderRouteDiscardsOtherMessages() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractMessageType(any())).thenReturn(LoraMessageType.UNKNOWN);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(routingContextMock.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
    }

    /**
     * Verifies that the provider route rejects invalid gateway credentials with unauthorized.
     */
    @Test
    public void handleProviderRouteCausesUnauthorizedForInvalidGatewayCredentials() {
        final LoraProvider providerMock = getLoraProviderMock();
        final RoutingContext routingContextMock = getRoutingContextMock();
        when(routingContextMock.user()).thenReturn(null);

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyUnauthorized(routingContextMock);
    }

    /**
     * Verifies that the provider route rejects a missing device id with bad request.
     */
    @Test
    public void handleProviderRouteCausesBadRequestForMissingDeviceId() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractDeviceId(any())).thenReturn(null);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyBadRequest(routingContextMock);
    }

    /**
     * Verifies that the provider route rejects a missing payload with bad request.
     */
    @Test
    public void handleProviderRouteCausesBadRequestForMissingPayload() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractPayload(any())).thenReturn(null);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyBadRequest(routingContextMock);
    }

    /**
     * Verifies that the provider route rejects an invalid payload with bad request.
     */
    @Test
    public void handleProviderRouteCausesBadRequestForInvalidPayload() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractPayload(any())).thenThrow(ClassCastException.class);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);
        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyBadRequest(routingContextMock);
    }

    /**
     * Verifies that the provider route rejects a malformed payload with bad request.
     */
    @Test
    public void handleProviderRouteCausesBadRequestInCaseOfPayloadTransformationException() {
        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.extractPayload(any())).thenThrow(LoraProviderMalformedPayloadException.class);
        final RoutingContext routingContextMock = getRoutingContextMock();

        adapter.handleProviderRoute(routingContextMock, providerMock);

        verify(routingContextMock).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyBadRequest(routingContextMock);
    }

    /**
     * Verifies that the provider name is added to the message when using customized downstream message.
     */
    @Test
    public void customizeDownstreamMessageAddsProviderNameToMessage() {
        final RoutingContext routingContextMock = getRoutingContextMock();
        final Message messageMock = mock(Message.class);

        adapter.customizeDownstreamMessage(messageMock, routingContextMock);

        verify(messageMock).setApplicationProperties(argThat(properties -> TEST_PROVIDER
                .equals(properties.getValue().get(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER))));
    }

    private LoraProvider getLoraProviderMock() {
        final LoraProvider provider = mock(LoraProvider.class);
        when(provider.getProviderName()).thenReturn(TEST_PROVIDER);
        when(provider.pathPrefix()).thenReturn("/bumlux");
        when(provider.extractMessageType(any())).thenReturn(LoraMessageType.UPLINK);
        when(provider.extractDeviceId(any())).thenReturn(TEST_DEVICE_ID);
        when(provider.extractPayload(any())).thenReturn(TEST_PAYLOAD);

        return provider;
    }

    private RoutingContext getRoutingContextMock() {
        final RoutingContext context = mock(RoutingContext.class);
        when(context.user()).thenReturn(new DeviceUser(TEST_TENANT_ID, TEST_GATEWAY_ID));
        when(context.response()).thenReturn(mock(HttpServerResponse.class));
        when(context.get(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER)).thenReturn(TEST_PROVIDER);

        return context;
    }

    private void verifyUnauthorized(final RoutingContext routingContextMock) {
        verifyErrorCode(routingContextMock, HttpResponseStatus.UNAUTHORIZED);
    }

    private void verifyBadRequest(final RoutingContext routingContextMock) {
        verifyErrorCode(routingContextMock, HttpResponseStatus.BAD_REQUEST);
    }

    private void verifyErrorCode(final RoutingContext routingContextMock, final HttpResponseStatus expectedStatusCode) {
        final ArgumentCaptor<ClientErrorException> failCaptor = ArgumentCaptor.forClass(ClientErrorException.class);
        verify(routingContextMock).fail(failCaptor.capture());
        assertEquals(expectedStatusCode.code(), failCaptor.getValue().getErrorCode());
    }
}
