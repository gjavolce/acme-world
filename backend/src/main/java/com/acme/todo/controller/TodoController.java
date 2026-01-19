package com.acme.todo.controller;

import com.acme.todo.dto.request.CreateTodoRequest;
import com.acme.todo.dto.request.UpdateTodoRequest;
import com.acme.todo.dto.response.TodoAuditLogResponse;
import com.acme.todo.dto.response.TodoResponse;
import com.acme.todo.dto.response.UrgentTodosResponse;
import com.acme.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
@Tag(name = "Todos", description = "Todo management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class TodoController {

    private final TodoService todoService;

    @GetMapping
    @Operation(summary = "Get all todos for current user")
    public ResponseEntity<List<TodoResponse>> getAllTodos(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Boolean completed,
            @RequestParam(required = false) String priority) {
        return ResponseEntity.ok(
                todoService.getAllTodos(userDetails.getUsername(), completed, priority));
    }

    @GetMapping("/urgent")
    @Operation(summary = "Get urgent todos (overdue and due today)",
               description = "Returns todos that are overdue or due today, sorted by priority (HIGH first) then by due date (oldest first). " +
                            "Completed and deleted todos are excluded.")
    public ResponseEntity<UrgentTodosResponse> getUrgentTodos(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(todoService.getUrgentTodos(userDetails.getUsername()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a specific todo by ID")
    public ResponseEntity<TodoResponse> getTodoById(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(todoService.getTodoById(userDetails.getUsername(), id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get audit history for a todo",
               description = "Returns the complete audit trail for a todo, including all create, update, toggle, and delete actions. " +
                            "History is available even for soft-deleted todos.")
    public ResponseEntity<List<TodoAuditLogResponse>> getTodoHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(todoService.getTodoHistory(userDetails.getUsername(), id));
    }

    @PostMapping
    @Operation(summary = "Create a new todo")
    public ResponseEntity<TodoResponse> createTodo(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateTodoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(userDetails.getUsername(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing todo")
    public ResponseEntity<TodoResponse> updateTodo(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTodoRequest request) {
        return ResponseEntity.ok(todoService.updateTodo(userDetails.getUsername(), id, request));
    }

    @PatchMapping("/{id}/toggle")
    @Operation(summary = "Toggle todo completion status")
    public ResponseEntity<TodoResponse> toggleTodo(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(todoService.toggleTodoCompletion(userDetails.getUsername(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a todo")
    public ResponseEntity<Void> deleteTodo(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        todoService.deleteTodo(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
