package com.trade.api;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ProcessPlanResponse;
import com.trade.dto.ProcessRequest;
import com.trade.service.ProcessAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 流程规划 REST 接口 —— /api/v1/flows
 *
 * 业务场景：用户将业务目标（如"新员工入职流程"、"系统发布流程"）交给 AI，
 * AI 自动将其拆解为可执行的任务计划，包含任务列表、负责人角色、风险点、监控指标。
 *
 * 技术栈：
 * - Spring WebFlux 响应式框架（非阻塞，高并发）
 * - SSE (Server-Sent Events) 单向流式推送（Content-Type: text/event-stream）
 * - JSR-303 Bean Validation 参数校验（@Valid + @NotBlank/@Size）
 * - Jackson JSON 解析（将 AI 输出的 JSON 转为结构化响应）
 *
 * 端点说明：
 * - POST /assistant：同步流程规划，等待 AI 完整返回后一次性响应
 * - POST /assistant/stream：流式流程规划，AI 逐字推送，前端实时展示生成过程
 */
@Validated
@RestController
@RequestMapping("/api/v1/flows")
@Tag(name = "流程规划", description = "业务流程自动拆解和任务计划生成")
public class ProcessController {

    /**
     * 流程编排助手服务 —— 处理业务逻辑：
     * 1. 将 ProcessRequest 转为 AiPromptRequest（含流程目标、当前状态、变量）
     * 2. 智能路由（FLOW 场景优先阿里百炼，因为长上下文和规划能力强）
     * 3. 调用 AI 模型（含降级：首选失败自动切换 fallback 模型）
     * 4. JSON 解析：将 AI 输出的 JSON 解析为 ProcessPlanResponse（含 ProcessTaskDto 列表）
     * 5. JSON 解析失败时降级为 fallbackPlan（默认模板），保证服务不中断
     */
    private final ProcessAssistantService processService;

    public ProcessController(ProcessAssistantService processService) {
        this.processService = processService;
    }

