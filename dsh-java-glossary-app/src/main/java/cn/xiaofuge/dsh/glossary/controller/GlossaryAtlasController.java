package cn.xiaofuge.dsh.glossary.controller;

import cn.xiaofuge.dsh.glossary.config.GlossaryAtlasProperties;
import cn.xiaofuge.dsh.glossary.model.ApiException;
import cn.xiaofuge.dsh.glossary.service.GlossaryAtlasService;
import cn.xiaofuge.dsh.glossary.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class GlossaryAtlasController {
    private final GlossaryAtlasService glossaryService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final GlossaryAtlasProperties properties;

    public GlossaryAtlasController(GlossaryAtlasService glossaryService,
                                   HarnessAiStreamService harnessAiStreamService,
                                   GlossaryAtlasProperties properties) {
        this.glossaryService = glossaryService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record AddTermRequest(String en, String zh, List<String> aliases, String tag,
                                 String definition, String example, String source) {
    }

    public record MineRequest(String text) {
    }

    @GetMapping("/api/terms")
    public List<Map<String, Object>> list() {
        return glossaryService.list();
    }

    @GetMapping("/api/terms/stats")
    public Map<String, Object> stats() {
        return glossaryService.stats();
    }

    @PostMapping("/api/terms/lookup")
    public Map<String, Object> lookup(@RequestBody Map<String, String> request) {
        return glossaryService.lookup(request.getOrDefault("keyword", ""));
    }

    @PostMapping("/api/terms")
    public Map<String, Object> add(@RequestBody AddTermRequest request) {
        return glossaryService.addTerm(request.en(), request.zh(), request.aliases(),
                request.tag(), request.definition(), request.example(),
                request.source() == null ? "manual" : request.source());
    }

    @PostMapping("/api/terms/mine")
    public Map<String, Object> mine(@RequestBody MineRequest request) {
        return glossaryService.mineFromDoc(request.text());
    }

    @PostMapping("/api/terms/bilingual-card")
    public Map<String, Object> bilingualCard(@RequestBody Map<String, String> request) {
        return glossaryService.bilingualCard(request.getOrDefault("keyword", ""));
    }

    @PostMapping("/api/terms/conflicts")
    public Map<String, Object> conflicts() {
        return glossaryService.conflictCheck();
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Term Atlas（团队术语典）：中英术语/缩写/黑话库\n");
        prompt.append("- 你可以使用以下术语工具获取真实数据（回答术语含义、收录、冲突类问题时，必须先调用工具查证，禁止臆造术语库中不存在的词条）：\n");
        prompt.append("  - plugin__dsh-java-glossary-plugin__gl_lookup(keyword)：按关键词查术语（英文/中文/别名/释义模糊匹配）\n");
        prompt.append("  - plugin__dsh-java-glossary-plugin__gl_add_term(en, zh, aliases, tag, definition, example)：新增词条；已存在则自动转评审意见，不会覆盖\n");
        prompt.append("  - plugin__dsh-java-glossary-plugin__gl_mine_from_doc(text)：从文档挖掘候选术语（频率 + 中英映射提示）\n");
        prompt.append("  - plugin__dsh-java-glossary-plugin__gl_bilingual_card(keyword)：生成双语释义卡片（中/英/用例三面）\n");
        prompt.append("  - plugin__dsh-java-glossary-plugin__gl_conflict_check()：冲突检查（英文重名、中文一对多映射、待评审）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 术语释义只引用术语库真实内容；库里没有就明确说\"术语库未收录\"，禁止编造。\n");
        prompt.append("- 引用工具结果时给出关键数字与词条，不要原样转储大段 JSON。\n");
        prompt.append("- 红线：不删除已有词条、不改写他人释义（冲突走评审意见）。\n");
        return prompt.toString();
    }
}
