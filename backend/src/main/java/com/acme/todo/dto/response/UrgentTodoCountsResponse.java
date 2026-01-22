package com.acme.todo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrgentTodoCountsResponse {

    private int overdue;
    private int dueToday;
    private int total;

    public static UrgentTodoCountsResponse of(int overdue, int dueToday) {
        return UrgentTodoCountsResponse.builder()
                .overdue(overdue)
                .dueToday(dueToday)
                .total(overdue + dueToday)
                .build();
    }
}
