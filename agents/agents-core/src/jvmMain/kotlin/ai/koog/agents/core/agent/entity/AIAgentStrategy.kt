@file:Suppress("MissingKDocForPublicAPI", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package ai.koog.agents.core.agent.entity

import ai.koog.agents.annotations.JavaAPI
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.utils.asCoroutineContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService

public actual interface AIAgentStrategy<TInput, TOutput, TContext : AIAgentContext> {
    public actual val name: String
    public actual suspend fun execute(context: TContext, input: TInput): TOutput?
}

// kotlin: override suspend fun execute()
//
// java: AIAgentFunctionalStrategy(executor) {
//    override fun execute() {
//
//    }
// } -> override suspend fun execute() = executor.schedule{ execute() }

// AIAgentStrategy
//  .functionalStrategy()
//

