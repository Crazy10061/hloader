package com.hloader.mod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an hloader mod entrypoint. The annotated class must
 * implement {@link ModEntrypoint} and have a public no-args constructor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HMod {

    /** Unique identifier for this mod, e.g. "double-jump". */
    String id();

    /** Semver-ish version string. */
    String version() default "1.0.0";
}
