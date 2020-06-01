package failedlineinspection

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.stacktrace.StackTraceLine
import com.intellij.openapi.editor.colors.CodeInsightColors.RUNTIME_ERROR
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.psi.*
import java.util.*

/**
 * Based on [com.intellij.execution.testframework.TestFailedLineInspection].
 */
class TestFailedLineInspection: LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        object: KtVisitorVoid() {
            private val visited = IdentityHashMap<KtElement, Unit>()

            override fun visitCallExpression(expression: KtCallExpression) =
                checkAssertionFailureAt(expression.findParentElement())

            // Need to visit KtBinaryExpressions because `1 shouldEqual 2` doesn't contain KtCallExpressions
            override fun visitBinaryExpression(expression: KtBinaryExpression) =
                checkAssertionFailureAt(expression.findParentElement())

            // Search for the topmost expression before KtBlockExpression because visitor visits tree bottom-up
            // but it makes more sense to highlight the topmost expression which will more closely correspond
            // to the line with failed assertion.
            private tailrec fun KtElement.findParentElement(): KtElement {
                val parentPsi: PsiElement = parent ?: return this
                if (parentPsi is KtBlockExpression || parentPsi !is KtElement) return this
                if (parentPsi is KtValueArgument || parentPsi is KtValueArgumentList) return parentPsi.findParentElement()
                return parentPsi.findParentElement()
            }

            private fun checkAssertionFailureAt(ktElement: KtElement) {
                if (visited.put(ktElement, Unit) != null) return

                val state = TestFailedLineManager.getInstance(holder.project).getFailedLineState(ktElement) ?: return
                val fixes = arrayOf<LocalQuickFix>(
                    RunActionFix(ktElement, DefaultRunExecutor.getRunExecutorInstance()),
                    DebugFailedTestFix(ktElement, state.topStacktraceLine)
                )
                // Drop "AssertionError" because it's the most common error.
                val errorMessage = state.errorMessage.removePrefix("java.lang.AssertionError: ")
                val descriptor = InspectionManager.getInstance(holder.project)
                    .createProblemDescriptor(ktElement, errorMessage, isOnTheFly, fixes, GENERIC_ERROR_OR_WARNING)

                descriptor.setTextAttributes(RUNTIME_ERROR)
                holder.registerProblem(descriptor)
            }
        }

    private class DebugFailedTestFix(
        element: PsiElement,
        private val topStacktraceLine: String
    ): RunActionFix(element, DefaultDebugExecutor.getDebugExecutorInstance()) {
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val line = StackTraceLine(project, topStacktraceLine)
            val location = line.getMethodLocation(project)
            if (location != null) {
                val document = PsiDocumentManager.getInstance(project).getDocument(location.psiElement.containingFile)
                if (document != null) {
                    DebuggerManagerEx.getInstanceEx(project).breakpointManager.addLineBreakpoint(document, line.lineNumber)
                }
            }
            super.applyFix(project, descriptor)
        }
    }

    private open class RunActionFix(element: PsiElement, private val executor: Executor): LocalQuickFix, Iconable {
        private val context = ConfigurationContext(element)
        private val configuration = context.configuration!!

        @Nls(capitalization = Nls.Capitalization.Sentence)
        override fun getFamilyName(): String {
            val text = executor.getStartActionText(ProgramRunnerUtil.shortenName(configuration.name, 0))
            return UIUtil.removeMnemonic(text)
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) =
            ExecutionUtil.runConfiguration(configuration, executor)

        override fun getIcon(flags: Int) = executor.icon
    }
}
