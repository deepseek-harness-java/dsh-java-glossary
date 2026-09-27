package cn.xiaofuge.dsh.glossary.service;

import cn.xiaofuge.dsh.glossary.config.GlossaryAtlasProperties;
import cn.xiaofuge.dsh.glossary.model.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Term Atlas 团队术语典：
 * - 术语 = 中英词条（英文规范名/中文正名/别名/缩写/释义/用例/标签/来源）
 * - 从文档挖掘候选术语（英文驼峰/缩写/「中文即XX」模式）
 * - 冲突检查：同名不同义、中英映射矛盾
 * 红线：不删已有词条；不覆盖他人释义（新增评审意见而非改写）。
 */
@Service
public class GlossaryAtlasService {

    private final GlossaryAtlasProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> terms = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> reviews = new LinkedHashMap<>();

    /** 候选英文术语：驼峰、连字符、全大写缩写（2-10 字符）。 */
    private static final Pattern EN_TERM = Pattern.compile(
            "\\b([A-Z][a-z]+(?:[A-Z][a-z0-9]+)+|[a-z]+(?:-[a-z0-9]+)+|[A-Z]{2,10})\\b");
    /** 「XX（即 YY）」「XX, 也就是 YY」中文释义模式。 */
    private static final Pattern ZH_DEF = Pattern.compile(
            "([\\u4e00-\\u9fa5][\\u4e00-\\u9fa5A-Za-z0-9 ]{1,20}?)\\s*[（(]\\s*(?:即|就是|指的是?|英文[:：]?\\s*)?([\\w\\- ]{2,40})[）)]");
    private static final Pattern ALIAS_LINE = Pattern.compile(
            "([\\w\\-\\u4e00-\\u9fa5·]{2,30})\\s*(?:[（(]|即|又叫|也叫|aka|=>|→|:)\\s*([\\w\\-\\u4e00-\\u9fa5·]{2,30})");

    public GlossaryAtlasService(GlossaryAtlasProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        Path file = Path.of(properties.dataDirectory(), "terms.json");
        if (Files.exists(file)) {
            try {
                List<Map<String, Object>> list = objectMapper.readValue(file.toFile(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                for (Map<String, Object> term : list) {
                    terms.put(String.valueOf(term.get("id")), term);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法读取术语库", e);
            }
        }
        seedDemoTerms();
    }

    /** 首次启动内置少量示例术语，方便演示与 E2E。 */
    private void seedDemoTerms() {
        if (!terms.isEmpty()) return;
        addTerm("Daily Standup", "每日站会", List.of("站会", "晨会"), "Scrum",
                "每天固定时间的短会，同步进展/计划/阻塞，通常 15 分钟内。", "例句：明早 10 点 Daily Standup，记得说阻塞点。", "seed");
        addTerm("QPS", "每秒查询数", List.of("吞吐"), "性能",
                "Queries Per Second，衡量服务每秒能处理的查询请求数。", "例句：网关峰值 QPS 到 3 万了。", "seed");
        addTerm("Idempotent", "幂等", List.of("幂等性"), "架构",
                "同一操作执行多次与执行一次效果相同，重试安全的前提。", "例句：下单接口要做幂等，防止重复扣款。", "seed");
        persist();
    }

    // ---------- 查询 / 新增 ----------

    public Map<String, Object> lookup(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "keyword 不能为空");
        }
        String q = keyword.trim().toLowerCase();
        List<Map<String, Object>> hits = terms.values().stream()
                .filter(t -> matchesKeyword(t, q))
                .map(this::withUsage)
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", keyword);
        result.put("hitCount", hits.size());
        result.put("terms", hits);
        return result;
    }

    private boolean matchesKeyword(Map<String, Object> t, String q) {
        for (String field : new String[]{"en", "zh", "definition"}) {
            if (String.valueOf(t.getOrDefault(field, "")).toLowerCase().contains(q)) return true;
        }
        for (Object alias : (List<?>) t.getOrDefault("aliases", List.of())) {
            if (String.valueOf(alias).toLowerCase().contains(q)) return true;
        }
        return false;
    }

    private Map<String, Object> withUsage(Map<String, Object> t) {
        Map<String, Object> copy = new LinkedHashMap<>(t);
        copy.put("example", t.getOrDefault("example", ""));
        return copy;
    }

    public synchronized Map<String, Object> addTerm(String en, String zh, List<String> aliases,
                                                    String tag, String definition, String example, String source) {
        if (en == null || en.isBlank() || definition == null || definition.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "en 与 definition 不能为空");
        }
        // 冲突检查：同一 en 已存在 → 拒绝覆盖（红线：不改他人释义），转为评审意见
        Optional<Map<String, Object>> dup = terms.values().stream()
                .filter(t -> String.valueOf(t.get("en")).equalsIgnoreCase(en.trim()))
                .findFirst();
        if (dup.isPresent()) {
            Map<String, Object> existing = dup.get();
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("id", "rev-" + UUID.randomUUID().toString().substring(0, 8));
            review.put("termId", existing.get("id"));
            review.put("en", en.trim());
            review.put("proposedDefinition", definition);
            review.put("existingDefinition", existing.get("definition"));
            review.put("status", "pending");
            review.put("source", source == null ? "unknown" : source);
            reviews.put(String.valueOf(review.get("id")), review);
            persist();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("duplicated", true);
            result.put("termId", existing.get("id"));
            result.put("reviewId", review.get("id"));
            result.put("message", "该英文术语已存在（不覆盖他人释义），已转为评审意见");
            return result;
        }
        Map<String, Object> term = buildTerm(en, zh, aliases, tag, definition, example, source);
        terms.put(String.valueOf(term.get("id")), term);
        persist();
        Map<String, Object> result = new LinkedHashMap<>(term);
        result.put("duplicated", false);
        return result;
    }

