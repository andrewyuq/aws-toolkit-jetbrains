// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cwc.controller

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import migration.software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererModelConfigurator
import software.amazon.awssdk.services.codewhispererstreaming.model.UserIntent
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.core.coroutines.EDT
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQAppInitContext
import software.aws.toolkits.jetbrains.services.amazonq.auth.AuthController
import software.aws.toolkits.jetbrains.services.amazonq.auth.AuthNeededState
import software.aws.toolkits.jetbrains.services.amazonq.messages.MessagePublisher
import software.aws.toolkits.jetbrains.services.amazonq.onboarding.OnboardingPageInteraction
import software.aws.toolkits.jetbrains.services.amazonq.onboarding.OnboardingPageInteractionType
import software.aws.toolkits.jetbrains.services.amazonq.project.RelevantDocument
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererConfigurable
import software.aws.toolkits.jetbrains.services.cwc.InboundAppMessagesHandler
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.exceptions.ChatApiException
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.model.ChatRequestData
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.model.FollowUpType
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.model.TriggerType
import software.aws.toolkits.jetbrains.services.cwc.clients.chat.v1.ChatSessionFactoryV1
import software.aws.toolkits.jetbrains.services.cwc.commands.CodeScanIssueActionMessage
import software.aws.toolkits.jetbrains.services.cwc.commands.ContextMenuActionMessage
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.StaticPrompt
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.StaticTextResponse
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.messenger.ChatPromptHandler
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.telemetry.TelemetryHelper
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.telemetry.getStartUrl
import software.aws.toolkits.jetbrains.services.cwc.controller.chat.userIntent.UserIntentRecognizer
import software.aws.toolkits.jetbrains.services.cwc.editor.context.ActiveFileContext
import software.aws.toolkits.jetbrains.services.cwc.editor.context.ActiveFileContextExtractor
import software.aws.toolkits.jetbrains.services.cwc.editor.context.ExtractionTriggerType
import software.aws.toolkits.jetbrains.services.cwc.messages.AuthNeededException
import software.aws.toolkits.jetbrains.services.cwc.messages.ChatMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.ChatMessageType
import software.aws.toolkits.jetbrains.services.cwc.messages.EditorContextCommandMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.ErrorMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.FocusType
import software.aws.toolkits.jetbrains.services.cwc.messages.FollowUp
import software.aws.toolkits.jetbrains.services.cwc.messages.IncomingCwcMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.OnboardingPageInteractionMessage
import software.aws.toolkits.jetbrains.services.cwc.messages.QuickActionMessage
import software.aws.toolkits.jetbrains.services.cwc.storage.ChatSessionStorage
import software.aws.toolkits.telemetry.CwsprChatCommandType
import java.util.UUID

