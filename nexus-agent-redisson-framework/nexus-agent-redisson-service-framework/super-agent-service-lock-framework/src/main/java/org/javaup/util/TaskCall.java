package org.javaup.util;

@FunctionalInterface
public interface TaskCall<V> {

    V call();
}
