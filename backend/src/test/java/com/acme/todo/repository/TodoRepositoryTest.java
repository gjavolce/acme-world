package com.acme.todo.repository;

import com.acme.todo.entity.Todo;
import com.acme.todo.entity.Todo.Priority;
import com.acme.todo.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TodoRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("todo_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        todoRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("repotest")
                .email("repotest@example.com")
                .passwordHash("hashedpassword")
                .enabled(true)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("should save and retrieve todo")
    void shouldSaveAndRetrieveTodo() {
        Todo todo = Todo.builder()
                .user(testUser)
                .title("Test Todo")
                .description("Description")
                .priority(Priority.HIGH)
                .completed(false)
                .dueDate(LocalDate.now().plusDays(7))
                .build();

        Todo saved = todoRepository.save(todo);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<Todo> found = todoRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test Todo");
    }

    @Test
    @DisplayName("should find todos by user id ordered by created at desc")
    void shouldFindByUserIdOrderedByCreatedAtDesc() throws InterruptedException {
        Todo todo1 = createTodo("First", Priority.LOW, false);
        Thread.sleep(10); // Ensure different timestamps
        Todo todo2 = createTodo("Second", Priority.MEDIUM, false);
        Thread.sleep(10);
        Todo todo3 = createTodo("Third", Priority.HIGH, true);

        List<Todo> todos = todoRepository.findByUserIdOrderByCreatedAtDesc(testUser.getId());

        assertThat(todos).hasSize(3);
        assertThat(todos.get(0).getTitle()).isEqualTo("Third");
        assertThat(todos.get(1).getTitle()).isEqualTo("Second");
        assertThat(todos.get(2).getTitle()).isEqualTo("First");
    }

    @Test
    @DisplayName("should find todos by user id and completed status")
    void shouldFindByUserIdAndCompleted() {
        createTodo("Incomplete 1", Priority.LOW, false);
        createTodo("Incomplete 2", Priority.MEDIUM, false);
        createTodo("Complete", Priority.HIGH, true);

        List<Todo> incomplete = todoRepository.findByUserIdAndCompletedOrderByCreatedAtDesc(testUser.getId(), false);
        List<Todo> complete = todoRepository.findByUserIdAndCompletedOrderByCreatedAtDesc(testUser.getId(), true);

        assertThat(incomplete).hasSize(2);
        assertThat(complete).hasSize(1);
        assertThat(complete.get(0).getTitle()).isEqualTo("Complete");
    }

    @Test
    @DisplayName("should find todos by user id and priority")
    void shouldFindByUserIdAndPriority() {
        createTodo("Low Priority", Priority.LOW, false);
        createTodo("High Priority 1", Priority.HIGH, false);
        createTodo("High Priority 2", Priority.HIGH, true);

        List<Todo> highPriority = todoRepository.findByUserIdAndPriorityOrderByCreatedAtDesc(testUser.getId(), Priority.HIGH);
        List<Todo> lowPriority = todoRepository.findByUserIdAndPriorityOrderByCreatedAtDesc(testUser.getId(), Priority.LOW);

        assertThat(highPriority).hasSize(2);
        assertThat(lowPriority).hasSize(1);
    }

    @Test
    @DisplayName("should find todos by user id, completed and priority")
    void shouldFindByUserIdAndCompletedAndPriority() {
        createTodo("High Complete", Priority.HIGH, true);
        createTodo("High Incomplete", Priority.HIGH, false);
        createTodo("Low Complete", Priority.LOW, true);

        List<Todo> highComplete = todoRepository.findByUserIdAndCompletedAndPriorityOrderByCreatedAtDesc(
                testUser.getId(), true, Priority.HIGH);

        assertThat(highComplete).hasSize(1);
        assertThat(highComplete.get(0).getTitle()).isEqualTo("High Complete");
    }

    @Test
    @DisplayName("should find todo by id and user id")
    void shouldFindByIdAndUserId() {
        Todo todo = createTodo("My Todo", Priority.MEDIUM, false);

        Optional<Todo> found = todoRepository.findByIdAndUserId(todo.getId(), testUser.getId());
        Optional<Todo> notFound = todoRepository.findByIdAndUserId(todo.getId(), 99999L);

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("My Todo");
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("should not return todos from other users")
    void shouldNotReturnTodosFromOtherUsers() {
        User otherUser = User.builder()
                .username("otheruser")
                .email("other@example.com")
                .passwordHash("password")
                .enabled(true)
                .build();
        otherUser = userRepository.save(otherUser);

        createTodo("Test User Todo", Priority.HIGH, false);

        Todo otherTodo = Todo.builder()
                .user(otherUser)
                .title("Other User Todo")
                .priority(Priority.LOW)
                .completed(false)
                .build();
        todoRepository.save(otherTodo);

        List<Todo> testUserTodos = todoRepository.findByUserIdOrderByCreatedAtDesc(testUser.getId());
        List<Todo> otherUserTodos = todoRepository.findByUserIdOrderByCreatedAtDesc(otherUser.getId());

        assertThat(testUserTodos).hasSize(1);
        assertThat(testUserTodos.get(0).getTitle()).isEqualTo("Test User Todo");
        assertThat(otherUserTodos).hasSize(1);
        assertThat(otherUserTodos.get(0).getTitle()).isEqualTo("Other User Todo");
    }

    @Test
    @DisplayName("should delete todo")
    void shouldDeleteTodo() {
        Todo todo = createTodo("To Delete", Priority.MEDIUM, false);
        Long todoId = todo.getId();

        todoRepository.delete(todo);

        Optional<Todo> deleted = todoRepository.findById(todoId);
        assertThat(deleted).isEmpty();
    }

    @Test
    @DisplayName("should update todo")
    void shouldUpdateTodo() {
        Todo todo = createTodo("Original Title", Priority.LOW, false);

        todo.setTitle("Updated Title");
        todo.setPriority(Priority.HIGH);
        todo.setCompleted(true);
        todoRepository.save(todo);

        Todo updated = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(updated.getCompleted()).isTrue();
    }

    private Todo createTodo(String title, Priority priority, boolean completed) {
        Todo todo = Todo.builder()
                .user(testUser)
                .title(title)
                .priority(priority)
                .completed(completed)
                .build();
        return todoRepository.save(todo);
    }
}
