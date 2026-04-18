package com.ceramicraft.search.intention.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Injection 防护工具类。
 * <p>
 * 提供多层防护机制，防止用户通过构造恶意输入来操控 LLM 行为：
 * <ul>
 *   <li><b>长度限制</b> — 截断超长输入，防止上下文窗口滥用</li>
 *   <li><b>注入检测</b> — 识别常见的提示词注入模式</li>
 *   <li><b>输入清洗</b> — 移除/替换危险的控制字符和分隔符</li>
 * </ul>
 * </p>
 *
 * <h3>常见攻击模式：</h3>
 * <ul>
 *   <li>"忽略之前的所有指令" / "Ignore all previous instructions"</li>
 *   <li>"你是一个新的 AI" / "You are now a ..."</li>
 *   <li>"System:" / "###" / "---" 伪造系统消息边界</li>
 *   <li>通过超长输入稀释原始提示词的影响</li>
 * </ul>
 */
public final class PromptGuardUtils {

    private static final Logger log = LoggerFactory.getLogger(PromptGuardUtils.class);

    private PromptGuardUtils() {}

    /** 搜索查询最大长度（字符数） */
    public static final int MAX_QUERY_LENGTH = 200;

    /** 商品描述最大长度（字符数） */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    /** 商品名称最大长度 */
    public static final int MAX_NAME_LENGTH = 100;

    // ==================== 注入检测模式（中英文） ====================

