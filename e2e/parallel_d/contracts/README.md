# Frozen C/D shared proposal contract 1.0.0

This package was authored once by the design agent before C00/D00. Its exact content is immutable after delivery. It defines a proposed seam, not a runtime capability, A journal migration or proof of Minecraft effects.

Use contract.schema.json and the field-by-field dictionary as the wire authority. The Python checker implements exactly the schema subset present here and semantic/contextual fixture checks. It is not a general-purpose JSON Schema implementation. C/D must also map the schema to their own strict Java codecs and run every shared fixture through those codecs.

Canonicalization: UTF-8 without BOM, Unicode NFC strings, sorted keys, compact JSON separators, no floats/NaN/infinity/duplicate object keys, integers within +/-9007199254740991, exactly one LF. Array order is preserved. Semantic sets are normalized by the producer: ACL permissions and roles sorted uniquely, physical members sorted by x/y/z/incarnation, duplicate physical identities rejected. Synthetic fixture IDs never become trusted authority.

Candidate DialogueIntent and PlayerReplyIntent carry suggestions only. Validated structures are private server-to-server projection contracts. An external caller presenting byte-valid TrustedScope, ALLOW, effect ID or save proof still has no authority. J adapters construct these fields from authenticated owner APIs and never deserialize trusted projections directly from chat.

C copies this directory unchanged to e2e/parallel_c/contracts/ and docs/implementation/parallel-c/contracts/. D copies it unchanged to its corresponding parallel_d/parallel-d paths. No shared Java source is required. Each side has independently named DTO packages and verifies the same schema, fixture and manifest hashes. The authoritative manifest's SHA-256 is listed in the package freeze record outside this directory, avoiding self-reference.

Any later amendment gets a new contract version, canonical package, migration matrix and before/after hashes. Both branches stop incompatible advancement until the same amendment is explicitly installed and all parity tests pass. Never silently regenerate fixtures or import peer code. Unknown versions fail closed. A/B existing schema versions and admitted artifacts remain unchanged.

Verify using: python3 contract/verify_contract.py --contract contract

This verification exercises synthetic contract examples only. Native identity, permissions, save acknowledgement, actual transfers and original-owner settlement are tests for D/J at their specified evidence tiers.

---

Documentation archive anchor: `d609e9980ca2cde8330042d2332226f50200d6b6` (historical blueprint audit only). Current C/D baseline is the separately pinned B commit; this anchor does not assert current implementation status.
