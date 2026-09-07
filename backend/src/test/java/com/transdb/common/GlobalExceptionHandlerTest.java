package com.transdb.common;

import com.transdb.domain.Segment;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void businessExceptionMappedToCodeAndStatus() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("条目不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void validationFailureMappedTo9001() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9001))
                .andExpect(jsonPath("$.message").value("参数校验失败: name 不能为空"));
    }

    @Test
    void unknownPathMappedTo9002() throws Exception {
        mockMvc.perform(get("/test/never-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(9002));
    }

    @Test
    void optimisticLockFailureMappedTo409Code2002() throws Exception {
        mockMvc.perform(get("/test/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    void maxUploadSizeMappedTo400Code3002() throws Exception {
        mockMvc.perform(get("/test/too-large"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3002));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test/business")
        public ApiResponse<String> business() {
            throw BusinessException.of(ErrorCode.SEGMENT_NOT_FOUND);
        }

        @PostMapping("/test/validate")
        public ApiResponse<String> validate(@RequestBody @jakarta.validation.Valid ValidDto dto) {
            return ApiResponse.ok("ok");
        }

        @GetMapping("/test/optimistic")
        public ApiResponse<String> optimistic() {
            throw new ObjectOptimisticLockingFailureException(Segment.class, 42L);
        }

        @GetMapping("/test/too-large")
        public ApiResponse<String> tooLarge() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(50_000_000);
        }
    }

    // 注意：处理程序拼接格式为 "<field> <message>"，此处 message 不重复字段名，
    // 组合结果为 "name 不能为空"（与测试断言一致）。
    record ValidDto(@NotBlank(message = "不能为空") String name) {}
}
