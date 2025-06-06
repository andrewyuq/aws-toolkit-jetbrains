// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument

data class InlineCompletionReferencePosition(
    var startCharacter: Int = 0,
    var endCharacter: Int = 0,
)