    private Map<String, Object> buildTerm(String en, String zh, List<String> aliases,
                                          String tag, String definition, String example, String source) {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("id", "term-" + UUID.randomUUID().toString().substring(0, 8));
        term.put("en", en.trim());
        term.put("zh", zh == null ? "" : zh.trim());
        term.put("aliases", aliases == null ? List.of() : aliases.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        term.put("tag", tag == null || tag.isBlank() ? "未分类" : tag.trim());
        term.put("definition", definition.trim());
        term.put("example", example == null ? "" : example.trim());
        term.put("source", source == null ? "manual" : source);
        term.put("createdAt", java.time.Instant.now().toString());
        return term;
    }

    // ---------- 文档挖掘 ----------

    public Map<String, Object> mineFromDoc(String text) {
        if (text == null || text.trim().length() < 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文档文本太短（至少 20 字符）");
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        Map<String, List<String>> zhHints = new LinkedHashMap<>();
        for (String line : text.split("\\n+")) {
            Matcher en = EN_TERM.matcher(line);
            while (en.find()) {
                String candidate = en.group(1);
                if (candidate.length() < 2) continue;
                freq.merge(candidate, 1, Integer::sum);
            }
            Matcher zh = ZH_DEF.matcher(line);
            while (zh.find()) {
                zhHints.computeIfAbsent(zh.group(2).trim(), k -> new ArrayList<>()).add(zh.group(1).trim());
            }
        }
        List<Map<String, Object>> candidates = freq.entrySet().stream()
                .filter(e -> e.getValue() >= 2 || e.getKey().matches("[A-Z]{2,10}"))
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .map(e -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    String en = e.getKey();
                    c.put("en", en);
                    c.put("freq", e.getValue());
                    c.put("known", terms.values().stream().anyMatch(t -> String.valueOf(t.get("en")).equalsIgnoreCase(en)));
                    List<String> hints = zhHints.get(en);
                    c.put("zhHint", hints == null || hints.isEmpty() ? "" : hints.get(0));
                    c.put("inGlossary", c.get("known"));
                    return c;
                })
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedChars", text.length());
        result.put("candidateCount", candidates.size());
        result.put("newCount", candidates.stream().filter(c -> !(Boolean) c.get("known")).count());
        result.put("candidates", candidates);
        return result;
    }

    // ---------- 双语卡片 ----------

    public Map<String, Object> bilingualCard(String keyword) {
        Map<String, Object> lookup = lookup(keyword);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) lookup.get("terms");
        if (hits.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "术语库中没有「" + keyword + "」，可先 gl_add_term 或 gl_mine_from_doc 补充");
        }
        Map<String, Object> term = hits.get(0);
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card(term, "zh", "中文释义：**" + term.get("zh") + "**（" + term.get("tag") + "）\n" + term.get("definition")));
        cards.add(card(term, "en", "English: **" + term.get("en") + "**\n" + enRender(term)));
        cards.add(card(term, "usage", "用例：" + term.get("example")
                + "\n别名：" + joinAliases(term)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("en", term.get("en"));
        result.put("zh", term.get("zh"));
        result.put("cardCount", cards.size());
        result.put("cards", cards);
        return result;
    }

    private String enRender(Map<String, Object> term) {
        String def = String.valueOf(term.get("definition"));
        return def + (term.get("example") == null || String.valueOf(term.get("example")).isBlank()
                ? "" : "\nExample: " + term.get("example"));
    }

    private String joinAliases(Map<String, Object> term) {
        List<?> aliases = (List<?>) term.getOrDefault("aliases", List.of());
        return aliases.isEmpty() ? "无" : aliases.stream().map(String::valueOf).collect(Collectors.joining("、"));
    }

    private Map<String, Object> card(Map<String, Object> term, String side, String content) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("termId", term.get("id"));
        c.put("side", side);
        c.put("content", content);
        return c;
    }

    // ---------- 冲突检查 ----------

    public Map<String, Object> conflictCheck() {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        // 1) 同英文规范名（大小写不同视为冲突源）
        Map<String, List<Map<String, Object>>> byEn = terms.values().stream()
                .collect(Collectors.groupingBy(t -> String.valueOf(t.get("en")).toLowerCase()));
        byEn.forEach((en, group) -> {
            if (group.size() > 1) {
                conflicts.add(conflict("duplicate-en", en, group.stream().map(t -> String.valueOf(t.get("id"))).toList(),
                        "英文规范名重复 " + group.size() + " 条"));
            }
        });
        // 2) 同一中文正名映射到不同英文
        Map<String, Set<String>> zhToEn = new LinkedHashMap<>();
        for (Map<String, Object> t : terms.values()) {
            String zh = String.valueOf(t.getOrDefault("zh", "")).trim();
            if (!zh.isBlank()) {
                zhToEn.computeIfAbsent(zh, k -> new LinkedHashSet<>()).add(String.valueOf(t.get("en")));
            }
        }
        zhToEn.forEach((zh, ens) -> {
            if (ens.size() > 1) {
                conflicts.add(conflict("zh-many-en", zh, new ArrayList<>(ens), "中文「" + zh + "」对应多个英文术语"));
            }
        });
        // 3) 待评审意见数
        long pendingReviews = reviews.values().stream()
                .filter(r -> "pending".equals(r.get("status"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termCount", terms.size());
        result.put("conflictCount", conflicts.size());
        result.put("pendingReviews", pendingReviews);
        result.put("conflicts", conflicts);
        return result;
    }

    private Map<String, Object> conflict(String type, Object key, List<String> ids, String message) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("key", key);
        c.put("termIds", ids);
        c.put("message", message);
        return c;
    }

    // ---------- 列表 / 统计 ----------

    public List<Map<String, Object>> list() {
        return terms.values().stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("createdAt"))))
                .toList();
    }

    public Map<String, Object> stats() {
        Map<String, Long> byTag = terms.values().stream()
                .collect(Collectors.groupingBy(t -> String.valueOf(t.get("tag")), Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termCount", terms.size());
        result.put("reviewCount", reviews.size());
        result.put("byTag", sortByValueDesc(byTag));
        return result;
    }

    private Map<String, Long> sortByValueDesc(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    // ---------- persistence ----------

    private void persist() {
        try {
            Path file = Path.of(properties.dataDirectory(), "terms.json");
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), terms.values());
        } catch (IOException e) {
            throw new IllegalStateException("无法保存术语库", e);
        }
    }
}
