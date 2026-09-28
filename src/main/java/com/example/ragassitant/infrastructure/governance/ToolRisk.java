package com.example.ragassitant.infrastructure.governance;

/**
 * 工具风险等级。
 * <ul>
 *   <li>{@link #READ_ONLY}：只读查询，直接执行；</li>
 *   <li>{@link #WRITE}：有副作用但可恢复（如修改一个配置项），直接执行 + 幂等去重 + 审计；</li>
 *   <li>{@link #DANGEROUS}：不可逆或影响面大（如重启设备、批量下发），<b>必须人工确认</b>后才执行。</li>
 * </ul>
 */
public enum ToolRisk {
    READ_ONLY, WRITE, DANGEROUS
}
