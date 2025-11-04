package ai.koog.agents.core.agent

import ai.koog.agents.core.agent.AIAgent.Companion.State.Finished
import ai.koog.agents.core.agent.AIAgent.Companion.State.Running
import ai.koog.agents.core.agent.GraphAIAgent.FeatureContext
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.AIAgentConfigBase
import ai.koog.agents.core.agent.config.MissingToolsConversionStrategy
import ai.koog.agents.core.agent.config.ToolCallDescriber
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.feature.AIAgentFunctionalFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.utils.ConfigureAction
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.io.Closeable
import kotlinx.datetime.Clock
import kotlin.jvm.JvmStatic
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

/**
 * Represents a basic interface for AI agent.
 */
public interface AIAgent<Input, Output> : Closeable {

    /**
     * Represents the unique identifier for the AI agent.
     */
    public val id: String

    /**
     * The configuration for the AI agent.
     */
    public val agentConfig: AIAgentConfigBase

    /**
     * Retrieves the current state of the AI agent during its lifecycle.
     *
     * This method provides the current `State` of the agent, which can
     * be one of the defined states: [State.NotStarted], [State.Running], [State.Finished], or [State.Failed].
     *
     * @return The current state of the AI agent.
     */
    public suspend fun getState(): State<Output>

    /**
     * Retrieves the result of the operation if the current state is `State.Finished`.
     * Throws an `IllegalStateException` if the operation is not in a finished state.
     *
     * @return The result of type `Output` when the operation is completed successfully.
     * @throws IllegalStateException if the operation's state is not `State.Finished`.
     */
    public suspend fun result(): Output = when (val state = getState()) {
        is Finished<Output> -> state.result
        else -> throw IllegalStateException("Output is not ready, agent's state is: $state")
    }

    /**
     * Executes the AI agent with the given input and retrieves the resulting output.
     *
     * @param agentInput The input for the agent.
     * @return The output produced by the agent.
     */
    public suspend fun run(agentInput: Input): Output


