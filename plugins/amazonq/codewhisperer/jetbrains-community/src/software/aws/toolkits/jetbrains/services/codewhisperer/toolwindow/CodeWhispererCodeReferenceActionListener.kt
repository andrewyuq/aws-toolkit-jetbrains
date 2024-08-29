// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.toolwindow

import com.intellij.openapi.editor.RangeMarker
import groovy.lang.Tuple3
import groovy.lang.Tuple4
import software.aws.toolkits.jetbrains.services.codewhisperer.model.DetailContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.SessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererUserActionListener

class CodeWhispererCodeReferenceActionListener : CodeWhispererUserActionListener {
    override fun afterAccept(states: InvocationContext, details: List<Tuple3<DetailContext, String, String>>, sessionContext: SessionContext, rangeMarker: RangeMarker) {
        val (project, editor) = states.requestContext
        val manager = CodeWhispererCodeReferenceManager.getInstance(project)
        manager.insertCodeReference(states, details, sessionContext.selectedIndex)
        manager.addListeners(editor)
    }
}
