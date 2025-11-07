package ai.koog.agents.core.feature.pipeline

import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.node.NodeExecutionEventHandler
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline.RegisteredFeature
import kotlin.collections.set

/**
 *
 * */
public actual class AIAgentGraphPipeline {
    /**
     * Map of node execution handlers registered for different features.
     * Keys are feature storage keys, values are node execution handlers.
     */
    private val executeNodeHandlers: MutableMap<AIAgentStorageKey<*>, NodeExecutionEventHandler> = mutableMapOf()


    /**
     *
     * */
    public actual fun <TConfig : FeatureConfig, TFeature : Any> install(
        feature: AIAgentGraphFeature<TConfig, TFeature>,
        configure: TConfig.() -> Unit,
    ) {
        val featureConfig = feature.createInitialConfig().apply { configure() }
        val featureImpl = feature.install(
            config = featureConfig,
            pipeline = this,
        )

        registeredFeatures[feature.key] = RegisteredFeature(featureImpl, featureConfig)
    }
}
