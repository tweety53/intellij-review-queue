package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.ResetAllAction

/**
 * Reset All on the diff toolbar, right-aligned.
 *
 * The marker interface is the whole of this class. ResetAllAction already confirms, so overriding
 * `actionPerformed` to add a confirmation would stack two dialogs.
 */
class DiffResetAllAction : ResetAllAction(), RightAlignedToolbarAction