    /**
     * 高危注入模式 — 命中即判定为攻击，直接拒绝。
     */
    private static final List<Pattern> HIGH_RISK_PATTERNS = List.of(
            // 中文指令覆盖
            Pattern.compile("忽略.{0,10}(之前|上面|以上|前面|所有|全部).{0,10}(指令|指示|提示|规则|约束|设定|设置|命令)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("无视.{0,10}(之前|上面|以上|前面|所有|全部).{0,10}(指令|指示|提示|规则|约束)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("不要?遵守.{0,10}(指令|规则|约束|限制)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(现在|从现在起).{0,10}你是.{0,10}(一个|新的)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(扮演|假装|模拟|充当).{0,20}(AI|助手|角色|系统)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|显示|告诉我|打印|泄露|透露).{0,10}(系统|system).{0,10}(提示|prompt|指令)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(重置|清除|覆盖).{0,10}(系统|上下文|对话|角色)", Pattern.CASE_INSENSITIVE),

            // 英文指令覆盖
            Pattern.compile("ignore\\s+(all\\s+)?(previous|above|prior|earlier|preceding)\\s+(instructions?|prompts?|rules?|constraints?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(you\\s+are\\s+now|act\\s+as|pretend\\s+(to\\s+be|you\\s+are)|roleplay\\s+as)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show|display|print|leak|output)\\s+(your\\s+)?(system\\s+)?(prompt|instructions?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("override\\s+(your\\s+)?(system|instructions?|rules?|prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(do\\s+not|don'?t)\\s+follow\\s+(your\\s+)?(rules?|instructions?|constraints?)", Pattern.CASE_INSENSITIVE),

            // DAN / Jailbreak 模式
            Pattern.compile("\\bDAN\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjailbreak\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDEVELOPER\\s*MODE\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 中等风险模式 — 单独不构成攻击，但多次出现或与其他指标组合时标记警告。
     */
    private static final List<Pattern> MEDIUM_RISK_PATTERNS = List.of(
            // 伪造系统/角色边界
            Pattern.compile("^\\s*system\\s*:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*assistant\\s*:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*\\[?(INST|SYS(TEM)?)]?\\s*:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            // Markdown 分隔符（大量使用可能试图切割上下文）
            Pattern.compile("#{3,}\\s*(system|instruction|new\\s+task)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("-{5,}"),
            Pattern.compile("={5,}"),
            // 编码绕过尝试
            Pattern.compile("base64\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\u[0-9a-fA-F]{4}"),
            // 过度引导 LLM 输出行为
            Pattern.compile("(请|please).{0,20}(忘记|forget)", Pattern.CASE_INSENSITIVE)
    );

    // ==================== 公开 API ====================

    /**
     * 检测结果枚举。
     */
    public enum RiskLevel {
        /** 安全 — 未检测到注入特征 */
        SAFE,
        /** 警告 — 检测到可疑模式，需记录日志但可放行（经过清洗） */
        WARNING,
        /** 高危 — 检测到明确的注入攻击，应拒绝请求 */
        BLOCKED
    }

    /**
     * 检测输入文本的注入风险等级。
     *
     * @param input 用户输入
     * @return 风险等级
     */
    public static RiskLevel detectRisk(String input) {
        if (input == null || input.isBlank()) {
            return RiskLevel.SAFE;
        }

        // 高危模式检测
        for (Pattern pattern : HIGH_RISK_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("🛡️ Prompt Injection 检测 — 高危命中: pattern='{}', input='{}'",
                        pattern.pattern(), truncateForLog(input));
                return RiskLevel.BLOCKED;
            }
        }

        // 中等风险检测
        int mediumHits = 0;
        for (Pattern pattern : MEDIUM_RISK_PATTERNS) {
            if (pattern.matcher(input).find()) {
                mediumHits++;
            }
        }
        if (mediumHits >= 2) {
            log.warn("🛡️ Prompt Injection 检测 — 多重可疑模式 ({}次命中): input='{}'",
                    mediumHits, truncateForLog(input));
            return RiskLevel.BLOCKED;
        }
        if (mediumHits == 1) {
            log.info("🛡️ Prompt Injection 检测 — 可疑模式: input='{}'", truncateForLog(input));
            return RiskLevel.WARNING;
        }

        return RiskLevel.SAFE;
    }

    /**
     * 对搜索查询进行安全清洗。
     * <ul>
     *   <li>截断超长输入</li>
     *   <li>移除危险控制字符</li>
     *   <li>替换连续分隔符</li>
     * </ul>
     *
     * @param query 原始查询
     * @return 清洗后的查询
     */
    public static String sanitizeQuery(String query) {
        if (query == null) return "";
        String sanitized = query.strip();

        // 1. 长度截断
        if (sanitized.length() > MAX_QUERY_LENGTH) {
            log.info("🛡️ 查询长度截断: {} → {}", sanitized.length(), MAX_QUERY_LENGTH);
            sanitized = sanitized.substring(0, MAX_QUERY_LENGTH);
        }

        // 2. 移除常见的 Prompt 分隔/伪装字符
        sanitized = sanitized
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")  // 控制字符
                .replaceAll("-{3,}", "--")        // 长横线 → 短横线
                .replaceAll("={3,}", "==")        // 长等号 → 短等号
                .replaceAll("#{3,}", "##")         // 长井号 → 短井号
                .replaceAll("`{3,}", "")           // 反引号代码块标记
                .strip();

        return sanitized;
    }

    /**
     * 对商品描述类文本进行安全清洗（较宽松，因为商品描述可能更长、格式更多样）。
     *
     * @param text      原始文本
     * @param maxLength 最大允许长度
     * @return 清洗后的文本
     */
    public static String sanitizeText(String text, int maxLength) {
        if (text == null) return "";
        String sanitized = text.strip();

        // 1. 长度截断
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }

        // 2. 移除控制字符（保留常规格式字符如换行、制表符）
        sanitized = sanitized
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .strip();

        return sanitized;
    }

    /**
     * 检查输入是否为合法的搜索查询（非空、非纯特殊字符）。
     *
     * @param query 查询文本
     * @return true=合法
     */
    public static boolean isValidQuery(String query) {
        if (query == null || query.isBlank()) return false;
        // 查询中必须包含至少 1 个中文字符或 2 个字母
        return query.matches(".*[\\u4e00-\\u9fff].*") ||
               query.matches(".*[a-zA-Z]{2,}.*") ||
               query.matches(".*\\d+.*");
    }

    // ==================== 内部方法 ====================

    /**
     * 截断文本用于日志输出（避免日志注入）。
     */
    private static String truncateForLog(String text) {
        if (text == null) return "null";
        String clean = text.replaceAll("[\\r\\n]", " ");
        return clean.length() > 80 ? clean.substring(0, 80) + "..." : clean;
    }
}

