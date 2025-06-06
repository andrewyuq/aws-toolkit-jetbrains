// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import org.apache.http.entity.ContentType
import java.nio.file.Path

fun saveFileFromUrl(url: String, path: Path, indicator: ProgressIndicator? = null) =
    HttpRequests.request(url).userAgent(AwsClientManager.getUserAgent()).saveToFile(path.toFile(), indicator)

fun readBytesFromUrl(url: String, indicator: ProgressIndicator? = null) =
    HttpRequests.request(url).userAgent(AwsClientManager.getUserAgent()).readBytes(indicator)

fun getTextFromUrl(url: String): String =
    HttpRequests.request(url).userAgent(AwsClientManager.getUserAgent()).readString()

fun writeJsonToUrl(url: String, jsonString: String, indicator: ProgressIndicator? = null): String =
    HttpRequests.post(url, ContentType.APPLICATION_JSON.toString())
        .userAgent(AwsClientManager.getUserAgent())
        .connect { request ->
            request.write(jsonString)
            request.readString(indicator)
        }

fun getETagFromUrl(url: String): String =
    HttpRequests.head(url)
        .userAgent(AwsClientManager.getUserAgent())
        .connect { request ->
            request.connection.headerFields["ETag"]?.firstOrNull().orEmpty()
        }
