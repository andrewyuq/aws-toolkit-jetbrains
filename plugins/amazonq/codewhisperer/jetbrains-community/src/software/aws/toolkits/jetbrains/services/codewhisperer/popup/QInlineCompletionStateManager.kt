// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.popup

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionListWithReferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Service to manage state for inline completions provided through JetBrains' InlineCompletionProvider API.
 * This handles telemetry, import suggestions, and session tracking.
 */
@Service(Service.Level.PROJECT)
class QInlineCompletionStateManager(private val project: Project) : Disposable {
    private val logger = thisLogger()

    // Map of session ID to completion state
    private val completionStates = ConcurrentHashMap<String, CompletionState>()

    data class CompletionState(
        val sessionId: String,
        val completion: InlineCompletionListWithReferences,
        val offset: Int,
        val timestamp: Long = System.currentTimeMillis(),
        var isAccepted: Boolean = false,
        var isRejected: Boolean = false
    )

    fun storeCompletionState(
        sessionId: String,
        completion: InlineCompletionListWithReferences,
        offset: Int
    ) {
        logger.debug("Storing completion state for session: $sessionId")

        val state = CompletionState(
            sessionId = sessionId,
            completion = completion,
            offset = offset
        )

        completionStates[sessionId] = state

        // Clean up old states (older than 5 minutes)
        cleanupOldStates()
    }

    fun markAccepted(sessionId: String) {
        completionStates[sessionId]?.let { state ->
            state.isAccepted = true
            logger.debug("Marked completion as accepted for session: $sessionId")

            // Handle import suggestions if any
            handleImportSuggestions(state)

            // Send telemetry
            sendAcceptedTelemetry(state)
        }
    }

    fun markRejected(sessionId: String) {
        completionStates[sessionId]?.let { state ->
            state.isRejected = true
            logger.debug("Marked completion as rejected for session: $sessionId")

            // Send telemetry
            sendRejectedTelemetry(state)
        }
    }

    fun getCompletionState(sessionId: String): CompletionState? {
        return completionStates[sessionId]
    }

    /**
     * Append new completion items to an existing session
     */
    fun appendCompletionItems(sessionId: String, newItems: List<software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionItem>) {
        completionStates[sessionId]?.let { state ->
            val updatedCompletion = state.completion.copy(
                items = state.completion.items + newItems
            )
            val updatedState = state.copy(completion = updatedCompletion)
            completionStates[sessionId] = updatedState
            logger.debug("Appended ${newItems.size} items to session: $sessionId")
        }
    }

    private fun handleImportSuggestions(state: CompletionState) {
        // Find the accepted completion item
        val acceptedItem = state.completion.items.firstOrNull { item ->
            item.insertText.isNotEmpty()
        }

        acceptedItem?.mostRelevantMissingImports?.let { imports ->
            if (imports.isNotEmpty()) {
                logger.debug("Handling ${imports.size} import suggestions for session: ${state.sessionId}")
                // TODO: Implement import insertion logic
                // This would need to integrate with the existing CodeWhispererImportAdder
            }
        }
    }

    private fun sendAcceptedTelemetry(state: CompletionState) {
        // TODO: Implement telemetry sending
        // This should integrate with the existing telemetry system
        logger.debug("Sending accepted telemetry for session: ${state.sessionId}")
    }

    private fun sendRejectedTelemetry(state: CompletionState) {
        // TODO: Implement telemetry sending
        logger.debug("Sending rejected telemetry for session: ${state.sessionId}")
    }

    private fun cleanupOldStates() {
        val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes
        val toRemove = completionStates.entries.filter { (_, state) ->
            state.timestamp < cutoffTime
        }.map { it.key }

        toRemove.forEach { sessionId ->
            completionStates.remove(sessionId)
            logger.debug("Cleaned up old completion state for session: $sessionId")
        }
    }

    override fun dispose() {
        completionStates.clear()
    }

    companion object {
        fun getInstance(project: Project): QInlineCompletionStateManager = project.service()
    }
}
