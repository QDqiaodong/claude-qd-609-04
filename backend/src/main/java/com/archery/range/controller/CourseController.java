package com.archery.range.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.CourseDtos;
import com.archery.range.service.CourseService;

import jakarta.validation.Valid;

/**
 * 模块四：课程预约（课程名 / 教练 / 等级 / 上课时间 / 人数上限 / 已报名 / 场地）。
 */
@RestController
@RequestMapping("/api")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping("/courses")
    public ApiResponse<List<CourseDtos.CourseView>> list(@RequestParam(required = false) String level) {
        if (level != null && !level.isBlank()) {
            return ApiResponse.success(courseService.listByLevel(level));
        }
        return ApiResponse.success(courseService.list());
    }

    @PostMapping("/courses")
    public ApiResponse<CourseDtos.CourseView> create(@Valid @RequestBody CourseDtos.CourseSaveReq req) {
        return ApiResponse.success("课程已发布", courseService.create(req));
    }

    @PostMapping("/courses/{id}/enroll")
    public ApiResponse<CourseDtos.CourseView> enroll(@PathVariable Long id,
            @Valid @RequestBody CourseDtos.EnrollReq req) {
        return ApiResponse.success("报名成功", courseService.enroll(id, req));
    }

    @PostMapping("/courses/{id}/cancel")
    public ApiResponse<CourseDtos.CourseView> cancel(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        return ApiResponse.success("已取消报名", courseService.cancel(id, body.get("memberId")));
    }

    @GetMapping("/courses/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("levels", List.of(
                Map.of("code", "BASIC", "name", RangeDict.courseLevelName("BASIC"),
                        "minLevel", RangeDict.courseMinMemberLevel("BASIC"),
                        "minLevelName", RangeDict.memberLevelName(RangeDict.courseMinMemberLevel("BASIC"))),
                Map.of("code", "ADVANCED", "name", RangeDict.courseLevelName("ADVANCED"),
                        "minLevel", RangeDict.courseMinMemberLevel("ADVANCED"),
                        "minLevelName", RangeDict.memberLevelName(RangeDict.courseMinMemberLevel("ADVANCED"))),
                Map.of("code", "COMPETITION", "name", RangeDict.courseLevelName("COMPETITION"),
                        "minLevel", RangeDict.courseMinMemberLevel("COMPETITION"),
                        "minLevelName", RangeDict.memberLevelName(RangeDict.courseMinMemberLevel("COMPETITION")))));
        return ApiResponse.success(options);
    }
}
