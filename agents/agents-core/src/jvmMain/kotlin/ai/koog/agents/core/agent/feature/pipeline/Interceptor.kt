package ai.koog.agents.core.agent.feature.pipeline

import java.util.concurrent.CompletableFuture

/**
 *
 * */
public fun interface SyncInterceptor<ContextT> {
    /**
     *
     * */
    public fun intercept(contextT: ContextT): CompletableFuture<Boolean>
}
