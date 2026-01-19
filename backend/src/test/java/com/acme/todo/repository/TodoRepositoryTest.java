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
import java.time.LocalDateTime;
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

        List<Todo> todos = todoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId());

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

        List<Todo> incomplete = todoRepository.findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId(), false);
        List<Todo> complete = todoRepository.findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId(), true);

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

        List<Todo> highPriority = todoRepository.findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId(), Priority.HIGH);
        List<Todo> lowPriority = todoRepository.findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId(), Priority.LOW);

        assertThat(highPriority).hasSize(2);
        assertThat(lowPriority).hasSize(1);
    }

    @Test
    @DisplayName("should find todos by user id, completed and priority")
    void shouldFindByUserIdAndCompletedAndPriority() {
        createTodo("High Complete", Priority.HIGH, true);
        createTodo("High Incomplete", Priority.HIGH, false);
        createTodo("Low Complete", Priority.LOW, true);

        List<Todo> highComplete = todoRepository.findByUserIdAndCompletedAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(
                testUser.getId(), true, Priority.HIGH);

        assertThat(highComplete).hasSize(1);
        assertThat(highComplete.get(0).getTitle()).isEqualTo("High Complete");
    }

    @Test
    @DisplayName("should find todo by id and user id")
    void shouldFindByIdAndUserId() {
        Todo todo = createTodo("My Todo", Priority.MEDIUM, false);

        Optional<Todo> found = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), testUser.getId());
        Optional<Todo> notFound = todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), 99999L);

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

        List<Todo> testUserTodos = todoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(testUser.getId());
        List<Todo> otherUserTodos = todoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(otherUser.getId());

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

    @Test
    @DisplayName("should find urgent todos by due date")
    void shouldFindUrgentTodosByDueDate() {
        LocalDate today = LocalDate.now();
        
        // Create todos with different due dates
        Todo overdue = createTodoWithDueDate("Overdue", Priority.MEDIUM, false, today.minusDays(5));
        Todo dueToday = createTodoWithDueDate("Due Today", Priority.MEDIUM, false, today);
        Todo dueTomorrow = createTodoWithDueDate("Due Tomorrow", Priority.MEDIUM, false, today.plusDays(1));
        Todo dueNextWeek = createTodoWithDueDate("Due Next Week", Priority.MEDIUM, false, today.plusDays(7));

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(testUser.getId(), today);

        assertThat(urgentTodos).hasSize(2);
        assertThat(urgentTodos).extracting(Todo::getTitle)
                .containsExactlyInAnyOrder("Overdue", "Due Today");
    }

    @Test
    @DisplayName("should exclude completed todos from urgent list")
    void shouldExcludeCompletedTodosFromUrgentList() {
        LocalDate today = LocalDate.now();
        
        // Create incomplete and completed overdue todos
        createTodoWithDueDate("Incomplete Overdue", Priority.HIGH, false, today.minusDays(1));
        createTodoWithDueDate("Completed Overdue", Priority.HIGH, true, today.minusDays(2));
        createTodoWithDueDate("Completed Due Today", Priority.MEDIUM, true, today);

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(testUser.getId(), today);

        assertThat(urgentTodos).hasSize(1);
        assertThat(urgentTodos.get(0).getTitle()).isEqualTo("Incomplete Overdue");
        assertThat(urgentTodos.get(0).getCompleted()).isFalse();
    }

    @Test
    @DisplayName("should exclude soft-deleted todos from urgent list")
    void shouldExcludeSoftDeletedTodosFromUrgentList() {
        LocalDate today = LocalDate.now();
        
        // Create active and soft-deleted overdue todos
        createTodoWithDueDate("Active Overdue", Priority.HIGH, false, today.minusDays(1));
        
        Todo softDeleted = createTodoWithDueDate("Soft Deleted Overdue", Priority.HIGH, false, today.minusDays(2));
        softDeleted.setDeletedAt(LocalDateTime.now());
        todoRepository.save(softDeleted);

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(testUser.getId(), today);

        assertThat(urgentTodos).hasSize(1);
        assertThat(urgentTodos.get(0).getTitle()).isEqualTo("Active Overdue");
        assertThat(urgentTodos.get(0).getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("should sort urgent todos by priority desc then due date asc")
    void shouldSortUrgentTodosByPriorityAndDueDate() {
        LocalDate today = LocalDate.now();
        
        // Create todos with different priorities and due dates
        // Expected order: HIGH priority first (oldest due date first), then MEDIUM, then LOW
        Todo lowOldest = createTodoWithDueDate("Low Oldest", Priority.LOW, false, today.minusDays(10));
        Todo mediumRecent = createTodoWithDueDate("Medium Recent", Priority.MEDIUM, false, today.minusDays(2));
        Todo highOldest = createTodoWithDueDate("High Oldest", Priority.HIGH, false, today.minusDays(5));
        Todo highRecent = createTodoWithDueDate("High Recent", Priority.HIGH, false, today.minusDays(1));
        Todo mediumOldest = createTodoWithDueDate("Medium Oldest", Priority.MEDIUM, false, today.minusDays(7));
        Todo lowRecent = createTodoWithDueDate("Low Recent", Priority.LOW, false, today.minusDays(3));

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(testUser.getId(), today);

        assertThat(urgentTodos).hasSize(6);
        // HIGH priority first, ordered by due date (oldest first)
        assertThat(urgentTodos.get(0).getTitle()).isEqualTo("High Oldest");
        assertThat(urgentTodos.get(0).getPriority()).isEqualTo(Priority.HIGH);
        assertThat(urgentTodos.get(1).getTitle()).isEqualTo("High Recent");
        assertThat(urgentTodos.get(1).getPriority()).isEqualTo(Priority.HIGH);
        // MEDIUM priority next, ordered by due date (oldest first)
        assertThat(urgentTodos.get(2).getTitle()).isEqualTo("Medium Oldest");
        assertThat(urgentTodos.get(2).getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(urgentTodos.get(3).getTitle()).isEqualTo("Medium Recent");
        assertThat(urgentTodos.get(3).getPriority()).isEqualTo(Priority.MEDIUM);
        // LOW priority last, ordered by due date (oldest first)
        assertThat(urgentTodos.get(4).getTitle()).isEqualTo("Low Oldest");
        assertThat(urgentTodos.get(4).getPriority()).isEqualTo(Priority.LOW);
        assertThat(urgentTodos.get(5).getTitle()).isEqualTo("Low Recent");
        assertThat(urgentTodos.get(5).getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    @DisplayName("should return empty list when no urgent todos exist")
    void shouldReturnEmptyListWhenNoUrgentTodosExist() {
        LocalDate today = LocalDate.now();
        
        // Create only future todos
        createTodoWithDueDate("Future 1", Priority.HIGH, false, today.plusDays(1));
        createTodoWithDueDate("Future 2", Priority.MEDIUM, false, today.plusDays(7));

        List<Todo> urgentTodos = todoRepository.findUrgentByUserId(testUser.getId(), today);

        assertThat(urgentTodos).isEmpty();
    }

    @Test
    @DisplayName("should find urgent todos only for specific user")
    void shouldFindUrgentTodosOnlyForSpecificUser() {
        LocalDate today = LocalDate.now();
        
        // Create another user
        User otherUser = User.builder()
                .username("otheruser")
                .email("other@example.com")
                .passwordHash("password")
                .enabled(true)
                .build();
        otherUser = userRepository.save(otherUser);

        // Create overdue todos for both users
        createTodoWithDueDate("Test User Overdue", Priority.HIGH, false, today.minusDays(1));
        
        Todo otherUserTodo = Todo.builder()
                .user(otherUser)
                .title("Other User Overdue")
                .priority(Priority.HIGH)
                .completed(false)
                .dueDate(today.minusDays(1))
                .build();
        todoRepository.save(otherUserTodo);

        List<Todo> testUserUrgent = todoRepository.findUrgentByUserId(testUser.getId(), today);
        List<Todo> otherUserUrgent = todoRepository.findUrgentByUserId(otherUser.getId(), today);

        assertThat(testUserUrgent).hasSize(1);
        assertThat(testUserUrgent.get(0).getTitle()).isEqualTo("Test User Overdue");
        assertThat(otherUserUrgent).hasSize(1);
        assertThat(otherUserUrgent.get(0).getTitle()).isEqualTo("Other User Overdue");
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

    private Todo createTodoWithDueDate(String title, Priority priority, boolean completed, LocalDate dueDate) {
        Todo todo = Todo.builder()
                .user(testUser)
                .title(title)
                .priority(priority)
                .completed(completed)
                .dueDate(dueDate)
                .build();
        return todoRepository.save(todo);
    }
}
