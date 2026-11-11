package com.mysi.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The model's request lock, held for a whole request, a model load, New chat, or a pipeline
 * started outside a request. It is reentrant along one coroutine tree: the coroutine that took it
 * marks its context, and that coroutine and every child it starts (a tool's run, the tool's
 * async work, a pipeline step inside it) pass straight through. Anything else waits.
 *
 * So a tool may call the model (Agent.complete) from its own coroutine or its children, but never
 * from a separate scope (a launch in Agent.scope, GlobalScope, runBlocking): that work does not
 * carry the mark and would wait for the request that is waiting for it.
 */
internal class EngineLock {
    private val mutex = Mutex()

    private class Held(val owner: EngineLock) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    /** True when the calling coroutine (or one of its ancestors) holds this lock. */
    suspend fun heldHere(): Boolean = currentCoroutineContext()[Held]?.owner === this

    suspend fun <T> withEngine(block: suspend () -> T): T =
        if (heldHere()) block()
        else mutex.withLock { withContext(Held(this)) { block() } }
}
