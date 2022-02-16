// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.ui.feedback

import software.aws.toolkits.jetbrains.services.telemetry.ClientMetadata
import java.net.URLEncoder

private const val GITHUB_LINK_BASE = "https://github.com/aws/aws-toolkit-jetbrains/issues/new?body="
private val toolkitMetadata by lazy {
    ClientMetadata.DEFAULT_METADATA.let {
        """
                ---
                Toolkit: ${it.productName} ${it.productVersion}
                OS: ${it.os} ${it.osVersion}
                IDE: ${it.parentProduct} ${it.parentProductVersion}
        """.trimIndent()
    }
}

fun buildGithubIssueUrl(issueBody: String) = "$GITHUB_LINK_BASE${ URLEncoder.encode("$issueBody\n\n$toolkitMetadata", Charsets.UTF_8.name())}"
