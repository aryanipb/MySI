package com.mysi.agent

import android.content.Context
import android.content.ContextWrapper

/**
 * A Context for building tool lists on the JVM. Building a skill's tools only stores the Context
 * in lambdas, so an uninitialised instance is enough; running a tool with it would fail, and no
 * test here does.
 */
object TestContext {
    val unsafe: Context by lazy {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ContextWrapper::class.java) as Context
    }
}
