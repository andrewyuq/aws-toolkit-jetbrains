// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.startup

import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.services.amazonq.CodeWhispererFeatureConfigService
import software.aws.toolkits.jetbrains.services.amazonq.calculateIfIamIdentityCenterConnection
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererModelConfigurator
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.isUserBuilderId
import software.aws.toolkits.jetbrains.services.codewhisperer.importadder.CodeWhispererImportAdderListener
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererPopupManager.Companion.CODEWHISPERER_USER_ACTION_PERFORMED
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.FEATURE_CONFIG_POLL_INTERVAL_IN_MS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererUtil.promptReAuth
import software.aws.toolkits.jetbrains.utils.isQConnected
import software.aws.toolkits.jetbrains.utils.isQExpired
import software.aws.toolkits.jetbrains.utils.pluginAwareExecuteOnPooledThread

// TODO: add logics to check if we want to remove recommendation suspension date when user open the IDE
class CodeWhispererProjectStartupActivity : StartupActivity.DumbAware {
    private var runOnce = false

    /**
     * Should be invoked when
     * (1) new users accept CodeWhisperer ToS (have to be triggered manually))
     * (2) existing users open the IDE (automatically triggered)
     */
    override fun runActivity(project: Project) {
        if (!isQConnected(project)) return

        // ---- Everything below will be triggered only when CW is enabled ----

        val actionManager = CodeWhispererExplorerActionManager.getInstance()
        val scanManager = CodeWhispererCodeScanManager.getInstance(project)
        actionManager.setMonthlyQuotaForCodeScansExceeded(false)
        // Setting up listeners for Auto File Code Scan triggers and Mouse Events.
        scanManager.setEditorListeners()
        //  Run Proactive Code File Scan and disabling Auto File Scan for Builder ID Users.
        if (!isUserBuilderId(project)) {
            scanManager.createDebouncedRunCodeScan(CodeWhispererConstants.CodeAnalysisScope.FILE, isPluginStarting = true)
        }

        // ---- Everything below will be triggered once after startup ----

        if (runOnce) return

        // Reconnect CodeWhisperer on startup
        promptReAuth(project, isPluginStarting = true)
        if (isQExpired(project)) return

        // Init featureConfig job
        initFeatureConfigPollingJob(project)

        calculateIfIamIdentityCenterConnection(project) {
            pluginAwareExecuteOnPooledThread {
                CodeWhispererModelConfigurator.getInstance().listCustomizations(project, passive = true)
            }
        }

        // install intellsense autotrigger listener, this only need to be executed once
        project.messageBus.connect().subscribe(LookupManagerListener.TOPIC, CodeWhispererIntelliSenseAutoTriggerListener)
        project.messageBus.connect().subscribe(CODEWHISPERER_USER_ACTION_PERFORMED, CodeWhispererImportAdderListener)

        runOnce = true
    }

    // Start a job that runs every 180 minutes
    private fun initFeatureConfigPollingJob(project: Project) {
        projectCoroutineScope(project).launch {
            while (isActive) {
                CodeWhispererFeatureConfigService.getInstance().fetchFeatureConfigs(project)
                CodeWhispererFeatureConfigService.getInstance().getCustomizationFeature()?.let { overrideContext ->
                    val persistedCustomizationOverride = CodeWhispererModelConfigurator.getInstance().getPersistedCustomizationOverride()
                    val latestCustomizationOverride = overrideContext.value.stringValue()
                    if (persistedCustomizationOverride == latestCustomizationOverride) return@let

                    // latest is different from the currently persisted, need update
                    val customization = CodeWhispererFeatureConfigService.getInstance().validateCustomizationOverride(project, overrideContext)
                    if (customization != null) {
                        CodeWhispererModelConfigurator.getInstance().switchCustomization(project, customization, isOverride = true)
                    }
                }

                delay(FEATURE_CONFIG_POLL_INTERVAL_IN_MS)
            }
        }
    }
}
