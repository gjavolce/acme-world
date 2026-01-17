package com.acme.todo.service;

import com.acme.todo.dto.request.CreateTodoRequest;
import com.acme.todo.dto.request.UpdateTodoRequest;
import com.acme.todo.dto.response.TodoResponse;
import com.acme.todo.entity.Todo;
import com.acme.todo.entity.Todo.Priority;
import com.acme.todo.entity.User;
import com.acme.todo.exception.BadRequestException;
import com.acme.todo.exception.ResourceNotFoundException;
import com.acme.todo.repository.TodoRepository;
import com.acme.todo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TodoService todoService;

    private User testUser;
    private Todo testTodo;

    @BeforeEach
    void setUp() {
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
    @DisplayName("getAllTodos")
    class GetAllTodosTests {

        @Test
        @DisplayName("should return all todos for user without filters")
        void shouldReturnAllTodosWithoutFilters() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(testTodo));

            List<TodoResponse> result = todoService.getAllTodos("testuser", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Test Todo");
            verify(todoRepository).findByUserIdOrderByCreatedAtDesc(1L);
        }

        @Test
        @DisplayName("should filter by completed status")
        void shouldFilterByCompletedStatus() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndCompletedOrderByCreatedAtDesc(1L, false))
                    .thenReturn(List.of(testTodo));

            List<TodoResponse> result = todoService.getAllTodos("testuser", false, null);

            assertThat(result).hasSize(1);
            verify(todoRepository).findByUserIdAndCompletedOrderByCreatedAtDesc(1L, false);
        }

        @Test
        @DisplayName("should filter by priority")
        void shouldFilterByPriority() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndPriorityOrderByCreatedAtDesc(1L, Priority.HIGH))
                    .thenReturn(List.of());

            List<TodoResponse> result = todoService.getAllTodos("testuser", null, "HIGH");

            assertThat(result).isEmpty();
            verify(todoRepository).findByUserIdAndPriorityOrderByCreatedAtDesc(1L, Priority.HIGH);
        }

        @Test
        @DisplayName("should filter by both completed and priority")
        void shouldFilterByCompletedAndPriority() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByUserIdAndCompletedAndPriorityOrderByCreatedAtDesc(1L, true, Priority.LOW))
                    .thenReturn(List.of());

            List<TodoResponse> result = todoService.getAllTodos("testuser", true, "LOW");

            assertThat(result).isEmpty();
            verify(todoRepository).findByUserIdAndCompletedAndPriorityOrderByCreatedAtDesc(1L, true, Priority.LOW);
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
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));

            TodoResponse result = todoService.getTodoById("testuser", 1L);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getTitle()).isEqualTo("Test Todo");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent todo")
        void shouldThrowForNonExistentTodo() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.getTodoById("testuser", 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");
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
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
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
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
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
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));

            assertThatThrownBy(() -> todoService.updateTodo("testuser", 1L, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid priority");
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
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.toggleTodoCompletion("testuser", 1L);

            assertThat(result.getCompleted()).isTrue();
        }

        @Test
        @DisplayName("should toggle from complete to incomplete")
        void shouldToggleToIncomplete() {
            testTodo.setCompleted(true);
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TodoResponse result = todoService.toggleTodoCompletion("testuser", 1L);

            assertThat(result.getCompleted()).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteTodo")
    class DeleteTodoTests {

        @Test
        @DisplayName("should delete existing todo")
        void shouldDeleteExistingTodo() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(testTodo));
            doNothing().when(todoRepository).delete(testTodo);

            todoService.deleteTodo("testuser", 1L);

            verify(todoRepository).delete(testTodo);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent todo")
        void shouldThrowForNonExistentTodoOnDelete() {
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(todoRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> todoService.deleteTodo("testuser", 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Todo not found");

            verify(todoRepository, never()).delete(any());
        }
    }
}
