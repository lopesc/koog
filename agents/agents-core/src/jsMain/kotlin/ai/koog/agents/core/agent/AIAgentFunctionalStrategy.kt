@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "MissingKDocForPublicAPI")

package ai.koog.agents.core.agent

import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.entity.AIAgentStrategy

public actual interface AIAgentFunctionalStrategy<TInput, TOutput> :
    AIAgentStrategy<TInput, TOutput, AIAgentFunctionalContext> {
    actual override suspend fun execute(
        context: AIAgentFunctionalContext,
        input: TInput
    ): TOutput
}
