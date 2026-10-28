# Field-by-field shared contract dictionary

All fields are required unless the schema explicitly permits omission. Nullable fields require an explicit JSON null. All objects reject unknown properties. References below expand through contract.schema.json; UUIDs are lowercase 36-character strings. Transport counters use bounded exact JSON integers, not floating point. Existing A signed-64-bit values outside the transport range must be refused by J's projection with an explicit range error, never truncated.

## VersionRevisionGeneration

| Field | Exact type/range or referenced definition |
|---|---|
| schema_version | {"const": 1} |
| revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |

## TrustedScope

| Field | Exact type/range or referenced definition |
|---|---|
| world_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| domain_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| principal_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |

## CitizenSelector

Discriminated variants:

- {"additionalProperties": false, "properties": {"kind": {"const": "ADDRESSED"}}, "required": ["kind"], "type": "object"}
- {"additionalProperties": false, "properties": {"kind": {"const": "NAMED"}, "label": {"maxLength": 64, "minLength": 1, "pattern": "^[^\\u0000-\\u001f\\u007f]+$", "type": "string"}}, "required": ["kind", "label"], "type": "object"}
- {"additionalProperties": false, "properties": {"citizen_id": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, "kind": {"const": "EXPLICIT_ID"}}, "required": ["kind", "citizen_id"], "type": "object"}

## StorageSelector

Discriminated variants:

- {"additionalProperties": false, "properties": {"container_type": {"enum": ["CHEST", "BARREL", "ANY_MVP"], "type": "string"}, "kind": {"const": "NAMED"}, "label": {"maxLength": 64, "minLength": 1, "pattern": "^[^\\u0000-\\u001f\\u007f]+$", "type": "string"}}, "required": ["kind", "label", "container_type"], "type": "object"}
- {"additionalProperties": false, "properties": {"container_type": {"enum": ["CHEST", "BARREL", "ANY_MVP"], "type": "string"}, "kind": {"const": "OWNER_ROLE"}, "owner": {"$ref": "#/definitions/CitizenSelector"}, "role": {"enum": ["DEFAULT", "FOOD", "MATERIALS", "OUTPUT"], "type": "string"}}, "required": ["kind", "owner", "role", "container_type"], "type": "object"}
- {"additionalProperties": false, "properties": {"anchor": {"enum": ["CALLER", "ACTOR"], "type": "string"}, "container_type": {"enum": ["CHEST", "BARREL", "ANY_MVP"], "type": "string"}, "kind": {"const": "NEAREST_PUBLIC"}}, "required": ["kind", "container_type", "anchor"], "type": "object"}
- {"additionalProperties": false, "properties": {"container_type": {"enum": ["CHEST", "BARREL", "ANY_MVP"], "type": "string"}, "kind": {"const": "SELECTED"}, "selection_id": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}}, "required": ["kind", "selection_id", "container_type"], "type": "object"}
- {"additionalProperties": false, "properties": {"container_type": {"enum": ["CHEST", "BARREL", "ANY_MVP"], "type": "string"}, "kind": {"const": "DEFAULT"}, "owner": {"$ref": "#/definitions/CitizenSelector"}}, "required": ["kind", "owner", "container_type"], "type": "object"}

## EndpointSelector

Discriminated variants:

- {"additionalProperties": false, "properties": {"kind": {"const": "STORAGE"}, "selector": {"$ref": "#/definitions/StorageSelector"}}, "required": ["kind", "selector"], "type": "object"}
- {"additionalProperties": false, "properties": {"kind": {"const": "CITIZEN"}, "selector": {"$ref": "#/definitions/CitizenSelector"}}, "required": ["kind", "selector"], "type": "object"}

## CandidateItem

| Field | Exact type/range or referenced definition |
|---|---|
| item_text | {"maxLength": 96, "minLength": 1, "type": "string"} |
| quantity | {"maximum": 64, "minimum": 1, "type": "integer"} |

## DialogueIntent

