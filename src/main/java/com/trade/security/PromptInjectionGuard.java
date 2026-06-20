package com.trade.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护组件 —— 检测并防止恶意 Prompt 注入攻击。
 *
 * 防护策略：
 * 1. 检测常见的 Prompt 注入模式
 * 2. 清洗用户输入中的恶意内容
 * 3. 验证系统提示词的完整性
 * 4. 检测角色扮演尝试
 * 5. 检测指令覆盖尝试
 *
 * 常见攻击模式：
 * - "Ignore previous instructions..."
 * - "You are now..."
 * - "System prompt:"
 * - "New instructions:"
 * - 角色扮演绕过
 * - 多语言混合攻击
 * - 编码绕过
 *
 * @author Security Team
 * @since 1.0
 */
@Component
public class PromptInjectionGuard {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuard.class);

    // Prompt 注入模式列表
    private final List<Pattern> injectionPatterns = new ArrayList<>();

    // 可疑关键词
    private final Set<String> suspiciousKeywords = ConcurrentHashMap.newKeySet();

    // 是否启用 Prompt 注入防护
    private boolean enabled = true;

    // 检测阈值：匹配模式数量超过此值则判定为注入
    private static final int DETECTION_THRESHOLD = 2;

    /**
     * 初始化 Prompt 注入防护规则。
     */
    @PostConstruct
    public void init() {
        // 常见 Prompt 注入模式
        String[] patterns = {
                // 忽略之前的指令
                "(?i)ignore\\s+(previous|above|prior|all)\\s+(instructions?|prompts?|rules?)",
                "(?i)disregard\\s+(previous|above|prior|all|instructions?|prompts?)",
                "(?i)forget\\s+(previous|above|prior|all|instructions?|prompts?)",
                "(?i)forget\\s+everything",

                // 新指令
                "(?i)new\\s+instructions?",
                "(?i)new\\s+prompts?",
                "(?i)new\\s+rules?",
                "(?i)new\\s+system\\s+prompt",

                // 系统提示词
                "(?i)system\\s+prompt",
                "(?i)system\\s+message",
                "(?i)system\\s+instructions?",
                "(?i)system\\s+role",

                // 角色扮演
                "(?i)you\\s+are\\s+now",
                "(?i)act\\s+as\\s+(if|a|an)",
                "(?i)pretend\\s+(to\\s+be|you('re|\\s+are))",
                "(?i)roleplay\\s+as",
                "(?i)from\\s+now\\s+on\\s+you('re|\\s+are)",

                // 指令覆盖
                "(?i)override\\s+(previous|above|prior|all|instructions?|prompts?)",
                "(?i)bypass\\s+(previous|above|prior|all|instructions?|prompts?)",
                "(?i)ignore\\s+your\\s+(previous|above|prior|all|instructions?|prompts?)",
                "(?i)ignore\\s+the\\s+(previous|above|prior|all|instructions?|prompts?)",

                // 提示词泄露
                "(?i)print\\s+(your|the)\\s+(system|initial|original)\\s+prompt",
                "(?i)show\\s+(your|the)\\s+(system|initial|original)\\s+prompt",
                "(?i)reveal\\s+(your|the)\\s+(system|initial|original)\\s+prompt",
                "(?i)what\\s+(was|were)\\s+your\\s+(system|initial|original)\\s+prompt",

                // 危险指令
                "(?i)execute\\s+the\\s+following",
                "(?i)run\\s+the\\s+following",
                "(?i)do\\s+the\\s+following",
                "(?i)output\\s+the\\s+following",

                // 多语言混合（可能的绕过尝试）
                "[\\u4e00-\\u9fff].*ignore.*instructions",
                "ignore.*instructions.*[\\u4e00-\\u9fff]",

                // 特殊字符绕过
                "(?i)\\x00", // 空字符
                "(?i)\\x08", // 退格
                "(?i)\\x1f", // 单元分隔符
                "(?i)\\x7f", // 删除字符

                // 编码绕过
                "(?i)base64",
                "(?i)rot13",
                "(?i)hex\\s+encode",
                "(?i)unicode\\s+encode",

                // 分隔符攻击
                "```\\s*system",
                "---\\s*system",
                "===\\s*system",
                "```\\s*instructions",
                "---\\s*instructions",
                "===\\s*instructions"
        };

        for (String pattern : patterns) {
            try {
                injectionPatterns.add(Pattern.compile(pattern));
            } catch (Exception e) {
                log.error("Failed to compile pattern: {}", pattern, e);
            }
        }

        // 可疑关键词
        String[] keywords = {
                "ignore", "disregard", "forget", "override", "bypass",
                "system prompt", "new instructions", "act as", "pretend",
                "roleplay", "print your prompt", "show your prompt",
                "reveal your prompt", "execute the following", "run the following"
        };

        for (String keyword : keywords) {
            suspiciousKeywords.add(keyword.toLowerCase());
        }

        log.info("PromptInjectionGuard initialized with {} patterns and {} keywords",
                injectionPatterns.size(), suspiciousKeywords.size());
    }

    /**
     * 检测文本是否包含 Prompt 注入尝试。
     *
     * @param text 待检测文本
     * @return true 检测到注入尝试，false 未检测到
     */
    public boolean detectInjection(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }

        String lowerText = text.toLowerCase();
        int matchCount = 0;

        // 检查正则模式
        for (Pattern pattern : injectionPatterns) {
            if (pattern.matcher(lowerText).find()) {
                matchCount++;
                log.debug("Prompt injection pattern detected: {}", pattern.pattern());
            }
        }

        // 检查关键词
        for (String keyword : suspiciousKeywords) {
            if (lowerText.contains(keyword)) {
                matchCount++;
            }
        }

        // 检查特殊字符
        if (containsSpecialCharacters(text)) {
            matchCount++;
        }

        // 检查长度异常（过长的输入可能是攻击）
        if (text.length() > 10000) {
            matchCount++;
        }

        // 检查重复模式（可能是填充攻击）
        if (hasRepetitivePatterns(text)) {
            matchCount++;
        }

        return matchCount >= DETECTION_THRESHOLD;
    }

    /**
     * 清洗用户输入，移除潜在的恶意内容。
     *
     * @param input 用户输入
     * @return 清洗后的输入
     */
    public String sanitizeInput(String input) {
        if (!enabled || input == null || input.isEmpty()) {
            return input;
        }

        String sanitized = input;

        // 移除特殊字符
        sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");

        // 移除可能的代码块标记
        sanitized = sanitized.replaceAll("```\\s*system", "");
        sanitized = sanitized.replaceAll("---\\s*system", "");
        sanitized = sanitized.replaceAll("===\\s*system", "");

        // 移除多余的空格和换行
        sanitized = sanitized.replaceAll("\\s+", " ");

        // 移除可能的 HTML/脚本标签
        sanitized = sanitized.replaceAll("<script[^>]*>.*?</script>", "");
        sanitized = sanitized.replaceAll("<[^>]+>", "");

        // 移除可能的编码字符串
        sanitized = sanitized.replaceAll("base64", "");
        sanitized = sanitized.replaceAll("rot13", "");

        return sanitized.trim();
    }

    /**
     * 验证系统提示词的完整性。
     * 检测系统提示词是否被篡改。
     *
     * @param systemPrompt 系统提示词
     * @return true 完整，false 可能被篡改
     */
    public boolean validateSystemPrompt(String systemPrompt) {
        if (!enabled || systemPrompt == null || systemPrompt.isEmpty()) {
            return true; // 空提示词视为有效
        }

        // 检查是否包含用户输入的特征
        String lowerPrompt = systemPrompt.toLowerCase();

        // 系统提示词不应包含用户输入的标记
        if (lowerPrompt.contains("user:") || lowerPrompt.contains("human:")) {
            return false;
        }

        // 系统提示词不应包含多个分隔符
        if (systemPrompt.split("```").length > 3) {
            return false;
        }

        return true;
    }

    /**
     * 检测角色扮演尝试。
     *
     * @param text 待检测文本
     * @return true 检测到角色扮演，false 未检测到
     */
    public boolean detectRolePlay(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }

        String lowerText = text.toLowerCase();
        String[] rolePlayPatterns = {
                "you are now", "act as if", "pretend to be", "roleplay as",
                "from now on you're", "you are a", "you're a"
        };

        for (String pattern : rolePlayPatterns) {
            if (lowerText.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测指令覆盖尝试。
     *
     * @param text 待检测文本
     * @return true 检测到指令覆盖，false 未检测到
     */
    public boolean detectOverride(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }

        String lowerText = text.toLowerCase();
        String[] overridePatterns = {
                "ignore previous", "ignore above", "ignore all",
                "disregard previous", "disregard above", "disregard all",
                "forget previous", "forget above", "forget all",
                "override previous", "override above", "override all",
                "bypass previous", "bypass above", "bypass all"
        };

        for (String pattern : overridePatterns) {
            if (lowerText.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查是否包含特殊字符（可能的绕过尝试）。
     *
     * @param text 待检测文本
     * @return true 包含特殊字符，false 不包含
     */
    private boolean containsSpecialCharacters(String text) {
        // 检查控制字符
        for (char c : text.toCharArray()) {
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }

        // 检查 Unicode 特殊字符
        return text.codePoints().anyMatch(c ->
                (c >= 0x200b && c <= 0x200f) || // 零宽字符
                        (c >= 0x2028 && c <= 0x202f) || // 格式字符
                        (c >= 0x2060 && c <= 0x206f) || // 词分隔符
                        (c >= 0xfeff && c <= 0xfeff) || // BOM
                        (c >= 0xfff0 && c <= 0xfffff) // 特殊用途
        );
    }

    /**
     * 检查是否包含重复模式（可能是填充攻击）。
     *
     * @param text 待检测文本
     * @return true 包含重复模式，false 不包含
     */
    private boolean hasRepetitivePatterns(String text) {
        if (text.length() < 100) {
            return false;
        }

        // 检查是否有大量重复的单词或短语
        String[] words = text.split("\\s+");
        if (words.length < 10) {
            return false;
        }

        Map<String, Integer> wordCount = new HashMap<>();
        for (String word : words) {
            if (word.length() > 3) { // 只检查长度大于3的单词
                wordCount.merge(word.toLowerCase(), 1, Integer::sum);
            }
        }

        // 如果某个单词出现超过文本长度的 10%，则认为是重复
        double threshold = words.length * 0.1;
        return wordCount.values().stream().anyMatch(count -> count > threshold);
    }

    /**
     * 获取检测到的所有注入模式。
     *
     * @param text 待检测文本
     * @return 检测到的模式列表
     */
    public List<String> getDetectedPatterns(String text) {
        List<String> detected = new ArrayList<>();
        if (!enabled || text == null || text.isEmpty()) {
            return detected;
        }

        String lowerText = text.toLowerCase();

        // 检查正则模式
        for (Pattern pattern : injectionPatterns) {
            if (pattern.matcher(lowerText).find()) {
                detected.add("Pattern: " + pattern.pattern());
            }
        }

        // 检查关键词
        for (String keyword : suspiciousKeywords) {
            if (lowerText.contains(keyword)) {
                detected.add("Keyword: " + keyword);
            }
        }

        return detected;
    }

    /**
     * 启用或禁用 Prompt 注入防护。
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("PromptInjectionGuard enabled: {}", enabled);
    }

    /**
     * 检查是否启用 Prompt 注入防护。
     *
     * @return true 启用，false 禁用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 添加自定义检测模式。
     *
     * @param pattern 正则表达式模式
     */
    public void addCustomPattern(String pattern) {
        try {
            injectionPatterns.add(Pattern.compile(pattern));
            log.info("Added custom pattern: {}", pattern);
        } catch (Exception e) {
            log.error("Failed to add custom pattern: {}", pattern, e);
        }
    }

    /**
     * 添加自定义关键词。
     *
     * @param keyword 关键词
     */
    public void addCustomKeyword(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            suspiciousKeywords.add(keyword.toLowerCase());
            log.info("Added custom keyword: {}", keyword);
        }
    }
}
