# Requirements (PRD intel)

One PRD-typed document was present in this ingest set. No competing acceptance
variants exist — it is the only PRD, with no scope overlap against another
requirement doc.

## REQ-scale-blog-series
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-13-scale-series-design.md
- description: Deliver a 9-part "Scale" blog series (2 acts, chapters 00–08)
  documenting horizontal scale-out of the payment-cancel MSA on k3s. Two
  through-lines: (1) is it safe to run many replicas (consistency, HA, deploy),
  (2) does adding replicas actually make it faster (N-replica ceiling reversal →
  payment_db root cause). Written from already-measured/corrected data, no new
  measurement. Editorial/content deliverable, not a change to the payment
  software itself.
- acceptance: (from 성공 기준) 9 chapter drafts + anchor-note expansion complete;
  every chapter has [!summary] and [!info] blocks plus crosslinks; the reversal
  (ch. 05) stands as the climax and ch. 06 honestly reunites with the Load
  fsync finding (stating per-rig bottleneck-composition differences); all
  numbers match the results document (no exaggerated or unverified claims,
  Load-series honesty bar maintained).
- scope: Scale blog series, k3s scale-out narrative, multi-pod consistency, HA
  and rolling deploy, payment_db bottleneck, Obsidian vault anchor note
  (쿠버네티스 정리), index.md registration

Out of scope (from 범위 밖): new AWS measurement/experiments (data already
exists, rig destroyed); code changes (payment.yaml strategy fix already merged
in PR #70/#71); edits to Load-series chapters (back-reference links only); new
diagram creation (reuse/link existing architecture HTML).

Note: classifier flagged this as PRD-vs-DOC ambiguous (specs/ path but no
technical-spec hallmarks; editorial domain; confidence: medium).
