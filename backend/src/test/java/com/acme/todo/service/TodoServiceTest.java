package com.acme.todo.service;

import com.acme.todo.dto.request.CreateTodoRequest;
import com.acme.todo.dto.request.UpdateTodoRequest;
import com.acme.todo.dto.response.TodoResponse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TodoAuditLogRepository todoAuditLogRepository;

    private ObjectMapper objectMapper;

    private TodoService todoService;

    private User testUser;
    private Todo testTodo;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        todoService = new TodoService(todoRepository, userRepository, todoAuditLogRepository, objectMapper);

        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashedpassword")
                .enabled(true)
                .build();
        testUser.setId(1L);

        testTodo = Todo.builder()
                .user(testUser)
                .title("Test Todo")
                .description("Test Description")
                .completed(false)
                .priority(Priority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(7))
                .build();
        testTodo.setId(1L);
        testTodo.setCreatedAt(LocalDateTime.now());
        testTodo.setUpdatedAt(LocalDateTime.now());
    }

    @Nested
    @DisplayName("getTodoHistory")
    class GetTodoHistoryTests {

        @Test
        @DisplayName("should return history for a todo")
        void shouldReturnHistoryForTodo() {
            TodoAuditLog auditLog1 = TodoAuditLog.builder()
                    .id(1L)
                    .todo(testTodo)
                    .user(testUser)
                    .actionType(TodoAuditActionType.CREATED)
                    .snapshot("{\"id\":1,\"title\":\"Test Todo\"}")
                    .createdBy("testuser")
                    .build();
            auditLog1.setCreatedAt(LocalDateTime.now().minusHours(2));

            TodoAuditLog auditLog2 = TodoAuditLog.builder()
                    .id(2L)
                    .todo(testTodo)
                    .user(testUser)
                    .actionType(TodoAuditActionType.UPDATED)
                    .snapshot("{\"id\":1,\"title\":\"Updated Todo\"}")
                    .createdBy("testuser")
                    .build();
            auditLog2.setCreatedAt(LocalDateTime.now().minusHours(1));

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(auditLog2, auditLog1));

            var result = todoService.getTodoHistory("testuser", 1L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getActionType()).isEqualTo("UPDATED");
            assertThat(result.get(1).getActionType()).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent todo")
        void shouldThrowForNonExistentTodo() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getTodoHistory("testuser", 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when user does not own the todo")
        void shouldThrowWhenUserDoesNotOwnTodo() {
            User otherUser = User.builder()
                    .username("otheruser")
                    .email("other@example.com")
                    .passwordHash("hashedpassword")
                    .enabled(true)
                    .build();
            otherUser.setId(2L);

            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            when(todoRepository.findByIdAndUserId(1L, 2L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getTodoHistory("otheruser", 1L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");
        }

        @Test
        @DisplayName("should return empty list for todo with no history")
        void shouldReturnEmptyListForTodoWithNoHistory() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

            var result = todoService.getTodoHistory("testuser", 1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should allow access to history for soft-deleted todos")
        void shouldAllowAccessToHistoryForSoftDeletedTodos() {
            testTodo.setDeletedAt(LocalDateTime.now());
            
            TodoAuditLog auditLog = TodoAuditLog.builder()
                    .id(1L)
                    .todo(testTodo)
                    .user(testUser)
                    .actionType(TodoAuditActionType.DELETED)
                    .snapshot("{\"id\":1,\"title\":\"Test Todo\"}")
                    .createdBy("testuser")
                    .build();
            auditLog.setCreatedAt(LocalDateTime.now());

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(auditLog));

            var result = todoService.getTodoHistory("testuser", 1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getActionType()).isEqualTo("DELETED");
        }
    }

    @Nested
    @DisplayName("getAllTodos")
    class GetAllTodosTests {

        @Test
        @DisplayName("should return all todos for user without filters")
        void shouldReturnAllTodosWithoutFilters() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L)).thenReturn(List.of(testTodo));

            List<TodoResponse> result = todoService.getAllTodos("testuser", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Test Todo");
            verify(todoRepository).findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L);
        }

        @Test
        @DisplayName("should filter by completed status")
        void shouldFilterByCompletedStatus() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(1L, false))
                    .thenReturn(List.of(testTodo));

            List<TodoResponse> result = todoService.getAllTodos("testuser", false, null);

            assertThat(result).hasSize(1);
            verify(todoRepository).findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(1L, false);
        }

        @Test
        @DisplayName("should filter by priority")
        void shouldFilterByPriority() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(1L, Priority.HIGH))
                    .thenReturn(List.of());

            List<TodoResponse> result = todoService.getAllTodos("testuser", null, "HIGH");

            assertThat(result).isEmpty();
            verify(todoRepository).findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(1L, Priority.HIGH);
        }

        @Test
        @DisplayName("should filter by both completed and priority")
        void shouldFilterByCompletedAndPriority() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndCompletedAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(1L, true, Priority.LOW))
                    .thenReturn(List.of());

            List<TodoResponse> result = todoService.getAllTodos("testuser", true, "LOW");

            assertThat(result).isEmpty();
            verify(todoRepository).findByUserIdAndCompletedAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(1L, true, Priority.LOW);
        }

        @Test
        @DisplayName("should throw BadRequestException for invalid priority")
        void shouldThrowForInvalidPriority() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() -> todoService.getAllTodos("testuser", null, "INVALID"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid priority")
                    .hasMessageContaining("INVALID")
                    .hasMessageContaining("LOW, MEDIUM, HIGH");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent user")
        void shouldThrowForNonExistentUser() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getAllTodos("unknown", null, null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found");
        }
    }

    @Nested
    @DisplayName("getTodoById")
    class GetTodoByIdTests {

        @Test
        @DisplayName("should return todo by id")
        void shouldReturnTodoById() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));

            TodoResponse result = todoService.getTodoById("testuser", 1L);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getTitle()).isEqualTo("Test Todo");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent todo")
        void shouldThrowForNonExistentTodo() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getTodoById("testuser", 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");
        }
    }

    @Nested
    @DisplayName("getUrgentTodos")
    class GetUrgentTodosTests {

        @Test
        @DisplayName("should return overdue and due today todos")
        void shouldReturnOverdueAndDueTodayTodos() {
            LocalDate today = LocalDate.now();
            
            Todo overdueTodo = Todo.builder()
                    .user(testUser)
                    .title("Overdue Todo")
                    .description("This is overdue")
                    .completed(false)
                    .priority(Priority.HIGH)
                    .dueDate(today.minusDays(2))
                    .build();
            overdueTodo.setId(2L);
            overdueTodo.setCreatedAt(LocalDateTime.now());
            overdueTodo.setUpdatedAt(LocalDateTime.now());

            Todo dueTodayTodo = Todo.builder()
                    .user(testUser)
                    .title("Due Today Todo")
                    .description("This is due today")
                    .completed(false)
                    .priority(Priority.MEDIUM)
                    .dueDate(today)
                    .build();
            dueTodayTodo.setId(3L);
            dueTodayTodo.setCreatedAt(LocalDateTime.now());
            dueTodayTodo.setUpdatedAt(LocalDateTime.now());

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today))
                    .thenReturn(List.of(overdueTodo, dueTodayTodo));

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).hasSize(1);
            assertThat(result.getOverdue().get(0).getTitle()).isEqualTo("Overdue Todo");
            assertThat(result.getDueToday()).hasSize(1);
            assertThat(result.getDueToday().get(0).getTitle()).isEqualTo("Due Today Todo");
            assertThat(result.getCounts().getOverdueCount()).isEqualTo(1);
            assertThat(result.getCounts().getDueTodayCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should partition multiple overdue and due today todos correctly")
        void shouldPartitionMultipleTodosCorrectly() {
            LocalDate today = LocalDate.now();
            
            Todo overdue1 = Todo.builder()
                    .user(testUser)
                    .title("Overdue 1")
                    .completed(false)
                    .priority(Priority.HIGH)
                    .dueDate(today.minusDays(5))
                    .build();
            overdue1.setId(2L);

            Todo overdue2 = Todo.builder()
                    .user(testUser)
                    .title("Overdue 2")
                    .completed(false)
                    .priority(Priority.MEDIUM)
                    .dueDate(today.minusDays(1))
                    .build();
            overdue2.setId(3L);

            Todo dueToday1 = Todo.builder()
                    .user(testUser)
                    .title("Due Today 1")
                    .completed(false)
                    .priority(Priority.HIGH)
                    .dueDate(today)
                    .build();
            dueToday1.setId(4L);

            Todo dueToday2 = Todo.builder()
                    .user(testUser)
                    .title("Due Today 2")
                    .completed(false)
                    .priority(Priority.LOW)
                    .dueDate(today)
                    .build();
            dueToday2.setId(5L);

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today))
                    .thenReturn(List.of(overdue1, overdue2, dueToday1, dueToday2));

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).hasSize(2);
            assertThat(result.getDueToday()).hasSize(2);
            assertThat(result.getCounts().getOverdueCount()).isEqualTo(2);
            assertThat(result.getCounts().getDueTodayCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should handle empty result when no urgent todos exist")
        void shouldHandleEmptyResult() {
            LocalDate today = LocalDate.now();
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today)).thenReturn(List.of());

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).isEmpty();
            assertThat(result.getDueToday()).isEmpty();
            assertThat(result.getCounts().getOverdueCount()).isEqualTo(0);
            assertThat(result.getCounts().getDueTodayCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle only overdue todos")
        void shouldHandleOnlyOverdueTodos() {
            LocalDate today = LocalDate.now();
            
            Todo overdueTodo = Todo.builder()
                    .user(testUser)
                    .title("Overdue Todo")
                    .completed(false)
                    .priority(Priority.HIGH)
                    .dueDate(today.minusDays(3))
                    .build();
            overdueTodo.setId(2L);

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today)).thenReturn(List.of(overdueTodo));

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).hasSize(1);
            assertThat(result.getDueToday()).isEmpty();
            assertThat(result.getCounts().getOverdueCount()).isEqualTo(1);
            assertThat(result.getCounts().getDueTodayCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle only due today todos")
        void shouldHandleOnlyDueTodayTodos() {
            LocalDate today = LocalDate.now();
            
            Todo dueTodayTodo = Todo.builder()
                    .user(testUser)
                    .title("Due Today Todo")
                    .completed(false)
                    .priority(Priority.MEDIUM)
                    .dueDate(today)
                    .build();
            dueTodayTodo.setId(2L);

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today)).thenReturn(List.of(dueTodayTodo));

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).isEmpty();
            assertThat(result.getDueToday()).hasSize(1);
            assertThat(result.getCounts().getOverdueCount()).isEqualTo(0);
            assertThat(result.getCounts().getDueTodayCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should filter out todos with null due dates")
        void shouldFilterOutNullDueDates() {
            LocalDate today = LocalDate.now();
            
            Todo todoWithNullDate = Todo.builder()
                    .user(testUser)
                    .title("No Due Date")
                    .completed(false)
                    .priority(Priority.HIGH)
                    .dueDate(null)
                    .build();
            todoWithNullDate.setId(2L);

            Todo overdueTodo = Todo.builder()
                    .user(testUser)
                    .title("Overdue Todo")
                    .completed(false)
                    .priority(Priority.MEDIUM)
                    .dueDate(today.minusDays(1))
                    .build();
            overdueTodo.setId(3L);

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today))
                    .thenReturn(List.of(todoWithNullDate, overdueTodo));

            var result = todoService.getUrgentTodos("testuser");

            assertThat(result.getOverdue()).hasSize(1);
            assertThat(result.getOverdue().get(0).getTitle()).isEqualTo("Overdue Todo");
            assertThat(result.getDueToday()).isEmpty();
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent user")
        void shouldThrowForNonExistentUser() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getUrgentTodos("unknown"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("should verify repository is called with correct parameters")
        void shouldVerifyRepositoryInteraction() {
            LocalDate today = LocalDate.now();
            
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findUrgentByUserId(1L, today)).thenReturn(List.of());

            todoService.getUrgentTodos("testuser");

            verify(userRepository).findByUsername("testuser");
            verify(todoRepository).findUrgentByUserId(1L, today);
        }
    }

    @Nested
    @DisplayName("createTodo")
    class CreateTodoTests {

        @Test
        @DisplayName("should create todo with all fields")
        void shouldCreateTodoWithAllFields() {
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("New Todo")
                    .description("New Description")
                    .priority("HIGH")
                    .dueDate(LocalDate.now().plusDays(5))
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> {
                Todo saved = invocation.getArgument(0);
                saved.setId(2L);
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            TodoResponse result = todoService.createTodo("testuser", request);

            assertThat(result.getTitle()).isEqualTo("New Todo");
            assertThat(result.getDescription()).isEqualTo("New Description");
            assertThat(result.getPriority()).isEqualTo("HIGH");
            assertThat(result.getCompleted()).isFalse();
        }

        @Test
        @DisplayName("should create todo with default priority when not specified")
        void shouldCreateTodoWithDefaultPriority() {
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("Simple Todo")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> {
                Todo saved = invocation.getArgument(0);
                saved.setId(2L);
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            TodoResponse result = todoService.createTodo("testuser", request);

            assertThat(result.getPriority()).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("should throw BadRequestException for invalid priority")
        void shouldThrowForInvalidPriorityOnCreate() {
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("Todo")
                    .priority("URGENT")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() -> todoService.createTodo("testuser", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid priority");
        }

        @Test
        @DisplayName("should handle case-insensitive priority")
        void shouldHandleCaseInsensitivePriority() {
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("Todo")
                    .priority("high")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> {
                Todo saved = invocation.getArgument(0);
                saved.setId(2L);
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            TodoResponse result = todoService.createTodo("testuser", request);

            assertThat(result.getPriority()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("should create audit log entry with CREATED action type")
        void shouldCreateAuditLogOnCreate() {
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("Audited Todo")
                    .description("Description for audit")
                    .priority("HIGH")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> {
                Todo saved = invocation.getArgument(0);
                saved.setId(2L);
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            todoService.createTodo("testuser", request);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            TodoAuditLog savedAuditLog = auditLogCaptor.getValue();
            assertThat(savedAuditLog.getActionType()).isEqualTo(TodoAuditActionType.CREATED);
            assertThat(savedAuditLog.getTodo().getId()).isEqualTo(2L);
            assertThat(savedAuditLog.getUser()).isEqualTo(testUser);
            assertThat(savedAuditLog.getCreatedBy()).isEqualTo("testuser");
            assertThat(savedAuditLog.getSnapshot()).isNotNull();
            assertThat(savedAuditLog.getSnapshot()).contains("Audited Todo");
            assertThat(savedAuditLog.getSnapshot()).contains("HIGH");
        }

        @Test
        @DisplayName("should include all todo fields in audit log snapshot")
        void shouldIncludeAllFieldsInAuditLogSnapshot() {
            LocalDate dueDate = LocalDate.now().plusDays(5);
            CreateTodoRequest request = CreateTodoRequest.builder()
                    .title("Complete Todo")
                    .description("Full description")
                    .priority("LOW")
                    .dueDate(dueDate)
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> {
                Todo saved = invocation.getArgument(0);
                saved.setId(3L);
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            todoService.createTodo("testuser", request);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            String snapshot = auditLogCaptor.getValue().getSnapshot();
            assertThat(snapshot).contains("Complete Todo");
            assertThat(snapshot).contains("Full description");
            assertThat(snapshot).contains("LOW");
            assertThat(snapshot).contains("false"); // completed status
        }
    }

    @Nested
    @DisplayName("updateTodo")
    class UpdateTodoTests {

        @Test
        @DisplayName("should update all fields")
        void shouldUpdateAllFields() {
            UpdateTodoRequest request = UpdateTodoRequest.builder()
                    .title("Updated Title")
                    .description("Updated Description")
                    .completed(true)
                    .priority("LOW")
                    .dueDate(LocalDate.now().plusDays(10))
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.updateTodo("testuser", 1L, request);

            assertThat(result.getTitle()).isEqualTo("Updated Title");
            assertThat(result.getDescription()).isEqualTo("Updated Description");
            assertThat(result.getCompleted()).isTrue();
            assertThat(result.getPriority()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("should update only specified fields")
        void shouldUpdateOnlySpecifiedFields() {
            UpdateTodoRequest request = UpdateTodoRequest.builder()
                    .title("Only Title Updated")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.updateTodo("testuser", 1L, request);

            assertThat(result.getTitle()).isEqualTo("Only Title Updated");
            assertThat(result.getDescription()).isEqualTo("Test Description");
            assertThat(result.getPriority()).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("should throw BadRequestException for invalid priority on update")
        void shouldThrowForInvalidPriorityOnUpdate() {
            UpdateTodoRequest request = UpdateTodoRequest.builder()
                    .priority("CRITICAL")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));

            assertThatThrownBy(() -> todoService.updateTodo("testuser", 1L, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid priority");
        }

        @Test
        @DisplayName("should create UPDATED audit log entry on update")
        void shouldCreateUpdatedAuditLogEntryOnUpdate() throws Exception {
            UpdateTodoRequest request = UpdateTodoRequest.builder()
                    .title("Updated Title")
                    .description("Updated Description")
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            todoService.updateTodo("testuser", 1L, request);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            TodoAuditLog savedAuditLog = auditLogCaptor.getValue();
            assertThat(savedAuditLog.getActionType()).isEqualTo(TodoAuditActionType.UPDATED);
            assertThat(savedAuditLog.getCreatedBy()).isEqualTo("testuser");
            assertThat(savedAuditLog.getTodo()).isEqualTo(testTodo);

            String snapshot = savedAuditLog.getSnapshot();
            assertThat(snapshot).contains("Updated Title");
            assertThat(snapshot).contains("Updated Description");
        }
    }

    @Nested
    @DisplayName("toggleTodoCompletion")
    class ToggleTodoCompletionTests {

        @Test
        @DisplayName("should toggle from incomplete to complete")
        void shouldToggleToComplete() {
            testTodo.setCompleted(false);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.toggleTodoCompletion("testuser", 1L);

            assertThat(result.getCompleted()).isTrue();
        }

        @Test
        @DisplayName("should toggle from complete to incomplete")
        void shouldToggleToIncomplete() {
            testTodo.setCompleted(true);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.toggleTodoCompletion("testuser", 1L);

            assertThat(result.getCompleted()).isFalse();
        }

        @Test
        @DisplayName("should create COMPLETED audit log entry when toggling to complete")
        void shouldCreateCompletedAuditLogEntryOnToggleToComplete() throws Exception {
            testTodo.setCompleted(false);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            todoService.toggleTodoCompletion("testuser", 1L);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            TodoAuditLog savedAuditLog = auditLogCaptor.getValue();
            assertThat(savedAuditLog.getActionType()).isEqualTo(TodoAuditActionType.COMPLETED);
            assertThat(savedAuditLog.getCreatedBy()).isEqualTo("testuser");

            String snapshot = savedAuditLog.getSnapshot();
            assertThat(snapshot).contains("true"); // completed status
        }

        @Test
        @DisplayName("should create UNCOMPLETED audit log entry when toggling to incomplete")
        void shouldCreateUncompletedAuditLogEntryOnToggleToIncomplete() throws Exception {
            testTodo.setCompleted(true);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            todoService.toggleTodoCompletion("testuser", 1L);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            TodoAuditLog savedAuditLog = auditLogCaptor.getValue();
            assertThat(savedAuditLog.getActionType()).isEqualTo(TodoAuditActionType.UNCOMPLETED);
            assertThat(savedAuditLog.getCreatedBy()).isEqualTo("testuser");

            String snapshot = savedAuditLog.getSnapshot();
            assertThat(snapshot).contains("false"); // completed status
        }
    }

    @Nested
    @DisplayName("deleteTodo")
    class DeleteTodoTests {

        @Test
        @DisplayName("should delete existing todo")
        void shouldDeleteExistingTodo() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            todoService.deleteTodo("testuser", 1L);

            verify(todoRepository).save(argThat(todo -> todo.getDeletedAt() != null));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent todo")
        void shouldThrowForNonExistentTodoOnDelete() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.deleteTodo("testuser", 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("should create DELETED audit log entry before soft delete")
        void shouldCreateDeletedAuditLogEntryOnDelete() throws Exception {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            todoService.deleteTodo("testuser", 1L);

            ArgumentCaptor<TodoAuditLog> auditLogCaptor = ArgumentCaptor.forClass(TodoAuditLog.class);
            verify(todoAuditLogRepository).save(auditLogCaptor.capture());

            TodoAuditLog savedAuditLog = auditLogCaptor.getValue();
            assertThat(savedAuditLog.getActionType()).isEqualTo(TodoAuditActionType.DELETED);
            assertThat(savedAuditLog.getCreatedBy()).isEqualTo("testuser");
            assertThat(savedAuditLog.getTodo()).isEqualTo(testTodo);

            String snapshot = savedAuditLog.getSnapshot();
            assertThat(snapshot).contains("Test Todo");
            assertThat(snapshot).contains("Test Description");
        }
    }
}
