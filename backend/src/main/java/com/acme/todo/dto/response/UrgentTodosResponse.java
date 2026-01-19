package com.acme.todo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrgentTodosResponse {

    private List<TodoResponse> overdue;
    private List<TodoResponse> dueToday;
    private UrgentTodoCountsResponse counts;

    public static UrgentTodosResponse of(List<TodoResponse> overdue, List<TodoResponse> dueToday) {
        return UrgentTodosResponse.builder()
                .overdue(overdue)
                .dueToday(dueToday)
                .counts(UrgentTodoCountsResponse.of(overdue.size(), dueToday.size()))
                .build();
    }
}
