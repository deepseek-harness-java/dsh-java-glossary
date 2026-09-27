package cn.xiaofuge.dsh.glossary.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshGlossaryPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-glossary-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshGlossaryPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new LookupTool(),
                new AddTermTool(),
                new MineTool(),
                new BilingualCardTool(),
                new ConflictCheckTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("glossary-atlas-capabilities", 20, """
                ## DSH Term Atlas Plugin
                - 查询/挖掘/卡片/冲突检查类工具只读；唯一写入工具是 gl_add_term（用户明确要求收录新术语时才调用）。
                - gl_add_term 遇到重复英文术语不会覆盖，会自动转评审意见——不要因此重试。
                - 红线：不删除已有词条、不改写他人释义。
                - 术语库中不存在的词，回答"术语库未收录"，禁止编造释义。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__gl_")) {
                return PluginHookResult.context("DSH Term Atlas audit: glossary terms stay local.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_GLOSSARY_ADMIN_URL", "http://127.0.0.1:8116")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String kwJson(Map<String, Object> args) {
        return "{\"keyword\":\"" + json(str(args, "keyword")) + "\"}";
    }

    private class LookupTool extends AbstractTool {
        @Override public String name() { return "gl_lookup"; }
        @Override public String description() { return "按关键词查术语库（英文/中文/别名/释义模糊匹配），返回命中词条列表。回答术语含义前必须调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("keyword", stringSchema("关键词，如 QPS、幂等、standup"))
                    .prop("adminBaseUrl", stringSchema("可选，Term Atlas 后台地址，默认 http://127.0.0.1:8116"))
                    .required("keyword")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/terms/lookup", kwJson(args), args));
        }
    }

    private class AddTermTool extends AbstractTool {
        @Override public String name() { return "gl_add_term"; }
        @Override public String description() { return "收录新术语词条（英文规范名+中文正名+别名+分类+释义+用例）。已存在同英文名时自动转评审意见不覆盖。仅当用户明确要求收录时调用。"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> aliasItem = stringSchema("别名");
            Map<String, Object> aliasesSchema = new java.util.HashMap<>();
            aliasesSchema.put("type", "array");
            aliasesSchema.put("description", "别名列表");
            aliasesSchema.put("items", aliasItem);
            return objectSchema()
                    .prop("en", stringSchema("英文规范名，如 Daily Standup"))
                    .prop("zh", stringSchema("中文正名，如 每日站会"))
                    .prop("aliases", aliasesSchema)
                    .prop("tag", stringSchema("分类标签，如 Scrum/性能/架构"))
                    .prop("definition", stringSchema("释义（中文为主）"))
                    .prop("example", stringSchema("例句或团队内用法"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("en", "definition")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder body = new StringBuilder("{");
            body.append("\"en\":\"").append(json(str(args, "en"))).append("\"");
            body.append(",\"zh\":\"").append(json(str(args, "zh"))).append("\"");
            body.append(",\"tag\":\"").append(json(str(args, "tag"))).append("\"");
            body.append(",\"definition\":\"").append(json(str(args, "definition"))).append("\"");
            body.append(",\"example\":\"").append(json(str(args, "example"))).append("\"");
            Object aliases = args.get("aliases");
            if (aliases instanceof List<?> list) {
                body.append(",\"aliases\":[");
                boolean first = true;
                for (Object a : list) {
                    if (!first) body.append(",");
                    body.append("\"").append(json(String.valueOf(a))).append("\"");
                    first = false;
                }
                body.append("]");
            }
            body.append("}");
            return ok(post("/api/terms", body.toString(), args));
        }
    }

    private class MineTool extends AbstractTool {
        @Override public String name() { return "gl_mine_from_doc"; }
        @Override public String description() { return "从文档文本挖掘候选术语：驼峰词/连字符词/缩写按频率排名，附中文映射提示与是否已收录。用户给出一段文档要求提取术语时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("文档文本全文（至少 20 字符）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/terms/mine", "{\"text\":\"" + json(str(args, "text")) + "\"}", args));
        }
    }

    private class BilingualCardTool extends AbstractTool {
        @Override public String name() { return "gl_bilingual_card"; }
        @Override public String description() { return "生成术语双语卡片：中文面/英文面/用例面三张。给新人解释术语或做分享物料时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("keyword", stringSchema("术语关键词"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("keyword")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/terms/bilingual-card", kwJson(args), args));
        }
    }

    private class ConflictCheckTool extends AbstractTool {
        @Override public String name() { return "gl_conflict_check"; }
        @Override public String description() { return "术语库冲突检查：英文重名、中文一对多映射、待评审意见数。整理术语库或疑似重复收录时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/terms/conflicts", "{}", args));
        }
    }
}
