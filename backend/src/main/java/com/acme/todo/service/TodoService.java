package com.acme.todo.service;

import com.acme.todo.dto.request.CreateTodoRequest;
import com.acme.todo.dto.request.UpdateTodoRequest;
import com.acme.todo.dto.response.TodoAuditLogResponse;
import com.acme.todo.dto.response.TodoResponse;
import com.acme.todo.dto.response.TodoSnapshotDto;
import com.acme.todo.dto.response.UrgentTodosResponse;
import com.acme.todo.entity.Todo;
import com.acme.todo.entity.Todo.Priority;
import com.acme.todo.entity.TodoAuditActionType;
import com.acme.todo.entity.TodoAuditLog;
import com.acme.todo.entity.User;
import com.acme.todo.exception.BadRequestException;
import com.acme.todo.exception.ResourceNotFoundException;
import com.acme.todo.repository.TodoAuditLogRepository;
import com.acme.todo.repository.TodoRepository;
import com.acme.todo.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final TodoAuditLogRepository todoAuditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<TodoResponse> getAllTodos(String username, Boolean completed, String priority) {
        User user = getUserByUsername(username);
        List<Todo> todos;

        if (completed != null && priority != null) {
            Priority p = parsePriority(priority);
            todos = todoRepository.findByUserIdAndCompletedAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(
                    user.getId(), completed, p);
        } else if (completed != null) {
            todos = todoRepository.findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId(), completed);
        } else if (priority != null) {
            Priority p = parsePriority(priority);
            todos = todoRepository.findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId(), p);
        } else {
            todos = todoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(user.getId());
        }

        return todos.stream()
                .map(TodoResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves the audit history for a specific todo.
     * This method allows access to history even for soft-deleted todos.
     *
     * @param username the username of the requesting user
     * @param todoId the ID of the todo to get history for
     * @return list of audit log entries in reverse chronological order (newest first)
     */
    @Transactional(readOnly = true)
    public List<TodoAuditLogResponse> getTodoHistory(String username, Long todoId) {
        User user = getUserByUsername(username);
        
        // Verify the todo belongs to this user (including soft-deleted todos)
        Todo todo = todoRepository.findByIdAndUserId(todoId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + todoId));
        
        // Get audit logs for this todo (ownership already verified above)
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(todoId);
        
        return auditLogs.stream()
                .map(TodoAuditLogResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TodoResponse getTodoById(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));
        return TodoResponse.fromEntity(todo);
    }

    /**
     * Retrieves urgent todos (overdue and due today) for the current user.
     * Todos are sorted by priority (HIGH first) then by due date (oldest first).
     *
     * @param username the username of the requesting user
     * @return UrgentTodosResponse containing overdue todos, due today todos, and counts
     */
    @Transactional(readOnly = true)
    public UrgentTodosResponse getUrgentTodos(String username) {
        User user = getUserByUsername(username);
        LocalDate today = LocalDate.now();

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(user.getId(), today);

        // Partition into overdue and due today
        List<TodoResponse> overdue = urgentTodos.stream()
                .filter(todo -> todo.getDueDate().isBefore(today))
                .map(TodoResponse::fromEntity)
                .collect(Collectors.toList());

        List<TodoResponse> dueToday = urgentTodos.stream()
                .filter(todo -> todo.getDueDate().isEqual(today))
                .map(TodoResponse::fromEntity)
                .collect(Collectors.toList());

        return UrgentTodosResponse.of(overdue, dueToday);
    }

    @Transactional
    public TodoResponse createTodo(String username, CreateTodoRequest request) {
        User user = getUserByUsername(username);

        Priority priority = request.getPriority() != null ? parsePriority(request.getPriority()) : Priority.MEDIUM;

        Todo todo = Todo.builder()
                .user(user)
                .title(request.getTitle())
                .description(request.getDescription())
                .completed(false)
                .priority(priority)
                .dueDate(request.getDueDate())
                .build();

        todo = todoRepository.save(todo);
        
        // Create audit log entry for the new todo
        createAuditLog(TodoAuditActionType.CREATED, todo, username);
        
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public TodoResponse updateTodo(String username, Long id, UpdateTodoRequest request) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));

        if (request.getTitle() != null) {
            todo.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            todo.setDescription(request.getDescription());
        }
        if (request.getCompleted() != null) {
            todo.setCompleted(request.getCompleted());
        }
        if (request.getPriority() != null) {
            todo.setPriority(parsePriority(request.getPriority()));
        }
        if (request.getDueDate() != null) {
            todo.setDueDate(request.getDueDate());
        }

        todo = todoRepository.save(todo);
        
        // Create audit log entry for the updated todo
        createAuditLog(TodoAuditActionType.UPDATED, todo, username);
        
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public TodoResponse toggleTodoCompletion(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));

        boolean wasCompleted = todo.getCompleted();
        todo.setCompleted(!wasCompleted);
        todo = todoRepository.save(todo);
        
        // Create audit log entry with appropriate action type
        TodoAuditActionType actionType = todo.getCompleted() ? TodoAuditActionType.COMPLETED : TodoAuditActionType.UNCOMPLETED;
        createAuditLog(actionType, todo, username);
        
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public void deleteTodo(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));
        
        // Create audit log entry before soft delete (capture final state)
        createAuditLog(TodoAuditActionType.DELETED, todo, username);
        
        // Soft delete - set deletedAt timestamp instead of hard delete
        todo.setDeletedAt(LocalDateTime.now());
        todoRepository.save(todo);
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private Priority parsePriority(String priority) {
        if (priority == null) {
            return null;
        }
        try {
            return Priority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            String validValues = Arrays.stream(Priority.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new BadRequestException("Invalid priority: '" + priority + "'. Valid values are: " + validValues);
        }
    }

    /**
     * Creates an audit log entry for a todo action.
     * Serializes the current todo state as a JSONB snapshot.
     *
     * @param actionType the type of action being performed
     * @param todo the todo entity to snapshot
     * @param username the username of the user performing the action
     */
    private void createAuditLog(TodoAuditActionType actionType, Todo todo, String username) {
        try {
            TodoSnapshotDto snapshot = TodoSnapshotDto.fromEntity(todo);
            String snapshotJson = objectMapper.writeValueAsString(snapshot);

            TodoAuditLog auditLog = TodoAuditLog.builder()
                    .todo(todo)
                    .user(todo.getUser())
                    .actionType(actionType)
                    .snapshot(snapshotJson)
                    .createdBy(username)
                    .build();

            todoAuditLogRepository.save(auditLog);
            log.debug("Created audit log entry: action={}, todoId={}, user={}", actionType, todo.getId(), username);
        } catch (JsonProcessingException e) {
            // Audit logging is best-effort: log the error but allow the main operation to succeed
            log.error("Failed to serialize todo snapshot for audit log; continuing without audit entry: todoId={}", todo.getId(), e);
        }
    }
}
