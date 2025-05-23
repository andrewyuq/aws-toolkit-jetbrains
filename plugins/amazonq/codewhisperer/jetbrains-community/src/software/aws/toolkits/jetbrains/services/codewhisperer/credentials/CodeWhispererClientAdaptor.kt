// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.credentials

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.text.nullize
import software.amazon.awssdk.services.codewhispererruntime.CodeWhispererRuntimeClient
import software.amazon.awssdk.services.codewhispererruntime.model.ChatInteractWithMessageEvent
import software.amazon.awssdk.services.codewhispererruntime.model.ChatMessageInteractionType
import software.amazon.awssdk.services.codewhispererruntime.model.CreateUploadUrlRequest
import software.amazon.awssdk.services.codewhispererruntime.model.CreateUploadUrlResponse
import software.amazon.awssdk.services.codewhispererruntime.model.Dimension
import software.amazon.awssdk.services.codewhispererruntime.model.GetCodeAnalysisRequest
import software.amazon.awssdk.services.codewhispererruntime.model.GetCodeAnalysisResponse
import software.amazon.awssdk.services.codewhispererruntime.model.GetCodeFixJobRequest
import software.amazon.awssdk.services.codewhispererruntime.model.GetCodeFixJobResponse
import software.amazon.awssdk.services.codewhispererruntime.model.GetTestGenerationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.IdeCategory
import software.amazon.awssdk.services.codewhispererruntime.model.InlineChatUserDecision
import software.amazon.awssdk.services.codewhispererruntime.model.ListCodeAnalysisFindingsRequest
import software.amazon.awssdk.services.codewhispererruntime.model.ListCodeAnalysisFindingsResponse
import software.amazon.awssdk.services.codewhispererruntime.model.ListFeatureEvaluationsResponse
import software.amazon.awssdk.services.codewhispererruntime.model.SendTelemetryEventResponse
import software.amazon.awssdk.services.codewhispererruntime.model.StartCodeAnalysisRequest
import software.amazon.awssdk.services.codewhispererruntime.model.StartCodeAnalysisResponse
import software.amazon.awssdk.services.codewhispererruntime.model.StartCodeFixJobRequest
import software.amazon.awssdk.services.codewhispererruntime.model.StartCodeFixJobResponse
import software.amazon.awssdk.services.codewhispererruntime.model.StartTestGenerationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.TargetCode
import software.amazon.awssdk.services.codewhispererruntime.model.UserIntent
import software.aws.toolkits.jetbrains.services.amazonq.codeWhispererUserContext
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfileManager
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererCustomization
import software.aws.toolkits.jetbrains.services.codewhisperer.language.CodeWhispererProgrammingLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererUtil.getTelemetryOptOutPreference
import java.time.Instant

// As the connection is project-level, we need to make this project-level too
@Deprecated(
    "It was needed as we were supporting two service models (sigv4 & bearer), " +
        "it's no longer the case as we remove sigv4 support, should use AwsClientManager.getClient() directly"
)
interface CodeWhispererClientAdaptor {
    val project: Project

    fun createUploadUrl(
        request: CreateUploadUrlRequest,
    ): CreateUploadUrlResponse

    fun createCodeScan(request: StartCodeAnalysisRequest): StartCodeAnalysisResponse

    fun getCodeScan(request: GetCodeAnalysisRequest): GetCodeAnalysisResponse

    fun listCodeScanFindings(request: ListCodeAnalysisFindingsRequest): ListCodeAnalysisFindingsResponse

    fun startCodeFixJob(request: StartCodeFixJobRequest): StartCodeFixJobResponse

    fun getCodeFixJob(request: GetCodeFixJobRequest): GetCodeFixJobResponse

    fun startTestGeneration(uploadId: String, targetCode: List<TargetCode>, userInput: String): StartTestGenerationResponse

    fun getTestGeneration(jobId: String, jobGroupName: String): GetTestGenerationResponse

    fun sendCodePercentageTelemetry(
        language: CodeWhispererProgrammingLanguage,
        customizationArn: String?,
        acceptedTokenCount: Long,
        totalTokenCount: Long,
        unmodifiedAcceptedTokenCount: Long?,
        userWrittenCodeCharacterCount: Long?,
        userWrittenCodeLineCount: Long?,
    ): SendTelemetryEventResponse

    fun sendUserModificationTelemetry(
        sessionId: String,
        requestId: String,
        language: CodeWhispererProgrammingLanguage,
        customizationArn: String?,
        acceptedCharacterCount: Int,
        unmodifiedAcceptedTokenCount: Int,
    ): SendTelemetryEventResponse

