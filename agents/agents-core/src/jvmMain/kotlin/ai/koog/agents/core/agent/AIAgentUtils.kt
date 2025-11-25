package ai.koog.agents.core.agent

import ai.koog.agents.annotations.JavaAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.coroutines.CoroutineContext

/**
 * Runs the given [AIAgent] with the provided [agentInput], and produces the agent's [Output].
 * Blocks the current thread of execution until the result is ready.
 *
 * @param agentInput input that would be provided to the agent
 * @param executorService optional [ExecutorService] to run the agent on. Note: an agent can spawn parallel tasks when executing it's strategy.
 */
@JavaAPI
@JvmOverloads
public fun <Input, Output> AIAgent<Input, Output>.run(
    agentInput: Input,
    executorService: ExecutorService? = null
): Output = if (executorService == null) {
    runBlocking {
        run(agentInput)
    }
} else {
    runBlocking(executorService.asCoroutineDispatcher()) {
        run(agentInput)
    }
}

/**
 *
 */
@JavaAPI
@JvmOverloads
public fun <Output> AIAgent<*, Output>.getState(
    executorService: ExecutorService? = null,
): CompletableFuture<AIAgent.Companion.State<Output>> =
    CoroutineScope(coroutineContext).future {
        getState()
    }


private fun ExecutorService?.asCoroutineContext(): CoroutineContext = this?.asCoroutineDispatcher() ?: Dispatchers.Default
