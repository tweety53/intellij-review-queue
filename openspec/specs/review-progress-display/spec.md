# review-progress-display Specification

## Purpose
TBD - created by archiving change kan-6-plugin-updates. Update Purpose after archive.
## Requirements
### Requirement: The diff shows how much of the scope is reviewed

A guided review pass SHALL display, above the diff content, the number of files in the current scope
that carry a reviewed mark, the total number of files in that scope, and the scope's name. The count
SHALL be the same one the file list reports, read from a single queue snapshot, so the two surfaces
can never disagree.

#### Scenario: The banner reports the reviewed count

- **WHEN** a pass is showing a file and 5 of the 12 files in scope are marked reviewed
- **THEN** the diff displays `5 / 12 files reviewed` together with the scope's name
- **AND** the proportion is also shown as a progress bar

#### Scenario: Nothing reviewed yet

- **WHEN** a pass starts in a scope with 10 files and no reviewed marks
- **THEN** the diff displays `0 / 10 files reviewed`

#### Scenario: The count comes from the queue, not from the pass

- **WHEN** the pass is walking only the unreviewed subset of a scope
- **THEN** the totals describe the whole scope, not the pass's remaining files

### Requirement: The reviewed count follows every change to the marks

The displayed count SHALL update whenever the queue changes, not only when a new file is shown.
Marking replaces the diff tab and would refresh the count incidentally; toggling a mark and a
background rebuild do not, and those are the gestures a reviewer uses to correct a mis-mark.

#### Scenario: Toggling a mark moves the number

- **WHEN** Toggle Reviewed removes the mark from the file on screen
- **AND** the diff tab is not replaced
- **THEN** the displayed reviewed count decreases by one

#### Scenario: A background rebuild moves the number

- **WHEN** a fix round rewrites a file and the queue rebuilds, dropping that file's mark
- **THEN** the displayed count reflects the rebuilt queue without any user gesture

#### Scenario: Marking the current file moves the number

- **WHEN** Mark Reviewed marks the file on screen and the next file opens
- **THEN** the displayed reviewed count has increased by one

### Requirement: The progress display belongs to this plugin's diffs only

The display SHALL appear only on diffs this plugin opened for a review pass, and SHALL NOT appear on
any other diff in the IDE.

#### Scenario: An unrelated diff is unaffected

- **WHEN** a diff is opened from the Git log, from local history, or by Compare Files
- **THEN** no reviewed-count display is added to it

