package failedlineinspection

import com.intellij.execution.TestStateStorage
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.FactoryMap
import org.jetbrains.kotlin.idea.refactoring.memberInfo.qualifiedClassNameForRendering
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import java.util.*

class TestFailedLineManager(project: Project, private val myStorage: TestStateStorage): FileEditorManagerListener {
    private val myMap: MutableMap<VirtualFile, MutableMap<String, TestInfo>> = FactoryMap.create { HashMap() }

    init {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    fun getFailedLineState(expression: KtExpression): TestStateStorage.Record? {
        val ktNamedFunction = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java) ?: return null
        val info = findTestInfo(ktNamedFunction) ?: return null

        val document = PsiDocumentManager.getInstance(expression.project).getDocument(expression.containingFile) ?: return null
        if (info.myPointer != null) {
            val element = info.myPointer!!.element
            if (element != null) {
                if (expression === element) {
                    info.myRecord!!.failedLine = document.getLineNumber(expression.textOffset) + 1
                    return info.myRecord
                }
                return null
            }
        }
        val state = info.myRecord
        val failedMethod = state?.failedMethod?.run {
            val i = indexOf('$') // because "assertEquals" is stored as "assertEquals$default" in TestStateStorage
            if (i == -1) this else substring(0, i)
        }
        if (state!!.failedLine == -1 || StringUtil.isEmpty(failedMethod)) return null
        if (failedMethod != expression.referenceExpression()?.text && failedMethod != expression.binaryExpression()?.operationReference?.text) return null
        if (state.failedLine != document.getLineNumber(expression.textOffset) + 1) return null
        info.myPointer = SmartPointerManager.createPointer(expression)
        return info.myRecord
    }

    private fun findTestInfo(ktNamedFunction: KtNamedFunction): TestInfo? {
        val ktClass = PsiTreeUtil.getParentOfType(ktNamedFunction, KtClass::class.java) ?: return null
//        val framework = TestFrameworks.detectFramework(psiClass)
//        if (framework == null || !framework.isTestMethod(ktNamedFunction, false)) return null

        val url = "java:test://" + ktClass.qualifiedClassNameForRendering() + "/" + ktNamedFunction.name
        val state = myStorage.getState(url) ?: return null

        val file = ktNamedFunction.containingFile.virtualFile
        val map = myMap[file]!!
        var info: TestInfo? = map[url]
        if (info == null || state.date != info.myRecord!!.date) {
            info = TestInfo(state)
            map[url] = info
        }
        return info
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        val map = myMap.remove(file)
        map?.forEach { s, info -> myStorage.writeState(s, info.myRecord) }
    }

    private class TestInfo(var myRecord: TestStateStorage.Record?) {
        var myPointer: SmartPsiElementPointer<PsiElement>? = null
    }

    private fun KtExpression.binaryExpression(): KtBinaryExpression? =
        if (this is KtBinaryExpression) this else null

    companion object {
        fun getInstance(project: Project) =
            ServiceManager.getService(project, TestFailedLineManager::class.java)!!
    }
}
