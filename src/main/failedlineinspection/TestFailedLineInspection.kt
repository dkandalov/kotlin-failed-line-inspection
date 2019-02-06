package failedlineinspection

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.stacktrace.StackTraceLine
import com.intellij.openapi.editor.colors.CodeInsightColors
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
            private val visited = IdentityHashMap<KtExpression, Unit>()

            override fun visitCallExpression(expression: KtCallExpression) =
                checkAssertionFailureAt(expression.findParentExpression())

            // Need to visit KtBinaryExpressions because `1 shouldEqual 2` doesn't contain KtCallExpressions
            override fun visitBinaryExpression(expression: KtBinaryExpression) =
                checkAssertionFailureAt(expression.findParentExpression())

            // Search for the topmost expression before KtBlockExpression because visitor visits tree bottom-up
            // but it makes more sense to highlight the topmost expression which will more closely correspond
            // to the line with failed assertion.
            private tailrec fun KtExpression.findParentExpression(): KtExpression {
                val parentElement = parent
                return if (parentElement is KtExpression && parentElement !is KtBlockExpression)
                    parentElement.findParentExpression()
                else this
            }

            private fun checkAssertionFailureAt(expression: KtExpression) {
                if (visited.put(expression, Unit) != null) return

                val state = TestFailedLineManager.getInstance(expression.project).getFailedLineState(expression) ?: return
                val fixes = arrayOf<LocalQuickFix>(
                    DebugFailedTestFix(expression, state.topStacktraceLine),
                    RunActionFix(expression, DefaultRunExecutor.EXECUTOR_ID)
                )
                // Drop "AssertionError" because it's the most common error.
                val errorMessage = state.errorMessage.removePrefix("java.lang.AssertionError: ")
                val descriptor = InspectionManager.getInstance(expression.project)
                    .createProblemDescriptor(expression, errorMessage, isOnTheFly, fixes, GENERIC_ERROR_OR_WARNING)
                descriptor.setTextAttributes(CodeInsightColors.RUNTIME_ERROR)
                holder.registerProblem(descriptor)
            }
        }

    private class DebugFailedTestFix(
        element: PsiElement,
        private val myTopStacktraceLine: String
    ): RunActionFix(element, DefaultDebugExecutor.EXECUTOR_ID) {
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val line = StackTraceLine(project, myTopStacktraceLine)
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

    private open class RunActionFix(element: PsiElement, executorId: String): LocalQuickFix, Iconable {
        private val myContext: ConfigurationContext = ConfigurationContext(element)
        private val myExecutor: Executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
        private val myConfiguration: RunnerAndConfigurationSettings? = myContext.configuration

        @Nls(capitalization = Nls.Capitalization.Sentence)
        override fun getFamilyName(): String {
            val text = myExecutor.getStartActionText(ProgramRunnerUtil.shortenName(myConfiguration!!.name, 0))
            return UIUtil.removeMnemonic(text)
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            ExecutionUtil.runConfiguration(myConfiguration!!, myExecutor)
        }

        override fun getIcon(flags: Int) = myExecutor.icon
    }
}