    fun sendCodeScanTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
    ): SendTelemetryEventResponse

    fun sendCodeScanSucceededTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
        findings: Int,
    ): SendTelemetryEventResponse

    fun sendCodeScanFailedTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
    ): SendTelemetryEventResponse

    fun sendCodeFixGenerationTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeFixJobId: String?,
        ruleId: String?,
        detectorId: String?,
        findingId: String?,
        linesOfCodeGenerated: Int?,
        charsOfCodeGenerated: Int?,
    ): SendTelemetryEventResponse

    fun sendCodeFixAcceptanceTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeFixJobId: String?,
        ruleId: String?,
        detectorId: String?,
        findingId: String?,
        linesOfCodeGenerated: Int?,
        charsOfCodeGenerated: Int?,
    ): SendTelemetryEventResponse

    fun sendCodeScanRemediationTelemetry(
        language: CodeWhispererProgrammingLanguage?,
        codeScanRemediationEventType: String?,
        detectorId: String?,
        findingId: String?,
        ruleId: String?,
        component: String?,
        reason: String?,
        result: String?,
        includesFix: Boolean?,
    ): SendTelemetryEventResponse

    fun sendTestGenerationEvent(
        jobId: String,
        groupName: String,
        language: CodeWhispererProgrammingLanguage?,
        ideCategory: IdeCategory?,
        numberOfUnitTestCasesGenerated: Int?,
        numberOfUnitTestCasesAccepted: Int?,
        linesOfCodeGenerated: Int?,
        linesOfCodeAccepted: Int?,
        charsOfCodeGenerated: Int?,
        charsOfCodeAccepted: Int?,
    ): SendTelemetryEventResponse

    fun listFeatureEvaluations(): ListFeatureEvaluationsResponse

    fun sendMetricDataTelemetry(eventName: String, metadata: Map<String, Any?>): SendTelemetryEventResponse

    fun sendChatAddMessageTelemetry(
        sessionId: String,
        requestId: String,
        userIntent: UserIntent?,
        hasCodeSnippet: Boolean?,
        programmingLanguage: String?,
        activeEditorTotalCharacters: Int?,
        timeToFirstChunkMilliseconds: Double?,
        timeBetweenChunks: List<Double>?,
        fullResponselatency: Double?,
        requestLength: Int?,
        responseLength: Int?,
        numberOfCodeBlocks: Int?,
        hasProjectLevelContext: Boolean?,
        customization: CodeWhispererCustomization?,
    ): SendTelemetryEventResponse

    fun sendChatInteractWithMessageTelemetry(
        sessionId: String,
        requestId: String,
        interactionType: ChatMessageInteractionType?,
        interactionTarget: String?,
        acceptedCharacterCount: Int?,
        acceptedSnippetHasReference: Boolean?,
        hasProjectLevelContext: Boolean?,
    ): SendTelemetryEventResponse

    fun sendChatInteractWithMessageTelemetry(event: ChatInteractWithMessageEvent): SendTelemetryEventResponse

    fun sendChatUserModificationTelemetry(
        sessionId: String,
        requestId: String,
        language: CodeWhispererProgrammingLanguage,
        modificationPercentage: Double,
        hasProjectLevelContext: Boolean?,
        customization: CodeWhispererCustomization?,
    ): SendTelemetryEventResponse

    fun sendInlineChatTelemetry(
        requestId: String,
        inputLength: Int?,
        numSelectedLines: Int?,
        codeIntent: Boolean?,
        userDecision: InlineChatUserDecision?,
        responseStartLatency: Double?,
        responseEndLatency: Double?,
        numSuggestionAddChars: Int?,
        numSuggestionAddLines: Int?,
        numSuggestionDelChars: Int?,
        numSuggestionDelLines: Int?,
        programmingLanguage: String?,
    ): SendTelemetryEventResponse

    companion object {
        fun getInstance(project: Project): CodeWhispererClientAdaptor = project.service()

        const val INVALID_CODESCANJOBID = "Invalid_CodeScanJobID"
        const val INVALID_CODEFIXJOBID = "Invalid_CodeFixJobID"
    }
}

open class CodeWhispererClientAdaptorImpl(override val project: Project) : CodeWhispererClientAdaptor {
    fun bearerClient() = QRegionProfileManager.getInstance().getQClient<CodeWhispererRuntimeClient>(project)

    override fun createUploadUrl(request: CreateUploadUrlRequest): CreateUploadUrlResponse =
        bearerClient().createUploadUrl(request)