class ChatController private constructor(
    private val context: AmazonQAppInitContext,
    private val chatSessionStorage: ChatSessionStorage,
    private val contextExtractor: ActiveFileContextExtractor,
    private val intentRecognizer: UserIntentRecognizer,
    private val authController: AuthController,
) : InboundAppMessagesHandler {

    private val messagePublisher: MessagePublisher = context.messagesFromAppToUi
    private val telemetryHelper = TelemetryHelper(context.project, chatSessionStorage)

    constructor(
        context: AmazonQAppInitContext,
    ) : this(
        context = context,
        chatSessionStorage = ChatSessionStorage(ChatSessionFactoryV1()),
        contextExtractor = ActiveFileContextExtractor.create(fqnWebviewAdapter = context.fqnWebviewAdapter, project = context.project),
        intentRecognizer = UserIntentRecognizer(),
        authController = AuthController(),
    )

    override suspend fun processClearQuickAction(message: IncomingCwcMessage.ClearChat) {
        chatSessionStorage.deleteSession(message.tabId)
        TelemetryHelper.recordTelemetryChatRunCommand(CwsprChatCommandType.Clear, startUrl = getStartUrl(context.project))
    }

    override suspend fun processHelpQuickAction(message: IncomingCwcMessage.Help) {
        val triggerId = UUID.randomUUID().toString()

        sendQuickActionMessage(triggerId, StaticPrompt.Help)

        val tabId = waitForTabId(triggerId)

        sendStaticTextResponse(
            tabId = tabId,
            triggerId = triggerId,
            response = StaticTextResponse.Help,
        )
        TelemetryHelper.recordTelemetryChatRunCommand(CwsprChatCommandType.Help, startUrl = getStartUrl(context.project))
    }

    override suspend fun processPromptChatMessage(message: IncomingCwcMessage.ChatPrompt) {
        val prompt = message.chatMessage
        val queryResult: List<RelevantDocument> = emptyList()
        val triggerId = UUID.randomUUID().toString()
        val shouldAddIndexInProgressMessage = false
        val shouldUseWorkspaceContext = false

        handleChat(
            tabId = message.tabId,
            triggerId = triggerId,
            message = prompt,
            activeFileContext = contextExtractor.extractContextForTrigger(ExtractionTriggerType.ChatMessage),
            userIntent = intentRecognizer.getUserIntentFromPromptChatMessage(message.chatMessage),
            TriggerType.Click,
            projectContextQueryResult = queryResult,
            shouldAddIndexInProgressMessage = shouldAddIndexInProgressMessage,
            shouldUseWorkspaceContext = shouldUseWorkspaceContext
        )
    }

    override suspend fun processTabWasRemoved(message: IncomingCwcMessage.TabRemoved) {
        chatSessionStorage.deleteSession(message.tabId)
    }

    override suspend fun processTabChanged(message: IncomingCwcMessage.TabChanged) {
        if (message.prevTabId != null) {
            telemetryHelper.recordExitFocusConversation(message.prevTabId)
        }
        telemetryHelper.recordEnterFocusConversation(message.tabId)
    }

    override suspend fun processFollowUpClick(message: IncomingCwcMessage.FollowupClicked) {
        val sessionInfo = getSessionInfo(message.tabId)
        val lastRequest = sessionInfo.history.lastOrNull()

        val fileContext = lastRequest?.activeFileContext ?: ActiveFileContext(null, null)

        // Re-use the editor context when making the follow-up request
        handleChat(
            tabId = message.tabId,
            triggerId = UUID.randomUUID().toString(),
            message = message.followUp.prompt,
            activeFileContext = fileContext,
            userIntent = intentRecognizer.getUserIntentFromFollowupType(message.followUp.type),
            TriggerType.Click,
            projectContextQueryResult = emptyList()
        )

        telemetryHelper.recordInteractWithMessage(message)
    }

    override suspend fun processCodeWasCopiedToClipboard(message: IncomingCwcMessage.CopyCodeToClipboard) {
        telemetryHelper.recordInteractWithMessage(message)
    }

    override suspend fun processOpenSettings(message: IncomingCwcMessage.OpenSettings) {
        runInEdt {
            ShowSettingsUtil.getInstance().showSettingsDialog(context.project, CodeWhispererConfigurable::class.java)
        }
    }

    override suspend fun processInsertCodeAtCursorPosition(message: IncomingCwcMessage.InsertCodeAtCursorPosition) {
        withContext(EDT) {
            val editor: Editor = FileEditorManager.getInstance(context.project).selectedTextEditor ?: return@withContext

            val caret: Caret = editor.caretModel.primaryCaret
            val offset: Int = caret.offset

            ApplicationManager.getApplication().runWriteAction {
                WriteCommandAction.runWriteCommandAction(context.project) {
                    if (caret.hasSelection()) {
                        editor.document.deleteString(caret.selectionStart, caret.selectionEnd)
                    }

                    editor.document.insertString(offset, message.code)

                    ReferenceLogController.addReferenceLog(message.code, message.codeReference, editor, context.project, null)
                }
            }
        }
        telemetryHelper.recordInteractWithMessage(message)
    }

    override suspend fun processStopResponseMessage(message: IncomingCwcMessage.StopResponse) {
        val sessionInfo = getSessionInfo(message.tabId)
        // Cancel any child jobs running in the tab's scope, without cancelling the scope itself
        sessionInfo.scope.coroutineContext.job.cancelChildren()
    }

    override suspend fun processChatItemVoted(message: IncomingCwcMessage.ChatItemVoted) {
        telemetryHelper.recordInteractWithMessage(message)
    }

    override suspend fun processChatItemFeedback(message: IncomingCwcMessage.ChatItemFeedback) {
        telemetryHelper.recordInteractWithMessage(message)
    }

    override suspend fun processUIFocus(message: IncomingCwcMessage.UIFocus) {
        if (message.type == FocusType.FOCUS) {
            telemetryHelper.recordEnterFocusChat()
        } else if (message.type == FocusType.BLUR) {
            telemetryHelper.recordExitFocusChat()
        }
    }

    override suspend fun processAuthFollowUpClick(message: IncomingCwcMessage.AuthFollowUpWasClicked) {
        authController.handleAuth(context.project, message.authType)
        TelemetryHelper.recordTelemetryChatRunCommand(CwsprChatCommandType.Auth, message.authType.name)
    }

    override suspend fun processOnboardingPageInteraction(message: OnboardingPageInteraction) {
        val context = contextExtractor.extractContextForTrigger(ExtractionTriggerType.OnboardingPageInteraction)
        val triggerId = UUID.randomUUID().toString()

        val prompt = when (message.type) {
            OnboardingPageInteractionType.CwcButtonClick -> StaticPrompt.OnboardingHelp.message
        }
        sendOnboardingPageInteractionMessage(prompt, message.type, triggerId)

        val tabId = waitForTabId(triggerId)

        if (message.type == OnboardingPageInteractionType.CwcButtonClick) {
            sendStaticTextResponse(
                tabId = tabId,
                triggerId = triggerId,
                response = StaticTextResponse.OnboardingHelp,
            )
            return
        }

        handleChat(
            tabId = tabId,
            triggerId = triggerId,
            message = prompt,
            activeFileContext = context,
            userIntent = intentRecognizer.getUserIntentFromOnboardingPageInteraction(message),
            triggerType = TriggerType.Click, // todo trigger type,
            projectContextQueryResult = emptyList()
        )
    }

    override suspend fun processCodeScanIssueAction(message: CodeScanIssueActionMessage) {
        logger.info { "Code Review Explain issue with Q message received for issue: ${message.issue["title"]}" }
        // Extract context
        val fileContext = contextExtractor.extractContextForTrigger(ExtractionTriggerType.CodeScanButton)
        val triggerId = UUID.randomUUID().toString()
        val codeSelection = "\n```\n${message.issue["code"]?.trimIndent()?.trim()}\n```\n"

        val prompt = "Explain the following part of my code \n\n " +
            "Issue:    \"${message.issue["title"]}\" \n" +
            "Code:    $codeSelection"

        val modelPrompt = "Explain the following part of my code \n\n " +
            "Issue:    \"${message.issue["title"]}\" \n" +
            "Description:    ${message.issue["description"]} \n" +
            "Code:    $codeSelection"

        processPromptActions(prompt, ContextMenuActionMessage(message.command, message.project), triggerId, fileContext, modelPrompt)
    }

    // JB specific (not in vscode)
    override suspend fun processContextMenuCommand(message: ContextMenuActionMessage) {
        // No-op since context commands are handled elsewhere. This function will be deprecated once we remove this class
    }

    private suspend fun processPromptActions(
        prompt: String,
        message: ContextMenuActionMessage,
        triggerId: String,
        fileContext: ActiveFileContext,
        modelPrompt: String? = null,
    ) {
        messagePublisher.publish(
            EditorContextCommandMessage(
                message = prompt,
                command = message.command.actionId,
                triggerId = triggerId,
            ),
        )

        // Wait for the tab ID to come back
        val tabId = waitForTabId(triggerId)

        if (tabId == NO_TAB_AVAILABLE) {
            logger.info { "No tab is available to handle action" }
            // exit the function without any further actions
            return
        }

        val inputPrompt = modelPrompt ?: prompt
        handleChat(
            tabId = tabId,
            triggerId = triggerId,
            message = inputPrompt,
            activeFileContext = fileContext,
            userIntent = intentRecognizer.getUserIntentFromContextMenuCommand(message.command),
            triggerType = message.command.triggerType,
            projectContextQueryResult = emptyList()
        )
    }

    override suspend fun processLinkClick(message: IncomingCwcMessage.ClickedLink) {
        processLinkClick(message, message.link)
    }

    private suspend fun processLinkClick(message: IncomingCwcMessage, link: String) {
        BrowserUtil.browse(link)
        telemetryHelper.recordInteractWithMessage(message)
    }

    private suspend fun handleChat(
        tabId: String,
        triggerId: String,
        message: String,
        activeFileContext: ActiveFileContext,
        userIntent: UserIntent?,
        triggerType: TriggerType,
        projectContextQueryResult: List<RelevantDocument>,
        shouldAddIndexInProgressMessage: Boolean = false,
        shouldUseWorkspaceContext: Boolean = false,
    ) {
        val credentialState = authController.getAuthNeededStates(context.project).chat
        if (credentialState != null) {
            sendAuthNeededException(
                tabId = tabId,
                triggerId = triggerId,
                credentialState = credentialState,
            )
            return
        }

        val requestData = ChatRequestData(
            tabId = tabId,
            message = message,
            activeFileContext = activeFileContext,
            userIntent = userIntent,
            triggerType = triggerType,
            customization = CodeWhispererModelConfigurator.getInstance().activeCustomization(context.project),
            relevantTextDocuments = projectContextQueryResult,
            useRelevantDocuments = shouldUseWorkspaceContext,
        )

        val sessionInfo = getSessionInfo(tabId)

        // Save the request in the history
        sessionInfo.history.add(requestData)
        telemetryHelper.recordEnterFocusConversation(tabId)
        telemetryHelper.recordStartConversation(tabId, requestData)
        // Send the request to the API and publish the responses back to the UI.
        // This is launched in a scope attached to the sessionInfo so that the Job can be cancelled on a per-session basis.
        ChatPromptHandler(telemetryHelper).handle(tabId, triggerId, requestData, sessionInfo, shouldAddIndexInProgressMessage)
            .catch { handleError(tabId, it) }
            .onEach { context.messagesFromAppToUi.publish(it) }
            .launchIn(sessionInfo.scope)
    }

    private suspend fun handleError(tabId: String, exception: Throwable) {
        val requestId = (exception as? ChatApiException)?.requestId
        logger.warn(exception) {
            "Exception encountered, tabId: $tabId, requestId: $requestId"
        }

        val messageContent = buildString {
            append("This error is reported to the team automatically. We will attempt to fix it as soon as possible.")
            exception.message?.let {
                append("\n\nDetails: ")
                append(it)
            }
            if (exception is ChatApiException) {
                exception.statusCode?.let {
                    append("\n\nStatus Code: ")
                    append(it)
                }
                append("\n\nSession ID: ")
                append(exception.sessionId)
                requestId?.let {
                    append("\n\nRequest ID: ")
                    append(it)
                }
            }
        }
        sendErrorMessage(tabId, messageContent, requestId)
    }

    private suspend fun sendErrorMessage(tabId: String, message: String, requestId: String?) {
        val errorMessage = ErrorMessage(
            tabId = tabId,
            title = "An error occurred while processing your request.",
            message = message,
            messageId = requestId,
        )
        messagePublisher.publish(errorMessage)
    }

    private suspend fun sendQuickActionMessage(triggerId: String, prompt: StaticPrompt) {
        val message = QuickActionMessage(
            triggerId = triggerId,
            message = prompt.message,
        )
        messagePublisher.publish(message)
    }

    private suspend fun sendStaticTextResponse(tabId: String, triggerId: String, response: StaticTextResponse) {
        val chatMessage = ChatMessage(
            tabId = tabId,
            triggerId = triggerId,
            messageType = ChatMessageType.Answer,
            messageId = "static_message_$triggerId",
            message = response.message,
            followUps = response.followUps.map {
                FollowUp(
                    type = FollowUpType.Generated,
                    pillText = it,
                    prompt = it,
                )
            },
            followUpsHeader = response.followUpsHeader,
        )
        messagePublisher.publish(chatMessage)
    }

    private suspend fun sendAuthNeededException(tabId: String, triggerId: String, credentialState: AuthNeededState) {
        val message = AuthNeededException(
            tabId = tabId,
            triggerId = triggerId,
            authType = credentialState.authType,
            message = credentialState.message,
        )
        messagePublisher.publish(message)
    }

    private suspend fun sendOnboardingPageInteractionMessage(prompt: String, type: OnboardingPageInteractionType, triggerId: String) {
        val message = OnboardingPageInteractionMessage(
            message = prompt,
            interactionType = type,
            triggerId = triggerId,
        )
        messagePublisher.publish(message)
    }

    private fun getSessionInfo(tabId: String) = chatSessionStorage.getSession(tabId, context.project)

    private suspend fun waitForTabId(triggerId: String) = context.messagesFromUiToApp.flow
        .mapNotNull { it as? IncomingCwcMessage.TriggerTabIdReceived }
        .filter { it.triggerId == triggerId }
        .map { it.tabId }
        .first()

    override fun processSessionClear() {
        chatSessionStorage.deleteAllSessions()
    }

    companion object {
        private val logger = getLogger<ChatController>()

        // This is a special tabID we can receive to indicate that there is no tab available for handling the context menu action
        private const val NO_TAB_AVAILABLE = "no-available-tabs"

        val objectMapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}
