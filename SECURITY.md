# Security

## Reporting a problem

Please report security problems **privately**, not in a public issue:
open the repository's **Security** tab and choose **Report a vulnerability** (GitHub's private vulnerability
reporting). Only the maintainer sees the report. You'll get an answer there.

Please include the Saruki and Saruki Remote versions (Saruki → Settings → About Saruki), the TV or stick model, and
the steps that show the problem.

## How the phone link is protected

- Saruki Remote talks to the TV only on your home network (LAN). There is no cloud server, account or relay.
- A phone has to be paired with a 6-character code shown on the TV. Five wrong codes from one address lock that address
  out for a minute.
- Settings → Saruki Remote → New pairing code drops every paired phone at once.
- Official APKs are signed with Saruki's own key. Only an app signed with that same key can update Saruki on a TV.

## Supported versions

Only the latest release gets fixes.
