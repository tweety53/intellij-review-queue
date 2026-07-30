## ADDED Requirements

### Requirement: A pass hides in the client session the reviewer is looking at

A guided review pass SHALL determine which tool windows are visible, and hide them, within the
client session that drives the visible user interface, so the hide reaches the layout on screen
rather than a layout nobody is looking at.

Exactly one session SHALL be chosen, in the order frontend, then controlling remote client, then
local. A guest session SHALL never be chosen and SHALL never be hidden in, because a guest is
another person whose layout this plugin does not manage.

Determining visibility, recording the hidden set, and hiding SHALL all happen within that one
session, so the record describes the same layout it mutates.

An installation with only a local session SHALL behave exactly as it did before this requirement
existed.

#### Scenario: The sweep runs in the frontend session

- **WHEN** a pass starts and the project has both a local session and a frontend session
- **THEN** the windows visible in the frontend session are the ones hidden and recorded
- **AND** the sweep is performed as that session, not as the local one

#### Scenario: A controlling remote client is chosen when there is no frontend

- **WHEN** a pass starts and the project has a local session and a controlling remote client
- **THEN** the controlling client's session is the one swept

#### Scenario: A plain local installation is unaffected

- **WHEN** a pass starts and the local session is the only one
- **THEN** the local session is swept, and the hidden set and record are what they were before

#### Scenario: A guest's layout is never touched

- **WHEN** a pass starts and a guest session is present alongside the reviewer's session
- **THEN** nothing is enumerated, hidden or recorded in the guest session

### Requirement: Ending a pass restores into every session the reviewer could be looking at

Restoring SHALL show each recorded window in every non-guest client session in turn, rather than in
one chosen session, because the session available when a pass ends — in particular at project
startup after the IDE was quit mid-pass — need not be the one that was swept when it began.

This SHALL NOT widen what is reopened: restoring only ever shows ids already on the record, so it
can never open a window the pass did not hide.

A recorded id SHALL be forgotten once it was **reopened** — that is, resolved to a registered tool
window *and* shown without error — in at least one session **and** showing it failed in none. It
SHALL be kept otherwise.

Reopening a window in one session SHALL NOT excuse showing it failing in another. The same id
addresses a different window in each session, so one may reopen cleanly while another stays hidden —
and the session that failed may well be the one the reviewer is looking at. A session in which the
id resolves to no registered tool window SHALL NOT count as a failure: nothing is hidden there for a
restore to be owed to.

Resolving alone SHALL NOT be enough. A window may resolve and still fail to reopen, because the
sweep covers arbitrary third-party tool windows and a plugin that disposes its content while a pass
is running makes showing it throw. Such a window is still hidden and this plugin still hid it, so
it is owed a restore exactly as an id that resolved nowhere is. Both causes SHALL be given the same
answer **about the record**: the id stays on it, and one failing window SHALL NOT stop the remaining
ids or the remaining sessions being restored.

**They are reported differently, because only one of them is an error.** A window that resolved and
then threw SHALL be reported at once, naming the id and the session: something exists there, this
plugin hid it, and showing it failed. A session in which the id resolves to nothing SHALL NOT be
reported as a failure in that session — per the paragraph above it is not one — so an id that resolves
nowhere at all is surfaced by the next sweep carrying it forward rather than by restoring.

An id kept this way SHALL survive the next pass's hide sweep. That sweep records what it found
visible, and a window that was never reopened is not visible — so the sweep SHALL record the union
of what it hid and what is still owed a restore, never overwriting the record with what it hid
alone. Carrying an id forward SHALL be reported, so an operator can tell a record entry meaning
"this pass hid it" from one meaning "an earlier pass hid it and nothing has reopened it since".

Keeping an id SHALL NOT be able to stop the plugin hiding: a pass reclaims a leftover record and
then hides regardless of what the reclaim achieved.

#### Scenario: A window hidden in one session is reopened in the session now present

