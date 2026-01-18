package com.acme.todo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Entity representing an audit log entry for todo changes.
 * Each entry captures a snapshot of the todo's state at a specific point in time.
 * Audit records are immutable - they cannot be updated or deleted.
 */
@Entity
@Table(name = "todo_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"todo", "user"})
public class TodoAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "todo_id", nullable = false)
    private Todo todo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private TodoAuditActionType actionType;

    /**
     * JSONB snapshot of the todo's state at the time of this action.
     * Contains all todo fields serialized as JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * The username of the user who performed the action.
     * Captured from the security context at the time of the action.
     */
    @Column(name = "created_by", nullable = false)
    private String createdBy;
}
