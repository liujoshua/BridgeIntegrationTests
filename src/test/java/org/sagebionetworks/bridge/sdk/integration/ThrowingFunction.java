package org.sagebionetworks.bridge.sdk.integration;

/** Used for lambdas that can also throw checked exception. */
@FunctionalInterface
public interface ThrowingFunction<S,T> {
    T apply(S s) throws Exception;
}
