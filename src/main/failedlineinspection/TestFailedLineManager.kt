package failedlineinspection

import com.intellij.execution.TestStateStorage
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.FactoryMap
import org.jetbrains.kotlin.idea.editor.fixers.endLine
import org.jetbrains.kotlin.idea.editor.fixers.startLine
import org.jetbrains.kotlin.idea.refactoring.memberInfo.qualifiedClassNameForRendering
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.*

/**
 * Based on [com.intellij.testIntegration.TestFailedLineManager].
 */
class TestFailedLineManager(project: Project) {
    private val myMap: MutableMap<VirtualFile, MutableMap<String, TestInfo>> = FactoryMap.create { HashMap() }
    private val storage = TestStateStorage.getInstance(project)

    init {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                val map = myMap.remove(file)
                map?.forEach { s, info -> storage.writeState(s, info.myRecord) }
            }
        })
    }

    fun getFailedLineState(expression: KtElement): TestStateStorage.Record? {
        val ktNamedFunction = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java) ?: return null
        val info = findOrSetTestInfo(ktNamedFunction) ?: return null
        val document = PsiDocumentManager.getInstance(expression.project).getDocument(expression.containingFile) ?: return null

        val element = info.myPointer?.element
        if (element != null) {
            return if (expression !== element) null else {
                info.myRecord.failedLine = document.getLineNumber(expression.textOffset) + 1
                info.myRecord
            }
        }
        val state = info.myRecord
        if (state.failedLine == -1 || state.failedMethod.isNullOrEmpty()) return null
        if (state.failedLine < expression.startLine(document) + 1 || state.failedLine > expression.endLine(document) + 1) return null
        info.myPointer = SmartPointerManager.createPointer(expression)
        return info.myRecord
    }

    private fun findOrSetTestInfo(ktNamedFunction: KtNamedFunction): TestInfo? {
        val ktClass = PsiTreeUtil.getParentOfType(ktNamedFunction, KtClass::class.java) ?: return null
        val url = "java:test://" + ktClass.qualifiedClassNameForRendering() + "/" + ktNamedFunction.name
        val state = storage.getState(url) ?: return null

        val map = myMap[ktNamedFunction.containingFile.virtualFile]!!
        var info: TestInfo? = map[url]
        if (info == null || state.date != info.myRecord.date) {
            info = TestInfo(state)
            map[url] = info
        }
        return info
    }

    private class TestInfo(val myRecord: TestStateStorage.Record) {
        var myPointer: SmartPsiElementPointer<PsiElement>? = null
    }

    companion object {
        fun getInstance(project: Project) =
            ServiceManager.getService(project, TestFailedLineManager::class.java)!!
    }
}
