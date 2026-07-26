## ADDED Requirements

### Requirement: Scope is selectable from both the menu and the diff toolbar

The review scope SHALL be selectable from the Tools menu group and from the review diff viewer's
toolbar, with both surfaces driving the same three scope choices — Staged, Branch vs Base, and Commit
Range.

#### Scenario: Both surfaces offer the same choices

- **WHEN** the user opens the scope control from either the menu or the diff toolbar
- **THEN** the same three scope options are offered
- **AND** both surfaces resolve them from the single registered scope group

#### Scenario: The diff toolbar control names the current scope

- **WHEN** a review pass is running
- **THEN** the diff toolbar's scope control displays the name of the current scope

#### Scenario: The scope control is enabled during a pass

- **WHEN** a review pass is running
- **THEN** the diff toolbar's scope control is enabled

### Requirement: Ref-bearing scopes prompt before anything is changed

Scopes that need refs SHALL prompt for them, and SHALL abort without side effects when the prompt is
cancelled.

#### Scenario: Branch vs Base prompts for a base ref

- **WHEN** the user chooses Branch vs Base
- **THEN** a prompt for the base ref appears, with an empty value meaning the tracked branch

#### Scenario: Commit Range validates its refs

- **WHEN** the user enters a ref containing a space or a semicolon
- **THEN** the dialog rejects it and does not close

#### Scenario: Cancelling the prompt changes nothing

- **WHEN** the user cancels the ref prompt
- **THEN** the scope is unchanged
- **AND** no confirmation dialog is shown
- **AND** any running pass is untouched

### Requirement: No ref beginning with a dash ever reaches git

Every ref handed to git SHALL be rejected if it begins with `-`, whether the reviewer typed it or the
plugin derived it. This MUST include a resolved base ref and the current branch name, which are
repository-controlled rather than user input, because git parses a leading-dash argument as an option
and `git rev-list --output=<file>` truncates that file before rejecting the missing commit — a
repository write from a plugin whose single invariant is that every git command is a query.

#### Scenario: A typed ref beginning with a dash is refused

- **WHEN** the user enters a ref beginning with `-` in the commit-range or base-ref prompt
- **THEN** the dialog rejects it and does not close
- **AND** the message names the real reason — git would read it as an option, not a ref — rather than
  calling it a shell metacharacter

#### Scenario: A resolved base ref the user never typed is validated too

- **WHEN** Branch vs Base derives its base from the tracked branch or from `origin/HEAD`
- **THEN** that ref is validated before git runs
- **AND** a ref beginning with `-` fails that root with git's reason rather than being executed

#### Scenario: A hostile branch name cannot write to the repository

- **WHEN** the current branch's own name begins with `-` — a legal refname, and one `git clone` checks
  out for the reviewer without them typing anything
- **THEN** the resolve fails that root instead of passing the branch name to `git rev-list`
- **AND** no file inside `.git` is modified

#### Scenario: Validation is at the service boundary, not only in the dialog

- **WHEN** a review scope is constructed by any caller other than the prompt
- **THEN** its refs are validated again before git runs, because the dialog is only one of the ways a
  scope comes into existence

### Requirement: Navigating between files never ends the pass

Previous File and Next File SHALL move the cursor without ending the pass. Only marking the last file,
and a jump that finds nothing showable, may end one.

#### Scenario: Next File off the end of the queue stays put

- **WHEN** the reviewer presses Next File on the last file of the pass
- **THEN** the pass continues on the same file
- **AND** the diff tab is not closed

#### Scenario: Navigation skips over files that have left the queue

- **WHEN** the next file in the direction of travel has left the queue
- **THEN** the cursor lands on the nearest file in that direction that is still in the queue
- **AND** it never overshoots past the file on screen in the opposite direction

#### Scenario: Navigation stays put when its destination cannot be rendered

