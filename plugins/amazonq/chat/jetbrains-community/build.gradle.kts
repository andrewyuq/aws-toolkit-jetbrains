// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import software.aws.toolkits.gradle.intellij.IdeFlavor

plugins {
    id("toolkit-intellij-subplugin")
}

intellijToolkit {
    ideFlavor.set(IdeFlavor.IC)
}

dependencies {
    intellijPlatform {
        localPlugin(project(":plugin-core"))
    }

    implementation(project(":plugin-amazonq:shared:jetbrains-community"))
    // everything references codewhisperer, which is not ideal
    implementation(project(":plugin-amazonq:codewhisperer:jetbrains-community"))
    implementation(libs.diff.util)
    implementation(libs.commons.text)

    compileOnly(project(":plugin-core:jetbrains-community"))

    testImplementation(testFixtures(project(":plugin-core:jetbrains-community")))
}
