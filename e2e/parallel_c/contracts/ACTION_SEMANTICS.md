# Candidate interpretation and projection rules

All DialogueIntent fields are required; null means unresolved/absent, never implicit authority. Candidate acceptance is not action admission. Unresolved quantity/source/recipient/registration label is an explicit clarification, never a guessed default. Pure validated projections and runtime authority are separate.

| Action | Candidate payload | Additional trusted admission required at J |
|---|---|---|
| CHAT / FORGET | actor, utterance; all endpoints/edits null, items empty | Authenticated visible citizen; FORGET clears only caller's own ephemeral context and does not erase A activity/audit state |
| COME / FOLLOW / STOP / STATUS | actor and utterance only | Current actor control and active root; COME captures server location, FOLLOW binds player identity; STOP cannot cancel someone else's work |
| GO_NAMED | destination storage selector, no items or storage edits | Exact current permitted storage binding; unsupported landmarks clarify |
| DELIVER | source/destination selectors and at most one candidate item/quantity | Resolve all omissions, installed item/default components, q=1..64, same world/dimension, operation-specific grants, native capacity; source citizen must be carrier |
| REGISTER_STORAGE | destination SELECTED, proposed label/optional purpose role | Fresh server selection, full explicit summary and confirmation, native/admin checks; default private policy |
| RENAME_STORAGE | source storage selector and new label | Explicit confirmation, RENAME, expected revision |
| ASSIGN_STORAGE | source storage selector, beneficiary actor selector and role | Both beneficiary control and ASSIGN; StorageRoleAssignment changes no ACL |
| SET_STORAGE_ACCESS | source storage selector and requested_access enum | Explicit readable grants summary and confirmation by trusted administrator; broad public withdrawal never inferred |

CHEST/BARREL/ANY_MVP is mandatory on every StorageSelector, including OWNER_ROLE and SELECTED. Type mismatch asks for a corrected selector, never changes the physical target behind the user's back. An arbitrary selected chest can be registered; inventory effects require its verified registry identity. 'These items' is resolved only from an explicit authorized storage slot/item selection supplied by the server UI, or a named source plus explicit item and quantity. No player inventory inspection is implied.

PlayerReplyIntent CHECK_AGAIN is a request to observe. CHOOSE_TARGET may carry an endpoint selector; other reply types have null selection. CORRECT_GOAL is a proposal requiring confirmation and A policy; old committed work is never erased. CANCEL awaits actual A acknowledgement. HELP explains the same question without changing root identity. A question cannot be resumed by another principal, old generation, expired deadline or a bare matching message string.

Assistance provenance priority is privacy/authority, unknown or stale, unsupported primitive/path, unavailable actor/facility/capacity/reservation, then provable shortage. Only the last yields a material provision request with exact native-authorized quantity and a supported continuation. Supplied items are reobserved and B revalidates; a root resumes only when A accepts. Ordinary chat has no persistent physical-world claims.

---

Documentation archive anchor: `d609e9980ca2cde8330042d2332226f50200d6b6` (historical blueprint audit only). Current C/D baseline is the separately pinned B commit; this anchor does not assert current implementation status.
