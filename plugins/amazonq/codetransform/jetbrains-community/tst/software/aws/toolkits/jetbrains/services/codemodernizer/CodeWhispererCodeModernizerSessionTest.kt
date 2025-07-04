// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.utils.io.createFile
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.delete
import kotlinx.coroutines.test.runTest
import org.apache.commons.codec.digest.DigestUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.DefaultAwsResponseMetadata
import software.amazon.awssdk.awscore.util.AwsHeader
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.services.codewhispererruntime.model.CreateUploadUrlResponse
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.amazon.awssdk.services.codewhispererruntime.model.UploadContext
import software.amazon.awssdk.services.ssooidc.model.SsoOidcException
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenAuthState
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerJobCompletedResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerSessionContext
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerStartJobResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeTransformHilDownloadArtifact
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeTransformType
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MAVEN_BUILD_SKIP_UNIT_TESTS
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.UploadFailureReason
import software.aws.toolkits.jetbrains.services.codemodernizer.model.ZipCreationResult
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.utils.rules.HeavyJavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.utils.rules.addFileToModule
import java.io.File
import java.io.FileInputStream
import java.net.ConnectException
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory

class CodeWhispererCodeModernizerSessionTest : CodeWhispererCodeModernizerTestBase(HeavyJavaCodeInsightTestFixtureRule()) {
    private fun addFilesToProjectModule(vararg path: String) {
        val module = projectRule.module
        path.forEach { projectRule.fixture.addFileToModule(module, it, it) }
    }

