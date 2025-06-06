// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.language.languages

import software.aws.toolkits.jetbrains.services.codewhisperer.language.CodeWhispererProgrammingLanguage
import software.aws.toolkits.telemetry.CodewhispererLanguage

class CodeWhispererPython private constructor() : CodeWhispererProgrammingLanguage() {
    override val languageId = ID

    override fun toTelemetryType(): CodewhispererLanguage = CodewhispererLanguage.Python

    override fun isAutoFileScanSupported(): Boolean = true

    override fun lineCommentPrefix() = "#"

    override fun blockCommentPrefix() = "\"\"\""

    override fun blockCommentSuffix() = "\"\"\""

    companion object {
        const val ID = "python"

        val INSTANCE = CodeWhispererPython()
    }
}
