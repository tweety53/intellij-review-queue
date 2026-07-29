## ADDED Requirements

### Requirement: No tool window

The plugin SHALL NOT register a tool window. No part of the review workflow may depend on a tool
window being present, visible, or registered.

#### Scenario: Plugin exposes no tool window

- **WHEN** the plugin is loaded in an IDE
- **THEN** no tool window with id `Review Queue` is registered
- **AND** the `Review Queue` notification group remains registered, being unrelated to the window

#### Scenario: Plugin verifier reports no deprecated or experimental API usage

- **WHEN** `verifyPlugin` runs against the configured IDE build
- **THEN** it reports zero deprecated-API usages and zero experimental-API usages
- **AND** the compatibility result remains Compatible

### Requirement: Out-of-session commands live in a Tools menu group

The plugin SHALL provide a `Review Queue` group under the IDE's Tools menu containing Scope, Start
Review, Show File List, Refresh and Reset All.

#### Scenario: Menu group is reachable

- **WHEN** the user opens the Tools menu
- **THEN** a `Review Queue` submenu is present
- **AND** it lists Scope, Start Review, Show File List, Refresh and Reset All

#### Scenario: Nested groups render as submenus

- **WHEN** the menu group and its nested Scope group are registered
- **THEN** both declare `popup="true"` and a display text
- **AND** neither group's children are inlined flat into its parent menu

#### Scenario: Merely opening the menu does not start background work

- **WHEN** the user opens the Tools menu without invoking any command
- **THEN** no `ReviewQueueService` instance is created
- **AND** no VCS or git repository listeners are subscribed

### Requirement: Start Review carries a keyboard shortcut on macOS

Start Review SHALL be bound to `Cmd+Option+Shift+R` on the macOS keymap, joining the plugin's
existing `Cmd+Option+Shift` cluster.

#### Scenario: Shortcut is bound on macOS

- **WHEN** the macOS keymap is queried for the Start Review action
- **THEN** `meta alt shift R` is among its shortcuts

#### Scenario: No binding on other platforms

- **WHEN** the default keymap is queried for the Start Review action
- **THEN** it has no keyboard shortcut
- **AND** Start Review remains reachable from the Tools menu group and Find Action

#### Scenario: The known overlap is pinned, not hidden

- **WHEN** the macOS keymap is queried for the actions bound to `meta alt shift R`
- **THEN** the result is exactly the Start Review action together with the three platform actions that
  already hold that chord — `ForceRefresh`, `DatabaseView.ForceRefresh` and `ReloadScriptConfiguration`
- **AND** a change to that set in either direction fails the test rather than passing silently

Corrected during implementation: this scenario originally named `ForceRefresh` alone, which is what
the keymap file declares. Querying the live keymap found four ids, because `DatabaseView.ForceRefresh`
borrows the chord via `use-shortcut-of`, and `ReloadScriptConfiguration` declares
`ctrl alt shift R` on `$default`, which `MacOSDefaultKeymap` translates to `meta alt shift R`. The
original wording would have failed against a correct implementation.

### Requirement: Every action retains a registry home

Actions previously declared inside the deleted toolbar group SHALL remain registered, so that they
stay reachable from Find Action and from user keymap customisation.

#### Scenario: End Review survives the toolbar group's deletion

- **WHEN** the action registry is queried after the `ReviewQueue.Toolbar` group is removed
- **THEN** `ReviewQueue.EndReview` resolves to a registered action
- **AND** it is declared top-level with no `add-to-group`

#### Scenario: The scope group takes a fresh id

- **WHEN** the nested scope group is registered
- **THEN** its id is `ReviewQueue.ScopeMenu`, not the id previously used by the `ScopeSelector` action
- **AND** existing keymap customisation referring to the old id cannot silently bind to a group

### Requirement: The file list renders repository-controlled text as text

The file-list popup's rows SHALL show the repo-relative path as literal text, because the row renderer
is a Swing label and a Swing label interprets its content as HTML whenever that content begins with
`<html>`.

#### Scenario: A file name that looks like markup is not interpreted

- **WHEN** a tracked file's path begins with `<html>` or contains a tag
- **THEN** the row shows those characters as text
- **AND** no request is made to any host named in the path — merely drawing the row must not reach the
  network

#### Scenario: An ordinary path is shown unchanged

- **WHEN** a row shows a path containing `&`, or any other character the escaping touches
- **THEN** the reviewer reads the path exactly as it is on disk, not as entity references

### Requirement: Enablement depends on git roots, not queue contents

Because the queue is cold until a gesture asks for it, action enablement SHALL be determined by
whether the project has any git root, not by whether the queue holds items.

#### Scenario: Start Review is enabled with a git root and no active pass

- **WHEN** the project has at least one git root and no review session is active
- **THEN** Start Review is enabled, regardless of how many items the queue currently holds

#### Scenario: Start Review is disabled during a pass

- **WHEN** a review session is active
- **THEN** Start Review is disabled

#### Scenario: Show File List no longer requires a pass or a diff context

- **WHEN** the project has at least one git root
- **THEN** Show File List is enabled whether or not a session is active
- **AND** whether or not a diff viewer has focus

#### Scenario: Enablement recovers after VCS mappings initialise

- **WHEN** the project has just opened and git repositories are not yet discovered
- **THEN** Start Review is disabled
- **AND** it becomes enabled on a later action update without requiring any event
