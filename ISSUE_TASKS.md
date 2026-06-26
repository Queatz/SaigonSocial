# Issue Task List

Tracking the fixes requested in the issue. Check off (`[x]`) as completed.

## Refactor
- [x] Split `SaigonApp.kt` into smaller UI files.

## Theme & labels
- [x] Update theme to forest green + sky blue.
- [x] "Refresh name & avatar" button should just say "Refresh".
- [x] Replace "Interests" with "Introduction" (profile).
- [x] Replace offline schedule with "General availability".
- [x] Replace language buttons with full name + flag emoji.

## Auth / onboarding
- [x] Switch access-token sharing/sign-in to an active invite code (short code, used to create an account).
- [x] Require setting a username and password when creating an account.
- [x] Rename "Already set a password?" section to a "Sign in" form.
- [x] Sign in with password error should read "Invalid username and password." (was "That token is invalid").
- [x] A user should be able to change their username to one that is not taken.

## Founder / members
- [x] Put "Change your password" and "Create an access token" (invite code) into a collapsed accordion.
- [x] Members list shows detailed member info + intents in both desktop and mobile layouts.
- [x] Members list shows the profile icon for each member.
- [x] Founder cannot change their own role at all.
- [x] Remove the "Seeker" role option from the member list (no UI demote-to-Seeker).
- [x] After generating an invite code, reload the members list.

## Messages / threads
- [x] Messages show the user's room-local name (not their ID).
- [x] Thread replies show the user's room-local name (not their ID).
- [x] Messages show the user profile icon.
- [x] Thread replies show the user profile icon.
