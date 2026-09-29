package com.example.agent

import android.content.Context
import com.example.brain.DvexIntent
import com.example.brain.DvexToolResult
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus

/**
 * Everything an action handler is allowed to touch. Handlers delegate to the
 * EXISTING repositories/services — the agent layer never reimplements device work.
 */
class DvexAgentEnvironment(
  val context: Context,
  val appLauncher: AppLauncherRepository,
  val deviceControl: DeviceControlRepository,
  val toolRouter: DvexToolRouter
)

/** One action type → one handler. Adding a capability means adding a handler here. */
interface AgentActionHandler {
  val actionType: AgentActionType
  suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult
}

/**
 * Extensible action registry. The engine looks actions up here instead of branching,
 * so future commands plug in without rewriting the brain.
 */
class DvexActionRegistry {

  private val handlers = LinkedHashMap<AgentActionType, AgentActionHandler>()

  fun registerAction(handler: AgentActionHandler) {
    handlers[handler.actionType] = handler
  }

  fun handlerFor(type: AgentActionType): AgentActionHandler? = handlers[type]

  fun registeredTypes(): Set<AgentActionType> = handlers.keys.toSet()

  val size: Int get() = handlers.size

  companion object {
    fun withDefaults(): DvexActionRegistry = DvexActionRegistry().apply {
      DvexDefaultActions.all().forEach { registerAction(it) }
    }
  }
}

/**
 * Maps an existing tool result onto the agent's honest result type.
 *
 * A SUCCESS from the tool/repository layer means the platform API reported the
 * effect (volume changed, global action returned true, intent accepted) — there is
 * no further signal to read back on Android, so it is recorded as
 * [VerificationOutcome.DISPATCH_CONFIRMED]. It is NOT recorded as VERIFIED, and an
 * effect a handler cannot confirm must say so itself.
 */
fun mapToolResult(result: ToolExecutionResult, successMessage: String): AgentActionResult =
  when (result.status) {
    ToolResultStatus.SUCCESS -> AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = successMessage,
      verification = VerificationOutcome.DISPATCH_CONFIRMED
    )
    ToolResultStatus.NEEDS_PERMISSION -> AgentActionResult(
      status = AgentActionStatus.REQUIRES_PERMISSION,
      message = result.message
    )
    else -> AgentActionResult(
      status = AgentActionStatus.FAILURE,
      message = result.message
    )
  }

/**
 * Maps an existing router result onto the agent's honest result type. A router
 * SUCCESS is the deterministic tool layer reporting the change (see [mapToolResult]).
 */
fun mapRouterResult(result: DvexToolResult): AgentActionResult =
  when (result.status) {
    DvexToolStatus.SUCCESS -> AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = result.message,
      verification = VerificationOutcome.DISPATCH_CONFIRMED
    )

    DvexToolStatus.UNVERIFIED -> AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = result.message,
      verification = VerificationOutcome.UNVERIFIED
    )
    DvexToolStatus.PERMISSION_REQUIRED -> AgentActionResult(
      status = AgentActionStatus.REQUIRES_PERMISSION,
      message = result.message
    )
    DvexToolStatus.CONFIRMATION_REQUIRED -> AgentActionResult(
      status = AgentActionStatus.REQUIRES_CONFIRMATION,
      message = result.confirmationPrompt ?: result.message
    )
    DvexToolStatus.NOT_FOUND -> AgentActionResult(
      status = AgentActionStatus.FAILURE,
      message = result.message
    )
    DvexToolStatus.UNSUPPORTED -> AgentActionResult(
      status = AgentActionStatus.UNSUPPORTED,
      message = result.message
    )
    else -> AgentActionResult(
      status = AgentActionStatus.FAILURE,
      message = result.message
    )
  }

/**
 * Base for actions whose real implementation already lives in the tool router
 * (weather, messaging). The agent reuses it instead of duplicating the logic.
 */
abstract class RouterBackedHandler : AgentActionHandler {
  protected abstract fun intentFor(action: AgentAction): DvexIntent

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapRouterResult(env.toolRouter.execute(intentFor(action)))
}