    /**
     * The companion object for the AIAgent class, providing functionality to instantiate an AI agent
     * with a flexible configuration, input/output types, and execution strategy.
     */
    public companion object {
        /**
         * Creates and returns a new instance of the `Builder` class to configure and construct an AI agent.
         *
         * @return An instance of `Builder` for configuring an AI agent.
         */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Represents the state of an AI agent during its lifecycle.
         *
         * This sealed interface provides different states to reflect whether the agent
         * has not started, is currently running, has completed its task successfully with a result,
         * or has failed with an exception.
         */
        public sealed interface State<Output> {
            /**
             * Creates and returns a copy of the current state object.
             *
             * @return A new instance of `State<Output>` that is a copy of the current object.
             */
            public fun copy(): State<Output>

            /**
             * Represents a state that indicates an action or process has not yet started.
             *
             * This class is part of the `State` sealed interface and is used to define
             * a specific state where no progress, execution, or processing has occurred.
             */
            public class NotStarted<Output> : State<Output> {
                override fun copy(): State<Output> = NotStarted()
            }

            /**
             * Represents the starting state of an operation or process.
             *
             * This class is a specialization of the `State` class, indicating the initial
             * state prior to progression or change. It overrides the `copy` method to
             * return a new instance of the same starting state.
             *
             * @param Output The type of output associated with the state.
             */
            public class Starting<Output> : State<Output> {
                override fun copy(): State<Output> = Starting()
            }

            /**
             * Represents the `Running` state of an AI agent, indicating that the agent is actively executing its tasks.
             *
             * This state provides access to the root context of the agent via the `rootContext` property, allowing
             * interaction with the overall execution environment, configuration, and state management facilities.
             *
             * The `rootContext` is marked with the `@InternalAgentsApi` annotation, meaning its usage is intended for
             * internal agent-related implementations and may not maintain backwards compatibility.
             *
             * @property rootContext Provides access to the root context of the agent, facilitating operations
             *                       such as state management, feature retrieval, and context-based workflows.
             *                       This allows the agent to perform actions and manage its execution lifecycle within the given context.
             */
            public class Running<Output>(
                @property:InternalAgentsApi public val rootContext: AIAgentContext
            ) : State<Output> {
                @OptIn(InternalAgentsApi::class)
                override fun copy(): State<Output> = Running(rootContext)
            }

            /**
             * Represents the final state of a computation or process with its resulting output.
             *
             * @param Output The type of the result produced by the finished computation or process.
             * @property result The computed result of the finished process.
             */
            public class Finished<Output>(
                public val result: Output
            ) : State<Output> {
                override fun copy(): State<Output> = Finished(result)
            }

            /**
             * Represents a state indicating an operation has failed.
             *
             * @property exception The throwable that caused the failure.
             */
            public class Failed<Output>(
                public val exception: Throwable
            ) : State<Output> {
                override fun copy(): State<Output> = Failed(exception)
            }
        }

        /**
         * Creates an instance of an AI agent based on the provided configuration, input/output types,
         * and execution strategy.
         *
         * @param Input The type of the input the AI agent will process.
         * @param Output The type of the output the AI agent will produce.
         * @param promptExecutor The executor responsible for processing prompts and interacting with the language model.
         * @param agentConfig The configuration for the AI agent, including the prompt, model, and other parameters.
         * @param toolRegistry The registry of tools available for use by the agent. Defaults to an empty registry.
         * @param strategy The strategy for executing the AI agent's graph logic, including workflows and decision-making.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @param clock The clock to be used for time-related operations. Defaults to the system clock.
         * @param installFeatures A lambda expression to install additional features in the agent's feature context. Defaults to an empty implementation.
         * @return An instance of an AI agent configured with the specified parameters and capable of executing its logic.
         */
        @OptIn(ExperimentalUuidApi::class)
        public inline operator fun <reified Input, reified Output> invoke(
            promptExecutor: PromptExecutor,
            agentConfig: AIAgentConfig,
            strategy: AIAgentGraphStrategy<Input, Output>,
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            id: String? = null,
            clock: Clock = Clock.System,
            noinline installFeatures: FeatureContext.() -> Unit = {},
        ): AIAgent<Input, Output> {
            return GraphAIAgent(
                inputType = typeOf<Input>(),
                outputType = typeOf<Output>(),
                promptExecutor = promptExecutor,
                agentConfig = agentConfig,
                toolRegistry = toolRegistry,
                strategy = strategy,
                id = id,
                clock = clock,
                installFeatures = installFeatures
            )
        }

        /**
         * Operator function to create and invoke an AI agent with the given parameters.
         *
         * @param promptExecutor The executor responsible for running the prompt and generating outputs.
         * @param prompt The prompt to be processed by the AI agent.
         * @param agentConfig Configuration settings for the AI agent.
         * @param strategy The strategy to be used for the AI agent's execution graph. Defaults to a single-run strategy.
         * @param toolRegistry Registry of tools available for the AI agent to use. Defaults to an empty registry.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @param installFeatures Lambda function for installing additional features into the feature context. Defaults to an empty lambda.
         * @return An instance of AIAgent configured with the graph strategy.
         */
        @OptIn(ExperimentalUuidApi::class)
        public operator fun invoke(
            promptExecutor: PromptExecutor,
            agentConfig: AIAgentConfig,
            strategy: AIAgentGraphStrategy<String, String> = singleRunStrategy(),
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            id: String? = null,
            installFeatures: FeatureContext.() -> Unit = {},
        ): AIAgent<String, String> = GraphAIAgent(
            inputType = typeOf<String>(),
            outputType = typeOf<String>(),
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry,
            strategy = strategy,
            id = id,
            clock = Clock.System,
            installFeatures = installFeatures
        )

        /**
         * Creates a functional AI agent with the provided configurations and execution strategy.
         *
         * @param Input The type of the input the AI agent will process.
         * @param Output The type of the output the AI agent will produce.
         * @param promptExecutor The executor responsible for running prompts against the language model.
         * @param agentConfig The configuration for the AI agent, including prompt setup, language model, and iteration limits.
         * @param toolRegistry The registry containing available tools for the AI agent. Defaults to an empty registry.
         * @param strategy The strategy for executing the agent's logic, including workflows and decision-making.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @return A `FunctionalAIAgent` instance configured with the provided parameters and execution strategy.
         */
        @OptIn(ExperimentalUuidApi::class)
        public operator fun <Input, Output> invoke(
            promptExecutor: PromptExecutor,
            agentConfig: AIAgentConfig,
            strategy: AIAgentFunctionalStrategy<Input, Output>,
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            id: String? = null,
            clock: Clock = Clock.System,
            installFeatures: FunctionalAIAgent.FeatureContext.() -> Unit = {},
        ): FunctionalAIAgent<Input, Output> {
            return FunctionalAIAgent(
                id = id,
                promptExecutor = promptExecutor,
                agentConfig = agentConfig,
                toolRegistry = toolRegistry,
                strategy = strategy,
                clock = clock,
                installFeatures = installFeatures
            )
        }

        /**
         * Construction of an AI agent with the specified configurations and parameters.
         *
         * @param promptExecutor The executor responsible for processing language model prompts.
         * @param llmModel The specific large language model to be used for the agent.
         * @param strategy The strategy that defines the agent's workflow, defaulting to the [singleRunStrategy].
         * @param toolRegistry The set of tools available for the agent, defaulting to an empty registry.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @param systemPrompt The system-level prompt used as context for the agent, defaulting to an empty string.
         * @param temperature The randomness or creativity of the model's responses, with valid values ranging typically from 0.0 to 1.0. Defaults to 1.0.
         * @param numberOfChoices The number of response choices to be generated, defaulting to 1.
         * @param maxIterations The maximum number of iterations the agent is allowed to perform, defaulting to 50.
         * @param installFeatures A function to configure additional features into the agent during initialization. Defaults to an empty configuration.
         * @return An instance of [AIAgent] configured with the provided parameters.
         */
        @OptIn(ExperimentalUuidApi::class)
        public operator fun invoke(
            promptExecutor: PromptExecutor,
            llmModel: LLModel,
            strategy: AIAgentGraphStrategy<String, String> = singleRunStrategy(),
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            id: String? = null,
            systemPrompt: String = "",
            temperature: Double = 1.0,
            numberOfChoices: Int = 1,
            maxIterations: Int = 50,
            installFeatures: FeatureContext.() -> Unit = {}
        ): AIAgent<String, String> = AIAgent(
            id = id,
            promptExecutor = promptExecutor,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    id = "chat",
                    params = LLMParams(
                        temperature = temperature,
                        numberOfChoices = numberOfChoices
                    )
                ) {
                    system(systemPrompt)
                },
                model = llmModel,
                maxAgentIterations = maxIterations,
            ),
            toolRegistry = toolRegistry,
            installFeatures = installFeatures
        )

        /**
         * Creates and configures an AI agent using the provided parameters.
         *
         * @param Input The input type for the AI agent.
         * @param Output The output type for the AI agent.
         * @param promptExecutor An instance of [PromptExecutor] responsible for executing prompts with the language model.
         * @param llmModel The language model [LLModel] to be used by the agent.
         * @param strategy The agent strategy [AIAgentGraphStrategy] defining how the agent processes inputs and outputs.
         * @param toolRegistry An optional [ToolRegistry] specifying the tools available to the agent for execution. Defaults to `[ToolRegistry.EMPTY]`.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @param clock A `Clock` instance used for time-related operations. Defaults to `Clock.System`.
         * @param systemPrompt A string representing the system-level prompt for the agent. Defaults to an empty string.
         * @param temperature A double value controlling the randomness of the model's output. Defaults to `1.0`.
         * @param numberOfChoices The number of choices the model should generate per invocation. Defaults to `1`.
         * @param maxIterations The maximum number of iterations the agent can perform. Defaults to `50`.
         * @param installFeatures An extension function on `FeatureContext` to install custom features for the agent. Defaults to an empty lambda.
         * @return A configured [AIAgent] instance that can process inputs and generate outputs using the specified strategy and model.
         */
        @OptIn(ExperimentalUuidApi::class)
        public inline operator fun <reified Input, reified Output> invoke(
            promptExecutor: PromptExecutor,
            llmModel: LLModel,
            strategy: AIAgentGraphStrategy<Input, Output>,
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            id: String? = null,
            clock: Clock = Clock.System,
            systemPrompt: String = "",
            temperature: Double = 1.0,
            numberOfChoices: Int = 1,
            maxIterations: Int = 50,
            noinline installFeatures: FeatureContext.() -> Unit = {},
        ): AIAgent<Input, Output> {
            return AIAgent(
                id = id,
                promptExecutor = promptExecutor,
                strategy = strategy,
                agentConfig = AIAgentConfig(
                    prompt = prompt(
                        id = "chat",
                        params = LLMParams(
                            temperature = temperature,
                            numberOfChoices = numberOfChoices
                        )
                    ) {
                        system(systemPrompt)
                    },
                    model = llmModel,
                    maxAgentIterations = maxIterations,
                ),
                toolRegistry = toolRegistry,
                installFeatures = installFeatures
            )
        }

        /**
         * Creates an [FunctionalAIAgent] with the specified parameters to execute a strategy with the assistance of a tool registry,
         * configured language model, and associated features.
         *
         * @param promptExecutor The executor used to process prompts for the language model.
         * @param llmModel The language model configuration defining the underlying LLM instance and its behavior.
         * @param func The operational strategy for the AI agent, which determines how to handle the provided input.
         * @param toolRegistry Registry containing tools available to the agent for use during execution. Default is an empty registry.
         * @param id Unique identifier for the agent. Random UUID will be generated if set to null.
         * @param systemPrompt The system prompt that sets the initial context or instructions for the AI agent.
         * @param temperature The temperature setting for the language model, which adjusts the diversity of output. Default is 1.0.
         * @param numberOfChoices The number of response choices to generate when querying the language model. Default is 1.
         * @param maxIterations The maximum number of iterations the agent is allowed to perform during execution. Default is 50.
         * @param installFeatures A lambda to configure and install features in the agent's context.
         * @return An AI agent instance configured with the provided parameters and ready to execute the specified strategy.
         */
        public operator fun <Input, Output> invoke(
            promptExecutor: PromptExecutor,
            llmModel: LLModel,
            toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
            strategy: AIAgentFunctionalStrategy<Input, Output>,
            id: String? = null,
            systemPrompt: String = "",
            temperature: Double = 1.0,
            numberOfChoices: Int = 1,
            maxIterations: Int = 50,
            installFeatures: FunctionalAIAgent.FeatureContext.() -> Unit = {},
        ): AIAgent<Input, Output> = FunctionalAIAgent(
            promptExecutor = promptExecutor,
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    id = "chat",
                    params = LLMParams(
                        temperature = temperature,
                        numberOfChoices = numberOfChoices
                    )
                ) {
                    system(systemPrompt)
                },
                model = llmModel,
                maxAgentIterations = maxIterations,
            ),
            installFeatures = installFeatures,
            toolRegistry = toolRegistry,
            strategy = strategy
        )
    }

    /**
     * Represents a configurational builder for setting up and customizing the execution parameters and
     * components of an AI agent. This builder enables fine-grained control over tools, strategies,
     * and prompts utilized by an AI agent during its execution.
     */
    public class Builder internal constructor() {
        /**
         * Represents the `PromptExecutor` instance to be utilized within the builder.
         *
         * This variable optionally holds a reference to the configured `PromptExecutor`, which is responsible for:
         * - Executing prompts against language models.
         * - Managing tool interactions for enriched processing.
         * - Handling and processing responses from the language model.
         *
         * If not explicitly set, the builder may rely on a default or alternative mechanism for prompt execution.
         * Primarily used internally to facilitate the construction of instances within the `Builder`.
         */
        @property:PublishedApi
        internal var promptExecutor: PromptExecutor? = null

        /**
         * Holds the `ToolRegistry` instance used in the builder for managing and organizing tool integrations.
         *
         * This variable defines the tool set available for an AI agent or workflow, enabling its interactions and
         * operations with the registered tools. It is initialized with an empty `ToolRegistry` by default and can
         * be updated during the builder's configuration process.
         */
        @property:PublishedApi
        internal var toolRegistry: ToolRegistry = ToolRegistry.EMPTY

        /**
         * Internal identifier for the builder instance.
         *
         * This nullable property holds an optional string value that serves as a unique identifier
         * for the builder. It can be set and modified via the corresponding `id` method in the
         * `Builder` class.
         *
         * The identifier is primarily used to distinguish between different instances of the builder
         * configuration, aiding in tracking or debugging processes.
         *
         * Marked as `internal` and annotated with `@PublishedApi` to allow safe internal access for
         * inline functions or other components within the same module.
         */
        @property:PublishedApi
        internal var id: String? = null

        /**
         * Holds the current `Prompt` instance to be configured within the builder.
         *
         * This variable represents the prompt data utilized by the builder during its setup.
         * It is initialized with an empty prompt by default and can be updated using
         * the `prompt(prompt: Prompt)` method.
         *
         * This property is internal and is intended for use within the builder class.
         */
        @property:PublishedApi
        internal var prompt: Prompt = Prompt.Empty

        /**
         * The `llmModel` variable represents an instance of a Large Language Model (LLM) associated with the builder.
         *
         * This variable holds the configuration or setup of the language model to be*/
        @property:PublishedApi
        internal var llmModel: LLModel? = null

        /**
         * Represents the temperature setting used to control the randomness of outputs generated by language models.
         *
         * A higher temperature value results in more random and creative outputs, whereas a lower value makes the outputs
         * more focused and deterministic. Common values range between 0.0 and 1.0, but specific use cases might require
         * different ranges. The default value is set to 1.0.
         *
         * This property is intended for internal use by the builder to configure the behavior of the language model
         * during execution.
         */
        @property:PublishedApi
        internal var temperature: Double = 1.0

        /**
         * Specifies the number of distinct choices or outputs the agent should generate during execution.
         * This value determines the breadth of the agent's response generation process.
         *
         * The default value*/
        @property:PublishedApi
        internal var numberOfChoices: Int = 1

        /**
         * Specifies the strategy to be used for handling missing tool definitions when converting prompts
         * or messages during the building process. This variable determines how tool calls present in the
         * prompt history, but lacking definitions in the current context, are transformed.
         *
         * The default*/
        @property:PublishedApi
        internal var missingToolsConversionStrategy: MissingToolsConversionStrategy =
            MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON)

        /**
         * Defines the maximum number of iterations allowed during the execution of a process within the Builder.
         *
         * This property is used to limit the number of execution cycles a certain process can perform, preventing
         * infinite*/
        @property:PublishedApi
        internal var maxIterations: Int = 50

        /**
         * Represents the clock used to determine the current time in the builder.
         * By default, it is set to the system clock, but can be customized for testing or specific time-related behaviors*/
        @property:PublishedApi
        internal var clock: Clock = Clock.System

        /**
         * Sets the `PromptExecutor` to be used by the builder instance.
         *
         * This method configures the builder with the provided `PromptExecutor`, which is responsible
         * for executing prompts against language models, managing tool interactions, and handling output.
         *
         * @param promptExecutor An instance of `PromptExecutor` that will be utilized for processing prompts
         * and interacting with language models.
         * @return The current instance of the `Builder` for chaining additional configurations.
         */
        public fun promptExecutor(promptExecutor: PromptExecutor): Builder = apply {
            AIAgentConfig
            this.promptExecutor = promptExecutor
        }

        /**
         * Sets the specified Large Language Model (LLM) to be used by the builder.
         *
         * @param model The instance of [LLModel] representing the Large Language Model to be set for the builder.
         * @return The current instance of*/
        public fun llmModel(model: LLModel): Builder = apply {
            this.llmModel = model
        }

        /**
         * Sets the given `ToolRegistry` instance to the builder configuration.
         *
         * @param toolRegistry The instance of `ToolRegistry` to be used in the builder.
         * @return The current instance of the `Builder` for chaining further configurations.
         */
        public fun toolRegistry(toolRegistry: ToolRegistry): Builder = apply {
            this.toolRegistry = toolRegistry
        }

        /**
         * Configures and returns a `GraphAgentBuilder` instance using the specified `AIAgentGraphStrategy`.
         *
         * The method allows associating an AI agent with a specific graph-based strategy for managing
         * and executing workflows. It provides flexibility to define input and output types
         * specific to the desired strategy.
         *
         * @param Input The type of input data that the strategy will process.
         * @param Output The type of output data that the strategy will produce.
         * @param strategy The `AIAgentGraphStrategy` instance defining the workflow, including
         * the start and finish nodes as well as the tool selection strategy.
         * @return An instance of `GraphAgentBuilder` configured with the specified input type,
         * output type, and strategy.
         */
        public fun <Input, Output> graphStrategy(
            strategy: AIAgentGraphStrategy<Input, Output>
        ): GraphAgentBuilder<Input, Output> = GraphAgentBuilder(
            strategy = strategy,
            inputType = strategy.inputType,
            outputType = strategy.outputType,
            id = this.id,
            prompt = this.prompt,
            llmModel = this.llmModel,
            temperature = this.temperature,
            numberOfChoices = this.numberOfChoices,
            maxIterations = this.maxIterations,
            missingToolsConversionStrategy = this.missingToolsConversionStrategy,
            clock = this.clock
        )

        /**
         * Sets the functional strategy to be used by the agent builder.
         *
         * The provided [strategy] defines the behavior and processing logic for the AI agent in a
         * loop-based execution model. This method configures the builder to utilize the specified
         * strategy and returns an instance of [FunctionalAgentBuilder] for further configuration.
         *
         * @param Input The type of the input data to be processed by the strategy.
         * @param Output The type of the output data to be produced by the strategy.
         * @param strategy An instance of [AIAgentFunctionalStrategy] that contains the custom logic
         * used by the AI agent for decision-making or execution processes.
         * @return An instance of [FunctionalAgentBuilder] configured with the provided functional strategy.
         */
        public fun <Input, Output> functionalStrategy(
            strategy: AIAgentFunctionalStrategy<Input, Output>
        ): FunctionalAgentBuilder<Input, Output> = FunctionalAgentBuilder(
            strategy = strategy,
            id = this.id,
            prompt = this.prompt,
            llmModel = this.llmModel,
            temperature = this.temperature,
            numberOfChoices = this.numberOfChoices,
            maxIterations = this.maxIterations,
            missingToolsConversionStrategy = this.missingToolsConversionStrategy,
            clock = this.clock
        )

        /**
         * Sets the identifier for the builder configuration.
         *
         * @param id The identifier string to be set. Can be null.
         * @return The current instance of the builder for chaining method calls.
         */
        public fun id(id: String?): Builder = apply {
            this.id = id
        }

        /**
         * Sets the system prompt to be used by the builder.
         *
         * This method configures the prompt with a system-level message that provides
         * instructions or context for a language model.
         *
         * @param systemPrompt The content of the system message to set as the prompt.
         * @return The current instance of the builder with the updated system prompt.
         */
        public fun systemPrompt(systemPrompt: String): Builder = apply {
            this.prompt = prompt(id = "agent") { system(systemPrompt) }
        }

        /**
         * Sets the prompt to be used by the builder.
         *
         * @param prompt The [Prompt] instance to set.
         * @return The current instance of the builder.
         */
        public fun prompt(prompt: Prompt): Builder = apply {
            this.prompt = prompt
        }

        /**
         * Sets the temperature value for the builder.
         *
         * Temperature is typically used to control the randomness of outputs in language models. Higher values result in more
         * random outputs, while lower values make outputs more deterministic.
         *
         * @param temperature The temperature value to set. It should be a non-negative double, where common values are within
         *                     the range [0.0, 1.0].
         * @return The current instance of the Builder for method chaining.
         */
        public fun temperature(temperature: Double): Builder = apply {
            this.temperature = temperature
        }

        /**
         * Sets the number of choices for the builder configuration.
         *
         * @param numberOfChoices The desired number of choices to be set.
         * @return The builder instance with*/
        public fun numberOfChoices(numberOfChoices: Int): Builder = apply {
            this.numberOfChoices = numberOfChoices
        }

        /**
         * Sets the maximum number of iterations for the builder.
         *
         * @param maxIterations The maximum number of iterations to be used. Must be a positive integer.
         * @return The current instance of the*/
        public fun maxIterations(maxIterations: Int): Builder = apply {
            this.maxIterations = maxIterations
        }

        /**
         * Installs a graph-specific AI agent feature into the builder with its provided configuration.
         *
         * This method allows the integration of an [AIAgentGraphFeature] into the builder and its
         * configuration using a lambda function. The feature is then added to the list of feature
         * installers, enabling its functionality within the AI agent being constructed.
         *
         * @param TConfig The type of the configuration for the feature, extending [FeatureConfig].
         * @param feature The [AIAgentGraphFeature] to be installed into the builder.
         * @param configure A lambda function to configure the feature's properties and behavior.
         * @return An instance of [GraphAgentBuilder] configured with the installed feature.
         */
        public fun <TConfig : FeatureConfig> install(
            feature: AIAgentGraphFeature<TConfig, *>,
            configure: ConfigureAction<TConfig>
        ): GraphAgentBuilder<String, String> = GraphAgentBuilder(
            strategy = singleRunStrategy(),
            inputType = typeOf<String>(),
            outputType = typeOf<String>(),
            id = this.id,
            prompt = this.prompt,
            llmModel = this.llmModel,
            temperature = this.temperature,
            numberOfChoices = this.numberOfChoices,
            maxIterations = this.maxIterations,
            missingToolsConversionStrategy = this.missingToolsConversionStrategy,
            clock = this.clock,
            featureInstallers = mutableListOf({
                install(feature) {
                    configure.configure(this)
                }
            })
        )

        /**
         * Builds and returns an instance of [AIAgent] configured according to the builder's settings.
         *
         * This method finalizes the current configuration and constructs an AI agent. The agent is
         * equipped with the specified execution strategy, tool registry, identifier, prompt, language
         * model, and other optional configurations. If required fields, such as `promptExecutor` or
         * `llmModel`, are not set, this method throws an exception.
         *
         * @return An instance of [AIAgent] with the configured input and output types as `String`.
         */
        public fun build(): AIAgent<String, String> {
            return AIAgent(
                promptExecutor = requireNotNull(promptExecutor) { "promptExecutor must be set" },
                strategy = singleRunStrategy(),
                toolRegistry = toolRegistry,
                id = id,
                agentConfig = AIAgentConfig(
                    prompt = prompt ?: Prompt.Empty,
                    model = requireNotNull(llmModel) { "llmModel must be set" },
                    maxAgentIterations = maxIterations,
                ),
                clock = clock
            )
        }
    }

    /**
     * A builder class for creating instances of [AIAgent]. This builder provides a fluent interface
     * to configure various parameters and components required to construct an AI agent with a
     * specific set of features, tools, and execution strategies.
     *
     * @param Input The input type that the agent processes.
     * @param Output The output type that the agent produces.
     * @param strategy The execution strategy used by the agent for processing input and generating results.
     * @param inputType The [KType] representation of the input parameter type.
     * @param outputType The [KType] representation of the output parameter type.
     */
    public class GraphAgentBuilder<Input, Output>(
        private val strategy: AIAgentGraphStrategy<Input, Output>,
        private val inputType: KType,
        private val outputType: KType,
        private var promptExecutor: PromptExecutor? = null,
        private var toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        private var id: String? = null,
        private var prompt: Prompt? = Prompt.Empty,
        private var llmModel: LLModel? = null,
        private var temperature: Double = 1.0,
        private var numberOfChoices: Int = 1,
        private var missingToolsConversionStrategy: MissingToolsConversionStrategy =
            MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
        private var maxIterations: Int = 50,
        private var clock: Clock = Clock.System,
        private var featureInstallers: MutableList<FeatureContext.() -> Unit> = mutableListOf(),
    ) {

        /**
         * Sets the `PromptExecutor` instance to be used by this `GraphAgentBuilder`.
         *
         * @param promptExecutor The `PromptExecutor` instance responsible for executing prompts
         *                        and interacting with the language model.
         * @return The current instance of `GraphAgentBuilder` for method chaining.
         */
        public fun promptExecutor(promptExecutor: PromptExecutor): GraphAgentBuilder<Input, Output> = apply {
            this.promptExecutor = promptExecutor
        }

        /**
         * Sets the large language model (LLM) for the agent builder.
         *
         * @param model The `LLModel` instance representing the large language model to be used by the agent.
         * @return The current instance of `GraphAgentBuilder<Input, Output>` for method chaining.
         */
        public fun llmModel(model: LLModel): GraphAgentBuilder<Input, Output> = apply {
            this.llmModel = model
        }

        /**
         * Sets the `toolRegistry` for the `GraphAgentBuilder` and returns the updated builder instance.
         *
         * @param toolRegistry The `ToolRegistry` instance to be associated with the `GraphAgentBuilder`.
         * @return The current instance of `GraphAgentBuilder` with the updated `toolRegistry`.
         */
        public fun toolRegistry(toolRegistry: ToolRegistry): GraphAgentBuilder<Input, Output> = apply {
            this.toolRegistry = toolRegistry
        }

        /**
         * Sets the unique identifier for the `GraphAgentBuilder` instance.
         *
         * @param id The unique identifier to associate with the agent. Can be null if no identifier is required.
         * @return The current instance of `GraphAgentBuilder` with the updated identifier.
         */
        public fun id(id: String?): GraphAgentBuilder<Input, Output> = apply {
            this.id = id
        }

        /**
         * Sets the system-level prompt for the agent.
         *
         * The system prompt provides predefined instructions or context
         * to guide the behavior of the agent.
         *
         * @param systemPrompt The system-level instructions or context for the agent.
         * @return The updated instance of GraphAgentBuilder with the system prompt applied.
         */
        public fun systemPrompt(systemPrompt: String): GraphAgentBuilder<Input, Output> = apply {
            this.prompt = prompt(id = "agent") { system(systemPrompt) }
        }

        /**
         * Sets the prompt for the GraphAgentBuilder and returns the builder instance for further configuration.
         *
         * @param prompt The prompt configuration to be set.
         * @return The updated instance of GraphAgentBuilder.
         */
        public fun prompt(prompt: Prompt): GraphAgentBuilder<Input, Output> = apply {
            this.prompt = prompt
        }

        /**
         * Sets the temperature parameter for the AI agent configuration.
         *
         * Temperature controls the randomness of the AI model's output.
         * A higher temperature results in more randomness, while a lower temperature makes the output more deterministic.
         *
         * @param temperature The temperature value to be set, typically ranging between 0.0 and 1.0.
         * @return The current instance of [GraphAgentBuilder], allowing method chaining.
         */
        public fun temperature(temperature: Double): GraphAgentBuilder<Input, Output> = apply {
            this.temperature = temperature
        }

        /**
         * Sets the number of choices the agent can generate and returns the updated builder instance.
         *
         * @param numberOfChoices The number of choices to configure for the agent.
         * @return The updated instance of the GraphAgentBuilder with the specified number of choices.
         */
        public fun numberOfChoices(numberOfChoices: Int): GraphAgentBuilder<Input, Output> = apply {
            this.numberOfChoices = numberOfChoices
        }

        /**
         * Sets the maximum number of iterations allowed for the agent's execution.
         *
         * @param maxIterations The maximum number of iterations to configure.
         * @return The current instance of [GraphAgentBuilder] for method chaining.
         */
        public fun maxIterations(maxIterations: Int): GraphAgentBuilder<Input, Output> = apply {
            this.maxIterations = maxIterations
        }

        /**
         * Installs a specified feature into the current context and applies its configuration.
         *
         * @param TConfig The type of configuration required by the feature, extending [FeatureConfig].
         * @param feature The feature to install, represented by an implementation of [AIAgentGraphFeature].
         * @param configure A lambda used to customize the configuration of the feature.
         * @return The current [GraphAgentBuilder] instance, enabling further configurations.
         */
        public fun <TConfig : FeatureConfig> install(
            feature: AIAgentGraphFeature<TConfig, *>,
            configure: ConfigureAction<TConfig>
        ): GraphAgentBuilder<Input, Output> = apply {
            this.featureInstallers += {
                install(feature) {
                    configure.configure(this)
                }
            }
        }

        /**
         * Builds and returns an instance of `AIAgent` configured using the parameters
         * provided to the `GraphAgentBuilder`.
         *
         * @return an instance of `AIAgent` initialized with the specified input and output types,
         *         strategy, tool registry, prompt executor, model configuration, and other optional settings.
         */
        public fun build(): AIAgent<Input, Output> {
            return GraphAIAgent(
                inputType = inputType,
                outputType = outputType,
                strategy = strategy,
                promptExecutor = requireNotNull(promptExecutor) { "promptExecutor must be set" },
                toolRegistry = toolRegistry,
                id = id,
                agentConfig = AIAgentConfig(
                    prompt = prompt ?: Prompt.Empty,
                    model = requireNotNull(llmModel) { "llmModel must be set" },
                    maxAgentIterations = maxIterations,
                ),
                clock = clock
            ) {
                featureInstallers.forEach { install ->
                    install()
                }
            }
        }
    }

    /**
     * A builder class for constructing instances of `FunctionalAIAgent` with customizable configuration.
     *
     * This builder simplifies the configuration process by providing a fluent API to set various
     * parameters for the `FunctionalAIAgent`, including its behavior strategy, prompt details, model settings,
     * tool registry, and additional features. The builder enforces the presence of required configurations
     * and allows the addition of optional parameters to tailor the agent's functionality.
     *
     * @param Input The type of input that the resulting AI agent will process.
     * @param Output The type of output that the resulting AI agent will produce.
     * @property strategy The strategy defining the behavior of the AI agent, responsible for the core iterative logic.
     * @property promptExecutor The initial executor responsible for executing prompts, defaults to `null` if not set.
     * @property toolRegistry A registry of tools available to the agent, by default set to `ToolRegistry.EMPTY`.
     * @property id An optional unique identifier for the agent.
     * @property prompt The system prompt or series of prompts defining the agent's conversational context, initialized to `Prompt.Empty`.
     * @property llmModel The model used by the agent for language generation, defaults to `null` if not set.
     * @property temperature The sampling temperature affecting the randomness of outputs, default is `1.0`.
     * @property numberOfChoices The number of choices generated by the model per output, default is `1`.
     * @property missingToolsConversionStrategy A strategy for handling missing tools during execution, default is `MissingToolsConversionStrategy.Missing`.
     * @property maxIterations The maximum number of iterations the agent can perform during execution, default is `50`.
     * @property clock The clock instance used for time-related functionality, default is `Clock.System`.
     * @property featureInstallers A list of feature installation lambdas defining additional functionalities the agent should have.
     */
    public class FunctionalAgentBuilder<Input, Output>(
        private val strategy: AIAgentFunctionalStrategy<Input, Output>,
        private var promptExecutor: PromptExecutor? = null,
        private var toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        private var id: String? = null,
        private var prompt: Prompt? = Prompt.Empty,
        private var llmModel: LLModel? = null,
        private var temperature: Double = 1.0,
        private var numberOfChoices: Int = 1,
        private var missingToolsConversionStrategy: MissingToolsConversionStrategy =
            MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
        private var maxIterations: Int = 50,
        private var clock: Clock = Clock.System,
        private var featureInstallers: MutableList<FunctionalAIAgent.FeatureContext.() -> Unit> = mutableListOf(),
    ) {

        /**
         * Sets the provided `PromptExecutor` instance for the current `FunctionalAgentBuilder`.
         *
         * @param promptExecutor The `PromptExecutor` instance to be used for executing prompts.
         * @return The updated `FunctionalAgentBuilder` instance with the specified prompt executor configuration.
         */
        public fun promptExecutor(promptExecutor: PromptExecutor): FunctionalAgentBuilder<Input, Output> = apply {
            this.promptExecutor = promptExecutor
        }

        /**
         * Configures the functional agent builder to use a specific Large Language Model (LLM) for processing.
         *
         * @param model The Large Language Model (LLM) instance to be used, which defines the provider, identifier, and capabilities.
         * @return The updated instance of [FunctionalAgentBuilder] for further configuration chaining.
         */
        public fun llmModel(model: LLModel): FunctionalAgentBuilder<Input, Output> = apply {
            this.llmModel = model
        }

        /**
         * Sets the tool registry to be used by the FunctionalAgentBuilder.
         *
         * @param toolRegistry The ToolRegistry instance to be associated with this builder.
         * @return The current FunctionalAgentBuilder instance to allow method chaining.
         */
        public fun toolRegistry(toolRegistry: ToolRegistry): FunctionalAgentBuilder<Input, Output> = apply {
            this.toolRegistry = toolRegistry
        }

        /**
         * Sets the unique identifier for the FunctionalAgent.
         *
         * @param id The unique identifier as a nullable string. It can be used to reference the agent in various contexts or hierarchies.
         * @return The builder instance, allowing for method chaining during the configuration of the FunctionalAgent.
         */
        public fun id(id: String?): FunctionalAgentBuilder<Input, Output> = apply {
            this.id = id
        }

        /**
         * Sets the system-level prompt for the agent being built.
         * The system-level prompt provides contextual instructions to the agent.
         *
         * @param systemPrompt The message that defines the behavior or context for the agent.
         * @return The instance of FunctionalAgentBuilder with the updated system-level prompt.
         */
        public fun systemPrompt(systemPrompt: String): FunctionalAgentBuilder<Input, Output> = apply {
            this.prompt = prompt(id = "agent") { system(systemPrompt) }
        }

        /**
         * Sets the specified prompt for the functional agent being built.
         *
         * @param prompt The prompt to be used by the agent.
         * @return The current instance of FunctionalAgentBuilder with the updated prompt.
         */
        public fun prompt(prompt: Prompt): FunctionalAgentBuilder<Input, Output> = apply {
            this.prompt = prompt
        }

        /**
         * Sets the temperature parameter for the functional agent. Temperature controls the randomness of the
         * agent's outputs, where higher values result in more diverse outputs and lower values result in more focused outputs.
         *
         * @param temperature The temperature value to be used. It should typically range between 0.0 and 1.0.
         * @return The updated instance of the FunctionalAgentBuilder with the temperature parameter set.
         */
        public fun temperature(temperature: Double): FunctionalAgentBuilder<Input, Output> = apply {
            this.temperature = temperature
        }

        /**
         * Configures the number of choices to be considered during the functional agent's operations.
         *
         * @param numberOfChoices The desired number of choices to configure for the functional agent.
         * @return The updated instance of [FunctionalAgentBuilder] configured with the specified number of choices.
         */
        public fun numberOfChoices(numberOfChoices: Int): FunctionalAgentBuilder<Input, Output> = apply {
            this.numberOfChoices = numberOfChoices
        }

        /**
         * Sets the maximum number of iterations for the functional agent builder.
         *
         * @param maxIterations The maximum number of iterations to be set.
         * @return The current instance of FunctionalAgentBuilder with the updated maximum iterations.
         */
        public fun maxIterations(maxIterations: Int): FunctionalAgentBuilder<Input, Output> = apply {
            this.maxIterations = maxIterations
        }

        /**
         * Installs and configures a given feature into the functional agent builder.
         *
         * @param TConfig the type of the feature configuration, which extends [FeatureConfig].
         * @param feature the feature to be installed, represented by an implementation of [AIAgentFunctionalFeature].
         * @param configure a lambda function to customize the configuration of the feature, where the provided [TConfig] can be modified.
         * @return the current [FunctionalAgentBuilder] instance for chaining further configurations.
         */
        public fun <TConfig : FeatureConfig> install(
            feature: AIAgentFunctionalFeature<TConfig, *>,
            configure: ConfigureAction<TConfig>
        ): FunctionalAgentBuilder<Input, Output> = apply {
            this.featureInstallers += {
                install(feature) {
                    configure.configure(this)
                }
            }
        }

        /**
         * Builds and returns an instance of `AIAgent<Input, Output>` based on the current configuration
         * of the `FunctionalAgentBuilder`. This method ensures that all required fields are set,
         * and applies any configured feature installers to the agent.
         *
         * @return an instance of `AIAgent<Input, Output>` created using the provided configuration.
         * @throws IllegalArgumentException if required fields, such as `promptExecutor` or `llmModel`, are not set.
         */
        public fun build(): AIAgent<Input, Output> {
            return FunctionalAIAgent(
                strategy = strategy,
                promptExecutor = requireNotNull(promptExecutor) { "promptExecutor must be set" },
                toolRegistry = toolRegistry,
                id = id,
                agentConfig = AIAgentConfig(
                    prompt = prompt ?: Prompt.Empty,
                    model = requireNotNull(llmModel) { "llmModel must be set" },
                    maxAgentIterations = maxIterations,
                ),
                clock = clock
            ) {
                featureInstallers.forEach { install ->
                    install()
                }
            }
        }
    }
}

/**
 * Checks whether the AI agent is currently in a running state.
 *
 * @return `true` if the AI agent's state is `Running`, otherwise `false`.
 */
public suspend fun AIAgent<*, *>.isRunning(): Boolean = this.getState() is Running

/**
 * Checks whether the AI agent has reached a finished state.
 *
 * @return true if the current state of the AI agent is of type `Finished`, false otherwise.
 */
public suspend fun AIAgent<*, *>.isFinished(): Boolean = this.getState() is Finished
