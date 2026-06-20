package com.trade.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感词过滤器 —— 基于 DFA（确定性有限自动机）算法实现的高效敏感词检测。
 *
 * 核心功能：
 * 1. 构建敏感词库（支持从配置文件加载）
 * 2. 检测文本中是否包含敏感词
 * 3. 替换敏感词为指定字符（如 *）
 * 4. 获取所有匹配的敏感词
 *
 * 技术特点：
 * - 使用 DFA 算法，时间复杂度 O(n)，性能优异
 * - 支持并发操作（使用 ConcurrentHashMap）
 * - 支持动态添加/删除敏感词
 * - 支持多模式匹配（检测、替换、提取）
 *
 * 使用场景：
 * - 用户输入前的敏感词检测
 * - AI 输出后的内容审核
 * - 聊天记录的合规性检查
 *
 * @author Security Team
 * @since 1.0
 */
@Component
public class SensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordFilter.class);

    // DFA 敏感词树：每个节点是一个 Map<字符, 子节点>
    private final Map<Character, Object> sensitiveWordMap = new ConcurrentHashMap<>();

    // 敏感词缓存，提高重复检测效率
    private final Set<String> sensitiveWords = ConcurrentHashMap.newKeySet();

    // 默认替换字符
    private static final char REPLACE_CHAR = '*';

    // 是否启用敏感词过滤
    private boolean enabled = true;

    /**
     * 初始化敏感词库 —— 从内置列表加载基础敏感词。
     * 生产环境建议从数据库或配置文件加载。
     */
    @PostConstruct
    public void init() {
        // 内置敏感词列表（示例）
        // 生产环境应从数据库或配置文件加载
        String[] builtInSensitiveWords = {
                // 政治敏感词
                "政治", "反动", "颠覆", "政权",
                // 色情相关
                "色情", "赌博", "暴力",
                // 诈骗相关
                "诈骗", "钓鱼", "黑产",
                // 非法活动
                "黑客", "攻击", "入侵", "hacker",
                // Prompt 注入关键词
                "ignore previous", "ignore above", "disregard", "forget",
                "system prompt", "new instructions", "override", "bypass"
        };

        for (String word : builtInSensitiveWords) {
            addSensitiveWord(word.toLowerCase());
        }

        log.info("SensitiveWordFilter initialized with {} sensitive words", sensitiveWords.size());
    }

    /**
     * 添加敏感词到 DFA 树。
     * 时间复杂度 O(m)，m 为敏感词长度
     *
     * @param word 敏感词（会自动转为小写）
     */
    public void addSensitiveWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            return;
        }

        word = word.toLowerCase().trim();
        if (sensitiveWords.contains(word)) {
            return;
        }

        sensitiveWords.add(word);

        // 构建 DFA 树
        Map<Character, Object> currentMap = sensitiveWordMap;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            final Map<Character, Object> finalCurrentMap = currentMap;
            currentMap.computeIfAbsent(c, k -> new ConcurrentHashMap<>());
            currentMap = (Map<Character, Object>) currentMap.get(c);
        }

        // 标记为敏感词结尾
        if (currentMap != null) {
            currentMap.put('\0', new ConcurrentHashMap<>()); // 使用 \0 标记结尾
        }
    }

    /**
     * 检测文本是否包含敏感词。
     * 时间复杂度 O(n)，n 为文本长度
     *
     * @param text 待检测文本
     * @return true 包含敏感词，false 不包含
     */
    public boolean containsSensitiveWord(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }

        text = text.toLowerCase();
        for (int i = 0; i < text.length(); i++) {
            if (matchSensitiveWord(text, i) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取文本中所有匹配的敏感词。
     *
     * @param text 待检测文本
     * @return 匹配的敏感词集合
     */
    public Set<String> getSensitiveWords(String text) {
        Set<String> words = new HashSet<>();
        if (!enabled || text == null || text.isEmpty()) {
            return words;
        }

        text = text.toLowerCase();
        for (int i = 0; i < text.length(); i++) {
            String word = matchSensitiveWord(text, i);
            if (word != null) {
                words.add(word);
                i += word.length() - 1; // 跳过已匹配的部分
            }
        }
        return words;
    }

    /**
     * 替换文本中的敏感词为指定字符。
     *
     * @param text 原始文本
     * @return 替换后的文本
     */
    public String replaceSensitiveWords(String text) {
        return replaceSensitiveWords(text, REPLACE_CHAR);
    }

    /**
     * 替换文本中的敏感词为指定字符。
     *
     * @param text      原始文本
     * @param replaceChar 替换字符
     * @return 替换后的文本
     */
    public String replaceSensitiveWords(String text, char replaceChar) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }

        char[] chars = text.toCharArray();
        String lowerText = text.toLowerCase();

        for (int i = 0; i < lowerText.length(); i++) {
            String word = matchSensitiveWord(lowerText, i);
            if (word != null) {
                // 替换敏感词为指定字符
                for (int j = i; j < i + word.length(); j++) {
                    chars[j] = replaceChar;
                }
                i += word.length() - 1;
            }
        }

        return new String(chars);
    }

    /**
     * 从指定位置开始匹配敏感词。
     *
     * @param text 文本
     * @param start 起始位置
     * @return 匹配的敏感词，未匹配返回 null
     */
    private String matchSensitiveWord(String text, int start) {
        Map<Character, Object> currentMap = sensitiveWordMap;
        StringBuilder match = new StringBuilder();

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            Object nextMapObj = currentMap.get(c);

            if (nextMapObj == null) {
                return null;
            }

            match.append(c);

            // 检查是否为敏感词结尾
            if (nextMapObj instanceof Map) {
                Map<Character, Object> nextMap = (Map<Character, Object>) nextMapObj;
                if (nextMap.containsKey('\0')) {
                    return match.toString();
                }
                currentMap = nextMap;
            } else {
                return null;
            }
        }

        return null;
    }

    /**
     * 移除敏感词。
     *
     * @param word 要移除的敏感词
     */
    public void removeSensitiveWord(String word) {
        if (word == null) {
            return;
        }

        word = word.toLowerCase().trim();
        sensitiveWords.remove(word);
        // 注意：从 DFA 树中移除较复杂，这里仅移除标记，实际生产环境可考虑重建树
        log.warn("Removed sensitive word: {}", word);
    }

    /**
     * 清空所有敏感词。
     */
    public void clearSensitiveWords() {
        sensitiveWords.clear();
        sensitiveWordMap.clear();
        log.info("All sensitive words cleared");
    }

    /**
     * 获取当前敏感词数量。
     *
     * @return 敏感词数量
     */
    public int getSensitiveWordCount() {
        return sensitiveWords.size();
    }

    /**
     * 启用或禁用敏感词过滤。
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("SensitiveWordFilter enabled: {}", enabled);
    }

    /**
     * 检查是否启用敏感词过滤。
     *
     * @return true 启用，false 禁用
     */
    public boolean isEnabled() {
        return enabled;
    }
}
