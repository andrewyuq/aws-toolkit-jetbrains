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

    compileOnly(project(":plugin-core:jetbrains-community"))

    implementation(project(":plugin-amazonq:shared:jetbrains-community"))
    // CodeWhispererTelemetryService uses a CircularFifoQueue, previously transitive from zjsonpatch
    implementation(libs.commons.collections)
//    implementation("com.alphacephei:vosk:0.3.38")
    implementation(platform("com.google.cloud:libraries-bom:26.1.4"))
    implementation("com.google.cloud:google-cloud-speech")
//    implementation("software.amazon.awssdk:transcribestreaming:2.29.52")
//    implementation("software.amazon.awssdk:translate:2.29.52")
//    implementation("software.amazon.awssdk:auth:2.29.52")

    testFixturesApi(testFixtures(project(":plugin-core:jetbrains-community")))
    testFixturesApi(project(path = ":plugin-toolkit:jetbrains-core", configuration = "testArtifacts"))
}

// hack because our test structure currently doesn't make complete sense
tasks.prepareTestSandbox {
    val pluginXmlJar = project(":plugin-amazonq").tasks.jar

    dependsOn(pluginXmlJar)
    intoChild(intellijPlatform.projectName.map { "$it/lib" })
        .from(pluginXmlJar)
}
