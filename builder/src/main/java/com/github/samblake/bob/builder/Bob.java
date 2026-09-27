package com.github.samblake.bob.builder;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates {@code <Name>Builder}, which tracks in its type which constructor arguments have been set,
 * and {@code <Name>Builders}, whose static {@code from} only accepts a builder with every argument set.
 * The annotated class must have exactly one non private constructor and must extend {@code <Name>Builders}
 * so that {@code <Name>.from(builder)} is available.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Bob {
}