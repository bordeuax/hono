/*******************************************************************************
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.hono.deviceregistry.util;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.auth.BCryptHelper;
import org.eclipse.hono.auth.HonoPasswordEncoder;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.service.management.credentials.CommonCredential;
import org.eclipse.hono.service.management.credentials.PasswordCredential;
import org.eclipse.hono.service.management.credentials.PasswordSecret;
import org.eclipse.hono.service.management.tenant.Tenant;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CacheDirective;
import org.eclipse.hono.util.CredentialsConstants;
import org.eclipse.hono.util.RegistryManagementConstants;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A collection of utility methods for implementing device registries.
 *
 */
public final class DeviceRegistryUtils {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceRegistryUtils.class);

    private DeviceRegistryUtils() {
        // prevent instantiation
    }

    /**
     * Converts tenant object of type {@link Tenant} to an object of type {@link TenantObject}.
     *
     * @param tenantId The identifier of the tenant.
     * @param source   The source tenant object.
     * @return The converted tenant object of type {@link TenantObject}
     * @throws NullPointerException if the tenantId or source is null.
     */
    public static JsonObject convertTenant(final String tenantId, final Tenant source) {
        return convertTenant(tenantId, source, false);
    }

    /**
     * Converts tenant object of type {@link Tenant} to an object of type {@link TenantObject}.
     *
     * @param tenantId The identifier of the tenant.
     * @param source   The source tenant object.
     * @param filterAuthorities if set to true filter out CAs which are not valid at this point in time.
     * @return The converted tenant object of type {@link TenantObject}
     * @throws NullPointerException if the tenantId or source is null.
     */
    public static JsonObject convertTenant(final String tenantId, final Tenant source, final boolean filterAuthorities) {

        final Instant now = Instant.now();

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(source);

        final TenantObject target = TenantObject.from(tenantId, Optional.ofNullable(source.isEnabled()).orElse(true));
        target.setResourceLimits(source.getResourceLimits());
        target.setTracingConfig(source.getTracing());

        Optional.ofNullable(source.getMinimumMessageSize())
        .ifPresent(size -> target.setMinimumMessageSize(size));

        Optional.ofNullable(source.getDefaults())
        .map(JsonObject::new)
        .ifPresent(defaults -> target.setDefaults(defaults));

        Optional.ofNullable(source.getAdapters())
                .filter(adapters -> !adapters.isEmpty())
                .map(adapters -> adapters.stream()
                                .map(adapter -> JsonObject.mapFrom(adapter))
                                .map(json -> json.mapTo(org.eclipse.hono.util.Adapter.class))
                                .collect(Collectors.toList()))
                .ifPresent(adapters -> target.setAdapters(adapters));

        Optional.ofNullable(source.getExtensions())
        .map(JsonObject::new)
        .ifPresent(extensions -> target.setProperty(RegistryManagementConstants.FIELD_EXT, extensions));

        Optional.ofNullable(source.getTrustedCertificateAuthorities())
        .map(list -> list.stream()
                .filter(ca -> {
                    if (filterAuthorities) {
                        // filter out CAs which are not valid at this point in time
                        return !now.isBefore(ca.getNotBefore()) && !now.isAfter(ca.getNotAfter());
                    } else {
                        return true;
                    }
                })
                .map(ca -> JsonObject.mapFrom(ca))
                .map(json -> {
                    // validity period is not included in TenantObject
                    json.remove(RegistryManagementConstants.FIELD_SECRETS_NOT_BEFORE);
                    json.remove(RegistryManagementConstants.FIELD_SECRETS_NOT_AFTER);
                    return json;
                })
                .collect(JsonArray::new, JsonArray::add, JsonArray::add))
        .ifPresent(authorities -> target.setProperty(TenantConstants.FIELD_PAYLOAD_TRUSTED_CA, authorities));

        return JsonObject.mapFrom(target);
    }

    /**
     * Gets the cache directive corresponding to the given max age for the cache.
     *
     * @param cacheMaxAge the maximum period of time in seconds that the information
     *                    returned by the service's operations may be cached for.
     * @return the cache directive corresponding to the given max age for the cache.
     */
    public static CacheDirective getCacheDirective(final int cacheMaxAge) {
        if (cacheMaxAge > 0) {
            return CacheDirective.maxAgeDirective(cacheMaxAge);
        } else {
            return CacheDirective.noCacheDirective();
        }
    }

    /**
     * Gets a unique identifier generated using {@link UUID#randomUUID()}.
     * 
     * @return The generated unique identifier.
     */
    public static String getUniqueIdentifier() {
        return UUID.randomUUID().toString();
    }

    /**
     * Gets the certificate of the device to be provisioned from the client context.
     *
     * @param tenantId The tenant to which the device belongs.
     * @param authId The authentication identifier.
     * @param clientContext The client context that can be used to get the X.509 certificate 
     *                      of the device to be provisioned.
     * @param span The active OpenTracing span for this operation. It is not to be closed in this method! An
     *             implementation should log (error) events on this span and it may set tags and use this span 
     *             as the parent for any spans created in this method.
     * @return A future indicating the outcome of the operation. If the operation succeeds, the
     *         retrieved certificate is returned. Else {@link Optional#empty()} is returned.
     * @throws NullPointerException if any of the parameters except span is {@code null}.
     */
    public static Future<Optional<X509Certificate>> getCertificateFromClientContext(
            final String tenantId,
            final String authId,
            final JsonObject clientContext,
            final Span span) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(authId);
        Objects.requireNonNull(clientContext);

        try {
            final byte[] bytes = clientContext.getBinary(CredentialsConstants.FIELD_CLIENT_CERT);
            if (bytes == null) {
                return Future.succeededFuture(Optional.empty());
            }
            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            final X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes));

            if (!cert.getSubjectX500Principal().getName(X500Principal.RFC2253).equals(authId)) {
                throw new IllegalArgumentException(
                        String.format("Subject DN of the client certificate does not match authId [%s] for tenant [%s]",
                                authId, tenantId));
            }
            return Future.succeededFuture(Optional.of(cert));
        } catch (final CertificateException | ClassCastException | IllegalArgumentException error) {
            final String errorMessage = String.format(
                    "Error getting certificate from client context with authId [%s] for tenant [%s]", authId, tenantId);
            LOG.error(errorMessage, error);
            TracingHelper.logError(span, errorMessage, error);
            return Future
                    .failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, errorMessage, error));
        }
    }

    /**
     * Validates the given secret.
     *
     * @param credential The secret to validate.
     * @param passwordEncoder The password encoder.
     * @param hashAlgorithmsWhitelist The list of supported hashing algorithms for pre-hashed passwords.
     * @param maxBcryptIterations The maximum number of iterations to use for bcrypt password hashes.
     * @throws IllegalStateException if the secret is not valid.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public static void checkCredential(final CommonCredential credential, final HonoPasswordEncoder passwordEncoder,
            final Set<String> hashAlgorithmsWhitelist, final int maxBcryptIterations) {
        Objects.requireNonNull(credential);
        Objects.requireNonNull(passwordEncoder);
        Objects.requireNonNull(hashAlgorithmsWhitelist);

        credential.checkValidity();
        if (credential instanceof PasswordCredential) {
            for (final PasswordSecret passwordSecret : ((PasswordCredential) credential).getSecrets()) {
                passwordSecret.encode(passwordEncoder);
                passwordSecret.checkValidity();
                verifyHashAlgorithmIsAuthorised(passwordSecret, hashAlgorithmsWhitelist);
                if (!passwordSecret.containsOnlySecretId()) {
                    switch (passwordSecret.getHashFunction()) {
                    case RegistryManagementConstants.HASH_FUNCTION_BCRYPT:
                        final String pwdHash = passwordSecret.getPasswordHash();
                        verifyBcryptPasswordHash(pwdHash, maxBcryptIterations);
                        break;
                    default:
                        // pass
                    }
                    // pass
                }
                // pass
            }
        }
    }

    /**
     * Verifies that a hash algorithm in the supplied PasswordSecret is authorised.
     * <p>
     * The value must be present in the whitelist provided.
     * If the whitelist is empty, any value will be accepted.
     *
     * @param secret The PasswordSecret object to verify.
     * @param hashAlgorithmsWhitelist The list of supported hashing algorithms for pre-hashed passwords.
     * @throws IllegalStateException if the hash algorithm provided in the PasswordSecret is not in the whitelist.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    private static void verifyHashAlgorithmIsAuthorised(final PasswordSecret secret, final Set<String> hashAlgorithmsWhitelist) {
        Objects.requireNonNull(secret);
        Objects.requireNonNull(hashAlgorithmsWhitelist);

        if (hashAlgorithmsWhitelist.isEmpty()
            || secret.containsOnlySecretId()) {
            return;
        }

        final String hashAlgorithm = secret.getHashFunction();
        Objects.requireNonNull(hashAlgorithm);

        if (hashAlgorithmsWhitelist.contains(hashAlgorithm)) {
                return;
        }
        throw new IllegalStateException("Hashing algorithm is not in whitelist: " + hashAlgorithm);
    }

    /**
     * Verifies that a hash value is a valid BCrypt password hash.
     * <p>
     * The hash must be a version 2a hash and must not use more than the given
     * maximum number of iterations.
     *
     * @param pwdHash The hash to verify.
     * @param maxBcryptIterations The maximum number of iterations to use for 
     *                            bcrypt password hashes.
     * @throws IllegalStateException if the secret does not match the criteria.
     * @throws NullPointerException if pwdHash is {@code null}.
     */
    private static void verifyBcryptPasswordHash(final String pwdHash, final int maxBcryptIterations) {
        Objects.requireNonNull(pwdHash);

        if (BCryptHelper.getIterations(pwdHash) > maxBcryptIterations) {
            throw new IllegalStateException("password hash uses too many iterations, max is " + maxBcryptIterations);
        }
    }
}
