# loci product specifications

Status: **Working index for product review.**

These specifications define product and interaction behavior.
They do not define HTTP routes, storage structures, or implementation plans.

The [product requirements](../product-requirements.md) define the proposed P0 scope.
User review decisions in its changelog take precedence over older draft text.
The [interaction design guide](../interaction-design.md) defines the established shell language.
The served shell defines the current foundation.

Each specification uses these labels:

- **Confirmed requirement** records a user decision or an applied PRD review decision.
- **PRD requirement** records proposed behavior inherited from the draft PRD.
- **Proposed interaction** gives a reviewable product choice that still needs approval.
- **Open decision** gives a bounded choice, recommendation, and impact.
- **Current foundation** describes behavior in the served shell.

A specification can cover one part of a functional requirement.
That does not make the full functional requirement complete.

## Sequence

| Order | Specification | Status | Main PRD areas |
| --- | --- | --- | --- |
| 01 | [Core research](01-core-research.md) | First draft | FR-003, FR-010–012, FR-020–023, FR-030–031, FR-040–041, FR-060–061, FR-070–071, FR-073–075, FR-080 |
| 02 | Product foundation and navigation | Queued P0 specification | FR-001–005 |
| 02a | Delivery and upgrade | Conditional specification after D3 review | FR-006 |
| 03 | Space and Item lifecycle | Queued P0 specification | Full FR-010–012 and FR-020–021 lifecycle behavior |
| 04 | Record, Facts, Subjects, and Search | Queued P0 specification | FR-030–031 and FR-040–050 |
| 05 | Table input and existing Source state | Queued P0 specification | Full FR-060–061 behavior |
| 06 | Actions, Flows, and services | Queued P0 specification | Full FR-070–071 and FR-073–075 behavior |
| 06a | Human and result judgment | Deferred outside P0 | FR-072 |
| 07 | Provenance and history | Queued P0 specification | Full FR-080 behavior |
| 08 | Backup and restore | Queued P0 specification | FR-090–091 |
| 09 | Product quality and release checks | Queued P0 specification | QR-001–004 and cross-domain release checks |

Rows marked **Queued P0 specification** still need a detailed specification.
It does not mean that the area is outside P0.

Document import, content export, source administration, and human result judgment remain deferred.
Delivery choice D3 remains open in the PRD.

## Current coverage

| PRD id | Coverage in specification 01 | Remaining detailed work |
| --- | --- | --- |
| FR-010 | Partial | Full create, rename, and remove behavior |
| FR-011 | Core purpose and Action context | Purpose editing and removal details |
| FR-012 | Core prose, references, and relation behavior | Full ordering and relationship management |
| FR-020 | Shared Item references and result Items | Archive, removal, and global delete |
| FR-021 | Core Table and Report behavior | Full Document behavior and all Item states |
| FR-022 | Table Views and independent reference choices | Text Views and the full View catalog |
| FR-023 | Transformation creation and placement | Other Item transformations and configuration detail |
| FR-030 | Record effects in the core journey | Full Record presentation and grouped changes |
| FR-031 | Action output Undo and past result inspection | General Undo ordering and the complete past-view surface |
| FR-040–041 | Used and missing Facts in Action provenance | Facts page, controls, Subjects, and Fact lifecycle |
| FR-050 | LEAP entry behavior for the journey | Complete Search results, states, and coverage |
| FR-060 | User-supplied table data in the journey | Full input limits and all supported product paths |
| FR-061 | Historical and live-byte Source limits | Full Source state and credential behavior |
| FR-070 | Core Action context and approval boundaries | Other Action types and all context sources |
| FR-071 | Status, Stop, output retention, and restart | Full Flow presentation and every execution path |
| FR-072 | Explicitly deferred | Future human and result judgment design |
| FR-073 | Failure, defined retry, and exact table answers | Retry definitions for each supported Action type |
| FR-074 | Saved and one-off functions | Basic creation, finding, and person or agent reuse |
| FR-075 | Service visibility in the journey | Complete configured-service failure behavior |
| FR-080 | Core result, Action, input, and version paths | Fact entry points and the full provenance fixture |
| FR-090–091 | Not covered | Backup and restore specification |

This map records specification coverage.
It does not report implementation or release completion.