- **WHEN** a pass hid windows in the frontend session
- **AND** the pass ends
- **THEN** each recorded window is shown in every non-guest session
- **AND** the record is cleared of what was reopened

#### Scenario: An id that resolves nowhere stays on the record

- **WHEN** restoring runs and a recorded id resolves to no registered tool window in any non-guest
  session
- **THEN** that id stays on the record
- **AND** a later restore reopens it

#### Scenario: An id that resolves but fails to reopen stays on the record

- **WHEN** restoring runs and a recorded id resolves to a registered tool window in every non-guest
  session, but showing it throws in each
- **THEN** that id stays on the record
- **AND** the remaining recorded ids are still shown, in every non-guest session
- **AND** the failure is reported naming the id and the session

#### Scenario: An id reopened in one session but failing in another stays on the record

- **WHEN** restoring runs and a recorded id resolves in two non-guest sessions
- **AND** showing it succeeds in one and throws in the other
- **THEN** that id stays on the record, because it is still hidden in the session that failed
- **AND** a later restore retries it there

#### Scenario: The next pass keeps an id it could not reopen rather than laundering it out

- **WHEN** a pass starts while the record names a window that cannot be reopened
- **AND** the reclaim before the sweep fails to reopen it
- **THEN** that id is still on the record after the sweep, alongside what the sweep hid
- **AND** carrying it forward is reported
- **AND** the sweep still hides every window it found visible

#### Scenario: A guest session is not restored into

- **WHEN** restoring runs and a guest session is present
- **THEN** no window is shown in the guest session

### Requirement: A window re-shown during a pass is reported where the platform announces it

After a pass has hidden the visible tool windows, the plugin SHALL report the first time any window
it hid becomes visible again while the pass is still running, naming the window and the session the
pass swept. A window SHALL be reported at most once per pass.

This exists because checking visibility immediately after hiding proves only that hiding took effect
at that instant; it cannot distinguish a window re-shown afterwards from hiding never having worked.
The report SHALL therefore be driven by the window actually being shown, not by re-checking at a
chosen moment.

The plugin's own restoring SHALL NOT be reported, since reopening the recorded windows is what
ending a pass means.

**The report is bounded by what the platform announces, and this bound was measured rather than
assumed.** Two limits follow, disclosed here rather than left for a later reader to discover:

- **The reported session is the session the pass swept, not the session the window was shown in.**
  There is no per-session message bus to subscribe to — `ClientSession.messageBus` is deprecated at
  ERROR level with the message *"sessions don't have their own message bus"*, and the only
  implementation throws. The subscription is therefore on the project's bus and discriminates by
  window id alone. In a multi-session configuration, a window re-shown in some *other* session is
  attributed to the swept one.
- **Under a split / remote-dev IDE the report may not fire at all.** A re-show in a frontend session
  is routed by the backend's `ToolWindowManager` directly to its own per-client state push, without
  reaching the platform code that publishes a tool-window-shown event, so no
  `ToolWindowManagerListener` can observe it. Closing this would require referencing the split
  module's internals, which this change's constraints forbid.

Where the platform publishes no event, the plugin SHALL stay silent rather than report a re-show it
cannot observe. A diagnostic that reports nothing is a known gap; one that guessed would raise a
false alarm about the very behaviour it exists to check.

#### Scenario: Something reopens a hidden window mid-pass

- **WHEN** a pass has hidden the visible tool windows
- **AND** one of them is shown again before the pass ends, by a path the platform announces
- **THEN** a warning names that window and the session the pass swept

#### Scenario: A window reopened several times in one pass is reported once

- **WHEN** a pass has hidden the visible tool windows
- **AND** one of them is shown, hidden and shown again before the pass ends
- **THEN** exactly one warning is produced for that window

#### Scenario: Ending the pass is not reported as a re-show

- **WHEN** a pass ends and the recorded windows are reopened
- **THEN** no re-show is reported

#### Scenario: A second pass reports independently

- **WHEN** a pass ends and another pass starts
- **THEN** the report covers the windows the new pass hid
