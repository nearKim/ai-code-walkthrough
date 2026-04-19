package com.github.nearkim.aicodewalkthrough.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CodeTourToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Disposer.register(toolWindow.disposable) {
            scope.cancel()
        }
        val panel = CodeTourPanel(project, scope)
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
