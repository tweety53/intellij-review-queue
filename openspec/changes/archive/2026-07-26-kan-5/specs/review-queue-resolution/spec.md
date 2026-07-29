## ADDED Requirements

### Requirement: The queue resolves on demand, not at project open

The plugin SHALL NOT resolve the review scope at project open. Resolution SHALL happen when a user
gesture needs a queue it can act on.

#### Scenario: Nothing resolves at project open

- **WHEN** a project is opened
- **THEN** no git subprocess is spawned on the plugin's behalf

#### Scenario: Start Review resolves before starting

- **WHEN** the user invokes Start Review
- **THEN** the current scope is resolved to completion under a progress indicator
- **AND** the pass begins on the first unreviewed, renderable file of the resolved queue

#### Scenario: Show File List resolves when no pass is running

- **WHEN** the user invokes Show File List with no pass running
- **THEN** the current scope is resolved to completion before the popup is shown

#### Scenario: Refresh resolves synchronously from both surfaces

- **WHEN** the user invokes Refresh, from the menu or from the diff toolbar
- **THEN** the current scope is resolved to completion under a progress indicator
- **AND** both surfaces behave identically apart from the diff toolbar's confirmation

### Requirement: All git resolution for a project is serialised

Synchronous and asynchronous resolution SHALL share the single-threaded executor that owns git access
for the project, so that two resolutions never run git concurrently against the same root.

#### Scenario: A user gesture does not race a background rebuild

- **WHEN** a background refresh is already resolving a scope
- **AND** the user invokes a gesture that resolves synchronously
- **THEN** the second resolution waits for the first
- **AND** no two git queries run concurrently against the same root

#### Scenario: Waiting is cancellable

- **WHEN** the user cancels while a synchronous resolution is waiting for the executor
- **THEN** the wait aborts
- **AND** the queue is left exactly as it was

#### Scenario: Resolution after disposal is not an error

- **WHEN** the executor has been shut down because the project is disposed
- **THEN** a synchronous resolution is treated as producing no queue
- **AND** no exception escapes to the user

### Requirement: Synchronous resolution states and asserts its threading contract

Synchronous resolution SHALL be callable only from the event dispatch thread, outside any read or
write access, and with no modal dialog already showing; it SHALL assert the first of these rather
than failing obscurely.

#### Scenario: Called off the EDT

- **WHEN** synchronous resolution is invoked from a background thread
- **THEN** it fails a threading assertion immediately

#### Scenario: Git never runs under a read action

- **WHEN** synchronous resolution runs
- **THEN** the git queries execute outside any read or write access

#### Scenario: A stale result is never applied

- **WHEN** a newer resolution request has superseded the one in flight
- **THEN** the superseded result is discarded rather than applied

#### Scenario: A late cancellation is honoured

- **WHEN** the user cancels after the git queries finish but before the result is applied
- **THEN** the result is not applied

### Requirement: Failed git roots are reported once per distinct failure

Per-root resolution failures SHALL be reported through the `Review Queue` notification group, and
SHALL NOT repeat while the set of failures is unchanged.

#### Scenario: A failing root is announced

- **WHEN** a resolution produces one or more per-root errors
- **THEN** a notification names the failed roots and what git reported

#### Scenario: Repeated rebuilds do not repeat the notification

- **WHEN** subsequent rebuilds produce the same set of per-root errors
- **THEN** no further notification is shown

#### Scenario: A recurrence is announced again

- **WHEN** a root recovers and later fails again
- **THEN** a notification is shown again

#### Scenario: A cancelled resolution is not a failure

- **WHEN** the user cancels a resolution
- **THEN** no error notification is shown

### Requirement: An empty result says which nothing it is

When a resolution completes and leaves nothing for the invoked gesture to act on, the plugin SHALL say
so rather than appearing to do nothing, and the message SHALL name which of the four causes applies. A
positive claim that nothing is unreviewed MUST NOT be made for a scope that failed to resolve, for a
project with no git repository, or for a queue whose unreviewed files could not be displayed.

#### Scenario: Nothing is unreviewed

- **WHEN** the user invokes Start Review or Show File List, the project has a git root, no root failed
  to resolve, and every file in the resolved scope is already reviewed
- **THEN** a notification states that nothing is unreviewed in the current scope
- **AND** no diff tab is opened, no popup is shown, and no tool window is hidden

#### Scenario: The scope could not be read

- **WHEN** the resolution produced one or more per-root errors
- **THEN** the notification states that the review scope could not be read, and how many repositories
  failed to resolve
- **AND** it does not claim that nothing is unreviewed, because an empty queue says nothing about how
  much is unreviewed when the scope never resolved

#### Scenario: The claim survives the failed-root balloon's deduplication

- **WHEN** the user invokes the same gesture a second time against the same unchanged set of per-root
  failures
- **THEN** the failed-root notification is correctly suppressed as a repeat
- **AND** this notification is shown again, so that the only message left is not a false claim that
  nothing is unreviewed

#### Scenario: No git repository at all

- **WHEN** the project has no git root at the moment the gesture resolves, the enablement gate having
  been raced by a repository cache that emptied
- **THEN** the notification states that there is no git repository in this project

#### Scenario: Unreviewed, but nothing that can be displayed

- **WHEN** the resolved queue holds unreviewed files and the diff framework can render none of them
- **THEN** the notification states that no unreviewed file in the current scope could be displayed
- **AND** no pass begins and no tool window is hidden — a pass that cannot render anything must not
  hide the Project window and immediately restore it

#### Scenario: A non-empty queue is not announced

- **WHEN** a resolution produces a queue with something to act on
- **THEN** no such notification is shown

### Requirement: Notification text is escaped before it is published

Notification content is rendered as HTML, so every value the plugin interpolates into it that comes
from git or from the repository SHALL be escaped rather than passed through.

#### Scenario: git's own stderr cannot become markup

- **WHEN** a failed root's message quotes back a ref or a path that begins with `<html>` or contains a
  tag
- **THEN** the balloon shows those characters as text
- **AND** no request is made to any host named in it

#### Scenario: Multiple failed roots are on separate lines

- **WHEN** more than one root failed to resolve
- **THEN** each root appears on its own line, using a break the HTML renderer honours
