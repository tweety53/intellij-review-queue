package dev.tweety.reviewqueue.actions.diff

import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import dev.tweety.reviewqueue.actions.ResetAllAction

/**
 * Reset All on the diff toolbar, grouped at the end of the toolbar behind a separator.
 *
 * The marker interface is the whole of this class. ResetAllAction already confirms, so overriding
 * `actionPerformed` to add a confirmation would stack two dialogs.
 *
 * See `DiffStartReviewAction`'s KDoc for why `RightAlignedToolbarAction` is implemented here without
 * actually right-aligning anything.
 */
class DiffResetAllAction : ResetAllAction(), RightAlignedToolbarAction