| Field | Exact type/range or referenced definition |
|---|---|
| action | {"enum": ["CHAT", "FORGET", "COME", "FOLLOW", "STOP", "GO_NAMED", "DELIVER", "REGISTER_STORAGE", "RENAME_STORAGE", "ASSIGN_STORAGE", "SET_STORAGE_ACCESS", "STATUS"], "type": "string"} |
| actor | {"$ref": "#/definitions/CitizenSelector"} |
| utterance | {"maxLength": 1024, "minLength": 1, "type": "string"} |
| source | {"oneOf": [{"$ref": "#/definitions/EndpointSelector"}, {"type": "null"}]} |
| destination | {"oneOf": [{"$ref": "#/definitions/EndpointSelector"}, {"type": "null"}]} |
| items | {"items": {"$ref": "#/definitions/CandidateItem"}, "maxItems": 1, "minItems": 0, "type": "array"} |
| label | {"oneOf": [{"maxLength": 64, "minLength": 1, "pattern": "^[^\\u0000-\\u001f\\u007f]+$", "type": "string"}, {"type": "null"}]} |
| role | {"oneOf": [{"enum": ["DEFAULT", "FOOD", "MATERIALS", "OUTPUT"], "type": "string"}, {"type": "null"}]} |
| requested_access | {"oneOf": [{"enum": ["PRIVATE", "PUBLIC_DEPOSIT", "PUBLIC_INSPECT_DEPOSIT", "PUBLIC_INSPECT_DEPOSIT_WITHDRAW"], "type": "string"}, {"type": "null"}]} |

## PlayerReplyIntent

| Field | Exact type/range or referenced definition |
|---|---|
| question_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| dialogue_revision | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| reply | {"enum": ["CHECK_AGAIN", "CHOOSE_TARGET", "CORRECT_GOAL", "CANCEL", "HELP"], "type": "string"} |
| text | {"maxLength": 1024, "minLength": 1, "type": "string"} |
| selection | {"oneOf": [{"$ref": "#/definitions/EndpointSelector"}, {"type": "null"}]} |

## ActivityRef

| Field | Exact type/range or referenced definition |
|---|---|
| scope | {"$ref": "#/definitions/TrustedScope"} |
| root_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| original_owner_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| attempt_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| responsible_citizen_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| execution_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| root_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| original_budget_digest | {"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"} |
| absolute_deadline_ms | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |

## ItemKey

| Field | Exact type/range or referenced definition |
|---|---|
| registry_id | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| component_policy | {"const": "DEFAULT_COMPONENTS_ONLY"} |
| component_fingerprint | {"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"} |

## MaterialRequirement

| Field | Exact type/range or referenced definition |
|---|---|
| item | {"$ref": "#/definitions/ItemKey"} |
| missing | {"maximum": 1000000, "minimum": 1, "type": "integer"} |

## BlockerEvidence

