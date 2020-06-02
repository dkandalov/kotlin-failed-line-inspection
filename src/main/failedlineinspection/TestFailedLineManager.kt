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
    private val myMap: MutableMap<VirtualFile, MutableMap<String, TestInfo>> = HashMap()
    private val storage = TestStateStorage.getInstance(project)

    init {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                val map = myMap.remove(file)
                map?.forEach { s, info -> storage.writeState(s, info.record) }
            }
        })
    }

    fun findTestFailureAt(ktElement: KtElement): TestStateStorage.Record? {
        val ktNamedFunction = PsiTreeUtil.getParentOfType(ktElement, KtNamedFunction::class.java) ?: return null
        val testInfo = findOrSetTestInfo(ktNamedFunction) ?: return null
        val document = PsiDocumentManager.getInstance(ktElement.project).getDocument(ktElement.containingFile) ?: return null

        val pointedElement = testInfo.elementPointer?.element
        return when {
            pointedElement == null       -> {
                val state = testInfo.record
                if (state.failedLine == -1 || state.failedMethod.isNullOrEmpty()) return null
                if (state.failedLine < ktElement.startLine(document) + 1 || state.failedLine > ktElement.endLine(document) + 1) return null
                testInfo.elementPointer = SmartPointerManager.createPointer(ktElement)
                testInfo.originalElementText = ktElement.text
                testInfo.record
            }
            pointedElement === ktElement -> {
                if (ktElement.text != testInfo.originalElementText) return null
                testInfo.record.failedLine = document.getLineNumber(ktElement.textOffset) + 1
                testInfo.record
            }
            else                         -> null
        }
    }

    private fun findOrSetTestInfo(ktNamedFunction: KtNamedFunction): TestInfo? {
        val ktClass = PsiTreeUtil.getParentOfType(ktNamedFunction, KtClass::class.java) ?: return null
        var url = ideaRunnerUrl(ktClass, ktNamedFunction)
        var record = storage.getState(url)
        if (record == null) {
            url = gradleTestUrl(ktClass, ktNamedFunction)
            record = storage.getState(url) ?: return null
        }

        val map = myMap.getOrPut(ktNamedFunction.containingFile.virtualFile, { HashMap() })
        var testInfo = map[url]
        if (testInfo == null || record.date != testInfo.record.date) {
            testInfo = TestInfo(record)
            map[url] = testInfo
        }
        return testInfo
    }

    private fun ideaRunnerUrl(ktClass: KtClass, ktNamedFunction: KtNamedFunction) =
        "java:test://" + ktClass.qualifiedClassNameForRendering() + "/" + ktNamedFunction.name

    private fun gradleTestUrl(ktClass: KtClass, ktNamedFunction: KtNamedFunction) =
        "java:test://" + ktClass.qualifiedClassNameForRendering() + "." + ktNamedFunction.name

    private class TestInfo(
        val record: TestStateStorage.Record,
        var elementPointer: SmartPsiElementPointer<PsiElement>? = null,
        var originalElementText: String? = null
    )

    companion object {
        fun getInstance(project: Project) =
            ServiceManager.getService(project, TestFailedLineManager::class.java)!!
    }
}
