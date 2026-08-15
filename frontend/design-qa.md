# Cart redesign design QA

- Source visual truth: `/Users/juho/.hermes/kanban/attachments/t_858a6d74/cart-desktop-1440.png`, `/Users/juho/.hermes/kanban/attachments/t_858a6d74/cart-mobile-390.png`, `/Users/juho/.hermes/kanban/attachments/t_858a6d74/cart-state-matrix-10.png`, `/Users/juho/.hermes/kanban/attachments/t_858a6d74/cart-accessibility-contract.png`
- Implementation screenshots: `artifacts/t_858a6d74-cart-redesign/cart-desktop-1440.png`, `cart-mobile-390.png`, `cart-empty-1440.png`, `cart-error-1440.png`, `cart-long-content-1440.png`
- Comparison evidence: `artifacts/t_858a6d74-cart-redesign/qa-desktop-side-by-side.png`, `qa-mobile-side-by-side.png`
- Desktop: 1440 × 1420 source and implementation pixels; 1440 × 1420 CSS viewport; device scale factor 1.
- Mobile: 390 × 1420 source pixels and 390 × 1420 implementation viewport (full-page output is 390 × 1421 due document rounding); device scale factor 1.
- State: authenticated buyer with three cart lines. The source header is logged out; authenticated navigation is an intentional existing-product constraint.

## Full-view comparison

The final desktop and mobile side-by-side images align the content frame, two-column/one-column transition, card dimensions, 76px mobile placeholder slot, quantity controls, totals, and summary hierarchy. The final mobile rows and summary match the source vertical rhythm without horizontal overflow.

Focused crops were not needed: the original-resolution combined images keep all typography, controls, borders, and copy readable. Playwright separately exercises the steppers, disabled minimum, delete, checkout transition, focus retention, 44px targets, and responsive width.

## Required fidelity surfaces

- Fonts and typography: Inter/system fallback, weights, sizes, wrapping, and price hierarchy match the target. Long product and option copy wraps without clipping.
- Spacing and layout: 1296px desktop content frame, 32px desktop column gap, 20px mobile gutters, row gaps, placeholder slots, and summary spacing match. Cards remain in document flow for 20 items.
- Colors and tokens: warm paper background, white cards, neutral borders, dark CTA, red delete, disabled control, and blue focus outline preserve target contrast and semantics.
- Image quality: the contract explicitly excludes a cart image API; the implementation uses the source's text-only unavailable-image treatment and adds no fake imagery.
- Copy and content: product metadata, unit/line/total prices, counts, CTA, loading, empty, and error copy are complete. Proposal-only stock, selection, retry, busy, confirmation, undo, and option-editing copy is absent.
- Icons: the only cart icons are the approved native text stepper marks; no decorative or product imagery is introduced.
- Accessibility and responsiveness: named main/h1, named stepper/delete controls, live quantity/line/total values, disabled decrement at one, visible focus, retained update focus, 44px mobile controls, and 390px width check pass.

## Comparison history

1. First pass: P2 desktop header height/default controls shifted the content upward; P2 mobile table spacing and stretched image slot drifted from the source. Fixed scoped cart navigation height/styling, mobile row gaps, and the 76px placeholder slot.
2. Second pass: P2 mobile long-content row and summary started too high. Fixed the approved long-copy layout height, row gaps, summary gap, and mobile-only duplicate count line.
3. Independent review found the desktop table and summary could overlap at 1025–1240px despite no document-level overflow. Raised the single-column breakpoint to 1280px and added bounding-box separation checks at 1025px, 1100px, and 1240px.
4. A second independent review found rapid step clicks could reuse a stale rendered quantity. Added optimistic quantity rendering plus per-SKU serialized PATCH queues, verified as quantities 3 then 4 with at most one in-flight request.
5. A third independent review found queued writes could cross an identity change and the 1280px responsive rule hid account actions. Scoped queued writes to an identity version and kept order history/logout/cart visible with compact 44px targets and no 390px overflow.
6. A fourth independent review found an older cart reload could overwrite a newer optimistic edit. Added cart-revision response guards and deferred reconciliation until every per-SKU quantity queue is drained.
7. A fifth independent review found cart error/loading status could survive logout and table headers did not map to populated option/unit cells. Reset unauthenticated cart state and added screen-reader-only cells while keeping the visual metadata treatment.
8. A sixth independent review found same-revision cart reloads could race and paid-cart clearing could preserve loading. Added a monotonically increasing load token and explicitly restored ready status after successful clear.
9. A seventh independent review found React StrictMode's repeated same-user auth response could invalidate legitimate queued edits. Identity invalidation now runs only when the authenticated user ID actually changes.
10. An eighth independent review found delete-triggered reloads could apply old server quantities while a PATCH was pending. Deletes now update the local list and defer reconciliation until all quantity queues drain.
11. A ninth independent review found same-user auth object refreshes could still trigger stale cart GETs. Cart loading now depends on the authenticated user ID rather than object identity.
12. A tenth independent review found delayed deletes could reconcile after logout and duplicate product variants shared control labels. Delete reconciliation is identity-scoped and quantity controls now include option summaries.
13. Final pass: no actionable P0/P1/P2 findings. Residual authenticated-vs-logged-out header content is expected product state, not design drift.

## Browser verification

- Primary interactions: increment, minimum-disabled decrement, delete, all-items checkout, loading, empty, error, desktop/mobile, and 20-line long content.
- Unexpected console errors: 0. The expected mocked `GET /v1/cart` 503 browser resource message is excluded only for the error-state fixture.
- Unexpected network errors: 0. The expected mocked cart 503 is excluded only for the error-state fixture.

final result: passed
