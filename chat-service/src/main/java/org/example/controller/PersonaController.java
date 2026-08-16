package org.example.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.example.common.Result;
import org.example.service.PersonaService;
import org.example.service.PersonaService.PersonaOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 人格信息接口: 上传(自动分片) / 取回(自动合并) / 列表.
 * <p>
 * 本接口所有方法都需要登录(由 {@link org.example.config.SaTokenConfigure} 的拦截器保护),
 * userId 直接从 Sa-Token 登录态取, 不再由前端传入.
 *
 * @author ckj
 */
@RestController
@RequestMapping("/api/persona")
public class PersonaController {

    private final PersonaService personaService;

    public PersonaController(PersonaService personaService) {
        this.personaService = personaService;
    }

    /** 上传请求体: 人格名称 + 完整内容(任意长度). userId 从登录态取, 不在请求体里. */
    public record UploadRequest(String name, String content) {
    }

    /**
     * 上传一条人格信息: POST /api/persona/upload
     * <p>
     * 超过 4000 字会自动分片入库, 返回 personaId.
     * <p>
     * 请求头需带: {@code satoken: {登录返回的 tokenValue}}
     */
    @PostMapping("/upload")
    public Result<?> upload(@RequestBody UploadRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();   // 从登录态取当前用户, 不信任客户端
        if (req.content() == null || req.content().isBlank()) {
            return Result.fail("人格内容不能为空");
        }
        String personaId = personaService.save(userId, req.name(), req.content());
        return Result.ok(Map.of("personaId", personaId));
    }

    /**
     * 取回某条人格的【完整内容】: GET /api/persona?personaId=xxx
     * <p>
     * 底层查出该 personaId 的所有分片, 按 seq 升序合并后返回原文.
     */
    @GetMapping
    public Result<?> get(@RequestParam String personaId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return personaService.getContent(userId, personaId)
                .map(content -> Result.ok(Map.of("content", content)))
                .orElseGet(() -> Result.fail("人格不存在或已删除"));
    }

    /**
     * 列出【当前登录用户】的全部人格: GET /api/persona/list
     */
    @GetMapping("/list")
    public Result<List<PersonaOverview>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(personaService.listByUser(userId));
    }
}
