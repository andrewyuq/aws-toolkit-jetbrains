// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.popup

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderPresentation
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.runInEdtAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import migration.software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import org.eclipse.lsp4j.jsonrpc.messages.Either
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.jetbrains.core.coroutines.getCoroutineBgContext
import software.aws.toolkits.jetbrains.services.amazonq.lsp.AmazonQLspService
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionItem
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionListWithReferences
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfileManager
import software.aws.toolkits.jetbrains.services.codewhisperer.model.TriggerTypeInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.utils.isQConnected
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isCodeWhispererEnabled
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isQExpired
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isValidCodeWhispererFile
import software.aws.toolkits.telemetry.CodewhispererTriggerType
import javax.swing.JComponent
import javax.swing.JLabel

class QInlineCompletionProvider(private val cs: CoroutineScope) : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = Q_INLINE_PROVIDER_ID
    override val providerPresentation: InlineCompletionProviderPresentation
        get() = object : InlineCompletionProviderPresentation {
            override fun getTooltip(project: Project?): JComponent {
                return JLabel("Amazon Q internal testing")
//                TODO("Not yet implemented")
            }

        }
    companion object {
        private const val MAX_CHANNELS = 5
        val Q_INLINE_PROVIDER_ID = InlineCompletionProviderID("Amazon Q")
//        fun getInstance(): QInlineCompletionProvider {
//            return QInlineCompletionProvider()
//        }
    }
    private val logger = thisLogger()

    // Store active channels for pagination - using channel index as key
    private val activeChannels = mutableMapOf<String, Channel<InlineCompletionElement>>()

    // Track the next available channel index for each session during pagination
    private val sessionChannelCounters = mutableMapOf<String, Int>()

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
            val triggerTypeInfo = TriggerTypeInfo(
                triggerType = if (request.event is InlineCompletionEvent.ManualCall) CodewhispererTriggerType.OnDemand else CodewhispererTriggerType.AutoTrigger,
                automatedTriggerType = software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererAutomatedTriggerType.Classifier()
            )
            println("trigger type: ${triggerTypeInfo.triggerType}")

            // Get first page of completions
            val completionResult = withContext(getCoroutineBgContext()) {
                AmazonQLspService.executeIfRunning(project) { server ->
                    val params = createInlineCompletionParams(editor, triggerTypeInfo, null)
                    server.inlineCompletionWithReferences(params)
                }?.await()
            }

            val completion = completionResult ?: return InlineCompletionSuggestion.Empty
