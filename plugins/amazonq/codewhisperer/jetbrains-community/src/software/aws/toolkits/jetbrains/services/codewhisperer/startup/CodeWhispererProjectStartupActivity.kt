// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.startup

import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.ide.DataManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.impl.ui.KeymapPanel
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.ui.components.ActionLink
import com.intellij.util.concurrency.EdtExecutorService
import com.intellij.util.progress.sleepCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.core.plugin.PluginUpdateManager
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererCustomizationListener.Companion.TOPIC
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererModelConfigurator
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.isUserBuilderId
import software.aws.toolkits.jetbrains.services.codewhisperer.importadder.CodeWhispererImportAdderListener
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererPopupManager.Companion.CODEWHISPERER_USER_ACTION_PERFORMED
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererFeatureConfigService
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererConfigurable
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererSettings
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.FEATURE_CONFIG_POLL_INTERVAL_IN_MS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererUtil.promptReAuth
import software.aws.toolkits.jetbrains.services.codewhisperer.util.calculateIfIamIdentityCenterConnection
import software.aws.toolkits.jetbrains.utils.isQConnected
import software.aws.toolkits.jetbrains.utils.isQExpired
import software.aws.toolkits.jetbrains.utils.isRunningOnCWNotSupportedRemoteBackend
import software.aws.toolkits.jetbrains.utils.notifyInfo
import software.aws.toolkits.jetbrains.utils.notifyWarn
import software.aws.toolkits.jetbrains.utils.pluginAwareExecuteOnPooledThread
import software.aws.toolkits.resources.message
import java.time.LocalDate

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

        if (!CodeWhispererSettings.getInstance().isInlineShortcutFeatureNotificationDisplayed()) {
            CodeWhispererSettings.getInstance().setInlineShortcutFeatureNotificationDisplayed(true)
            showInlineShortcutFeatureNotification(project)
        }

        if (PluginUpdateManager.getInstance().isBeta()) {
            postWelcomeToBetaMessage()
            checkBetaExpiryInfo()
        }


        // ---- Everything below will be triggered once after startup ----

        if (runOnce) return

        checkRemoteDevVersionAndPromptUpdate()

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

    // Start a job that runs every 30 mins
    private fun initFeatureConfigPollingJob(project: Project) {
        projectCoroutineScope(project).launch {
            while (isActive) {
                CodeWhispererFeatureConfigService.getInstance().fetchFeatureConfigs(project)
                delay(FEATURE_CONFIG_POLL_INTERVAL_IN_MS)
            }
        }
    }

    private fun checkRemoteDevVersionAndPromptUpdate() {
        if (!isRunningOnCWNotSupportedRemoteBackend()) return
        notifyWarn(
            title = message("codewhisperer.notification.remote.ide_unsupported.title"),
            content = message("codewhisperer.notification.remote.ide_unsupported.message"),
        )
    }

    private fun showInlineShortcutFeatureNotification(project: Project) {
        notifyInfo(
            title = message("codewhisperer.notification.inline.shortcut_config.title"),
            content = message("codewhisperer.notification.inline.shortcut_config.content"),
            project = project,
            listOf(
                object: NotificationAction(message("codewhisperer.notification.inline.shortcut_config.open_setting")) {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeWhispererConfigurable::class.java)
                    }
                }
            )
        )
    }

    private fun postWelcomeToBetaMessage() {
        notifyInfo(
            title = "Welcome to Amazon Q Plugin Beta",
            content = "Thank you for participating in Amazon Q beta plugin testing. Plugin auto-update is always turned on to ensure the best beta experience.",
            project = null
        )
    }

    private fun checkBetaExpiryInfo() {
        // hard 2024/10/1 stop
        val expiryDate = LocalDate.of(2024, 10, 1)

        // Get the current date
        val currentDate = LocalDate.now()
        if (currentDate.isAfter(expiryDate)) {
            notifyInfo(
                title = "Amazon Q current beta period ended",
                content = "The current beta period has ended on $expiryDate, please switch to the marketplace version to continue using Amazon Q.",
                project = null
            )
            CodeWhispererService.getInstance().isBetaExpired = true
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).refreshUi()
        }
    }
}