    override fun createCodeScan(request: StartCodeAnalysisRequest): StartCodeAnalysisResponse = bearerClient().startCodeAnalysis(request)

    override fun getCodeScan(request: GetCodeAnalysisRequest): GetCodeAnalysisResponse = bearerClient().getCodeAnalysis(request)

    override fun listCodeScanFindings(request: ListCodeAnalysisFindingsRequest): ListCodeAnalysisFindingsResponse =
        bearerClient().listCodeAnalysisFindings(request)

    override fun startCodeFixJob(request: StartCodeFixJobRequest): StartCodeFixJobResponse = bearerClient().startCodeFixJob(request)

    override fun getCodeFixJob(request: GetCodeFixJobRequest): GetCodeFixJobResponse = bearerClient().getCodeFixJob(request)

    override fun startTestGeneration(uploadId: String, targetCode: List<TargetCode>, userInput: String): StartTestGenerationResponse =
        bearerClient().startTestGeneration { builder ->
            builder.uploadId(uploadId)
            builder.targetCodeList(targetCode)
            builder.userInput(userInput)
            builder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
            // TODO: client token
        }

    override fun getTestGeneration(jobId: String, jobGroupName: String): GetTestGenerationResponse =
        bearerClient().getTestGeneration { builder ->
            builder.testGenerationJobId(jobId)
            builder.testGenerationJobGroupName(jobGroupName)
            builder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
        }