//            val completion = InlineCompletionListWithReferences(
//                items = listOf(
//                    InlineCompletionItem("item1", "(x, y) {\n        return x + y\n    }"),
//                ),
//                sessionId = "sessionIdhha",
//                partialResultToken = Either.forLeft("nextTokenhaha")
//            )

            if (completion.items.isEmpty()) {
                logger.debug("No completions received from LSP server")
                return InlineCompletionSuggestion.Empty
            }

            logger.debug("Received ${completion.items.size} completions, nextToken: ${completion.partialResultToken}")

            // Store completion state for telemetry and import handling
            QInlineCompletionStateManager.getInstance(project).storeCompletionState(
                completion.sessionId,
                completion,
                request.endOffset
            )

            val nextToken = completion.partialResultToken

            // Start pagination in background if there's a nextToken
            if (nextToken != null && !nextToken.left.isNullOrEmpty()) {
                cs.launch {
                    startPaginationInBackground(
                        project,
                        editor,
                        triggerTypeInfo,
                        completion.sessionId,
                        nextToken
                    )
                }
            }

            return object : InlineCompletionSuggestion {
                override suspend fun getVariants(): List<InlineCompletionVariant> {
                    // HACK: Always return exactly 5 variants (5 channels)
                    // Initialize channel counter for this session
                    sessionChannelCounters[completion.sessionId] = completion.items.size
                    logger.debug("Initialized session ${completion.sessionId} with ${completion.items.size} initial items, creating $MAX_CHANNELS channels")

                    return (0 until MAX_CHANNELS).map { channelIndex ->
                        val channel = Channel<InlineCompletionElement>(Channel.UNLIMITED)
                        val flow = channel.receiveAsFlow()

                        // Store the channel with unique key for each channel index
                        val channelKey = "${completion.sessionId}_$channelIndex"
                        activeChannels[channelKey] = channel

                        // Emit initial element if we have an item for this channel
                        if (channelIndex < completion.items.size) {
                            val item = completion.items[channelIndex]
                            channel.trySend(InlineCompletionGrayTextElement(item.insertText))
                            logger.debug("Channel $channelIndex: Initial item '${item.itemId}' with text: ${item.insertText.take(50)}...")
                        } else {
                            // Create placeholder for empty channels - they will be updated via pagination
                            logger.debug("Channel $channelIndex: Created as placeholder, waiting for pagination")
                        }

                        InlineCompletionVariant.build(elements = flow)
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("Error getting inline completion suggestion", e)
            return InlineCompletionSuggestion.Empty
        }
    }

    private suspend fun startPaginationInBackground(
        project: Project,
        editor: Editor,
        triggerTypeInfo: TriggerTypeInfo,
        sessionId: String,
        initialNextToken: Either<String, Int>
    ) {
        // Launch coroutine for background pagination
        withContext(getCoroutineBgContext()) {
//        GlobalScope.launch(getCoroutineBgContext()) {
            var nextToken: Either<String, Int>? = initialNextToken

            while (nextToken != null && !nextToken.left.isNullOrEmpty()) {
                try {
                    logger.debug("Fetching next page with token: $nextToken")

                    var nextPageResult = AmazonQLspService.executeIfRunning(project) { server ->
                        val params = createInlineCompletionParams(editor, triggerTypeInfo, nextToken)
                        server.inlineCompletionWithReferences(params)
                    }?.await() ?: return@withContext

//                    nextPageResult = InlineCompletionListWithReferences(
//                        items = listOf(
//                            InlineCompletionItem("item2", "哈哈"),
//                        ),
//                        sessionId = "sessionIdhha",
//                        partialResultToken = Either.forLeft("")
//                    )

                    logger.debug("Received ${nextPageResult.items.size} items from pagination")

                    // Update channels in order with new items from pagination
                    val currentChannelCounter = sessionChannelCounters[sessionId] ?: 0

                    nextPageResult.items.forEachIndexed { itemIndex, newItem ->
                        // Calculate which channel this item should go to
                        // Continue from where we left off, cycling through channels 0-4
                        val globalItemIndex = currentChannelCounter + itemIndex
                        val channelIndex = globalItemIndex % MAX_CHANNELS
                        val channelKey = "${sessionId}_$channelIndex"
                        val existingChannel = activeChannels[channelKey]

                        if (existingChannel != null) {
                            // Add to the corresponding channel
                            val success = existingChannel.trySend(InlineCompletionGrayTextElement(newItem.insertText))
                            if (success.isSuccess) {
                                logger.debug("Added paginated item '${newItem.itemId}' to channel $channelIndex (global index: $globalItemIndex): ${newItem.insertText.take(50)}...")
                            } else {
                                logger.warn("Failed to add paginated item '${newItem.itemId}' to channel $channelIndex")
                            }
                        } else {
                            logger.warn("Channel $channelIndex not found for session $sessionId")
                        }
                    }

                    // Update the channel counter
                    sessionChannelCounters[sessionId] = currentChannelCounter + nextPageResult.items.size

                    // Update state manager with new items
                    val stateManager = QInlineCompletionStateManager.getInstance(project)
                    stateManager.appendCompletionItems(sessionId, nextPageResult.items)

                    nextToken = nextPageResult.partialResultToken

                } catch (e: Exception) {
                    logger.warn("Error during pagination", e)
                    break
                }
            }

            logger.debug("Pagination completed for session: $sessionId")
            val session = InlineCompletionSession.getOrNull(editor) ?: return@withContext
            println("list")
            println(session.context.state.elements)
            for (element in session.context.state.elements) {
                println(element.element.text)
            }
            runInEdt {
                println("capture")
                println(session.capture())
                println("list")
                println(session.context.state.elements)
            }

            // Close all 5 channels when pagination is complete
            (0 until MAX_CHANNELS).forEach { channelIndex ->
                val channelKey = "${sessionId}_$channelIndex"
                activeChannels[channelKey]?.close()
                activeChannels.remove(channelKey)
                logger.debug("Closed channel $channelIndex for session: $sessionId")
            }

            // Clean up session counter
            sessionChannelCounters.remove(sessionId)
            logger.debug("Cleaned up session counter for: $sessionId")
        }
    }

    private fun createInlineCompletionParams(
        editor: Editor,
        triggerTypeInfo: TriggerTypeInfo,
        nextToken: Either<String, Int>?
    ) = CodeWhispererService.getInstance().createInlineCompletionParams(
        editor,
        triggerTypeInfo,
        nextToken
    )

    /**
     * Clean up channels for a specific session
     */
    fun cleanupSession(sessionId: String) {
        (0 until MAX_CHANNELS).forEach { channelIndex ->
            val channelKey = "${sessionId}_$channelIndex"
            activeChannels[channelKey]?.close()
            activeChannels.remove(channelKey)
        }
        sessionChannelCounters.remove(sessionId)
        logger.debug("Cleaned up all $MAX_CHANNELS channels and counter for session: $sessionId")
    }

    /**
     * Clean up all active channels
     */
    fun cleanupAllChannels() {
        activeChannels.values.forEach { it.close() }
        activeChannels.clear()
        sessionChannelCounters.clear()
        logger.debug("Cleaned up all active channels and session counters")
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val request = event.toRequest() ?: return false
        val editor = request.editor
        val project = editor.project ?: return false

        if (!isQConnected(project)) return false
        // Temporarily disable backspace
        if (event is InlineCompletionEvent.Backspace) return false
        if (!CodeWhispererExplorerActionManager.getInstance().isAutoEnabled()) return false
        if (QRegionProfileManager.getInstance().hasValidConnectionButNoActiveProfile(project)) return false
//        if (type == CodewhispererTriggerType.AutoTrigger && !CodeWhispererExplorerActionManager.getInstance().isAutoEnabled()) {
//            LOG.debug { "CodeWhisperer auto-trigger is disabled, not invoking service" }
//            return false
//        }
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