    @Rule
    @JvmField
    val wireMock = WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort())

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Before
    override fun setup() {
        super.setup()
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Process Proxy: Launcher")
        setupConnection(BearerTokenAuthState.AUTHORIZED)
    }

    @Test
    fun `CodeModernizerSessionContext shows the transformation hub once ide maven finishes successfully`() {
        val module = projectRule.module
        val fileText = "Morning"
        projectRule.fixture.addFileToModule(module, "src/tmp.txt", fileText)

        // get project.projectFile because project.projectFile can not be null
        val roots = ModuleRootManager.getInstance(module).contentRoots
        val root = roots[0]
        val context = spy(CodeModernizerSessionContext(project, root.children[0], JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11))
        val result = spy(MavenCopyCommandsResult.Success(File("")))
        doReturn(null).`when`(result).dependencyDirectory
        doReturn(result).`when`(context).executeMavenCopyCommands(any(), any())
        runInEdtAndWait {
            context.createZipWithModuleFiles(result).payload
            verify(context, times(1)).showTransformationHub()
            verify(result, atLeastOnce()).dependencyDirectory
        }
    }

    @Test
    fun `CodeModernizerSessionContext does not show the transformation hub once ide maven fails`() {
        val module = projectRule.module
        val fileText = "Morning"
        projectRule.fixture.addFileToModule(module, "src/tmp.txt", fileText)

        // get project.projectFile because project.projectFile can not be null
        val roots = ModuleRootManager.getInstance(module).contentRoots
        val root = roots[0]
        val context = spy(CodeModernizerSessionContext(project, root.children[0], JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11))
        val result = MavenCopyCommandsResult.Failure
        doReturn(result).`when`(context).executeMavenCopyCommands(any(), any())
        runInEdtAndWait {
            context.createZipWithModuleFiles(result).payload
            verify(context, times(0)).showTransformationHub()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip with module files`() {
        val module = projectRule.module
        val fileText = "Morning"
        projectRule.fixture.addFileToModule(module, "src/tmp.txt", fileText)

        // get project.projectFile because project.projectFile can not be null
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)
        assertThat(rootManager.dependencies).isEmpty()

        val root = roots[0]
        val context = CodeModernizerSessionContext(
            project,
            root.children[0],
            JavaSdkVersion.JDK_1_8,
            JavaSdkVersion.JDK_11,
            listOf(EXPLAINABILITY_V1, SELECTIVE_TRANSFORMATION_V2),
            MAVEN_BUILD_SKIP_UNIT_TESTS
        )
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            var numEntries = 0
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                numEntries += 1
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> {
                        assertThat(fileContent).isNotNull()
                        assertThat(fileContent).contains(MAVEN_BUILD_SKIP_UNIT_TESTS)
                        assertThat(fileContent).contains(SELECTIVE_TRANSFORMATION_V2)
                        assertThat(fileContent).contains("\"noInteractiveMode\":true")
                    }
                    Path("sources/src/tmp.txt") -> assertThat(fileContent).isEqualTo(fileText)
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> fail("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
            assert(numEntries == 3)
        }
    }

    @Test
    fun `CodeModernizerSession can create zip with module files and excludes target dir if pom xml present`() {
        val module = projectRule.module
        val fileText = "Morning"
        projectRule.fixture.addFileToModule(module, "src/tmp.java", fileText)
        projectRule.fixture.addFileToModule(module, "target/smth.java", fileText)
        projectRule.fixture.addFileToModule(module, "target/somedir/anotherthing.class", fileText)
        projectRule.fixture.addFileToModule(module, "pom.xml", fileText)

        // get project.projectFile because project.projectFile can not be null
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)
        assertThat(rootManager.dependencies).isEmpty()

        val pom = roots[0].children.first { it.name == "pom.xml" }
        val context = CodeModernizerSessionContext(project, pom, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> assertThat(fileContent).isNotNull()
                    Path("sources/src/tmp.java") -> assertThat(fileContent).isEqualTo(fileText)
                    Path("sources/pom.xml") -> assertThat(fileContent).isEqualTo(fileText)
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> fail("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip with module files and dependency files excludes target dir if pom xml present`() {
        val module = projectRule.module
        val fileText = "Morning"
        projectRule.fixture.addFileToModule(module, "src/tmp.java", fileText)
        projectRule.fixture.addFileToModule(module, "target/smth.java", fileText)
        projectRule.fixture.addFileToModule(module, "target/somedir/anotherthing.class", fileText)
        projectRule.fixture.addFileToModule(module, "pom.xml", fileText)

        // get project.projectFile because project.projectFile can not be null
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)

        val pom = roots[0].children.first { it.name == "pom.xml" }
        val context = CodeModernizerSessionContext(project, pom, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> assertThat(fileContent).isNotNull()
                    Path("sources/src/tmp.java") -> assertThat(fileContent).isEqualTo(fileText)
                    Path("sources/pom.xml") -> assertThat(fileContent).isEqualTo(fileText)
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> fail("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip and exclude nested target`() {
        addFilesToProjectModule(
            "src/tmp.java",
            "target/smth.java",
            "target/somedir/anotherthing.class",
            "pom.xml",
            "someModule/pom.xml",
            "someModule/target/smth.class",
            "someModule/src/helloworld.java",
        )
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)

        val pom = roots[0].children.first { it.name == "pom.xml" }
        val context = CodeModernizerSessionContext(project, pom, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> assertThat(fileContent).isNotNull()
                    Path("sources/src/tmp.java") -> assertThat(fileContent).isEqualTo("src/tmp.java")
                    Path("sources/pom.xml") -> assertThat(fileContent).isEqualTo("pom.xml")
                    Path("sources/someModule/src/helloworld.java") -> assertThat(fileContent).isEqualTo("someModule/src/helloworld.java")
                    Path("sources/someModule/pom.xml") -> assertThat(fileContent).isEqualTo("someModule/pom.xml")
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> fail("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip and replace Windows file path`() {
        addFilesToProjectModule(
            "src\\tmp.java",
            "target\\smth.java",
            "target\\somedir\\anotherthing.class",
            "pom.xml",
            "someModule\\pom.xml",
            "someModule\\target\\smth.class",
            "someModule\\src\\helloworld.java",
        )
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)

        val pom = roots[0].children.first { it.name == "pom.xml" }
        val context = CodeModernizerSessionContext(project, pom, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> assertThat(fileContent).isNotNull()
                    Path("sources/src/tmp.java") -> assertThat(fileContent).isEqualTo("src\\tmp.java")
                    Path("sources/pom.xml") -> assertThat(fileContent).isEqualTo("pom.xml")
                    Path("sources/someModule/src/helloworld.java") -> assertThat(fileContent).isEqualTo("someModule\\src\\helloworld.java")
                    Path("sources/someModule/pom.xml") -> assertThat(fileContent).isEqualTo("someModule\\pom.xml")
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> fail("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip and excludes idea folder`() {
        addFilesToProjectModule(
            "pom.xml",
            "src/tmp.java",
            ".idea/smth.iml",
            "someModule/pom.xml",
            "someModule/.idea/smthelse.iml"
        )
        // get project.projectFile because project.projectFile can not be null
        val rootManager = ModuleRootManager.getInstance(module)
        val roots = rootManager.contentRoots
        assertThat(roots).hasSize(1)

        val pom = roots[0].children.first { it.name == "pom.xml" }
        val context = CodeModernizerSessionContext(project, pom, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val mockFile = mock(File::class.java)
        val mockStringBuilder = mock(StringBuilder::class.java)
        val file = runInEdtAndGet {
            val result = context.executeMavenCopyCommands(mockFile, mockStringBuilder)
            context.createZipWithModuleFiles(result).payload
        }
        ZipFile(file).use { zipFile ->
            assertThat(zipFile.entries().toList()).allSatisfy { entry ->
                val fileContent = zipFile.getInputStream(entry).bufferedReader().readLine()
                when (Path(entry.name)) {
                    Path("manifest.json") -> assertThat(fileContent).isNotNull()
                    Path("sources/pom.xml") -> assertThat(fileContent).isEqualTo("pom.xml")
                    Path("sources/src/tmp.java") -> assertThat(fileContent).isEqualTo("src/tmp.java")
                    Path("sources/someModule/pom.xml") -> assertThat(fileContent).isEqualTo("someModule/pom.xml")
                    Path("build-logs.txt") -> assertThat(fileContent).isNotNull()
                    else -> throw AssertionError("Unexpected entry in zip file: $entry")
                }
            }
            zipFile.close()
        }
    }

    @Test
    fun `CodeModernizerSession can create zip and excludes maven metadata from dependencies folder`() {
        // get project.projectFile because project.projectFile can not be null
        val context = CodeModernizerSessionContext(project, emptyPomFile, JavaSdkVersion.JDK_1_8, JavaSdkVersion.JDK_11)
        val m2Folders = listOf(
            "com/groupid1/artifactid1/version1",
            "com/groupid1/artifactid1/version2",
            "com/groupid1/artifactid2/version1",
            "com/groupid2/artifactid1/version1",
            "com/groupid2/artifactid1/version2",
        )
        // List of files that exist in m2 artifact directory
        val filesToAdd = listOf(
            "_remote.repositories",
            "test-0.0.1-20240315.145420-18.pom",
            "test-0.0.1-20240315.145420-18.pom.sha1",
            "test-0.0.1-SNAPSHOT.pom",
            "maven-metadata-test-repo.xml",
            "maven-metadata-test-repo.xml.sha1",
            "resolver-status.properties",
        )
        val expectedFilesAfterClean = listOf(
            "test-0.0.1-20240315.145420-18.pom",
            "test-0.0.1-SNAPSHOT.pom",
            "maven-metadata-test-repo.xml",
            "resolver-status.properties",
        )

        m2Folders.forEach {
            val newFolder = tempFolder.newFolder(*it.split("/").toTypedArray())
            filesToAdd.forEach { file -> newFolder.toPath().resolve(file).createFile() }
        }

        val dependenciesToUpload = context.iterateThroughDependencies(tempFolder.root)
        assertThat(dependenciesToUpload).hasSize(m2Folders.size * expectedFilesAfterClean.size)
        assertThat(dependenciesToUpload).allSatisfy {
            assertThat(it.name).isIn(expectedFilesAfterClean)
        }
    }

    @Test
    fun `CodeModernizer can create modernization job`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doNothing().whenever(clientAdaptorSpy).uploadArtifactToS3(any(), any(), any(), any(), any())
        doReturn(exampleStartCodeMigrationResponse).whenever(clientAdaptorSpy).startCodeModernization(any(), any(), any())
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.Started(jobId))
        verify(clientAdaptorSpy, times(1)).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        verify(clientAdaptorSpy, times(1)).startCodeModernization(any(), any(), any())
        verify(clientAdaptorSpy, times(1)).uploadArtifactToS3(any(), any(), any(), any(), any())
        verifyNoMoreInteractions(clientAdaptorSpy)
    }

    @Test
    fun `CodeModernizer cannot upload payload due to already disposed`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doAnswer { throw AlreadyDisposedException("mock exception") }.whenever(clientAdaptorSpy).uploadArtifactToS3(any(), any(), any(), any(), any())
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.Disposed)
    }

    @Test
    fun `CodeModernizer returns credentials expired when SsoOidcException during upload`() = runTest {
        setupConnection(BearerTokenAuthState.AUTHORIZED)
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doAnswer { throw SsoOidcException.builder().build() }.whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.CREDENTIALS_EXPIRED))
    }

    @Test
    fun `CodeModernizer returns credentials expired when expired before upload`() = runTest {
        listOf(BearerTokenAuthState.NEEDS_REFRESH, BearerTokenAuthState.NOT_AUTHENTICATED).forEach {
            setupConnection(it)
            val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
            assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.CREDENTIALS_EXPIRED))
        }
    }

    @Test
    fun `CodeModernizer cannot upload payload due to presigned url issue`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doAnswer { throw HttpRequests.HttpStatusException("mock error", 403, "mock url") }.whenever(testSessionSpy).uploadPayload(
            any(),
            nullable(UploadContext::class.java)
        )
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.PRESIGNED_URL_EXPIRED))
        verify(testSessionStateSpy, times(1)).putJobHistory(any(), eq(TransformationStatus.FAILED), any(), any())
        assertThat(testSessionStateSpy.currentJobStatus).isEqualTo(TransformationStatus.FAILED)
    }

    @Test
    fun `CodeModernizer cannot upload payload due to other status code`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doAnswer { throw HttpRequests.HttpStatusException("mock error", 407, "mock url") }.whenever(testSessionSpy).uploadPayload(
            any(),
            nullable(UploadContext::class.java)
        )
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.HTTP_ERROR(407)))
        verify(testSessionStateSpy, times(1)).putJobHistory(any(), eq(TransformationStatus.FAILED), any(), any())
        assertThat(testSessionStateSpy.currentJobStatus).isEqualTo(TransformationStatus.FAILED)
    }

    @Test
    fun `CodeModernizer cannot upload payload due to unknown client-side issue`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doAnswer { throw Exception("mock client-side exception") }.whenever(clientAdaptorSpy).uploadArtifactToS3(any(), any(), any(), any(), any())
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.OTHER("mock client-side exception")))
        verify(testSessionStateSpy, times(1)).putJobHistory(any(), eq(TransformationStatus.FAILED), any(), any())
        assertThat(testSessionStateSpy.currentJobStatus).isEqualTo(TransformationStatus.FAILED)
    }

    @Test
    fun `CodeModernizer cannot upload payload due to connection refused`() = runTest {
        doReturn(ZipCreationResult.Succeeded(File("./tst-resources/codemodernizer/test.txt")))
            .whenever(testSessionContextSpy).createZipWithModuleFiles(any())
        doReturn(exampleCreateUploadUrlResponse).whenever(clientAdaptorSpy).createGumbyUploadUrl(any(), nullable(UploadContext::class.java))
        doAnswer { throw ConnectException("mock exception") }.whenever(testSessionSpy).uploadPayload(any(), nullable(UploadContext::class.java))
        val result = testSessionSpy.createModernizationJob(MavenCopyCommandsResult.Success(File("./mock/path/")))
        assertThat(result).isEqualTo(CodeModernizerStartJobResult.ZipUploadFailed(UploadFailureReason.CONNECTION_REFUSED))
        verify(testSessionStateSpy, times(1)).putJobHistory(any(), eq(TransformationStatus.FAILED), any(), any())
        assertThat(testSessionStateSpy.currentJobStatus).isEqualTo(TransformationStatus.FAILED)
    }

    @Test
    fun `CodeModernizer can poll job for status updates`() = runTest {
        doReturn(exampleGetCodeMigrationResponse, *happyPathMigrationResponses.toTypedArray()).whenever(clientAdaptorSpy).getCodeModernizationJob(any())
        doReturn(exampleGetCodeMigrationPlanResponse).whenever(clientAdaptorSpy).getCodeModernizationPlan(any())
        doReturn(exampleStartCodeMigrationResponse).whenever(clientAdaptorSpy).startCodeModernization(any(), any(), any())

        doNothing().whenever(testSessionStateSpy).updateJobHistory(any(), any(), any())
        val result = testSessionSpy.pollUntilJobCompletion(CodeTransformType.LANGUAGE_UPGRADE, jobId) { _, _ -> }
        assertThat(result).isEqualTo(CodeModernizerJobCompletedResult.JobCompletedSuccessfully(jobId))

        // two polls to check status as we 1. check for plan existing and 2. check if job completed
        // since the transformationStatus is dynamic by the happyPathMigrationResponses so there will be 10 times to call getCodeModernizationJob
        verify(clientAdaptorSpy, atLeastOnce()).getCodeModernizationJob(any())
        verify(clientAdaptorSpy, atLeastOnce()).getCodeModernizationPlan(any())
    }

    @Test
    fun `CodeModernizer detects partially migrated code`() = runTest {
        doReturn(
            exampleGetCodeMigrationResponse.replace(TransformationStatus.STARTED),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.PLANNED),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.TRANSFORMING),
            exampleGetCodeMigrationResponse.replace(TransformationStatus.PARTIALLY_COMPLETED),
        ).whenever(clientAdaptorSpy).getCodeModernizationJob(any())
        doReturn(exampleGetCodeMigrationPlanResponse).whenever(clientAdaptorSpy).getCodeModernizationPlan(any())
        doReturn(exampleStartCodeMigrationResponse).whenever(clientAdaptorSpy).startCodeModernization(any(), any(), any())

        doNothing().whenever(testSessionStateSpy).updateJobHistory(any(), any(), any())
        val result = testSessionSpy.pollUntilJobCompletion(CodeTransformType.LANGUAGE_UPGRADE, jobId) { _, _ -> }
        assertThat(result).isEqualTo(CodeModernizerJobCompletedResult.JobPartiallySucceeded(jobId))
        verify(clientAdaptorSpy, times(4)).getCodeModernizationJob(any())
        verify(clientAdaptorSpy, atLeastOnce()).getCodeModernizationPlan(any())
    }

    @Test
    fun `overwritten files would have different checksum from expected files`() {
        val expectedSha256checksum: String = Base64.getEncoder().encodeToString(
            DigestUtils.sha256(FileInputStream(expectedFilePath.toAbsolutePath().toString()))
        )
        val fakeSha256checksum: String = Base64.getEncoder().encodeToString(
            DigestUtils.sha256(FileInputStream(overwrittenFilePath.toAbsolutePath().toString()))
        )
        assertThat(expectedSha256checksum).isNotEqualTo(fakeSha256checksum)
    }

    @Test
    fun `test uploadPayload()`() = runTest {
        val s3endpoint = "http://127.0.0.1:${wireMock.port()}"
        val gumbyUploadUrlResponse = CreateUploadUrlResponse.builder()
            .uploadUrl(s3endpoint)
            .uploadId("1234")
            .kmsKeyArn("0000000000000000000000000000000000:key/1234abcd")
            .responseMetadata(DefaultAwsResponseMetadata.create(mapOf(AwsHeader.AWS_REQUEST_ID to testRequestId)))
            .sdkHttpResponse(
                SdkHttpResponse.builder().headers(mapOf(CodeWhispererService.KET_SESSION_ID to listOf(testSessionId))).build()
            )
            .build() as CreateUploadUrlResponse
        val expectedSha256checksum: String =
            Base64.getEncoder().encodeToString(DigestUtils.sha256(FileInputStream(expectedFilePath.toAbsolutePath().toString())))
        clientAdaptorSpy.stub {
            onGeneric { clientAdaptorSpy.createGumbyUploadUrl(any(), nullable(UploadContext::class.java)) }
                .thenReturn(gumbyUploadUrlResponse)
        }
        wireMock.stubFor(put(urlEqualTo("/")).willReturn(aResponse().withStatus(200)))
        testSessionSpy.uploadPayload(expectedFilePath.toFile(), null)

        val inOrder = inOrder(clientAdaptorSpy)
        inOrder.verify(clientAdaptorSpy).createGumbyUploadUrl(eq(expectedSha256checksum), nullable(UploadContext::class.java))
        inOrder.verify(clientAdaptorSpy).uploadArtifactToS3(
            eq(gumbyUploadUrlResponse.uploadUrl()),
            eq(expectedFilePath.toFile()),
            eq(expectedSha256checksum),
            eq(gumbyUploadUrlResponse.kmsKeyArn()),
            any()
        )
    }

    @Test
    fun `Human in the loop will set and get download artifacts`() {
        val outputFolder = createTempDirectory("hilTest")
        val testZipFilePath = "humanInTheLoop/downloadResults.zip".toResourceFile().toPath()
        val hilDownloadArtifact = CodeTransformHilDownloadArtifact.create(testZipFilePath, outputFolder)

        // assert null before setting
        assertThat(testSessionSpy.getHilDownloadArtifact()).isNull()
        testSessionSpy.setHilDownloadArtifact(hilDownloadArtifact)
        assertThat(testSessionSpy.getHilDownloadArtifact()).isEqualTo(hilDownloadArtifact)

        // cleanup
        outputFolder.delete()
    }

    @Test
    fun `Human in the loop will clean up download artifacts`() {
        val outputFolder = createTempDirectory("hilTest")
        val testZipFilePath = "humanInTheLoop/downloadResults.zip".toResourceFile().toPath()
        val hilDownloadArtifact = CodeTransformHilDownloadArtifact.create(testZipFilePath, outputFolder)
        testSessionSpy.setHilDownloadArtifact(hilDownloadArtifact)
        testSessionSpy.setHilTempDirectoryPath(outputFolder)
        assertThat(outputFolder).exists()
        testSessionSpy.hilCleanup()
        assertThat(outputFolder).doesNotExist()
    }
}