| Field | Exact type/range or referenced definition |
|---|---|
| code | {"enum": ["INSUFFICIENT_MATERIAL", "NO_SUPPORTED_PRODUCTION_PATH", "UNSUPPORTED_EFFECT", "UNKNOWN_WORLD_STATE", "UNAUTHORIZED", "DESTINATION_FULL", "TARGET_UNAVAILABLE", "AMBIGUOUS_TARGET", "RESOURCE_RESERVED", "ACTOR_UNAVAILABLE", "BUDGET_OR_DEADLINE_EXCEEDED", "STALE_REFERENCE", "INCOMPATIBLE_SCHEMA", "SAME_ENDPOINT", "DEPENDENCY_NOT_QUALIFIED"], "type": "string"} |
| source | {"enum": ["A_RUNTIME", "B_PLANNER", "D_RESOLVER"], "type": "string"} |
| activity | {"oneOf": [{"$ref": "#/definitions/ActivityRef"}, {"type": "null"}]} |
| evidence_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| observed_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| world_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| visibility | {"enum": ["AUTHORIZED_FACTS", "REDACTED"], "type": "string"} |
| knowledge | {"enum": ["KNOWN", "UNKNOWN"], "type": "string"} |
| continuation | {"enum": ["FEASIBLE_AFTER_ASSISTANCE", "UNSUPPORTED", "UNKNOWN"], "type": "string"} |
| requirements | {"items": {"$ref": "#/definitions/MaterialRequirement"}, "maxItems": 16, "minItems": 0, "type": "array"} |
| storage_id | {"oneOf": [{"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, {"type": "null"}]} |

## AssistanceRequest

| Field | Exact type/range or referenced definition |
|---|---|
| question_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| dialogue_revision | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| activity | {"$ref": "#/definitions/ActivityRef"} |
| blocker | {"$ref": "#/definitions/BlockerEvidence"} |
| expires_ms | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| allowed_replies | {"items": {"enum": ["CHECK_AGAIN", "CHOOSE_TARGET", "CORRECT_GOAL", "CANCEL", "HELP"], "type": "string"}, "maxItems": 5, "minItems": 1, "type": "array"} |

## OwnerRef

Discriminated variants:

- {"additionalProperties": false, "properties": {"id": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, "kind": {"const": "PLAYER"}}, "required": ["kind", "id"], "type": "object"}
- {"additionalProperties": false, "properties": {"id": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, "kind": {"const": "CITIZEN"}}, "required": ["kind", "id"], "type": "object"}
- {"additionalProperties": false, "properties": {"id": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, "kind": {"const": "ORGANIZATION"}}, "required": ["kind", "id"], "type": "object"}
- {"additionalProperties": false, "properties": {"kind": {"const": "PUBLIC"}}, "required": ["kind"], "type": "object"}

## AclGrant

| Field | Exact type/range or referenced definition |
|---|---|
| subject | {"$ref": "#/definitions/OwnerRef"} |
| permissions | {"items": {"enum": ["INSPECT", "DEPOSIT", "WITHDRAW", "ASSIGN", "RENAME", "GRANT", "REVOKE"], "type": "string"}, "maxItems": 7, "minItems": 1, "type": "array"} |

## StorageRecord

| Field | Exact type/range or referenced definition |
|---|---|
| storage_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| world_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| label | {"maxLength": 64, "minLength": 1, "pattern": "^[^\\u0000-\\u001f\\u007f]+$", "type": "string"} |
| owner | {"$ref": "#/definitions/OwnerRef"} |
| roles | {"items": {"enum": ["DEFAULT", "FOOD", "MATERIALS", "OUTPUT"], "type": "string"}, "maxItems": 4, "minItems": 0, "type": "array"} |
| acl | {"items": {"$ref": "#/definitions/AclGrant"}, "maxItems": 32, "minItems": 0, "type": "array"} |
| stamp | {"$ref": "#/definitions/VersionRevisionGeneration"} |
| acl_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| state | {"enum": ["PENDING_BIND", "ACTIVE", "UNVERIFIED", "TOPOLOGY_CHANGED", "TOMBSTONED"], "type": "string"} |
| adapter_id | {"enum": ["minecraft:chest_single/v1", "minecraft:chest_double/v1", "minecraft:barrel/v1"], "type": "string"} |

## StorageRoleAssignment

| Field | Exact type/range or referenced definition |
|---|---|
| world_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| beneficiary | {"$ref": "#/definitions/OwnerRef"} |
| role | {"enum": ["DEFAULT", "FOOD", "MATERIALS", "OUTPUT"], "type": "string"} |
| storage_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| storage_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |

## BlockMember

| Field | Exact type/range or referenced definition |
|---|---|
| x | {"maximum": 29999984, "minimum": -29999984, "type": "integer"} |
| y | {"maximum": 2047, "minimum": -2048, "type": "integer"} |
| z | {"maximum": 29999984, "minimum": -29999984, "type": "integer"} |
| incarnation_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| marker_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |

## PhysicalStorageBinding

| Field | Exact type/range or referenced definition |
|---|---|
| storage_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| world_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| dimension | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| members | {"items": {"$ref": "#/definitions/BlockMember"}, "maxItems": 2, "minItems": 1, "type": "array"} |
| facing | {"enum": ["NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN"], "type": "string"} |
| topology_sha256 | {"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"} |
| binding_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| lifecycle_epoch | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| observed_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| status | {"enum": ["VERIFIED_LIVE", "UNKNOWN", "INVALIDATED"], "type": "string"} |
| adapter_id | {"enum": ["minecraft:chest_single/v1", "minecraft:chest_double/v1", "minecraft:barrel/v1"], "type": "string"} |

## StorageAccessDecision

| Field | Exact type/range or referenced definition |
|---|---|
| scope | {"$ref": "#/definitions/TrustedScope"} |
| storage_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| operation | {"enum": ["INSPECT", "DEPOSIT", "WITHDRAW", "ASSIGN", "RENAME", "GRANT", "REVOKE"], "type": "string"} |
| decision | {"enum": ["ALLOW", "DENY", "UNKNOWN"], "type": "string"} |
| registry_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| acl_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| binding_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| membership_revision | {"oneOf": [{"maximum": 9007199254740991, "minimum": 0, "type": "integer"}, {"type": "null"}]} |
| evaluated_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| valid_through_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| reason | {"oneOf": [{"enum": ["INSUFFICIENT_MATERIAL", "NO_SUPPORTED_PRODUCTION_PATH", "UNSUPPORTED_EFFECT", "UNKNOWN_WORLD_STATE", "UNAUTHORIZED", "DESTINATION_FULL", "TARGET_UNAVAILABLE", "AMBIGUOUS_TARGET", "RESOURCE_RESERVED", "ACTOR_UNAVAILABLE", "BUDGET_OR_DEADLINE_EXCEEDED", "STALE_REFERENCE", "INCOMPATIBLE_SCHEMA", "SAME_ENDPOINT", "DEPENDENCY_NOT_QUALIFIED"], "type": "string"}, {"type": "null"}]} |

## CitizenEndpoint

| Field | Exact type/range or referenced definition |
|---|---|
| world_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| dimension | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| citizen_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| entity_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| citizen_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| policy_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| inventory_revision | {"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"} |
| observed_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |

## ResolvedEndpoint

Discriminated variants:

- {"additionalProperties": false, "properties": {"access": {"$ref": "#/definitions/StorageAccessDecision"}, "binding": {"$ref": "#/definitions/PhysicalStorageBinding"}, "kind": {"const": "STORAGE"}}, "required": ["kind", "binding", "access"], "type": "object"}
- {"additionalProperties": false, "properties": {"access_decision": {"enum": ["ALLOW", "DENY", "UNKNOWN"], "type": "string"}, "citizen": {"$ref": "#/definitions/CitizenEndpoint"}, "kind": {"const": "CITIZEN"}, "permission": {"enum": ["INSPECT", "DEPOSIT", "WITHDRAW"], "type": "string"}, "scope": {"$ref": "#/definitions/TrustedScope"}}, "required": ["kind", "citizen", "scope", "access_decision", "permission"], "type": "object"}

## SelectionMember

| Field | Exact type/range or referenced definition |
|---|---|
| x | {"maximum": 29999984, "minimum": -29999984, "type": "integer"} |
| y | {"maximum": 2047, "minimum": -2048, "type": "integer"} |
| z | {"maximum": 29999984, "minimum": -29999984, "type": "integer"} |
| block_registry_id | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| live_instance_token | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |

## SelectionWitness

| Field | Exact type/range or referenced definition |
|---|---|
| selection_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| scope | {"$ref": "#/definitions/TrustedScope"} |
| dimension | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| lifecycle_epoch | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| selection_revision | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| member | {"$ref": "#/definitions/SelectionMember"} |
| captured_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| expires_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| distance_milliblocks | {"maximum": 6000, "minimum": 0, "type": "integer"} |
| line_of_sight | {"const": true} |

## Position

| Field | Exact type/range or referenced definition |
|---|---|
| dimension | {"maxLength": 96, "minLength": 1, "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "type": "string"} |
| x_milli | {"maximum": 29999984000, "minimum": -29999984000, "type": "integer"} |
| y_milli | {"maximum": 2047000, "minimum": -2048000, "type": "integer"} |
| z_milli | {"maximum": 29999984000, "minimum": -29999984000, "type": "integer"} |

## MobilityGoal

| Field | Exact type/range or referenced definition |
|---|---|
| activity | {"$ref": "#/definitions/ActivityRef"} |
| actor | {"$ref": "#/definitions/CitizenEndpoint"} |
| mode | {"enum": ["COME_TO_PLAYER", "FOLLOW_PLAYER", "GO_NAMED_STORAGE"], "type": "string"} |
| player_id | {"oneOf": [{"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, {"type": "null"}]} |
| captured_position | {"oneOf": [{"$ref": "#/definitions/Position"}, {"type": "null"}]} |
| storage_target | {"oneOf": [{"$ref": "#/definitions/ResolvedEndpoint"}, {"type": "null"}]} |
| accepted_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| max_ticks | {"maximum": 1200, "minimum": 1, "type": "integer"} |
| max_travel_blocks | {"maximum": 128, "minimum": 1, "type": "integer"} |
| repath_interval_ticks | {"maximum": 200, "minimum": 10, "type": "integer"} |
| arrival_radius_milliblocks | {"maximum": 2000, "minimum": 500, "type": "integer"} |
| follow_min_milliblocks | {"maximum": 4000, "minimum": 2000, "type": "integer"} |
| follow_max_milliblocks | {"maximum": 6000, "minimum": 2000, "type": "integer"} |

## DeliveryGoal

| Field | Exact type/range or referenced definition |
|---|---|
| activity | {"$ref": "#/definitions/ActivityRef"} |
| carrier | {"$ref": "#/definitions/CitizenEndpoint"} |
| source | {"$ref": "#/definitions/ResolvedEndpoint"} |
| destination | {"$ref": "#/definitions/ResolvedEndpoint"} |
| item | {"$ref": "#/definitions/ItemKey"} |
| quantity | {"maximum": 64, "minimum": 1, "type": "integer"} |
| mode | {"const": "TRANSFER_EXISTING"} |
| max_batches | {"maximum": 8, "minimum": 1, "type": "integer"} |
| max_ticks | {"maximum": 1200, "minimum": 1, "type": "integer"} |
| max_travel_blocks | {"maximum": 128, "minimum": 1, "type": "integer"} |

## ParticipantObservation

| Field | Exact type/range or referenced definition |
|---|---|
| participant_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| kind | {"enum": ["STORAGE", "CARRIER", "RECIPIENT"], "type": "string"} |
| item | {"$ref": "#/definitions/ItemKey"} |
| quantity | {"maximum": 1000000, "minimum": 0, "type": "integer"} |
| snapshot_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| observed_tick | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| identity_generation | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| save_evidence_id | {"oneOf": [{"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, {"type": "null"}]} |

## CustodyCheckpoint

| Field | Exact type/range or referenced definition |
|---|---|
| activity | {"$ref": "#/definitions/ActivityRef"} |
| step_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| effect_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| participants | {"items": {"$ref": "#/definitions/ParticipantObservation"}, "maxItems": 4, "minItems": 1, "type": "array"} |
| tier | {"enum": ["LIVE_OBSERVED", "JOURNAL_ACKNOWLEDGED", "PARTICIPANT_SAVES_CONFIRMED", "ORIGINAL_OWNER_SETTLED"], "type": "string"} |
| a_proof_reference | {"oneOf": [{"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, {"type": "null"}]} |
| original_effect_outcome | {"enum": ["UNKNOWN", "NO_EFFECT", "COMMITTED", "SETTLED"], "type": "string"} |

## ActivityResumeSignal

| Field | Exact type/range or referenced definition |
|---|---|
| activity | {"$ref": "#/definitions/ActivityRef"} |
| question_id | {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"} |
| dialogue_revision | {"maximum": 9007199254740991, "minimum": 1, "type": "integer"} |
| observations | {"items": {"maxLength": 36, "minLength": 1, "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "type": "string"}, "maxItems": 16, "minItems": 1, "type": "array"} |
| observed_world_revision | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| request_kind | {"const": "REVALIDATE_ONLY"} |

## StageMetric

| Field | Exact type/range or referenced definition |
|---|---|
| status | {"enum": ["PASS", "FAIL", "REFUSED_EXPECTED", "INCONCLUSIVE", "BLOCKED_DEPENDENCY", "NOT_EXECUTED"], "type": "string"} |
| planned | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| executed | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| passed | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| failed | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| refused_expected | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| inconclusive | {"maximum": 9007199254740991, "minimum": 0, "type": "integer"} |
| evidence | {"items": {"maxLength": 512, "minLength": 1, "type": "string"}, "maxItems": 256, "minItems": 0, "type": "array"} |

## StageManifest

| Field | Exact type/range or referenced definition |
|---|---|
| stage | {"maxLength": 3, "minLength": 1, "pattern": "^[CDJ][0-9]{2}$", "type": "string"} |
| owner | {"enum": ["C", "D", "J"], "type": "string"} |
| base_sha | {"maxLength": 40, "minLength": 1, "pattern": "^[0-9a-f]{40}$", "type": "string"} |
| source_sha | {"oneOf": [{"maxLength": 40, "minLength": 1, "pattern": "^[0-9a-f]{40}$", "type": "string"}, {"type": "null"}]} |
| tree_sha | {"oneOf": [{"maxLength": 40, "minLength": 1, "pattern": "^[0-9a-f]{40}$", "type": "string"}, {"type": "null"}]} |
| jar_sha256 | {"oneOf": [{"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"}, {"type": "null"}]} |
| docs_sha | {"oneOf": [{"maxLength": 40, "minLength": 1, "pattern": "^[0-9a-f]{40}$", "type": "string"}, {"type": "null"}]} |
| workflow_run | {"oneOf": [{"maximum": 9007199254740991, "minimum": 1, "type": "integer"}, {"type": "null"}]} |
| attempt | {"oneOf": [{"maximum": 9007199254740991, "minimum": 1, "type": "integer"}, {"type": "null"}]} |
| unit | {"$ref": "#/definitions/StageMetric"} |
| python | {"$ref": "#/definitions/StageMetric"} |
| game_tests | {"$ref": "#/definitions/StageMetric"} |
| client_e2e | {"$ref": "#/definitions/StageMetric"} |
| native_evidence | {"items": {"maxLength": 512, "minLength": 1, "type": "string"}, "maxItems": 256, "minItems": 0, "type": "array"} |
| blocked | {"items": {"maxLength": 512, "minLength": 1, "type": "string"}, "maxItems": 128, "minItems": 0, "type": "array"} |
| regressions | {"items": {"maxLength": 512, "minLength": 1, "type": "string"}, "maxItems": 256, "minItems": 0, "type": "array"} |
| gate_status | {"enum": ["NOT_EXECUTED", "IN_PROGRESS", "FAIL", "INCONCLUSIVE", "BLOCKED_DEPENDENCY", "OWNED_GATES_PASS", "BRANCH_READY", "DEFERRED_NOT_EXECUTED"], "type": "string"} |
| contract_sha256 | {"maxLength": 64, "minLength": 1, "pattern": "^[0-9a-f]{64}$", "type": "string"} |

---

Documentation archive anchor: `d609e9980ca2cde8330042d2332226f50200d6b6` (historical blueprint audit only). Current C/D baseline is the separately pinned B commit; this anchor does not assert current implementation status.
