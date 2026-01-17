# Technical Design Document (Solution Design)

## Design: Todo History & Audit Trail - Event Sourcing with JSONB Storage
**Feature:** Todo History & Audit Trail ([KAN-28](https://acme-world.atlassian.net/browse/KAN-28))
**Status:** Proposed
**Date:** 2026-01-17
**Author:** Bojan Dimovski

## Context

Users currently have no visibility into how their todos have changed over time. When a todo is modified or deleted, there is no record of what the previous values were or when changes occurred. This creates several problems:

**Business Challenges:**
- **Accountability Gap:** Users cannot track who changed what and when
- **Data Loss:** Hard deletes permanently erase todo data with no recovery option
- **Workflow Opacity:** No way to understand task evolution or review decision history
- **Audit Compliance:** Missing audit trail for accountability and compliance requirements

**Technical Constraints:**
- Must fit within existing **Spring Boot 3.2.1 + PostgreSQL 16** stack
- Must maintain current **user isolation** security patterns
- Cannot introduce breaking changes to existing **REST API contracts**
- Must leverage **existing authentication** (JWT) for user context
- Should avoid adding **new dependencies** if possible
- Must support **high-volume** operations (thousands of todos per user)

**Requirements from KAN-28:**
- Capture **full state snapshots** at each mutation point
- Implement **soft deletes** to preserve history of deleted todos
- Create **append-only, immutable** audit log
- Support action types: CREATED, UPDATED, COMPLETED, UNCOMPLETED, DELETED
- History must be **read-only** and accessible only to the todo owner
- Records must persist **indefinitely** with no automatic cleanup

The design must balance **storage efficiency**, **query performance**, and **schema evolution flexibility** while maintaining data integrity and security.

## Decision

We will implement an **event-sourced audit log** using **JSONB snapshots** stored in a dedicated `todo_audit_log` table. This approach:

1. **Captures Full State:** Every mutation creates a complete snapshot of the todo's state in JSONB format
2. **Soft Delete Pattern:** Adds `deleted_at` timestamp to todos table instead of hard delete
3. **Service-Layer Hooks:** Audit log creation triggered in `TodoService` methods within the same transaction
4. **Immutable Records:** No UPDATE or DELETE operations permitted on audit table at application layer
5. **Security Context:** Captures username from JWT token in `created_by` field
6. **JSONB Storage:** Leverages PostgreSQL JSONB for flexible, queryable snapshots

This solution provides complete auditability while maintaining backward compatibility and leveraging proven patterns in the existing codebase.

## Options Considered

### Option 1: Database Triggers for Audit Capture
**Description:** Use PostgreSQL triggers on INSERT/UPDATE/DELETE to automatically capture changes

**Pros:**
- Fully automatic - no application code changes required
- Database-enforced - cannot be bypassed by application bugs
- Guaranteed to capture all changes
- Simple to implement at database level

**Cons:**
- **Loses user context** - JWT username not available in database trigger
- Difficult to test - requires database-level testing infrastructure
- Hard to debug - trigger logic hidden from application code
- PostgreSQL-specific - reduces database portability
- Cannot easily skip audit logging for specific scenarios (e.g., batch operations)
- Breaks existing transaction patterns in service layer

**Verdict:** ❌ **Rejected** - Loss of user context (who made the change) is a critical flaw for audit requirements

---

### Option 2: Field-Level Diff Tracking
**Description:** Store only the fields that changed between versions, with before/after values

**Pros:**
- Storage efficient - only changed fields stored
- Shows exactly what was modified
- Smaller table size for large volumes
- Faster writes (less data per audit entry)

**Cons:**
- **Cannot reconstruct full state** at a given point in time without complex logic
- Requires complex diff computation on every mutation
- Fragile to entity schema changes (field renames break history)
- Difficult to query - "what was the priority on Jan 15?" requires walking back diffs
- More complex UI rendering logic to display history
- **Conflicts with requirement** for full state snapshots

**Verdict:** ❌ **Rejected** - Requirements explicitly specify full state snapshots for easy reconstruction

---

### Option 3: Event Sourcing with JSONB Snapshots ✅ **SELECTED**
**Description:** Store complete todo state as JSONB in dedicated audit log table

**Pros:**
- **Complete state reconstruction** - each record is self-contained
- **Flexible schema evolution** - JSONB handles entity changes gracefully
- **Easy to query** - PostgreSQL JSONB operators enable complex filtering
- **Follows event sourcing** best practices and proven patterns
- **Testable** at service layer with clear hooks
- **Preserves security context** - username from JWT stored in created_by field
- No new dependencies - uses PostgreSQL native JSONB
- Simple application code - just serialize entity to JSON
- Backward compatible - old snapshots remain readable after schema changes

**Cons:**
- More storage than diff-based approach (~1KB per audit record)
- Snapshot data duplication across records
- Requires JSONB deserialization for complex queries (though PostgreSQL optimizes this)

**Why Chosen:**
- Meets requirement for full state snapshots explicitly stated in KAN-28
- JSONB provides flexibility for future Todo entity evolution without breaking existing audit records
- Proven pattern in event sourcing and audit log systems
- PostgreSQL JSONB is mature, performant, and well-supported
- Simplifies query logic and UI rendering (no complex diff reconstruction)
- Acceptable storage trade-off given modern disk costs and PostgreSQL compression (TOAST)

---

### Option 4: Separate Versioned Tables (SCD Type 2)
**Description:** Create explicit columns mirroring Todo entity fields in audit table

**Pros:**
- SQL-friendly queries - no JSONB deserialization needed
- Type-safe at database level
- Traditional data warehousing pattern (well understood)
- Easier to index individual fields

**Cons:**
- **Schema rigidity** - every Todo entity change requires audit table migration
- Complex migration management when fields are added/removed/renamed
- More boilerplate code (explicit field mapping in service layer)
- Difficult to handle schema evolution gracefully
- Larger table definition (many columns)

**Verdict:** ❌ **Rejected** - Less flexible than JSONB approach, higher maintenance burden

---

## Architecture & Design

### Component Architecture

```
┌────────────────────────┐
│   React Frontend       │
│   (Todo List View)     │
└───────────┬────────────┘
            │ HTTP/REST
            │
            ▼
┌────────────────────────┐
│   TodoController       │◀─── @AuthenticationPrincipal UserDetails
│   (REST API Layer)     │      (extracts username from JWT)
└───────────┬────────────┘
            │
            ▼
┌────────────────────────┐
│   TodoService          │
│   (Business Logic)     │
└───────────┬────────────┘
            │
            ├──────────────────┬──────────────────┐
            │                  │                  │
            ▼                  ▼                  ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
    │ Validate     │  │ Mutate Todo  │  │ Create Audit Log │
    │ User Owns    │  │ Entity       │  │ Entry            │
    │ Todo         │  │              │  │ (JSONB snapshot) │
    └──────────────┘  └──────────────┘  └──────────────────┘
            │                  │                  │
            └──────────────────┴──────────────────┘
                               │
                               ▼
                    @Transactional boundary
                               │
            ┌──────────────────┴──────────────────┐
            │                                     │
            ▼                                     ▼
    ┌──────────────────┐              ┌──────────────────────┐
    │ TodoRepository   │              │ TodoAuditLogRepository│
    │ (JPA)            │              │ (JPA)                │
    └────────┬─────────┘              └────────┬─────────────┘
             │                                 │
             └─────────────┬───────────────────┘
                           │
                           ▼
                  ┌────────────────────┐
                  │   PostgreSQL       │
                  │   Database         │
                  │                    │
                  │  - todos           │
                  │    + deleted_at    │
                  │                    │
                  │  - todo_audit_log  │
                  │    + snapshot      │
                  │      (JSONB)       │
                  └────────────────────┘
```

**Data Flow for Mutations:**

1. **User Action:** User creates/updates/toggles/deletes a todo via React UI
2. **API Request:** Frontend sends HTTP request to TodoController endpoint
3. **Authentication:** JwtAuthenticationFilter validates JWT token, extracts UserDetails
4. **Controller Layer:** TodoController receives request with @AuthenticationPrincipal UserDetails
5. **Service Layer:** TodoService.{createTodo|updateTodo|toggleTodo|deleteTodo}() called with username
6. **Permission Check:** Service fetches User entity and validates ownership (for update/delete)
7. **Todo Mutation:** Todo entity modified (or soft-deleted with deleted_at timestamp)
8. **Audit Log Creation:** Service calls createAuditLog() with action type and current todo state
9. **JSONB Serialization:** Todo entity serialized to JSONB snapshot using Jackson ObjectMapper
10. **Database Write:** Both Todo and TodoAuditLog saved within same @Transactional boundary
11. **Atomic Commit:** Transaction commits both changes or rolls back on failure
12. **Response:** TodoResponse DTO returned to frontend

**Soft Delete Flow:**

1. User clicks "Delete" button on todo
2. DELETE /api/v1/todos/{id} endpoint invoked
3. Service sets `todo.setDeletedAt(LocalDateTime.now())` instead of repository.delete()
4. Audit log entry created with actionType = DELETED and final state snapshot
5. Transaction committed
6. Subsequent queries filter `WHERE deleted_at IS NULL` to hide deleted todos
7. History endpoint still accessible: GET /api/v1/todos/{id}/history works for deleted todos

---

### Data Model Design

#### New Entity: TodoAuditLog

```
TodoAuditLog Entity (JPA)
├── id (Long, BIGSERIAL, Primary Key)
├── todoId (Long, BIGINT, Foreign Key → todos.id, NOT NULL)
├── userId (Long, BIGINT, Foreign Key → users.id, NOT NULL)
├── actionType (TodoAuditActionType enum, VARCHAR(20), NOT NULL)
├── snapshot (String, JSONB via @Column(columnDefinition = "jsonb"), NOT NULL)
├── createdAt (LocalDateTime, TIMESTAMP, NOT NULL, @CreationTimestamp)
└── createdBy (String, VARCHAR(255), NOT NULL)

Relationships:
- @ManyToOne(fetch = LAZY) Todo todo
- @ManyToOne(fetch = LAZY) User user

Annotations:
- @Entity
- @Table(name = "todo_audit_log")
- @NoArgsConstructor, @AllArgsConstructor, @Builder (Lombok)
- Extends BaseEntity for createdAt field
```

**Database Table: todo_audit_log**

```sql
CREATE TABLE todo_audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    todo_id         BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    action_type     VARCHAR(20)     NOT NULL,
    snapshot        JSONB           NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255)    NOT NULL,

    CONSTRAINT fk_audit_todo FOREIGN KEY (todo_id)
        REFERENCES todos(id) ON DELETE RESTRICT,

    CONSTRAINT fk_audit_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT chk_action_type CHECK (
        action_type IN ('CREATED', 'UPDATED', 'COMPLETED', 'UNCOMPLETED', 'DELETED')
    )
);

-- Indexes for performance
CREATE INDEX idx_audit_todo_created ON todo_audit_log(todo_id, created_at DESC);
CREATE INDEX idx_audit_user ON todo_audit_log(user_id);
```

**Index Strategy:**
- **PRIMARY KEY (id):** Unique identifier for each audit entry
- **INDEX (todo_id, created_at DESC):** Composite index for most common query: "Get history for todo X, newest first"
- **INDEX (user_id):** Security filtering - quickly find all audit records for a user
- **NO GIN INDEX on JSONB initially:** Add later only if complex JSONB queries needed (e.g., search within snapshots)

**Constraints:**
- **FK todo_id RESTRICT:** Prevents hard deletion of todos if audit history exists (enforces immutability)
- **FK user_id RESTRICT:** Ensures audit records tied to valid users
- **CHECK action_type:** Validates enum values at database level

---

#### Modified Entity: Todo

```
Todo Entity (changes)
├── ... (existing fields: id, user, title, description, completed, priority, dueDate, createdAt, updatedAt)
└── deletedAt (LocalDateTime, TIMESTAMP, nullable) ← NEW FIELD

Annotation:
- @Column(name = "deleted_at")
- Default: null (active todos)
```

**Database Table: todos (modified)**

```sql
-- Add column via Liquibase migration
ALTER TABLE todos ADD COLUMN deleted_at TIMESTAMP;

-- Index for query optimization
CREATE INDEX idx_todos_deleted ON todos(deleted_at);
```

**Query Pattern:**
```sql
-- Active todos only (default behavior)
SELECT * FROM todos WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC;

-- Include deleted todos (optional, for "Trash" view)
SELECT * FROM todos WHERE user_id = ? ORDER BY created_at DESC;
```

---

#### JSONB Snapshot Structure

**Example Snapshot in todo_audit_log.snapshot:**

```json
{
  "id": 123,
  "title": "Complete project documentation",
  "description": "Write comprehensive API docs including all endpoints and examples",
  "completed": false,
  "priority": "HIGH",
  "dueDate": "2026-01-30",
  "createdAt": "2026-01-17T10:00:00",
  "updatedAt": "2026-01-17T15:30:00",
  "deletedAt": null
}
```

**Schema Evolution Example:**

```json
// Future: If Todo entity adds "tags" field
{
  "id": 124,
  "title": "New task with tags",
  "description": "...",
  "completed": false,
  "priority": "MEDIUM",
  "dueDate": "2026-02-15",
  "tags": ["work", "urgent"],  ← New field, old snapshots don't break
  "createdAt": "2026-01-18T09:00:00",
  "updatedAt": "2026-01-18T09:00:00"
}
```

**JSONB Benefits:**
- Old snapshots remain valid even when Todo entity evolves
- PostgreSQL handles missing fields gracefully (returns null)
- Can query within JSONB: `snapshot->>'priority' = 'HIGH'`
- Compressed automatically by PostgreSQL TOAST for large values

---

### API Design

#### New Endpoint: Get Todo History

```
GET /api/v1/todos/{id}/history

Description: Retrieve complete audit trail for a specific todo

Authentication: Required (JWT Bearer token)

Path Parameters:
  - id (Long): Todo ID

Query Parameters: None

Response: 200 OK
Content-Type: application/json

Body:
[
  {
    "id": 456,
    "actionType": "CREATED",
    "snapshot": {
      "title": "Original title",
      "description": "Original description",
      "completed": false,
      "priority": "MEDIUM",
      "dueDate": "2026-02-01"
    },
    "createdAt": "2026-01-17T10:00:00Z",
    "createdBy": "john.doe"
  },
  {
    "id": 457,
    "actionType": "UPDATED",
    "snapshot": {
      "title": "Updated title",
      "description": "Updated description",
      "completed": false,
      "priority": "HIGH",
      "dueDate": "2026-02-01"
    },
    "createdAt": "2026-01-17T15:30:00Z",
    "createdBy": "john.doe"
  },
  {
    "id": 458,
    "actionType": "COMPLETED",
    "snapshot": {
      "title": "Updated title",
      "description": "Updated description",
      "completed": true,
      "priority": "HIGH",
      "dueDate": "2026-02-01"
    },
    "createdAt": "2026-01-18T09:15:00Z",
    "createdBy": "john.doe"
  }
]

Sorting: Results ordered by created_at DESC (newest first)

Error Responses:
  - 401 Unauthorized: Missing or invalid JWT token
  - 404 Not Found: Todo does not exist OR user does not own the todo
  - 500 Internal Server Error: Database failure

Security:
  - Only the todo owner can view history (enforced by userId filter)
  - Deleted todos' history still accessible to owner
```

**Controller Signature:**
```java
@GetMapping("/{id}/history")
@Operation(summary = "Get audit trail for a todo")
public ResponseEntity<List<TodoAuditLogResponse>> getTodoHistory(
    @PathVariable Long id,
    @AuthenticationPrincipal UserDetails userDetails
) {
    return ResponseEntity.ok(todoService.getTodoHistory(id, userDetails.getUsername()));
}
```

---

#### Modified Endpoints (Behavior Changes)

**No API contract changes** - existing endpoints work exactly as before, but now create audit log entries.

```
POST /api/v1/todos
  Change: After successful creation, automatically create CREATED audit log entry
  API Contract: Unchanged (still returns 201 Created with TodoResponse)

PUT /api/v1/todos/{id}
  Change: After successful update, create UPDATED audit log entry
  API Contract: Unchanged (still returns 200 OK with TodoResponse)

PATCH /api/v1/todos/{id}/toggle
  Change: Create COMPLETED or UNCOMPLETED audit log entry based on new state
  API Contract: Unchanged (still returns 200 OK with TodoResponse)

DELETE /api/v1/todos/{id}
  Change: Soft delete (set deleted_at) instead of hard delete, create DELETED audit log entry
  API Contract: Unchanged (still returns 204 No Content)

GET /api/v1/todos
  Change: Add WHERE deleted_at IS NULL filter to exclude soft-deleted todos
  API Contract: Unchanged (still returns 200 OK with List<TodoResponse>)
```

**Optional Future Enhancement:**
```
GET /api/v1/todos?includeDeleted=true
  Query Parameter: includeDeleted (boolean, default: false)
  Returns: All todos including soft-deleted ones (flagged with deletedAt timestamp)
  Use Case: "Trash" view to show and potentially restore deleted todos
```

---

### DTO Design

#### New Request/Response DTOs

**TodoAuditLogResponse (DTO):**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoAuditLogResponse {
    private Long id;
    private TodoAuditActionType actionType;
    private Map<String, Object> snapshot;  // JSONB deserialized to Map
    private LocalDateTime createdAt;
    private String createdBy;

    public static TodoAuditLogResponse fromEntity(TodoAuditLog entity) {
        return TodoAuditLogResponse.builder()
            .id(entity.getId())
            .actionType(entity.getActionType())
            .snapshot(deserializeSnapshot(entity.getSnapshot()))
            .createdAt(entity.getCreatedAt())
            .createdBy(entity.getCreatedBy())
            .build();
    }

    private static Map<String, Object> deserializeSnapshot(String jsonb) {
        try {
            return objectMapper.readValue(jsonb, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize audit snapshot", e);
        }
    }
}
```

**TodoAuditActionType (Enum):**
```java
public enum TodoAuditActionType {
    CREATED,
    UPDATED,
    COMPLETED,
    UNCOMPLETED,
    DELETED
}
```

#### Modified DTOs

**TodoResponse (add deletedAt field for future includeDeleted API):**
```java
@Data
@Builder
public class TodoResponse {
    // ... existing fields ...
    private LocalDateTime deletedAt;  // NEW: null for active todos

    public static TodoResponse fromEntity(Todo entity) {
        return TodoResponse.builder()
            // ... existing mappings ...
            .deletedAt(entity.getDeletedAt())  // NEW
            .build();
    }
}
```

---

### Technology Choices

**Backend Stack:**
- **Spring Boot 3.2.1** (existing)
- **Spring Data JPA / Hibernate 6.4.1** (existing)
- **Jackson ObjectMapper** (existing) - for JSONB serialization/deserialization
- **Liquibase** (existing) - for database migrations

**Database:**
- **PostgreSQL 16** (existing)
- **JSONB data type** (native support since PostgreSQL 9.4)
- **TOAST compression** (automatic for large JSONB values)

**Testing:**
- **Testcontainers** (existing) - integration tests with real PostgreSQL
- **Mockito** (existing) - unit tests for service layer
- **MockMvc** (existing) - API endpoint testing

**Frontend:**
- **React 18 + TypeScript** (existing)
- **Fetch API** (existing) - HTTP client for REST calls

**Reasoning:**
- **No new dependencies required** ✅
- Leverages existing, mature technologies
- PostgreSQL JSONB is production-ready, performant, and well-documented
- Jackson is already in classpath for JSON handling
- Liquibase provides versioned database schema management
- Team already familiar with entire stack (minimal learning curve)

---

## Impact Analysis

### Performance Impact

**Read Operations:**
- **No impact** on existing todo queries (no changes to SELECT statements)
- Soft delete filter adds `WHERE deleted_at IS NULL` clause (optimized with index)
- History endpoint is new, no baseline to compare

**Write Operations:**
- **+50ms overhead** per mutation for audit log INSERT
  - Includes JSONB serialization (~10ms)
  - Database INSERT (~30ms)
  - Transaction commit (~10ms)
- Acceptable trade-off for auditability requirement
- Mitigated by @Transactional batching

**Query Performance Estimates:**
- **History retrieval:** < 500ms for 10,000 audit records (via composite index)
- **JSONB serialization:** < 10ms per snapshot (Jackson is highly optimized)
- **Soft delete filter:** Negligible impact with index on deleted_at

**Storage Growth:**
- ~1KB per audit record (JSONB snapshot)
- 100 records per todo over lifetime = 100KB per todo
- 1,000 active todos per user = ~100MB per heavy user
- 100,000 total users with avg 50 todos = ~5GB for 5M audit records
- PostgreSQL handles this scale easily with proper indexing

---

### Data Impact

**Existing Data:**
- **No migration required** for existing todos - deleted_at defaults to NULL
- **No data loss** - all existing todos remain active
- **Backward compatible** - old code reads work fine (soft delete filter optional)

**New Data:**
- **New table:** todo_audit_log (initially empty)
- **New column:** todos.deleted_at (all rows start as NULL)
- **Schema size:** ~50 bytes per audit log row (excluding JSONB)
- **JSONB size:** ~800-1000 bytes per snapshot (depends on description length)

**Data Retention:**
- **Indefinite storage** per requirements
- No automatic archival or cleanup
- Manual cleanup only via DBA if needed (e.g., GDPR erasure requests)

---

### Testing Impact

**New Tests Required:**

1. **Unit Tests (TodoServiceTest):**
   - Assert audit log entry created on createTodo()
   - Assert audit log entry created on updateTodo()
   - Assert COMPLETED audit log on toggle(true)
   - Assert UNCOMPLETED audit log on toggle(false)
   - Assert DELETED audit log on deleteTodo()
   - Assert soft delete sets deleted_at timestamp
   - Assert getTodoHistory() filters by user ownership

2. **Integration Tests (TodoControllerIntegrationTest):**
   - POST /todos creates CREATED audit entry
   - PUT /todos/{id} creates UPDATED audit entry
   - PATCH /todos/{id}/toggle creates COMPLETED/UNCOMPLETED entry
   - DELETE /todos/{id} creates DELETED audit entry and soft deletes
   - GET /todos/{id}/history returns correct audit trail
   - GET /todos excludes soft-deleted todos
   - GET /todos/{id}/history returns 404 for other user's todo

3. **Repository Tests (TodoAuditLogRepositoryTest):**
   - findByTodoIdOrderByCreatedAtDesc() returns correct order
   - findByUserId() filters by user correctly
   - Foreign key constraints prevent invalid data

**Modified Tests:**
- Update existing TodoServiceTest to assert audit log creation
- Update TodoRepositoryTest to account for soft delete filtering
- Add audit log assertions to all mutation tests

**Estimated Effort:** 4 hours for comprehensive test coverage

---

### Code Impact

**New Files:**
```
backend/src/main/java/com/acme/todo/entity/TodoAuditLog.java
backend/src/main/java/com/acme/todo/entity/TodoAuditActionType.java
backend/src/main/java/com/acme/todo/repository/TodoAuditLogRepository.java
backend/src/main/java/com/acme/todo/dto/response/TodoAuditLogResponse.java
backend/src/main/resources/db/changelog/changes/004-create-audit-log-table.yaml
backend/src/test/java/com/acme/todo/repository/TodoAuditLogRepositoryTest.java
```

**Modified Files:**
```
backend/src/main/java/com/acme/todo/entity/Todo.java
  + private LocalDateTime deletedAt;

backend/src/main/java/com/acme/todo/service/TodoService.java
  + private void createAuditLog(...)
  + modified: createTodo() - add audit log creation
  + modified: updateTodo() - add audit log creation
  + modified: toggleTodoCompletion() - add audit log creation
  + modified: deleteTodo() - change to soft delete + audit log
  + new: getTodoHistory(Long todoId, String username)

backend/src/main/java/com/acme/todo/repository/TodoRepository.java
  + all finder methods add: AND deleted_at IS NULL

backend/src/main/java/com/acme/todo/controller/TodoController.java
  + new endpoint: GET /todos/{id}/history

backend/src/main/java/com/acme/todo/dto/response/TodoResponse.java
  + private LocalDateTime deletedAt;

backend/src/test/java/com/acme/todo/service/TodoServiceTest.java
  + audit log assertions in all mutation tests

backend/src/test/java/com/acme/todo/integration/TodoControllerIntegrationTest.java
  + new tests for history endpoint
  + modified tests for audit log creation
```

**Lines of Code Estimate:**
- **New code:** ~500 LOC (entities, repository, service methods, DTOs, migration)
- **Modified code:** ~200 LOC (service method updates, repository filters, tests)
- **Total impact:** ~700 LOC

---

## Risks & Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Audit log write failure causes todo operation to fail** | High | Low | Wrap both operations in @Transactional boundary. If audit log write fails, rollback todo mutation. Monitor error logs for audit failures. Add alerting for repeated failures. |
| **JSONB snapshot size grows too large (>1MB per record)** | Medium | Low | Validate Todo entity size limits already in place (title max 200 chars, description max 2000 chars). Monitor average snapshot size in production. Add database constraint if needed. |
| **Soft delete causes user confusion (expect permanent delete)** | Medium | Medium | Add clear UI messaging: "Moved to trash" vs "Deleted forever". Future enhancement: Add "Trash" view with restore/permanent delete options. |
| **Performance degradation with millions of audit records** | High | Medium | Implement table partitioning by created_at (yearly or monthly). Archive old audit records (>1 year) to cold storage (S3 + separate table). Monitor query performance and add composite indexes as needed. |
| **Foreign key RESTRICT prevents todo deletion** | Low | Low | This is by design - cannot hard delete if history exists. Document behavior clearly. If needed, add admin endpoint to force delete (removes audit trail first). |
| **JSONB schema drift (snapshot format changes over time)** | Medium | High | Add "schemaVersion": 1 to JSONB snapshots. Handle migration in deserialization logic (transform old format to new). Document snapshot schema in code comments. |
| **Audit log table grows unbounded** | Medium | High | Monitor table size. Implement archival strategy: move records older than 1 year to separate archive table or cold storage. Add data retention policy documentation. |
| **Users abuse history API (DoS via repeated requests)** | Medium | Low | Add rate limiting on history endpoint (e.g., 10 requests/minute per user). Cache history results for 60 seconds. Add monitoring for API abuse patterns. |
| **Transaction timeout due to large audit log writes** | Low | Low | Audit log write is simple INSERT with small JSONB payload (<1KB). Transaction should complete in <100ms. Monitor transaction duration in production. |
| **Audit log and todo get out of sync** | High | Very Low | Prevented by @Transactional boundary - both write or neither. Add unit tests to verify atomic behavior. |

**Monitoring Plan:**
- Log all audit log creation failures at ERROR level
- Track audit log table size growth (weekly report)
- Monitor query performance on history endpoint (p95 latency)
- Alert if audit log write failure rate exceeds 0.1%

---

## Rollback Plan

### Immediate Rollback (Within 1 Hour of Deployment)

**Scenario:** Critical bug discovered, need to revert immediately

**Steps:**
1. **Revert application code** to previous version (git revert)
2. **Deploy previous version** to production
3. **Audit table remains** but is unused (no data loss)
4. **Soft-deleted todos remain hidden** (WHERE deleted_at IS NULL still active)
5. **Fix soft-deleted todos** (if needed):
   ```sql
   UPDATE todos SET deleted_at = NULL WHERE deleted_at IS NOT NULL;
   ```

**Impact:** Audit history preserved, no data loss, <15 minutes downtime

---

### Partial Rollback (Feature Flag Approach)

**Scenario:** Audit log feature causing issues, but soft delete is fine

**Steps:**
1. **Add feature flag** in TodoService: `@Value("${feature.audit-log.enabled}") boolean auditLogEnabled`
2. **Disable audit log creation** in service methods:
   ```java
   if (auditLogEnabled) {
       createAuditLog(...);
   }
   ```
3. **History endpoint returns 501 Not Implemented:**
   ```java
   if (!auditLogEnabled) {
       return ResponseEntity.status(501).build();
   }
   ```
4. **Keep soft delete behavior** (less risky, data preserved)

**Impact:** Audit feature disabled, soft delete still active, no data loss

---

### Full Rollback (Remove All Changes)

**Scenario:** Complete feature removal required

**Steps:**
1. **Drop audit log table** via Liquibase rollback migration:
   ```yaml
   # File: 005-rollback-audit-log.yaml
   databaseChangeLog:
     - changeSet:
         id: 005-rollback-audit-log
         author: bojan.dimovski
         changes:
           - dropTable:
               tableName: todo_audit_log
               cascadeConstraints: true
           - dropColumn:
               tableName: todos
               columnName: deleted_at
           - dropIndex:
               indexName: idx_todos_deleted
   ```
2. **Revert all application code** (entity, service, controller, repository)
3. **Remove tests** for audit functionality
4. **Deploy reverted version**

**Impact:** ⚠️ **All audit history permanently lost**, soft-deleted todos become permanently deleted

**Decision Authority:** Requires product owner and engineering lead approval

---

### Rollback Testing

**Before production deployment:**
1. Test rollback procedure in staging environment
2. Verify soft-deleted todos can be recovered if needed
3. Document time required for each rollback scenario
4. Create runbook for on-call engineers

---

## Related Documents

- **Requirements:** [Todo History & Audit Trail Requirements](/docs/requirements/todo-history-audit.md)
- **JIRA Epic:** [KAN-28](https://acme-world.atlassian.net/browse/KAN-28)
- **Implementation Plan:** To be created after ADR approval
- **Testing Strategy:** To be created after ADR approval
- **Previous Designs:** None (new feature)
- **Related ADRs:** None yet

---

## Future Considerations

**Out of scope for MVP, but worth considering for future iterations:**

### 1. Diff View
**Description:** Highlight exactly what changed between audit entries (field-level comparison)

**Benefits:**
- Easier for users to see what was modified without comparing full snapshots
- Better UX for understanding change history

**Implementation:**
- Client-side diff algorithm (e.g., jsondiffpatch)
- OR backend endpoint: GET /todos/{id}/history/{entryId}/diff

**Effort:** ~8 hours

---

### 2. Undo/Restore Functionality
**Description:** Allow users to restore a todo to a previous state from history

**Benefits:**
- Recover from accidental changes
- "Time travel" to previous todo versions

**Implementation:**
- Add POST /todos/{id}/restore/{entryId} endpoint
- Copy snapshot data back to main todo entity
- Create new RESTORED audit entry

**Considerations:**
- What happens to changes made after the restore point?
- Should create a new todo or overwrite current?

**Effort:** ~12 hours

---

### 3. Export Functionality
**Description:** Download audit trail as CSV/JSON/PDF

**Benefits:**
- Compliance requirements (SOX, audit reports)
- Data portability

**Implementation:**
- GET /todos/{id}/history/export?format={csv|json|pdf}
- Generate file, stream to user

**Effort:** ~6 hours

---

### 4. Admin Audit View
**Description:** Platform admins can view all users' audit trails

**Benefits:**
- Support use case (troubleshoot user issues)
- Compliance investigations

**Security Concerns:**
- Requires strong RBAC (role-based access control)
- Audit the auditors (who viewed whose history)

**Effort:** ~16 hours (including RBAC implementation)

---

### 5. Real-time Change Notifications
**Description:** Notify user when their todo is modified (collaborative editing detection)

**Benefits:**
- Detect concurrent edits
- Better UX for multi-device users

**Implementation:**
- WebSocket or Server-Sent Events (SSE)
- Publish audit log creation event to message broker
- Frontend subscribes to updates

**Effort:** ~20 hours

---

### 6. Advanced JSONB Querying
**Description:** Search within snapshot data (e.g., "Find all times priority was HIGH")

**Benefits:**
- Advanced filtering and analytics
- Answer questions like "How many times did I change the priority?"

**Implementation:**
- Add GIN index on snapshot column:
  ```sql
  CREATE INDEX idx_audit_snapshot_gin ON todo_audit_log USING GIN (snapshot);
  ```
- Query: `WHERE snapshot->>'priority' = 'HIGH'`

**Effort:** ~4 hours

---

### 7. Snapshot Compression
**Description:** Compress JSONB snapshots for storage savings

**Benefits:**
- Reduce storage costs for large volumes
- PostgreSQL TOAST already compresses, but custom compression could improve further

**Implementation:**
- Use gzip or zstd compression before storing
- Decompress on read

**Considerations:**
- PostgreSQL TOAST already handles this automatically for values >2KB
- Custom compression may not provide significant additional benefit

**Effort:** ~8 hours

---

### 8. Archival Strategy
**Description:** Move audit records older than 1 year to cold storage (S3/Glacier)

**Benefits:**
- Reduce active database size
- Lower storage costs
- Improve query performance on recent data

**Implementation:**
- Nightly job: SELECT audit records WHERE created_at < NOW() - INTERVAL '1 year'
- Export to S3 as Parquet/JSON
- DELETE from active table
- On-demand retrieval from S3 for old history

**Effort:** ~24 hours (including S3 integration)

---

### 9. GDPR Compliance: Right to Erasure
**Description:** Permanently delete or anonymize user's audit trail on request

**Benefits:**
- GDPR compliance (right to be forgotten)
- User privacy

**Conflict:**
- **Violates immutability requirement** of audit logs
- Cannot delete audit records and maintain audit integrity

**Alternative:**
- **Anonymization:** Replace user_id and created_by with "DELETED_USER"
- Keep snapshot data but remove user identification

**Effort:** ~12 hours (including legal review)

---

### 10. Schema Versioning
**Description:** Add "schemaVersion": 1 to JSONB snapshots for backward compatibility

**Benefits:**
- Handle Todo entity evolution gracefully
- Migrate old snapshots to new format on read

**Implementation:**
```json
{
  "schemaVersion": 1,
  "id": 123,
  "title": "Task title",
  ...
}
```

**Migration Logic:**
```java
if (snapshot.get("schemaVersion") == null || snapshot.get("schemaVersion") < 2) {
    snapshot = migrateToV2(snapshot);
}
```

**Effort:** ~6 hours

---

### 11. Table Partitioning
**Description:** Partition todo_audit_log by created_at (yearly or monthly)

**Benefits:**
- Improved query performance for large datasets (100M+ records)
- Easier archival (drop old partitions)
- Better vacuum performance

**Implementation:**
```sql
CREATE TABLE todo_audit_log (
    -- columns...
) PARTITION BY RANGE (created_at);

CREATE TABLE todo_audit_log_2026 PARTITION OF todo_audit_log
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
```

**When to Implement:** When audit log exceeds 10M records

**Effort:** ~16 hours (including migration script)

---

## Approval Checklist

Before approving this ADR, verify:

- [x] **Context clearly describes** the problem and constraints
- [x] **All options were considered** with clear pros/cons analysis
- [x] **Selected approach fits** existing architecture patterns (Spring Boot + PostgreSQL)
- [x] **Non-functional requirements** are specified and achievable
- [x] **Database schema changes** are backward compatible (soft delete, new table)
- [x] **API design follows** REST conventions and maintains backward compatibility
- [x] **Security and user isolation** are maintained (user_id filtering, JWT context)
- [x] **Performance impact** is acceptable (<50ms overhead per mutation)
- [x] **Testing strategy** is comprehensive (unit + integration + repository tests)
- [x] **Rollback plan** is feasible (immediate, partial, full options documented)
- [x] **Implementation estimate** is realistic (13 hours backend + 4 hours frontend)
- [x] **Technology choices** leverage existing stack (no new dependencies)
- [x] **Risks identified** with clear mitigation strategies
- [x] **Future considerations** documented for post-MVP enhancements

---

**Next Steps After Approval:**
1. Review ADR with product owner and engineering team
2. Address any feedback and update ADR
3. Publish to Confluence (separate task)
4. Create detailed implementation tickets in JIRA
5. Begin Phase 1: Database migration (Liquibase changeset)
6. Implement backend entities, repositories, services
7. Add API endpoint and tests
8. Frontend implementation (separate design doc)
