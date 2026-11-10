package edu.harvard.hms.dbmi.avillach.operations.query;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Wraps a repository so a test can act around chosen calls: hold a thread after a lookup, or fail a write. Every call the interceptor does
 * not single out goes straight to the real repository.
 */
final class InterceptedRepository {

    private InterceptedRepository() {}

    /** One call on the real repository, not yet made. */
    @FunctionalInterface
    interface Call {

        /**
         * Makes the call on the real repository.
         *
         * @return what the repository returned
         * @throws Throwable what the repository threw
         */
        Object proceed() throws Throwable;
    }

    /** Decides what happens around each repository call. */
    @FunctionalInterface
    interface Interceptor {

        /**
         * Handles one repository call.
         *
         * @param methodName the name of the repository method called
         * @param call the call on the real repository
         * @return the value the caller of the repository receives
         * @throws Throwable the failure the caller of the repository receives
         */
        Object around(String methodName, Call call) throws Throwable;
    }

    /**
     * Returns a repository that passes every call through the interceptor.
     *
     * @param type the repository interface
     * @param target the real repository
     * @param interceptor what to do around each call
     * @param <T> the repository interface
     * @return the wrapped repository
     */
    static <T> T of(Class<T> type, T target, Interceptor interceptor) {
        return type.cast(
            Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> interceptor.around(method.getName(), () -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                })
            )
        );
    }
}
