@file:Suppress("MissingKDocForPublicAPI")

package ai.koog.agents.snapshot.providers

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import kotlin.jvm.JvmOverloads

@Deprecated(
    "`PersistencyStorageProvider` has been renamed to `PersistenceStorageProvider`",
    replaceWith = ReplaceWith(
        expression = "PersistenceStorageProvider",
        "ai.koog.agents.snapshot.feature.PersistenceStorageProvider"
    )
)
public typealias PersistencyStorageProvider<Filter> = PersistenceStorageProvider<Filter>

/**
 * Storage provider (ex: database, S3, file) to be used in [ai.koog.agents.snapshot.feature.Persistence] feature.
 * */
public interface PersistenceStorageProvider<Filter> {

    /**
     * Retrieves the list of checkpoints of the AI agent with the given [agentId] that match the provided [filter]
     * */
    @JvmOverloads
    public suspend fun getCheckpoints(agentId: String, filter: Filter? = null): List<AgentCheckpointData>

    /**
     * Saves provided checkpoint ([agentCheckpointData]) of the agent with [agentId] to the storage (ex: database, S3, file)
     * */
    public suspend fun saveCheckpoint(agentId: String, agentCheckpointData: AgentCheckpointData)

    /**
     * Retrieves the latest checkpoint of the AI agent with [agentId] matching the provided [filter]
     * */
    @JvmOverloads
    public suspend fun getLatestCheckpoint(agentId: String, filter: Filter? = null): AgentCheckpointData?
}
