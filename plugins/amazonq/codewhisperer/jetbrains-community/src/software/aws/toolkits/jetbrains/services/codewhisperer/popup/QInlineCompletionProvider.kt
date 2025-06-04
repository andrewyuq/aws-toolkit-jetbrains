// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.popup

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderPresentation
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import software.aws.toolkits.jetbrains.core.coroutines.getCoroutineBgContext
import software.aws.toolkits.jetbrains.services.amazonq.lsp.AmazonQLspService
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionItem
import software.aws.toolkits.jetbrains.services.codewhisperer.model.TriggerTypeInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isCodeWhispererEnabled
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isQExpired
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isValidCodeWhispererFile
import software.aws.toolkits.telemetry.CodewhispererTriggerType
import javax.swing.JComponent
import javax.swing.JLabel

class QInlineCompletionProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("Amazon Q")
    override val providerPresentation: InlineCompletionProviderPresentation
        get() = object : InlineCompletionProviderPresentation {
            override fun getTooltip(project: Project?): JComponent {
                return JLabel("hahahah")
//                TODO("Not yet implemented")
            }

        }
    companion object {
        fun getInstance(): QInlineCompletionProvider {
            return QInlineCompletionProvider()
        }
    }
    private val logger = thisLogger()

//    fun addPaginatedResults(editor: Editor, event: InlineCompletionEvent, newItems: List<InlineCompletionItem>) {
//        // Get access to the variants provider (this would need to be exposed)
//        InlineCompletionSession.getOrNull(editor)?.provider?.suggestionUpdateManager?.update(event) { snapshot ->
//            // Create new elements from your paginated results
//            val newElements = newItems.map { item ->
//                InlineCompletionGrayTextElement(item.insertText)
//            }
//
//            // Return updated snapshot with additional elements
//            UpdateResult.Changed(
//                snapshot.copy(elements = snapshot.elements + newElements)
//            )
//        }
//    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val project = editor.project ?: return InlineCompletionSuggestion.Empty

        logger.debug("Getting inline completion suggestion for offset: ${request.endOffset}")

        try {
            // Get completion from LSP server
            val triggerTypeInfo = TriggerTypeInfo(
                triggerType = CodewhispererTriggerType.AutoTrigger,
                automatedTriggerType = software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererAutomatedTriggerType.Classifier()
            )

            val completionResult = withContext(getCoroutineBgContext()) {
                AmazonQLspService.executeIfRunning(project) { server ->
                    val params = CodeWhispererService.getInstance().createInlineCompletionParams(
                        editor,
                        triggerTypeInfo,
                        null
                    )
                    server.inlineCompletionWithReferences(params)
                }?.await()
            }
//            completionResult?.await()


            val completion = completionResult
            println("completions:")
            completion?.items?.forEach {
                println(it.insertText)
            }
            if (completion == null || completion.items.isEmpty()) {
                logger.debug("No completions received from LSP server")
                return InlineCompletionSuggestion.Empty
            }

            // Get the first valid completion
            val firstCompletion = completion.items.firstOrNull { item ->
                item.insertText.isNotEmpty()
            } ?: return InlineCompletionSuggestion.Empty

            logger.debug("Returning completion: ${firstCompletion.insertText.take(50)}...")

            // Store completion state for telemetry and import handling
            QInlineCompletionStateManager.getInstance(project).storeCompletionState(
                completion.sessionId,
                completion,
                request.endOffset
            )

            // Return only one suggestion at a time
            return object : InlineCompletionSuggestion {
                override suspend fun getVariants(): List<InlineCompletionVariant> {
                    return completion.items.map { item ->
                        InlineCompletionVariant.build(elements = flow {
                            emit(InlineCompletionGrayTextElement(item.insertText))
                        })
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("Error getting inline completion suggestion", e)
            return InlineCompletionSuggestion.Empty

        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return true
    }

//    override fun isEnabled(event: InlineCompletionEvent): Boolean {
//        val editor = event.editor
//        val project = editor.project ?: return false
//
//         Check if CodeWhisperer is enabled for this project
//        if (!isCodeWhispererEnabled(project)) {
//            return false
//        }
//
//         Check authentication state
//        if (isQExpired(project)) {
//            return false
//        }
//
//         Check if this is a valid file type for CodeWhisperer
//        val file = ReadAction.compute<Boolean, RuntimeException> {
//            val psiFile = event.file
//            psiFile?.let { isValidCodeWhispererFile(it) } ?: false
//        }
//
//        if (!file) {
//            return false
//        }
//
//         Check if we can do invocation (not already in progress, etc.)
//        val canInvoke = CodeWhispererService.getInstance().canDoInvocation(
//            editor,
//            CodewhispererTriggerType.AutoTrigger
//        )
//
//        return canInvoke
//    }
}