    /**
     * 同步流程规划 —— 等待 AI 完整返回后一次性响应。
     *
     * 业务流程：
     * 客户端提交 ProcessRequest（goal 目标、processName 流程名、currentState 当前状态）
     * → 构造 System Prompt（要求 AI 输出 JSON 格式的流程计划）
     * → 智能路由选择最优模型（FLOW 场景默认选阿里百炼）
     * → 调用 AI 模型，等待完整 JSON 返回
     * → 解析 JSON：提取 processName、summary、tasks[]、risks[]、monitoringSignals[]
     * → 返回 ProcessPlanResponse 给客户端
     *
     * 请求示例：
     * POST /api/v1/flows/assistant
     * {
     *   "goal": "新员工入职流程规划",
     *   "processName": "入职流程",
     *   "currentState": "新员工已报到，尚未分配工位",
     *   "variables": {"department": "研发部", "level": "P6"}
     * }
     *
     * 响应示例：
     * {
     *   "processName": "入职流程",
     *   "tasks": [
     *     {"order": 1, "name": "分配工位和电脑", "ownerRole": "行政", "status": "TODO"},
     *     {"order": 2, "name": "开通系统账号", "ownerRole": "IT", "status": "TODO"}
     *   ],
     *   "risks": ["工位可能紧张，需提前确认"],
     *   "monitoringSignals": ["入职完成时效", "账号开通延迟"]
     * }
     *
     * 关键技术点：
     * - @Valid 开启参数校验：goal 字段标记了 @NotBlank，为空时返回 400
     * - @RequestBody 将 JSON 请求体反序列化为 ProcessRequest record
     * - Mono<ProcessPlanResponse> 是 Reactor 响应式类型，表示 0 或 1 个响应
     * - WebFlux 的响应式链：Controller 直接将 Mono 返回给框架，框架异步序列化
     */
    @PostMapping("/assistant")
    @Operation(
            summary = "同步流程规划",
            description = "提交业务目标，AI 自动拆解为可执行的任务计划（含任务列表、负责人、风险点、监控指标）"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "成功返回流程计划",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProcessPlanResponse.class),
                            examples = @ExampleObject(
                                    name = "入职流程",
                                    value = """
                                            {
                                              "processName": "入职流程",
                                              "summary": "新员工入职完整流程",
                                              "tasks": [
                                                {
                                                  "order": 1,
                                                  "name": "分配工位和电脑",
                                                  "ownerRole": "行政",
                                                  "status": "TODO"
                                                }
                                              ],
                                              "risks": ["工位可能紧张"],
                                              "monitoringSignals": ["入职完成时效"]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数校验失败（如 goal 为空）"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未授权"
            )
    })
    public Mono<ProcessPlanResponse> plan(@Valid @RequestBody ProcessRequest request) {
        return processService.plan(request);
    }

    /**
     * 流式流程规划 —— SSE (Server-Sent Events) 实时推送 AI 生成过程。
     *
     * 与同步接口的区别：
     * - 同步：等待 AI 完整返回 JSON → 解析 → 一次性返回（适合后端集成调用）
     * - 流式：AI 边生成边推送，前端可以实时展示文字逐字出现（适合前端展示）
     *
     * 业务流程：
     * 1. 构造 prompt（同同步接口）
     * 2. 智能路由（同同步接口）
     * 3. 流式调用模型：AI 逐字返回 token，每个 token 包装为 AiStreamEvent 推送
     * 4. 事件流顺序：
     *    - route 事件：告知客户端"本次使用了哪个模型、为什么选它"
     *    - token 事件（N 次）：每个 token 推送一次，前端逐字拼接展示
     *    - done 事件：流式输出完成
     *    - error 事件：如果 AI 调用失败，推送错误信息
     *
     * 请求示例：
     * POST /api/v1/flows/assistant/stream
     * {
     *   "goal": "新员工入职流程规划",
     *   "processName": "入职流程"
     * }
     *
     * SSE 响应示例（逐行推送）：
     *
     * id: uuid-1
     * event: route
     * data: {"scenario":"FLOW","selectedModel":"ALIBABA_BAILIAN","score":92.0,"reason":"流程助手场景优先..."}
     *
     * id: uuid-1
     * event: token
     * data: {"content":"{\"processName\":\"入职流程\",\"tasks\":"}
     *
     * id: uuid-1
     * event: token
     * data: {"content":"[{\"order\":1,\"name\":\"分配工位\"}..."}
     *
     * id: uuid-1
     * event: done
     * data: {"model":"ALIBABA_BAILIAN"}
     *
     * 关键技术点：
     * - produces = MediaType.TEXT_EVENT_STREAM_VALUE：声明 SSE 协议，Spring 自动设置响应头
     * - Flux<ServerSentEvent<AiStreamEvent>>：Flux 发射多个 ServerSentEvent，实现持续推送
     * - ServerSentEvent.builder()：Spring 的 SSE 包装器，自动设置 id/event/data 字段
     * - .event(event.type().name().toLowerCase())：将枚举 ROUTE/TOKEN/DONE/ERROR 转为小写事件名
     * - onErrorResume：流式降级，主模型失败切换 fallback，仍无法恢复则发送 error 事件
     */
    @PostMapping(value = "/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "流式流程规划（SSE）",
            description = "提交业务目标，通过 SSE 实时推送 AI 生成的流程计划内容"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "SSE 事件流",
                    content = @Content(
                            mediaType = "text/event-stream",
                            examples = @ExampleObject(
                                    name = "SSE 事件流",
                                    value = """
                                            id: event-1
                                            event: route
                                            data: {"scenario":"FLOW","selectedModel":"ALIBABA_BAILIAN"}

                                            id: event-1
                                            event: token
                                            data: {"content":"{\\"processName\\":\\"入职流程\\""}

                                            id: event-1
                                            event: done
                                            data: {"model":"ALIBABA_BAILIAN"}
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数校验失败"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未授权"
            )
    })
    public Flux<ServerSentEvent<AiStreamEvent>> stream(@Valid @RequestBody ProcessRequest request) {
        return processService.stream(request)
                // 将内部 AiStreamEvent 包装为 HTTP SSE 格式，推送给客户端
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())                           // SSE 事件 ID，用于断线重连定位
                        .event(event.type().name().toLowerCase()) // 事件类型：route/token/done/error
                        .data(event)                              // JSON 序列化后的事件数据
                        .build());
    }
}
