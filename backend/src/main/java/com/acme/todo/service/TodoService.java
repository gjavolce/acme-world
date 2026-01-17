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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<TodoResponse> getAllTodos(String username, Boolean completed, String priority) {
        User user = getUserByUsername(username);
        List<Todo> todos;

        if (completed != null && priority != null) {
            Priority p = parsePriority(priority);
            todos = todoRepository.findByUserIdAndCompletedAndPriorityOrderByCreatedAtDesc(
                    user.getId(), completed, p);
        } else if (completed != null) {
            todos = todoRepository.findByUserIdAndCompletedOrderByCreatedAtDesc(user.getId(), completed);
        } else if (priority != null) {
            Priority p = parsePriority(priority);
            todos = todoRepository.findByUserIdAndPriorityOrderByCreatedAtDesc(user.getId(), p);
        } else {
            todos = todoRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        }

        return todos.stream()
                .map(TodoResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TodoResponse getTodoById(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));
        return TodoResponse.fromEntity(todo);
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
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public TodoResponse updateTodo(String username, Long id, UpdateTodoRequest request) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserId(id, user.getId())
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
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public TodoResponse toggleTodoCompletion(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));

        todo.setCompleted(!todo.getCompleted());
        todo = todoRepository.save(todo);
        return TodoResponse.fromEntity(todo);
    }

    @Transactional
    public void deleteTodo(String username, Long id) {
        User user = getUserByUsername(username);
        Todo todo = todoRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Todo not found with id: " + id));
        todoRepository.delete(todo);
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
}
