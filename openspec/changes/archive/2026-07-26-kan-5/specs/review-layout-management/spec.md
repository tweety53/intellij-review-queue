## ADDED Requirements

### Requirement: A pass hides only the Project tool window

A guided review pass SHALL hide the Project tool window to give the diff the full width, and SHALL
restore whatever it hid when the pass ends.

#### Scenario: The Project window is hidden for a pass

- **WHEN** a pass starts and the Project tool window is visible
- **THEN** it is hidden
- **AND** the fact that it was hidden by the review is recorded

#### Scenario: A window the user had already closed is not reopened

- **WHEN** a pass starts and the Project tool window is already hidden
- **THEN** it is not recorded
- **AND** ending the pass does not open it

#### Scenario: Ending a pass restores what it hid

- **WHEN** the pass ends, by End Review, by marking the last file, or by closing the diff tab
- **THEN** every window the pass hid is reopened
- **AND** the record is cleared of what was reopened

#### Scenario: The record survives an IDE quit mid-pass

- **WHEN** the IDE is quit during a pass and the project is reopened
- **THEN** the windows hidden by the pass are reopened at startup

### Requirement: Persisted state naming an unmanaged window is pruned on load

Loading persisted layout state SHALL discard ids the plugin no longer manages, because such an id can
never be resolved and would otherwise remain on the record permanently.

#### Scenario: A stale Review Queue entry is dropped

- **WHEN** persisted state names `Review Queue`, written by a version that still had that tool window
- **THEN** the id is discarded as the state loads

#### Scenario: Hiding still works after a stale entry is loaded

- **WHEN** persisted state named only an unmanaged id
- **AND** a pass starts
- **THEN** the Project window is hidden normally
- **AND** the record describes that hide rather than carrying stale contents forward

#### Scenario: An id that is managed but not yet registered is kept

- **WHEN** restore runs before tool window registration is complete
- **THEN** an id that did not resolve stays on the record
- **AND** a later restore reopens it

#### Scenario: A second hide does not strand what the first one hid

- **WHEN** a hide runs twice with no restore in between
- **THEN** the record still names the windows left hidden
- **AND** a restore afterwards reopens them