- **WHEN** the nearest still-queued file in the direction of travel cannot be rendered by the diff viewer
- **THEN** the pass stays on the file already on screen
- **AND** the cursor does **not** chain on to a further file, because that would be a second move the
  reviewer did not ask for

A file that has left the queue and a file that is present but unrenderable are therefore handled
differently, deliberately: the first is skipped over, the second stops the move. An earlier draft of this
scenario said the cursor lands on the nearest file that is "still in the queue **and can be rendered**",
which reads naturally but describes chaining the code declines to do — and because this delta becomes the
main spec on archive, it would have instructed a future maintainer to undo that decision.

#### Scenario: Previous File does not reload the file on screen

- **WHEN** the reviewer presses Previous File and no earlier file is still live
- **THEN** nothing is shown again, so the scroll position of the file on screen is kept

#### Scenario: Marking the last file does end the pass

- **WHEN** the reviewer marks the last unreviewed file of the pass
- **THEN** the pass ends and the hidden Project tool window is restored

#### Scenario: A jump that finds nothing showable ends the pass

- **WHEN** the reviewer jumps to a file and nothing at or after it is still showable
- **THEN** the pass ends
- **AND** the caller is told, so it can open the file as a browsing diff instead

### Requirement: Changing the scope mid-pass restarts the pass in place

Choosing a scope while a review pass is running SHALL confirm, then restart the pass in the new
scope, keeping every reviewed mark.

#### Scenario: Confirmation names the resolved scope

- **WHEN** the user has chosen a scope and a pass is running
- **THEN** a confirmation is shown that names the resolved scope and states that the pass restarts

#### Scenario: Declining the confirmation leaves the pass alone

- **WHEN** the user declines the confirmation
- **THEN** the scope is unchanged and the pass continues on the same file

#### Scenario: Accepting restarts the pass without layout churn

- **WHEN** the user accepts the confirmation
- **THEN** the queue is rebuilt in the new scope
- **AND** the pass restarts on the first unreviewed file of the new scope
- **AND** the diff tab is replaced in place
- **AND** no tool window is hidden or restored in the process

#### Scenario: Reviewed marks survive a scope change

- **WHEN** the scope changes mid-pass
- **THEN** every reviewed mark recorded before the change is still recorded afterwards

#### Scenario: Switching into a scope with nothing unreviewed ends the pass

- **WHEN** the new scope holds no unreviewed, renderable file
- **THEN** the pass ends and the hidden Project tool window is restored

#### Scenario: A switch that ends the pass always reports why

- **WHEN** the new scope leaves the pass nothing to walk
- **THEN** the reviewer is told which empty result it was, because a gesture that silently ends the pass
  they were running is the worst available outcome
- **AND** this holds by both routes — whether the new scope held no unreviewed file at all, or held
  unreviewed files the diff framework refused to render

#### Scenario: A scope switch never announces a completion

- **WHEN** the scope changes mid-pass and every file in the new scope is already reviewed, because marks
  are content-addressed and so carry over
- **THEN** no "all files reviewed" notification is shown, and no workflow copy action is offered — the
  completion balloon means "you have finished reviewing", not "this scope is complete"
- **AND** the arming state is still consumed, so the next genuine completion behaves exactly as it would
  have, and no later rebuild can announce the completion the switch was not allowed to

#### Scenario: Cancelling the rebuild leaves the pass untouched

- **WHEN** the user cancels the progress dialog raised by the scope change
- **THEN** the pass continues on the file it was on
- **AND** the scope change is not applied

### Requirement: Choosing a scope outside a pass records it without prompting further

With no pass running, choosing a scope SHALL record it for the next resolution without asking for
confirmation.

#### Scenario: No confirmation outside a pass

- **WHEN** the user chooses a scope and no pass is running
- **THEN** no confirmation is shown
- **AND** the scope is recorded

#### Scenario: The recorded scope is what the next pass resolves

- **WHEN** the user chooses a scope outside a pass and then starts a review
- **THEN** the pass walks the files of the scope that was chosen
