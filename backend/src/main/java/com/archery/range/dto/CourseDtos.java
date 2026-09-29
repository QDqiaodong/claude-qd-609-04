package com.archery.range.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 课程预约模块的入参与出参。
 */
public final class CourseDtos {

    private CourseDtos() {
    }

    public record CourseSaveReq(
            @NotBlank(message = "课程名不能为空") String courseName,
            @NotBlank(message = "教练不能为空") String coach,
            @NotBlank(message = "请选择课程等级") String level,
            @NotNull(message = "请选择上课时间") LocalDateTime classTime,
            @NotNull(message = "请填写人数上限") Integer capacity,
            @NotBlank(message = "请填写场地") String venue) {
    }

    public record EnrollReq(@NotNull(message = "请选择会员") Long memberId) {
    }

    public record EnrollView(Long id, Long memberId, String memberName, String memberCardNo, LocalDateTime enrollTime) {
    }

    public record CourseView(
            Long id,
            String courseName,
            String coach,
            String level,
            String levelName,
            LocalDateTime classTime,
            Integer capacity,
            Integer enrolled,
            String venue,
            Boolean full,
            List<EnrollView> enrolls) {
    }
}
