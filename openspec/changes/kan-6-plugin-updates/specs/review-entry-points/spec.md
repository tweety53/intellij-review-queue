## ADDED Requirements

### Requirement: The review diff carries a context menu

Right-clicking the content area of a review diff SHALL open a menu offering the review actions, so
every command on the diff toolbar is reachable from the pointer already over the code.

The menu SHALL be ordered in three sections, separated: the per-file actions first, led by **Mark
Reviewed**; then the session and queue controls; then what the platform contributes to a diff's
context menu.

The session controls SHALL be the same confirming instances the diff toolbar uses, so a menu press
and a toolbar press ask the same question. They SHALL sit below the per-file actions, because
**Reset All** clears every mark in the project and must not occupy the position a slip lands on.

#### Scenario: Mark Reviewed leads the menu

- **WHEN** the user right-clicks inside a review diff
- **THEN** a menu opens whose first entry is Mark Reviewed

#### Scenario: Every per-file action is offered

- **WHEN** the menu is open
- **THEN** it offers Mark Reviewed, Toggle Reviewed, Show File List, Previous File, Next File,
  Previous Change and Next Change

#### Scenario: The session controls are offered below a separator

- **WHEN** the menu is open
- **THEN** Scope, Start Review, End Review, Refresh and Reset All appear after the per-file actions
- **AND** a separator divides the two sections

#### Scenario: Reset All still confirms from the menu

- **WHEN** Reset All is chosen from the context menu
- **THEN** it asks for confirmation before clearing any mark

### Requirement: The context menu keeps what other plugins contribute

The menu's platform section SHALL be composed from the live contents of the platform's diff context
menu group rather than from a list written here, so entries contributed by other plugins — Annotate
with Git Blame, review comments, and whatever a future IDE adds — remain available inside a review
diff.

#### Scenario: A contributed entry survives

- **WHEN** another plugin contributes an entry to the platform's diff context menu
- **THEN** that entry appears in the review diff's menu

### Requirement: Compare with Clipboard is removed from review diffs

The platform's *Compare with Clipboard* entry SHALL be absent from the review diff's context menu. It
compares the file on screen against the clipboard, which has no use during a review pass.

The removal SHALL be scoped to diffs this plugin opened. Every other diff in the IDE keeps the entry.

#### Scenario: The entry is gone inside a review diff

- **WHEN** the context menu of a review diff is open
- **THEN** Compare with Clipboard is not among its entries

#### Scenario: Other diffs keep the entry

- **WHEN** a diff is opened from the Git log, from local history, or by Compare Files
- **THEN** its context menu still offers Compare with Clipboard

### Requirement: The context menu belongs to this plugin's diffs only

The review context menu SHALL be installed only on diffs this plugin opened, identified by data this
plugin itself attached to the diff it opened. It SHALL NOT be installed on a diff merely because a review
pass happens to be running.

#### Scenario: An unrelated diff opened mid-pass is untouched

- **WHEN** a review pass is running
- **AND** the user opens a diff from the Git log
- **THEN** that diff's context menu is the platform's, unchanged
