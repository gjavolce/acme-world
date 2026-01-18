package com.acme.todo.integration;

import com.acme.todo.dto.request.CreateTodoRequest;
import com.acme.todo.dto.request.RegisterRequest;
import com.acme.todo.dto.request.UpdateTodoRequest;
import com.acme.todo.dto.response.TodoResponse;
import com.acme.todo.entity.TodoAuditActionType;
import com.acme.todo.entity.TodoAuditLog;
import com.acme.todo.repository.TodoAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TodoControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TodoAuditLogRepository todoAuditLogRepository;

    private String authToken;
    private static final String TEST_USERNAME = "todouser";
    private static final String TEST_PASSWORD = "password123";

    @BeforeEach
    void setUp() throws Exception {
        ensureUserExists();
        authToken = authenticateAndGetToken(TEST_USERNAME, TEST_PASSWORD);
    }

    private void ensureUserExists() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .username(TEST_USERNAME)
                .email("todouser@example.com")
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post(getBaseUrl() + "/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(registerRequest)));
        // Ignore result - user may already exist from previous test in same run
    }

    @Test
    @DisplayName("Should create a new todo")
    void shouldCreateTodo() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Test Todo")
                .description("Test Description")
                .priority("HIGH")
                .dueDate(LocalDate.now().plusDays(7))
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Test Todo"))
                .andExpect(jsonPath("$.description").value("Test Description"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.completed").value(false));
    }

    @Test
    @DisplayName("Should get all todos for user")
    void shouldGetAllTodos() throws Exception {
        // Create a todo first
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Todo for list")
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("Should get todo by id")
    void shouldGetTodoById() throws Exception {
        // Create a todo first
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Todo to get")
                .description("Description")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        mockMvc.perform(get(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId()))
                .andExpect(jsonPath("$.title").value("Todo to get"));
    }

    @Test
    @DisplayName("Should update todo")
    void shouldUpdateTodo() throws Exception {
        // Create a todo first
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Original Title")
                .priority("LOW")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Update the todo
        UpdateTodoRequest updateRequest = UpdateTodoRequest.builder()
                .title("Updated Title")
                .priority("HIGH")
                .build();

        mockMvc.perform(put(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    @DisplayName("Should toggle todo completion")
    void shouldToggleTodoCompletion() throws Exception {
        // Create a todo first
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Todo to toggle")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Toggle completion - should become true
        mockMvc.perform(patch(getBaseUrl() + "/todos/" + created.getId() + "/toggle")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));

        // Toggle again - should become false
        mockMvc.perform(patch(getBaseUrl() + "/todos/" + created.getId() + "/toggle")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false));
    }

    @Test
    @DisplayName("Should delete todo")
    void shouldDeleteTodo() throws Exception {
        // Create a todo first
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Todo to delete")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Delete the todo
        mockMvc.perform(delete(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify it's deleted
        mockMvc.perform(get(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should return 401 for unauthenticated request")
    void shouldReturn401ForUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 404 for non-existent todo")
    void shouldReturn404ForNonExistentTodo() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos/99999")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should filter todos by completed status")
    void shouldFilterTodosByCompleted() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .param("completed", "false")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Should filter todos by priority")
    void shouldFilterTodosByPriority() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .param("priority", "HIGH")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Should return 400 for invalid todo data")
    void shouldReturn400ForInvalidTodoData() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("")  // Empty title
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for invalid priority value")
    void shouldReturn400ForInvalidPriority() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Valid Title")
                .priority("INVALID_PRIORITY")
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Invalid priority")));
    }

    @Test
    @DisplayName("Should handle case-insensitive priority")
    void shouldHandleCaseInsensitivePriority() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Case Test Todo")
                .priority("high")
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    @DisplayName("Should filter by both completed and priority")
    void shouldFilterByCompletedAndPriority() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .param("completed", "true")
                        .param("priority", "HIGH")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Should return 400 for invalid priority filter")
    void shouldReturn400ForInvalidPriorityFilter() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .param("priority", "URGENT")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Invalid priority")));
    }

    @Test
    @DisplayName("Should return 401 for invalid token")
    void shouldReturn401ForInvalidToken() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 401 for expired token format")
    void shouldReturn401ForMalformedToken() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should create todo with default medium priority")
    void shouldCreateTodoWithDefaultPriority() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("No Priority Specified")
                .build();

        mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @Test
    @DisplayName("Should preserve unchanged fields on partial update")
    void shouldPreserveUnchangedFieldsOnPartialUpdate() throws Exception {
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Original")
                .description("Original Description")
                .priority("HIGH")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        UpdateTodoRequest updateRequest = UpdateTodoRequest.builder()
                .title("Updated Title Only")
                .build();

        mockMvc.perform(put(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title Only"))
                .andExpect(jsonPath("$.description").value("Original Description"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    @DisplayName("Should create CREATED audit log entry when todo is created")
    void shouldCreateAuditLogEntryOnTodoCreation() throws Exception {
        CreateTodoRequest request = CreateTodoRequest.builder()
                .title("Audited Todo Creation")
                .description("This should be audited")
                .priority("HIGH")
                .dueDate(LocalDate.now().plusDays(7))
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Verify audit log was created
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(created.getId());
        
        assertThat(auditLogs).hasSize(1);
        
        TodoAuditLog auditLog = auditLogs.get(0);
        assertThat(auditLog.getActionType()).isEqualTo(TodoAuditActionType.CREATED);
        assertThat(auditLog.getCreatedBy()).isEqualTo(TEST_USERNAME);
        assertThat(auditLog.getSnapshot()).isNotNull();
        assertThat(auditLog.getSnapshot()).contains("Audited Todo Creation");
        assertThat(auditLog.getSnapshot()).contains("This should be audited");
        assertThat(auditLog.getSnapshot()).contains("HIGH");
    }

    @Test
    @DisplayName("Should create UPDATED audit log entry when todo is updated")
    void shouldCreateAuditLogEntryOnTodoUpdate() throws Exception {
        // Create a todo first
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Original Title")
                .description("Original Description")
                .priority("LOW")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Update the todo
        UpdateTodoRequest updateRequest = UpdateTodoRequest.builder()
                .title("Updated Title")
                .description("Updated Description")
                .priority("HIGH")
                .build();

        mockMvc.perform(put(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(updateRequest)))
                .andExpect(status().isOk());

        // Verify audit logs (should have CREATED and UPDATED)
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(created.getId());
        
        assertThat(auditLogs).hasSize(2);
        
        // Most recent should be UPDATED
        TodoAuditLog updatedLog = auditLogs.get(0);
        assertThat(updatedLog.getActionType()).isEqualTo(TodoAuditActionType.UPDATED);
        assertThat(updatedLog.getCreatedBy()).isEqualTo(TEST_USERNAME);
        assertThat(updatedLog.getSnapshot()).contains("Updated Title");
        assertThat(updatedLog.getSnapshot()).contains("Updated Description");
        assertThat(updatedLog.getSnapshot()).contains("HIGH");
        
        // Older one should be CREATED
        TodoAuditLog createdLog = auditLogs.get(1);
        assertThat(createdLog.getActionType()).isEqualTo(TodoAuditActionType.CREATED);
    }

    @Test
    @DisplayName("Should create COMPLETED audit log entry when todo is toggled to complete")
    void shouldCreateCompletedAuditLogEntryOnToggle() throws Exception {
        // Create an incomplete todo
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Todo to Complete")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Toggle to complete
        mockMvc.perform(patch(getBaseUrl() + "/todos/" + created.getId() + "/toggle")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));

        // Verify audit logs (should have CREATED and COMPLETED)
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(created.getId());
        
        assertThat(auditLogs).hasSize(2);
        
        // Most recent should be COMPLETED
        TodoAuditLog completedLog = auditLogs.get(0);
        assertThat(completedLog.getActionType()).isEqualTo(TodoAuditActionType.COMPLETED);
        assertThat(completedLog.getCreatedBy()).isEqualTo(TEST_USERNAME);
        assertThat(completedLog.getSnapshot()).contains("true"); // completed status
    }

    @Test
    @DisplayName("Should create UNCOMPLETED audit log entry when todo is toggled to incomplete")
    void shouldCreateUncompletedAuditLogEntryOnToggle() throws Exception {
        // Create a todo and toggle to complete first
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Todo to Uncomplete")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Toggle to complete first
        mockMvc.perform(patch(getBaseUrl() + "/todos/" + created.getId() + "/toggle")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());

        // Toggle back to incomplete
        mockMvc.perform(patch(getBaseUrl() + "/todos/" + created.getId() + "/toggle")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false));

        // Verify audit logs (should have CREATED, COMPLETED, and UNCOMPLETED)
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(created.getId());
        
        assertThat(auditLogs).hasSize(3);
        
        // Most recent should be UNCOMPLETED
        TodoAuditLog uncompletedLog = auditLogs.get(0);
        assertThat(uncompletedLog.getActionType()).isEqualTo(TodoAuditActionType.UNCOMPLETED);
        assertThat(uncompletedLog.getCreatedBy()).isEqualTo(TEST_USERNAME);
        assertThat(uncompletedLog.getSnapshot()).contains("false"); // completed status
    }

    @Test
    @DisplayName("Should create DELETED audit log entry when todo is deleted")
    void shouldCreateDeletedAuditLogEntryOnDelete() throws Exception {
        // Create a todo
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Todo to Delete")
                .description("This will be deleted")
                .priority("HIGH")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Delete the todo
        mockMvc.perform(delete(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify audit logs (should have CREATED and DELETED)
        List<TodoAuditLog> auditLogs = todoAuditLogRepository.findByTodoIdOrderByCreatedAtDesc(created.getId());
        
        assertThat(auditLogs).hasSize(2);
        
        // Most recent should be DELETED
        TodoAuditLog deletedLog = auditLogs.get(0);
        assertThat(deletedLog.getActionType()).isEqualTo(TodoAuditActionType.DELETED);
        assertThat(deletedLog.getCreatedBy()).isEqualTo(TEST_USERNAME);
        assertThat(deletedLog.getSnapshot()).contains("Todo to Delete");
        assertThat(deletedLog.getSnapshot()).contains("This will be deleted");
        assertThat(deletedLog.getSnapshot()).contains("HIGH");
        
        // Verify the todo is soft-deleted (not accessible via API)
        mockMvc.perform(get(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should get todo history via API endpoint")
    void shouldGetTodoHistoryViaApi() throws Exception {
        // Create a todo
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Todo for History API")
                .description("Testing history endpoint")
                .priority("MEDIUM")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Update the todo to add more history
        UpdateTodoRequest updateRequest = UpdateTodoRequest.builder()
                .title("Updated Todo for History")
                .build();

        mockMvc.perform(put(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(updateRequest)))
                .andExpect(status().isOk());

        // Get history via API endpoint
        mockMvc.perform(get(getBaseUrl() + "/todos/" + created.getId() + "/history")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].actionType").value("UPDATED"))
                .andExpect(jsonPath("$[0].createdBy").value(TEST_USERNAME))
                .andExpect(jsonPath("$[0].snapshot").isNotEmpty())
                .andExpect(jsonPath("$[1].actionType").value("CREATED"))
                .andExpect(jsonPath("$[1].createdBy").value(TEST_USERNAME));
    }

    @Test
    @DisplayName("Should return 404 when getting history for non-existent todo")
    void shouldReturn404WhenGettingHistoryForNonExistentTodo() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos/99999/history")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should return 401 when getting history without authentication")
    void shouldReturn401WhenGettingHistoryWithoutAuth() throws Exception {
        mockMvc.perform(get(getBaseUrl() + "/todos/1/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return history for soft-deleted todo")
    void shouldReturnHistoryForSoftDeletedTodo() throws Exception {
        // Create a todo
        CreateTodoRequest createRequest = CreateTodoRequest.builder()
                .title("Todo to be soft-deleted")
                .build();

        MvcResult createResult = mockMvc.perform(post(getBaseUrl() + "/todos")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TodoResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TodoResponse.class
        );

        // Delete the todo (soft delete)
        mockMvc.perform(delete(getBaseUrl() + "/todos/" + created.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Should still be able to access history
        mockMvc.perform(get(getBaseUrl() + "/todos/" + created.getId() + "/history")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].actionType").value("DELETED"))
                .andExpect(jsonPath("$[1].actionType").value("CREATED"));
    }
}