    override fun sendCodePercentageTelemetry(
        language: CodeWhispererProgrammingLanguage,
        customizationArn: String?,
        acceptedTokenCount: Long,
        totalTokenCount: Long,
        unmodifiedAcceptedTokenCount: Long?,
        userWrittenCodeCharacterCount: Long?,
        userWrittenCodeLineCount: Long?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeCoverageEvent {
                it.programmingLanguage { languageBuilder -> languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId) }
                it.customizationArn(customizationArn.nullize(nullizeSpaces = true))
                it.acceptedCharacterCount(acceptedTokenCount.toInt())
                it.totalCharacterCount(totalTokenCount.toInt())
                it.timestamp(Instant.now())
                it.unmodifiedAcceptedCharacterCount(unmodifiedAcceptedTokenCount?.toInt())
                it.userWrittenCodeCharacterCount(userWrittenCodeLineCount?.toInt())
                it.userWrittenCodeLineCount(userWrittenCodeLineCount?.toInt())
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendUserModificationTelemetry(
        sessionId: String,
        requestId: String,
        language: CodeWhispererProgrammingLanguage,
        customizationArn: String?,
        acceptedCharacterCount: Int,
        unmodifiedAcceptedTokenCount: Int,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.userModificationEvent {
                it.sessionId(sessionId)
                it.requestId(requestId)
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.customizationArn(customizationArn.nullize(nullizeSpaces = true))
                // deprecated field, service side should not use this % anymore
                it.modificationPercentage(0.0)
                it.timestamp(Instant.now())
                it.acceptedCharacterCount(acceptedCharacterCount)
                it.unmodifiedAcceptedCharacterCount(unmodifiedAcceptedTokenCount)
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeScanTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeScanEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.codeScanJobId(if (codeScanJobId.isNullOrEmpty()) CodeWhispererClientAdaptor.INVALID_CODESCANJOBID else codeScanJobId)
                it.timestamp(Instant.now())
                it.codeAnalysisScope(scope.value)
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeScanSucceededTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
        findings: Int,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeScanSucceededEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.codeScanJobId(if (codeScanJobId.isNullOrEmpty()) CodeWhispererClientAdaptor.INVALID_CODESCANJOBID else codeScanJobId)
                it.timestamp(Instant.now())
                it.codeAnalysisScope(scope.value)
                it.numberOfFindings(findings)
                it.timestamp(Instant.now())
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeScanFailedTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeScanJobId: String?,
        scope: CodeWhispererConstants.CodeAnalysisScope,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeScanFailedEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.codeScanJobId(if (codeScanJobId.isNullOrEmpty()) CodeWhispererClientAdaptor.INVALID_CODESCANJOBID else codeScanJobId)
                it.timestamp(Instant.now())
                it.codeAnalysisScope(scope.value)
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeFixGenerationTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeFixJobId: String?,
        ruleId: String?,
        detectorId: String?,
        findingId: String?,
        linesOfCodeGenerated: Int?,
        charsOfCodeGenerated: Int?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeFixGenerationEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.jobId(if (codeFixJobId.isNullOrEmpty()) CodeWhispererClientAdaptor.INVALID_CODEFIXJOBID else codeFixJobId)
                it.ruleId(ruleId)
                it.detectorId(detectorId)
                it.findingId(findingId)
                it.linesOfCodeGenerated(linesOfCodeGenerated)
                it.charsOfCodeGenerated(charsOfCodeGenerated)
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeFixAcceptanceTelemetry(
        language: CodeWhispererProgrammingLanguage,
        codeFixJobId: String?,
        ruleId: String?,
        detectorId: String?,
        findingId: String?,
        linesOfCodeGenerated: Int?,
        charsOfCodeGenerated: Int?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeFixAcceptanceEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.jobId(if (codeFixJobId.isNullOrEmpty()) CodeWhispererClientAdaptor.INVALID_CODEFIXJOBID else codeFixJobId)
                it.ruleId(ruleId)
                it.detectorId(detectorId)
                it.findingId(findingId)
                it.linesOfCodeAccepted(linesOfCodeGenerated)
                it.charsOfCodeAccepted(charsOfCodeGenerated)
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendCodeScanRemediationTelemetry(
        language: CodeWhispererProgrammingLanguage?,
        codeScanRemediationEventType: String?,
        detectorId: String?,
        findingId: String?,
        ruleId: String?,
        component: String?,
        reason: String?,
        result: String?,
        includesFix: Boolean?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.codeScanRemediationsEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language?.toCodeWhispererRuntimeLanguage()?.languageId)
                }
                it.codeScanRemediationsEventType(codeScanRemediationEventType)
                it.detectorId(detectorId)
                it.findingId(findingId)
                it.ruleId(ruleId)
                it.component(component)
                it.reason(reason)
                it.result(result)
                it.includesFix(includesFix)
                it.timestamp(Instant.now())
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendTestGenerationEvent(
        jobId: String,
        groupName: String,
        language: CodeWhispererProgrammingLanguage?,
        ideCategory: IdeCategory?,
        numberOfUnitTestCasesGenerated: Int?,
        numberOfUnitTestCasesAccepted: Int?,
        linesOfCodeGenerated: Int?,
        linesOfCodeAccepted: Int?,
        charsOfCodeGenerated: Int?,
        charsOfCodeAccepted: Int?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.testGenerationEvent {
                it.programmingLanguage { languageBuilder ->
                    languageBuilder.languageName(language?.toCodeWhispererRuntimeLanguage()?.languageId)
                }
                it.jobId(jobId)
                it.groupName(groupName)
                it.ideCategory(ideCategory)
                it.numberOfUnitTestCasesGenerated(numberOfUnitTestCasesGenerated)
                it.numberOfUnitTestCasesAccepted(numberOfUnitTestCasesAccepted)
                it.linesOfCodeGenerated(linesOfCodeGenerated)
                it.linesOfCodeAccepted(linesOfCodeAccepted)
                it.charsOfCodeGenerated(charsOfCodeGenerated)
                it.charsOfCodeAccepted(charsOfCodeAccepted)
                it.timestamp(Instant.now())
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun listFeatureEvaluations(): ListFeatureEvaluationsResponse = bearerClient().listFeatureEvaluations {
        it.userContext(codeWhispererUserContext())
        it.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendMetricDataTelemetry(eventName: String, metadata: Map<String, Any?>): SendTelemetryEventResponse =
        bearerClient().sendTelemetryEvent { requestBuilder ->
            requestBuilder.telemetryEvent { telemetryEventBuilder ->
                telemetryEventBuilder.metricData { metricBuilder ->
                    metricBuilder.metricName(eventName)
                    metricBuilder.metricValue(1.0)
                    metricBuilder.timestamp(Instant.now())
                    metricBuilder.dimensions(metadata.filter { it.value != null }.map { Dimension.builder().name(it.key).value(it.value.toString()).build() })
                }
            }
            requestBuilder.optOutPreference(getTelemetryOptOutPreference())
            requestBuilder.userContext(codeWhispererUserContext())
            requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
        }

    override fun sendChatAddMessageTelemetry(
        sessionId: String,
        requestId: String,
        userIntent: UserIntent?,
        hasCodeSnippet: Boolean?,
        programmingLanguage: String?,
        activeEditorTotalCharacters: Int?,
        timeToFirstChunkMilliseconds: Double?,
        timeBetweenChunks: List<Double>?,
        fullResponselatency: Double?,
        requestLength: Int?,
        responseLength: Int?,
        numberOfCodeBlocks: Int?,
        hasProjectLevelContext: Boolean?,
        customization: CodeWhispererCustomization?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.chatAddMessageEvent {
                it.conversationId(sessionId)
                it.messageId(requestId)
                it.userIntent(userIntent)
                it.hasCodeSnippet(hasCodeSnippet)
                if (programmingLanguage != null) it.programmingLanguage { langBuilder -> langBuilder.languageName(programmingLanguage) }
                it.activeEditorTotalCharacters(activeEditorTotalCharacters)
                it.timeToFirstChunkMilliseconds(timeToFirstChunkMilliseconds)
                it.timeBetweenChunks(timeBetweenChunks)
                it.fullResponselatency(fullResponselatency)
                it.requestLength(requestLength)
                it.responseLength(responseLength)
                it.numberOfCodeBlocks(numberOfCodeBlocks)
                it.hasProjectLevelContext(hasProjectLevelContext)
                it.customizationArn(customization?.arn.nullize(nullizeSpaces = true))
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendChatInteractWithMessageTelemetry(
        sessionId: String,
        requestId: String,
        interactionType: ChatMessageInteractionType?,
        interactionTarget: String?,
        acceptedCharacterCount: Int?,
        acceptedSnippetHasReference: Boolean?,
        hasProjectLevelContext: Boolean?,
    ): SendTelemetryEventResponse = sendChatInteractWithMessageTelemetry(
        ChatInteractWithMessageEvent.builder().apply {
            conversationId(sessionId)
            messageId(requestId)
            interactionType(interactionType)
            interactionTarget(interactionTarget)
            acceptedCharacterCount(acceptedCharacterCount)
            acceptedSnippetHasReference(acceptedSnippetHasReference)
            hasProjectLevelContext(hasProjectLevelContext)
        }.build()
    )

    override fun sendChatInteractWithMessageTelemetry(event: ChatInteractWithMessageEvent): SendTelemetryEventResponse =
        bearerClient().sendTelemetryEvent { requestBuilder ->
            requestBuilder.telemetryEvent { telemetryEventBuilder ->
                telemetryEventBuilder.chatInteractWithMessageEvent(event)
            }
            requestBuilder.optOutPreference(getTelemetryOptOutPreference())
            requestBuilder.userContext(codeWhispererUserContext())
            requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
        }

    override fun sendChatUserModificationTelemetry(
        sessionId: String,
        requestId: String,
        language: CodeWhispererProgrammingLanguage,
        modificationPercentage: Double,
        hasProjectLevelContext: Boolean?,
        customization: CodeWhispererCustomization?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.chatUserModificationEvent {
                it.conversationId(sessionId)
                it.messageId(requestId)
                it.programmingLanguage { langBuilder ->
                    langBuilder.languageName(language.toCodeWhispererRuntimeLanguage().languageId)
                }
                it.modificationPercentage(modificationPercentage)
                it.hasProjectLevelContext(hasProjectLevelContext)
                it.customizationArn(customization?.arn.nullize(nullizeSpaces = true))
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }

    override fun sendInlineChatTelemetry(
        requestId: String,
        inputLength: Int?,
        numSelectedLines: Int?,
        codeIntent: Boolean?,
        userDecision: InlineChatUserDecision?,
        responseStartLatency: Double?,
        responseEndLatency: Double?,
        numSuggestionAddChars: Int?,
        numSuggestionAddLines: Int?,
        numSuggestionDelChars: Int?,
        numSuggestionDelLines: Int?,
        programmingLanguage: String?,
    ): SendTelemetryEventResponse = bearerClient().sendTelemetryEvent { requestBuilder ->
        requestBuilder.telemetryEvent { telemetryEventBuilder ->
            telemetryEventBuilder.inlineChatEvent {
                it.requestId(requestId)
                it.inputLength(inputLength)
                it.numSelectedLines(numSelectedLines)
                it.codeIntent(codeIntent)
                it.userDecision(userDecision)
                it.responseStartLatency(responseStartLatency)
                it.responseEndLatency(responseEndLatency)
                it.numSuggestionAddChars(numSuggestionAddChars)
                it.numSuggestionAddLines(numSuggestionAddLines)
                it.numSuggestionDelChars(numSuggestionDelChars)
                it.numSuggestionDelLines(numSuggestionDelLines)
                if (programmingLanguage != null) it.programmingLanguage { langBuilder -> langBuilder.languageName(programmingLanguage) }
                it.timestamp(Instant.now())
            }
        }
        requestBuilder.optOutPreference(getTelemetryOptOutPreference())
        requestBuilder.userContext(codeWhispererUserContext())
        requestBuilder.profileArn(QRegionProfileManager.getInstance().activeProfile(project)?.arn)
    }
}
