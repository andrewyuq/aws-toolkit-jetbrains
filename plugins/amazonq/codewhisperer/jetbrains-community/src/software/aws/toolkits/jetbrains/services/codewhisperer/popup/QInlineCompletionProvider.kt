// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.popup

//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isCodeWhispererEnabled
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isQExpired
//import software.aws.toolkits.jetbrains.services.codewhisperer.util.isValidCodeWhispererFile
import androidx.collection.mutableIntIntMapOf
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.logs.InlineCompletionUsageTracker
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.progress.sleepCancellable
import com.intellij.util.ui.JBUI
import icons.AwsIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import migration.software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import org.eclipse.lsp4j.jsonrpc.messages.Either
import software.aws.toolkits.jetbrains.core.coroutines.getCoroutineBgContext
import software.aws.toolkits.jetbrains.services.amazonq.lsp.AmazonQLspService
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionItem
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionReference
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionReferencePosition
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfileManager
import software.aws.toolkits.jetbrains.services.codewhisperer.layout.CodeWhispererLayoutConfig.addHorizontalGlue
import software.aws.toolkits.jetbrains.services.codewhisperer.layout.CodeWhispererLayoutConfig.inlineLabelConstraints
import software.aws.toolkits.jetbrains.services.codewhisperer.model.TriggerTypeInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererInvocationStatus
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.services.codewhisperer.toolwindow.CodeWhispererCodeReferenceManager
import software.aws.toolkits.jetbrains.utils.isQConnected
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodewhispererTriggerType
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel

class QInlineCompletionProvider(private val cs: CoroutineScope) : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = Q_INLINE_PROVIDER_ID
    override val providerPresentation: InlineCompletionProviderPresentation
        get() = object : InlineCompletionProviderPresentation {
            override fun getTooltip(project: Project?): JComponent {
                project ?: return JLabel()
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return JLabel()
                val session = InlineCompletionSession.getOrNull(editor) ?: return JLabel()
                return qToolTip(
                    "Amazon Q",
                    AwsIcons.Logos.AWS_Q_GRADIENT_SMALL,
                    arrayOf(
                        object : AnAction("←", null, AllIcons.Chooser.Left), DumbAware {
                            override fun actionPerformed(e: AnActionEvent) {
                                session.usePrevVariant()
                            }
                        },
                        object : AnAction("→", null, AllIcons.Chooser.Right), DumbAware {
                            override fun actionPerformed(e: AnActionEvent) {
                                session.useNextVariant()
                            }
                        }
                    ),
                    session,
                    project
                )
            }
        }
    private var cell: Cell<JEditorPane>? = null

    fun qToolTip(
        title: String,
        icon: Icon?,
        actions: Array<AnAction>,
        session: InlineCompletionSession,
        project: Project
    ): JComponent {
        return panel {
            row {
                if (icon != null) {
                    icon(icon).gap(RightGap.SMALL)
                }
                comment(title).gap(RightGap.SMALL)

                cell(object : ActionButton(
                    object : AnAction("←", null, AllIcons.Chooser.Left), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            session.usePrevVariant()
                        }
                    },
                    Presentation().apply {
                        this.icon = AllIcons.Chooser.Left
                        putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
                    },
                    ActionPlaces.EDITOR_POPUP,
                    JBUI.emptySize()
                ) {
                    override fun isFocusable() = false
                }).gap(RightGap.SMALL)

                cell = text("${getCurrentValidVariantIndex(session)}/${getAllValidVariantsCount(session)}").gap(RightGap.SMALL)
                cell

                cell(object : ActionButton(
                    object : AnAction("→", null, AllIcons.Chooser.Right), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            session.usePrevVariant()
                        }
                    },
                    Presentation().apply {
                        this.icon = AllIcons.Chooser.Right
                        putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
                    },
                    ActionPlaces.EDITOR_POPUP,
                    JBUI.emptySize()
                ) {
                    override fun isFocusable() = false
                })

                // if there are imports and references, add them here
                val currentVariant = session.capture()?.activeVariant ?: return@row
                val item = currentVariant.data.getUserData(KEY_Q_INLINE_ITEM) ?: return@row
                val imports = item.mostRelevantMissingImports
