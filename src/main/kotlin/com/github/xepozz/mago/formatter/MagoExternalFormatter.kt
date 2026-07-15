package com.github.xepozz.mago.formatter

import com.github.xepozz.mago.MagoBundle
import com.github.xepozz.mago.analysis.MagoCliOptions
import com.github.xepozz.mago.configuration.MagoProjectConfiguration
import com.github.xepozz.mago.execution.LocalMagoRunner
import com.github.xepozz.mago.execution.MagoRunner
import com.github.xepozz.mago.execution.RemoteInterpreterMagoRunner
import com.github.xepozz.mago.utils.DebugLogger
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.openapi.project.Project
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.PhpFileType
import java.io.File
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vcs.VcsFileListenerContextHelper
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.writeText
import java.io.IOException
import java.util.UUID
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.vcsUtil.VcsUtil
import com.intellij.openapi.diagnostic.Logger

class MagoExternalFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean {
        val project = file.project
        if (project.isDisposed) return false

        val settings = MagoProjectConfiguration.getInstance(project)
        if (!settings.formatterEnabled) return false

        val exe = settings.getEffectiveToolPath(project)
        if (exe.isBlank()) return false

        return file.fileType == PhpFileType.INSTANCE
    }

    override fun getNotificationGroupId(): String = "Mago"

    override fun getName(): String = MagoBundle.message("formatter.name")

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val project = context.project
        val virtualFile = context.virtualFile ?: return null
        val settings = MagoProjectConfiguration.getInstance(project)

        val (runner, exe) = chooseRunnerAndExe(project, settings)
        if (exe.isBlank()) return null

        return object : FormattingTask {
            @Volatile
            private var cancelled = false

            override fun run() {
                if (cancelled) return

                val parent = virtualFile.parent
                if (parent == null) {
                    request.onError(
                        MagoBundle.message("formatter.name"),
                        MagoBundle.message("formatter.error.noParentDir")
                    )
                    return
                }

                val originalPath =
                    FileUtil.toSystemIndependentName(virtualFile.path)

                val tempFile = WriteAction.compute<VirtualFile, IOException> {
                    parent.createChildData(
                        this,
                        ".mago-fmt-${UUID.randomUUID()}.php",
                    )
                }
                try {
                    val vcsContext = project.getService(VcsFileListenerContextHelper::class.java)
                    ignoreVcsAdditionCompat(project, tempFile)

                    val lastSlash = originalPath.lastIndexOf('/')

                    var tempPath = if (lastSlash >= 0) {
                        originalPath.substring(0, lastSlash + 1) + tempFile.name
                    } else {
                        FileUtil.toSystemIndependentName(tempFile.path)
                    }

                    WriteAction.run<IOException> {
                        tempFile.setBinaryContent(
                            request.documentText.toByteArray(Charsets.UTF_8),
                        )
                    }

                    // ensure that there are no deferred pending VFS updates (may cause a race in 2026.2 as 
                    // VFS writes are deferred there)
                    flushPendingVfsUpdatesIfSupported()

                    tempPath = FileUtil.toSystemIndependentName(tempFile.path)
                    val args = MagoCliOptions.getFormatOptions(
                        settings,
                        project,
                        listOf(tempPath),
                        originalPath,
                    )

                    DebugLogger.inform(
                        project,
                        title = MagoBundle.message("formatter.title"),
                        content = "File: ${virtualFile.path}<br>" +
                                "Executable: $exe<br><br>" +
                                "Format options: ${args.joinToString(" ")}"
                    )

                    val output = runner.run(project, exe, args)

                    if (cancelled) return

                    if (output.exitCode != 0) {
                        val stderr = output.stderr.trim()
                        request.onError(
                            MagoBundle.message("formatter.error.title"),
                            stderr.ifBlank { MagoBundle.message("formatter.error.exitCode", output.exitCode) }
                        )
                        return
                    }

                    tempFile.refresh(false, false)
                    val formattedText = VfsUtilCore.loadText(tempFile)
                    request.onTextReady(formattedText)
                } catch (t: Throwable) {
                    logger.error("Mago formatter failed", t)

                    request.onError(
                        MagoBundle.message("formatter.error.title"),
                        t.message ?: t::class.qualifiedName ?: t.javaClass.name ?: "Unknown formatter failure",
                    )
                } finally {
                    WriteAction.run<IOException> {
                        if (tempFile.isValid) {
                            tempFile.delete(MagoExternalFormatter::class.java)
                        }
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled = true
                return true
            }

            override fun isRunUnderProgress(): Boolean = true

            private fun flushPendingVfsUpdatesIfSupported() {
                // flushPendingUpdates() does not exist before 2026.2, so we make sure that
                // function actually exists before we run it
                val managingFs = ManagingFS.getInstance()

                runCatching {
                    managingFs.javaClass
                        .getMethod("flushPendingUpdates")
                        .invoke(managingFs)
                }
            }
        }
    }

    private fun chooseRunnerAndExe(project: Project, settings: MagoProjectConfiguration): Pair<MagoRunner, String> {
        val interpreter = settings.resolveInterpreter(project)
        return if (interpreter != null && interpreter.isRemote) {
            RemoteInterpreterMagoRunner(interpreter) to settings.getEffectiveToolPath(project)
        } else {
            LocalMagoRunner() to settings.getEffectiveToolPath(project)
        }
    }

    private fun ignoreVcsAdditionCompat(
        project: Project,
        tempFile: VirtualFile,
    ) {
        val helperClass = Class.forName(
            "com.intellij.openapi.vcs.VcsFileListenerContextHelper",
        )

        val getInstance = helperClass.getMethod(
            "getInstance",
            Project::class.java,
        )

        val helper = getInstance.invoke(null, project)

        val ignoreAdded = helperClass.getMethod(
            "ignoreAdded",
            Collection::class.java,
        )

        ignoreAdded.invoke(
            helper,
            listOf(VcsUtil.getFilePath(tempFile)),
        )
    }

    private val logger = Logger.getInstance(MagoExternalFormatter::class.java)
}
