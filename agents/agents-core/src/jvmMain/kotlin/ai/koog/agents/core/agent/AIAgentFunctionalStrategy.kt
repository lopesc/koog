@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "MissingKDocForPublicAPI")

package ai.koog.agents.core.agent

import ai.koog.agents.annotations.JavaAPI
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.entity.AIAgentStrategy
import java.util.concurrent.ExecutorService

public actual interface AIAgentFunctionalStrategy<TInput, TOutput> :
    AIAgentStrategy<TInput, TOutput, AIAgentFunctionalContext> {
    actual override suspend fun execute(
        context: AIAgentFunctionalContext,
        input: TInput
    ): TOutput
}

private fun f() {
    val s = object : AIAgentFunctionalStrategy<String, String> {
        override suspend fun execute(
            context: AIAgentFunctionalContext,
            input: String
        ): String {
            TODO("Not yet implemented")
        }

        override val name: String
            get() = TODO("Not yet implemented")

    }
}
