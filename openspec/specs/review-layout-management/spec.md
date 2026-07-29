# review-layout-management Specification

## Purpose
TBD - created by archiving change kan-5. Update Purpose after archive.
## Requirements
### Requirement: Persisted state naming an unmanaged window is pruned on load

Loading persisted layout state SHALL discard ids the plugin no longer manages, because such an id can
never be resolved and would otherwise remain on the record permanently.

This rule survives the hidden set becoming dynamic. The pruned ids are ones belonging to tool windows
that no longer exist at all — never ids that merely fall outside the visible sweep of the moment,
which are ordinary and must be kept.

#### Scenario: A stale Review Queue entry is dropped

- **WHEN** persisted state names `Review Queue`, written by a version that still had that tool window
- **THEN** the id is discarded as the state loads

#### Scenario: Hiding still works after a stale entry is loaded

- **WHEN** persisted state named only an unmanaged id
- **AND** a pass starts
- **THEN** the visible tool windows are hidden normally
- **AND** the record describes that hide rather than carrying stale contents forward

#### Scenario: An id that is managed but not yet registered is kept

- **WHEN** restore runs before tool window registration is complete
- **THEN** an id that did not resolve stays on the record
- **AND** a later restore reopens it

#### Scenario: A second hide does not strand what the first one hid

- **WHEN** a hide runs twice with no restore in between
- **THEN** the record still names the windows left hidden
- **AND** a restore afterwards reopens them

### Requirement: A pass hides every visible tool window

A guided review pass SHALL hide every tool window that is visible when it starts, on any side and
regardless of which plugin registered it, so the reviewer is left with the diff alone. It SHALL
restore whatever it hid when the pass ends.

The hidden set is determined by asking the tool window manager which windows are visible at that
moment. It SHALL NOT be a fixed list of ids, so a window registered by a plugin this one does not
know about is covered without any change here.

Hiding a tool window changes what is on screen and nothing else: work running behind a hidden window,
such as a build in the Terminal, SHALL continue.

#### Scenario: Every visible window is hidden for a pass

- **WHEN** a pass starts and the Project, Terminal and Git tool windows are visible
- **THEN** all three are hidden
- **AND** the fact that the review hid each of them is recorded

#### Scenario: A window from another plugin is hidden too

- **WHEN** a pass starts and a tool window registered by an unrelated plugin is visible
- **THEN** it is hidden and recorded, with no per-plugin knowledge involved

#### Scenario: A window the user had already closed is not reopened

- **WHEN** a pass starts and a tool window is already hidden
- **THEN** it is not recorded
- **AND** ending the pass does not open it

#### Scenario: Ending a pass restores what it hid

- **WHEN** the pass ends, by End Review, by marking the last file, or by closing the diff tab
- **THEN** every window the pass hid is reopened
- **AND** the record is cleared of what was reopened

#### Scenario: The record survives an IDE quit mid-pass

- **WHEN** the IDE is quit during a pass and the project is reopened
- **THEN** the windows hidden by the pass are reopened at startup

#### Scenario: The record is written before anything is hidden

- **WHEN** hiding fails part-way through the visible set
- **THEN** the record already names every window the attempt touched
- **AND** a restore reopens them