//                val references = item.references
                //data class InlineCompletionReference(
                //    var referenceName: String,
                //    var referenceUrl: String,
                //    var licenseName: String,
                //    var position: InlineCompletionReferencePosition,
                //)
                val references = listOf(
                    InlineCompletionReference(
                        referenceName = "myRepo",
                        referenceUrl = "https://opensource.org/license/mit",
                        licenseName = "MIT",
                        position = InlineCompletionReferencePosition(
                            startCharacter = 0,
                            endCharacter = 10,
                        )
                    ),
                    InlineCompletionReference(
                        referenceName = "myRepo2",
                        referenceUrl = "https://opensource.org/license/bsd-3-clause",
                        licenseName = "BSD",
                        position = InlineCompletionReferencePosition(
                            startCharacter = 11,
                            endCharacter = 20,
                        )
                    ),

                )
                println("imports: $imports, references: $references")
                cell(JLabel("2 imports").apply { toolTipText = "<html>import panda as tf<br>import tensorflow as pd</html>" })
                if (!imports.isNullOrEmpty()) {
                    cell(JLabel("${imports.size} imports").apply { toolTipText = "import a;\nimport b" })
                }
//                val licenseCodePanel = JPanel(GridBagLayout()).apply {
//                    border = BorderFactory.createEmptyBorder(0, 0, 3, 0)
//                    add(licenseCodeLabelPrefixText, inlineLabelConstraints)
//                    add(ActionLink(), inlineLabelConstraints)
//                    add(codeReferencePanelLink, inlineLabelConstraints)
//                    addHorizontalGlue()
//                }
//                popupComponents.licenseCodePanel.apply {
//                    removeAll()
//                    add(popupComponents.licenseCodeLabelPrefixText, inlineLabelConstraints)
//                    licenses.forEachIndexed { i, license ->
//                        add(popupComponents.licenseLink(license), inlineLabelConstraints)
//                        if (i == licenses.size - 1) return@forEachIndexed
//                        add(JLabel(", "), inlineLabelConstraints)
//                    }
//
//                    add(JLabel(".  "), inlineLabelConstraints)
//                    add(popupComponents.codeReferencePanelLink, inlineLabelConstraints)
//                    addHorizontalGlue()
//                }
//                cell(JLabel("3 references"))
                val components = CodeWhispererPopupComponents()
                if (!references.isNullOrEmpty()) {
                    cell(JLabel("Reference code under ")).customize(UnscaledGaps.EMPTY)
                    references.forEachIndexed { i, reference ->
                        cell(components.licenseLink(reference.licenseName)).customize(UnscaledGaps.EMPTY)
                        if (i == references.size - 1) return@forEachIndexed
                        cell(JLabel(", ")).customize(UnscaledGaps.EMPTY)
                    }
                    cell(JLabel(". ")).customize(UnscaledGaps.EMPTY)
                    cell(ActionLink("View Log") {
                        CodeWhispererCodeReferenceManager.getInstance(project).showCodeReferencePanel()

                    }).gap(RightGap.SMALL)
                }
            }
        }
    }

    fun getCurrentVariantIndex(session: InlineCompletionSession) = session.capture()?.activeVariant?.index ?: -1

    fun getCurrentValidVariantIndex(session: InlineCompletionSession): Int {
        var start = 1
        val variants = session.capture()?.variants ?: return -1
        variants.forEach {
            if (it.isActive) return start
            if (!it.isEmpty() && it.elements.any { element -> element.text.isNotEmpty() }) start++
//            start++
        }
        return -1
    }

    fun getAllValidVariantsCount(session: InlineCompletionSession) = session.capture()?.variants?.filter {
        !it.isEmpty() && it.elements.any { element -> element.text.isNotEmpty() }
    }?.size ?: 0

    companion object {
        private const val MAX_CHANNELS = 5
        val Q_INLINE_PROVIDER_ID = InlineCompletionProviderID("Amazon Q")
        val KEY_Q_INLINE_ITEM = Key<InlineCompletionItem>("amazon.q.inline.completion.item")
    }
    private val logger = thisLogger()

    // Store active channels for pagination - using channel index as key
    private val activeChannels = mutableMapOf<String, Channel<InlineCompletionElement>>()

    // Track the next available channel index for each session during pagination
    private val sessionChannelCounters = mutableMapOf<String, Int>()

    private fun addQInlineCompletionListener(session: InlineCompletionSession, handler: InlineCompletionHandler) {
        handler.addEventListener(object : InlineCompletionEventAdapter {
            // when all computations are done
            override fun onCompletion(event: InlineCompletionEventType.Completion) {
                // TODO: all channels are closing meaning all suggestions are coming back
                // 1. change invocation status
                // 2. refer to CodeWhispererPopupManager to see what we do after session end
                CodeWhispererInvocationStatus.getInstance().setIsInvokingQInline(session, false)
                println("---------------------------------------onCompletion: all computations are done-------------------------------------------")
                super.onCompletion(event)
            }

            override fun onComputed(event: InlineCompletionEventType.Computed) {
                cell?.applyToComponent { text = "${getCurrentValidVariantIndex(session)}/${getAllValidVariantsCount(session)}" }
                super.onComputed(event)
            }

            override fun onShow(event: InlineCompletionEventType.Show) {
                println("onShow: all elements are ready")
//                cell?.applyToComponent { text = "${getCurrentValidVariantIndex(session)}/${getAllValidVariantsCount(session)}" }
                super.onShow(event)
            }

            override fun onInvalidated(event: InlineCompletionEventType.Invalidated) {
                println("onInvalidated: an variant is invalidated, index: ${event.variantIndex}")
                super.onInvalidated(event)
            }

            override fun onEmpty(event: InlineCompletionEventType.Empty) {
                // set current index to be empty (might not needed)
                // event.variantIndex
                println("onEmpty: this variant is empty: ${event.variantIndex}")
                super.onEmpty(event)
            }

            override fun onVariantSwitched(event: InlineCompletionEventType.VariantSwitched) {
                // set current index to be seen (might not needed)
                // event.toVariantIndex
                println("onVariantSwitched: an variant is switched to show, from: ${event.fromVariantIndex}, to: ${event.toVariantIndex}")
                if (event.fromVariantIndex > event.toVariantIndex) {
                    cell?.applyToComponent { text = "${getCurrentValidVariantIndex(session) - 1}/${getAllValidVariantsCount(session)}" }
                } else {
                    cell?.applyToComponent { text = "${getCurrentValidVariantIndex(session) + 1}/${getAllValidVariantsCount(session)}" }
                }
                super.onVariantSwitched(event)
            }

//            override fun onInsert(event: InlineCompletionEventType.Insert) {
//                println("onInsert: before an variant is inserted")
//                super.onInsert(event)
//            }

            override fun onAfterInsert(event: InlineCompletionEventType.AfterInsert) {
                println("onAfterInsert: after an variant is inserted, ")
                // TODO: handle imports and references
                super.onAfterInsert(event)
            }

            override fun onHide(event: InlineCompletionEventType.Hide) {
                println("onHide: all, hide reason: ${event.finishType}")
                super.onHide(event)
                when (event.finishType) {
                    InlineCompletionUsageTracker.ShownEvents.FinishType.ESCAPE_PRESSED,
                    InlineCompletionUsageTracker.ShownEvents.FinishType.BACKSPACE_PRESSED -> {
                        // TODO: send reject
                        println("onHide: cancel")
                        session.dispose()
                    }
                    InlineCompletionUsageTracker.ShownEvents.FinishType.TYPED -> {
                        // TODO: send accept
                        //session.capture()?.activeVariant?.index
                        println("onHide: finish")
                    }
                    InlineCompletionUsageTracker.ShownEvents.FinishType.EMPTY -> {
                        if (session.request.event is InlineCompletionEvent.ManualCall) {
                            runInEdt {
                                HintManager.getInstance().showInformationHint(
                                    session.editor,
                                    message("codewhisperer.popup.no_recommendations"),
                                    HintManager.UNDER
                                )
                            }
                        }
                        // TODO: send empty
                    }
                    InlineCompletionUsageTracker.ShownEvents.FinishType.INVALIDATED -> {
                        // TODO: send reject
                        println("onHide: cancel")
                    }
                    InlineCompletionUsageTracker.ShownEvents.FinishType.SELECTED -> {
                        // TODO: send accept
                    }
//                    InlineCompletionEventType.Hide.FinishType.FINISH -> {
//                        println("onHide: finish")
//                        session.dispose()
//                    }
//                    InlineCompletionEventType.Hide.FinishType.CANCEL_AND_RESTART -> {
//                        println("onHide: cancel and restart")
//                        session.dispose()
//                    }
//                    InlineCompletionEventType.Hide.FinishType.FINISH_AND_RESTART -> {
//                        println("onHide: finish and restart")
//                        session.dispose()
//                    }
                    else -> {
                        println("onHide: unknown")
                    }
                }
            }

            override fun onNoVariants(event: InlineCompletionEventType.NoVariants) {
                println("onNoVariants: no variants")
                super.onNoVariants(event)
            }

        }, session)

    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val project = editor.project ?: return InlineCompletionSuggestion.Empty
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return InlineCompletionSuggestion.Empty
        val session = InlineCompletionSession.getOrNull(editor) ?: return InlineCompletionSuggestion.Empty
        val qInlineItemsMap = mutableMapOf<Int, InlineCompletionItem>()
        CodeWhispererInvocationStatus.getInstance().setIsInvokingQInline(session, true)
        Disposer.register(session) {
            CodeWhispererInvocationStatus.getInstance().setIsInvokingQInline(session, false)
            // potentially send UTDE here?
        }
        addQInlineCompletionListener(session, handler)

        logger.debug("Getting inline completion suggestion for offset: ${request.endOffset}")
        runReadAction {
            println("Getting inline completion suggestion for offset: ${request.endOffset}, left text of current line: ${request.document.text.subSequence(request.document.getLineStartOffset(request.document.getLineNumber(request.editor.caretModel.offset)), request.editor.caretModel.offset)}")
        }
        val triggerType = if (request.event is InlineCompletionEvent.ManualCall) CodewhispererTriggerType.OnDemand else CodewhispererTriggerType.AutoTrigger
        if (triggerType == CodewhispererTriggerType.AutoTrigger) {
            val a = 1
        }

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
                logger.debug("No completions received from LSP server, nextToken: ${completion.partialResultToken}")
                return InlineCompletionSuggestion.Empty
            }

            logger.debug("Received ${completion.items.size} completions, nextToken: ${completion.partialResultToken}")

            // Store completion state for telemetry and import handling
            completion.items.forEachIndexed { i, item ->
                qInlineItemsMap[i] = item
            }
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
                        nextToken,
                        session,
                    )
                }
            }

            return object : InlineCompletionSuggestion {
                override suspend fun getVariants(): List<InlineCompletionVariant> {
                    // Pagination workaround: Always return exactly 5 variants (5 channels)
                    // Initialize channel counter for this session
                    sessionChannelCounters[completion.sessionId] = completion.items.size
                    logger.debug("Initialized session ${completion.sessionId} with ${completion.items.size} initial items, creating $MAX_CHANNELS channels")

                    return (0 until MAX_CHANNELS).map { channelIndex ->
                        val channel = Channel<InlineCompletionElement>(Channel.UNLIMITED)
                        val flow = channel.receiveAsFlow()
                        val data = UserDataHolderBase()

                        // Store the channel with unique key for each channel index
                        val channelKey = "${completion.sessionId}_$channelIndex"
                        activeChannels[channelKey] = channel

                        // Emit initial element if we have an item for this channel
                        if (channelIndex < completion.items.size) {
                            val item = completion.items[channelIndex]
                            data.putUserData(KEY_Q_INLINE_ITEM, item)
                            channel.trySend(InlineCompletionGrayTextElement(item.insertText))
                            logger.debug("Channel $channelIndex: Initial item '${item.itemId}' with text: ${item.insertText.take(50)}...")
                        } else {
                            // Create placeholder for empty channels - they will be updated via pagination
                            logger.debug("Channel $channelIndex: Created as placeholder, waiting for pagination")
                        }

                        InlineCompletionVariant.build(data = data, elements = flow)
                    }
                }
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                println("request at offset ${request.endOffset} cancelled, trigger type: ${if (request.event is InlineCompletionEvent.ManualCall) CodewhispererTriggerType.OnDemand else CodewhispererTriggerType.AutoTrigger}")
                // TODO YUX: send discard events

                throw e
            }
            println("request at offset ${request.endOffset} returning empty, trigger type: ${if (request.event is InlineCompletionEvent.ManualCall) CodewhispererTriggerType.OnDemand else CodewhispererTriggerType.AutoTrigger}")
            logger.warn("Error getting inline completion suggestion", e)
            return InlineCompletionSuggestion.Empty
        }
    }

    private suspend fun startPaginationInBackground(
        project: Project,
        editor: Editor,
        triggerTypeInfo: TriggerTypeInfo,
        sessionId: String,
        initialNextToken: Either<String, Int>,
        session: InlineCompletionSession,
    ) {
        // Launch coroutine for background pagination
//        sleepCancellable(3000)
        withContext(getCoroutineBgContext()) {
//        GlobalScope.launch(getCoroutineBgContext()) {
            var nextToken: Either<String, Int>? = initialNextToken

//            val session = InlineCompletionSession.getOrNull(editor) ?: return@withContext
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

                    if (!session.isActive()) {
                    }
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
            println("all elements")
            val session = InlineCompletionSession.getOrNull(editor) ?: return@withContext
//            println(session.context.state.elements)
            for (element in session.context.state.elements) {
                println(element.element.text)
            }
//            runInEdt {
//                println("capture")
//                println(session.capture())
//                println("list")
//                println(session.context.state.elements)
//            }

            // Close all 5 channels when pagination is complete
            (0 until MAX_CHANNELS).forEach { channelIndex ->
                val channelKey = "${sessionId}_$channelIndex"
                println("closing channel $channelKey")
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
