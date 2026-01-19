package com.acme.todo.repository;

import com.acme.todo.entity.Todo;
import com.acme.todo.entity.Todo.Priority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    List<Todo> findByUserIdAndCompletedAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Boolean completed);

    List<Todo> findByUserIdAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Priority priority);

    List<Todo> findByUserIdAndCompletedAndPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId, Boolean completed, Priority priority);

    Optional<Todo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * Find a todo by ID and user ID, including soft-deleted todos.
     * Used for retrieving history of deleted todos.
     */
    Optional<Todo> findByIdAndUserId(Long id, Long userId);

    /**
     * Find urgent todos (overdue or due today) for a user.
     * Returns todos where dueDate <= today, not completed, and not deleted.
     * Sorted by priority (HIGH first) then by due date (oldest first).
     */
    @Query("SELECT t FROM Todo t WHERE t.user.id = :userId " +
           "AND t.dueDate <= :today " +
           "AND t.completed = false " +
           "AND t.deletedAt IS NULL " +
           "ORDER BY t.priority DESC, t.dueDate ASC")
    List<Todo> findUrgentByUserId(@Param("userId") Long userId, @Param("today") LocalDate today);

    void deleteByIdAndUserId(Long id, Long userId);
}
