## ADDED Requirements

### Requirement: A rename resolves to a single queue entry

Every review scope SHALL detect renames, so a renamed file appears once in the queue rather than as a
deletion of the old path plus an addition of the new one. This holds for the staged scope, for branch
versus base, and for a commit range alike.

The entry SHALL be keyed to the **new** path and hashed against the new content, which is what the
reviewer opens and what a later edit must invalidate.

#### Scenario: A staged rename is one entry

- **WHEN** a rename is staged and the scope is Staged
- **THEN** the queue holds one entry for that file
- **AND** its path is the new path

#### Scenario: A rename between a branch and its base is one entry

- **WHEN** a file was renamed on the branch and the scope is Branch vs Base
- **THEN** the queue holds one entry for it, keyed to the new path

#### Scenario: A rename inside a commit range is one entry

- **WHEN** a file was renamed within the selected commit range
- **THEN** the queue holds one entry for it, keyed to the new path

#### Scenario: A rename with edits is still one entry

- **WHEN** a file is renamed and its contents are also changed
- **THEN** the queue holds one entry, and opening it shows the content diff

#### Scenario: A mark on a renamed file follows its content

- **WHEN** a renamed file is marked reviewed and its content is then changed
- **THEN** the mark is dropped, exactly as for any other file

### Requirement: Staged resolution reports what git reports

Re-resolving the staged scope SHALL preserve the guarantees the previous status-based resolution
provided: a root whose git command fails reports that root's error rather than taking the whole queue
down, and every ref handed to git is validated before it is passed.

#### Scenario: A failing root still reports its own error

- **WHEN** one git root cannot be read while resolving the staged scope
- **THEN** that root reports git's own message
- **AND** the other roots still contribute their files

#### Scenario: Untracked and ignored files stay out of the staged queue

- **WHEN** the working tree holds untracked and ignored files
- **AND** the staged scope is resolved
- **THEN** neither appears in the queue
