package dev.tweety.reviewqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import dev.tweety.reviewqueue.model.CommitRangeValidator
import dev.tweety.reviewqueue.model.ReviewScope

/**
 * The ref-input dialogs for the scopes that need refs, lifted out of the scope combo box that KAN-5
 * deleted along with the tool window, so that the menu group and the diff toolbar ask in exactly one
 * way.
 *
 * Returns null when the user cancels, which callers must treat as "change nothing" — not as an
 * empty ref.
 */
object ScopePrompts {

    /**
     * The base ref is validated here now. It previously had **no** validator at all, which made it the
     * easier of the two routes to the repository write documented on `CommitRangeValidator`: a ref
     * beginning with `-` reaches `git rev-list` as an option, and `--output=<file>` truncates that file.
     *
     * An empty value is still accepted and still means "use the tracked branch", so the validator runs
     * only on a non-blank entry. `GitReviewSource` re-checks at the boundary regardless of caller.
     */
    fun branchVsBase(project: Project): ReviewScope? {
        val base = Messages.showInputDialog(
            project,
            "Base ref (leave empty to use the tracked branch):",
            "Branch vs Base",
            null,
            "",
            object : InputValidator {
                override fun checkInput(input: String) =
                    input.isBlank() || CommitRangeValidator.validateRef(input, "The base ref") == null
                override fun canClose(input: String) = checkInput(input)
            },
        ) ?: return null
        return ReviewScope.BranchVsBase(base.takeIf { it.isNotBlank() })
    }

    fun commitRange(project: Project): ReviewScope? {
        val from = Messages.showInputDialog(
            project, "From ref:", "Commit Range", null, "HEAD~1",
            object : InputValidator {
                override fun checkInput(input: String) = CommitRangeValidator.validate(input, "HEAD") == null
                override fun canClose(input: String) = checkInput(input)
            },
        ) ?: return null
        val to = Messages.showInputDialog(
            project, "To ref:", "Commit Range", null, "HEAD",
            object : InputValidator {
                override fun checkInput(input: String) = CommitRangeValidator.validate(from, input) == null
                override fun canClose(input: String) = checkInput(input)
            },
        ) ?: return null
        return ReviewScope.CommitRange(from, to)
    }
}
